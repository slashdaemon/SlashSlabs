"""Tiered regression runner. Drives the existing scripts in a safe order, asserts the server's
actual config (the self-test cannot see a disabled worldgen hook), cleans up between tiers, and
writes a report.

    python scripts/regress.py doctor                    preflight only, no side effects
    python scripts/regress.py quick [--band 26.1.2]     unit + one band self-test
    python scripts/regress.py bands                     buildAll + unit + self-test on every band
    python scripts/regress.py gate [--no-tbs]           buildAll + prodtest on the shipped jars
    python scripts/regress.py props [--survey]          determinism (+ the M2 survey)
    python scripts/regress.py full [--client]           bands -> gate -> props [-> client]

Every verb runs `doctor` first (--skip-doctor to bypass); --reset-config and --kill-stale let
doctor fix what it finds. Reports land in build/regression/<timestamp>/. Exit 0 only if every
tier passed.

Why this exists rather than calling the scripts by hand: see .claude/skills/slashslabs-testkit.
The two traps it closes are a stale run config (determinism.py leaves worldgen=false behind and
/slashslabs selftest still reports 399/399) and a world directory Windows kept locked, which
makes devserver.py's --fresh a silent no-op.
"""
import argparse, json, os, re, shutil, socket, subprocess, sys, time
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from rcon import Rcon

ROOT = Path(__file__).resolve().parent.parent
BANDS = ["26.1.2", "26.2", "26.3"]
DEV_GAME, DEV_RCON = 25580, 25581
PROD_GAME, PROD_RCON = 25595, 25596
RCON_PASS = "slashslabs"
PRISM = Path.home() / "AppData/Roaming/PrismLauncher"
INSTANCES = ["SlashSlabs-Vanilla-26.1.2", "SlashSlabs-Voxy-26.1.2"]
TBS_PACK = ROOT.parent / "TBS" / "TBS-server"
JAVA25 = Path(os.environ.get("JAVA25_HOME") or os.environ.get("JAVA_HOME")
              or PRISM / "java/java-runtime-epsilon")

# Mirrors SlabsConfig.java's field initialisers. Only used to describe drift in the doctor
# report -- the hard gate is the `slashslabs info` assertion at run time, which cannot drift.
CONFIG_DEFAULTS = {
    "worldgen": True, "hookStage": "light", "diagonals": False,
    "dimensions": ["minecraft:overworld"], "excludeBiomes": [], "structureGuard": True,
    "maxWaterDepth": 1, "snowLayers": 5,
    "grassPalette": ["#79C05A", "#88BB67", "#86B783"],
    "materialOverrides": {}, "stepHeight": False, "stepHeightOffWhileSneaking": True,
    "animalSpawnsOnGrassSlabs": False,
}


# ---- small helpers

def port_free(port):
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) != 0


def java_procs():
    """[(pid, commandline)] for every running java/javaw, via CIM (wmic is gone on Win11)."""
    if os.name != "nt":
        out = subprocess.run(["ps", "-eo", "pid=,args="], capture_output=True, text=True).stdout
        return [(int(l.split()[0]), l.strip()) for l in out.splitlines() if "java" in l]
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | "
          "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }")
    out = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    procs = []
    for line in out.splitlines():
        pid, _, cmd = line.partition("\t")
        if pid.strip().isdigit():
            procs.append((int(pid), cmd))
    return procs


def ours(cmd):
    """True if a command line belongs to a server started out of this checkout."""
    c = cmd.replace("\\", "/").lower()
    return str(ROOT).replace("\\", "/").lower() in c


def kill_stale():
    killed = [pid for pid, cmd in java_procs() if ours(cmd)]
    for pid in killed:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)] if os.name == "nt"
                       else ["kill", "-9", str(pid)], capture_output=True)
    if killed:
        time.sleep(3)
    return killed


def run(cmd, log_path, env=None, cwd=ROOT):
    """Runs a command, tees stdout to a log file, returns (exit code, text)."""
    print(f"  $ {' '.join(str(c) for c in cmd)}")
    p = subprocess.run([str(c) for c in cmd], cwd=cwd, env=env, capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    text = (p.stdout or "") + (p.stderr or "")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(text, encoding="utf-8")
    return p.returncode, text


def gradle_env():
    env = dict(os.environ, JAVA_HOME=str(JAVA25))
    env["PATH"] = str(JAVA25 / "bin") + os.pathsep + env["PATH"]
    return env


# ---- config handling

def dev_config(band):
    return ROOT / "versions" / band / "run" / "config" / "slashslabs.json"


def config_drift(band):
    """[(key, expected, found)] for a band's run config, or None when there is no file."""
    p = dev_config(band)
    if not p.exists():
        return None
    try:
        cfg = json.loads(p.read_text())
    except Exception as e:
        return [("<unreadable>", "valid json", str(e))]
    return [(k, v, cfg[k]) for k, v in CONFIG_DEFAULTS.items() if k in cfg and cfg[k] != v]


def reset_config(band):
    """Deletes the run config so the server regenerates defaults (devserver.py writes {} when
    the file is absent, and SlabsConfig fills in every field from that)."""
    p = dev_config(band)
    if p.exists():
        p.unlink()
        return True
    return False


INFO_RE = re.compile(r"worldgen (on|off) \| step height (on|off)")


def assert_info(text, band, expect_worldgen=True, expect_step=False):
    """Checks a `slashslabs info` reply. The self-test smooths through WorldOps, which ignores
    cfg.worldgen, so it passes 399/399 with the hook off -- this is the only detection."""
    m = INFO_RE.search(text)
    if not m:
        return f"{band}: no `slashslabs info` line in the reply (config state unverified)"
    wg, sh = m.group(1) == "on", m.group(2) == "on"
    if wg != expect_worldgen or sh != expect_step:
        return (f"{band}: config mismatch -- worldgen {'on' if wg else 'off'} "
                f"(want {'on' if expect_worldgen else 'off'}), step height {'on' if sh else 'off'} "
                f"(want {'on' if expect_step else 'off'})")
    return None


# ---- teardown

def world_dir(band, world="dev-world"):
    return ROOT / "versions" / band / "run" / world


def assert_world_gone(band, world="dev-world", tries=6):
    """devserver.py's --fresh uses rmtree(ignore_errors=True), which silently no-ops while
    Windows still holds the world's file handles -- the stale world is then reused and the tier
    reports a false pass. Retry the delete, then fail loudly."""
    d = world_dir(band, world)
    for _ in range(tries):
        if not d.exists():
            return None
        shutil.rmtree(d, ignore_errors=True)
        if not d.exists():
            return None
        time.sleep(2)
    return f"{band}: world {d} still present after teardown (locked by a surviving JVM?)"


def sweep_determinism_worlds():
    gone = []
    for band in BANDS:
        run_dir = ROOT / "versions" / band / "run"
        for d in run_dir.glob("det-*"):
            if d.is_dir():
                shutil.rmtree(d, ignore_errors=True)
                gone.append(d.name)
    return gone


def teardown(label, results):
    """Always between tiers: kill anything of ours still running, then sweep worlds."""
    killed = kill_stale()
    if killed:
        results.note(f"{label}: killed {len(killed)} surviving JVM(s) from this checkout")
    for port in (DEV_RCON, PROD_RCON):
        for _ in range(15):
            if port_free(port):
                break
            time.sleep(2)


# ---- results

class Results:
    def __init__(self, out_dir):
        self.out_dir, self.rows, self.notes = out_dir, [], []
        self.started = datetime.now()

    def add(self, tier, name, ok, detail=""):
        self.rows.append((tier, name, ok, detail))
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" -- {detail}" if detail else ""))

    def note(self, text):
        self.notes.append(text)
        print(f"  note: {text}")

    @property
    def ok(self):
        return all(r[2] for r in self.rows)

    def write(self):
        mins = (datetime.now() - self.started).total_seconds() / 60
        lines = [f"# SlashSlabs regression — {self.started:%Y-%m-%d %H:%M}", "",
                 f"{'**PASS**' if self.ok else '**FAIL**'} · {len(self.rows)} checks · {mins:.0f} min", "",
                 "| Tier | Check | Result | Detail |", "|---|---|---|---|"]
        for tier, name, ok, detail in self.rows:
            lines.append(f"| {tier} | {name} | {'PASS' if ok else '**FAIL**'} | {detail} |")
        if self.notes:
            lines += ["", "## Notes", ""] + [f"- {n}" for n in self.notes]
        lines += ["", "## Not covered here", "",
                  "Sounds, sneak at slab edges, vehicles over slabs, mining at 100+ ms ping, tint",
                  "seams by eye, Bedrock via Geyser, and AutoHost through the real TBS hostname all",
                  "still need a person — see the open items in `docs/TESTING.md`.", ""]
        p = self.out_dir / "report.md"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("\n".join(lines), encoding="utf-8")
        return p


# ---- doctor

def doctor(results, fix_config=False, kill=False):
    print("doctor")
    jv = subprocess.run([str(JAVA25 / "bin" / "java"), "-version"], capture_output=True, text=True)
    ver = (jv.stderr or jv.stdout or "").splitlines()[0] if jv.returncode == 0 else "not runnable"
    results.add("doctor", "JDK 25", jv.returncode == 0 and '"25' in ver, f"{JAVA25} → {ver}")

    busy = [p for p in (DEV_GAME, DEV_RCON, PROD_GAME, PROD_RCON) if not port_free(p)]
    results.add("doctor", "ports free", not busy, "in use: " + ", ".join(map(str, busy)) if busy else "25580/1, 25595/6")

    stale = [pid for pid, cmd in java_procs() if ours(cmd)]
    if stale and kill:
        kill_stale()
        results.add("doctor", "no stale JVMs", True, f"killed {len(stale)}")
    else:
        results.add("doctor", "no stale JVMs", not stale,
                    f"pids {stale} hold this checkout (re-run with --kill-stale)" if stale else "")

    version = re.search(r"mod_version=(\S+)", (ROOT / "gradle.properties").read_text()).group(1)
    jars = sorted((ROOT / "build" / "release").glob("slashslabs-*.jar")) if (ROOT / "build" / "release").exists() else []
    matched = [j for j in jars if f"-{version}+" in j.name]
    results.add("doctor", "release jars", len(matched) == len(BANDS),
                f"{len(matched)}/{len(BANDS)} at {version}" + (" (run buildAll)" if len(matched) != len(BANDS) else ""))

    drift_all = []
    for band in BANDS:
        d = config_drift(band)
        if d:
            drift_all += [f"{band}:{k}={found!r}" for k, _, found in d]
    if drift_all and fix_config:
        for band in BANDS:
            reset_config(band)
        results.add("doctor", "run config clean", True, "reset: " + ", ".join(drift_all))
    else:
        results.add("doctor", "run config clean", not drift_all,
                    ", ".join(drift_all) + " (re-run with --reset-config)" if drift_all else "defaults")

    packs = sorted(TBS_PACK.glob("*.mrpack")) if TBS_PACK.exists() else []
    results.add("doctor", "TBS pack", bool(packs),
                packs[-1].name if packs else f"{TBS_PACK} missing — the `tbs` gate target cannot run")

    missing = [i for i in INSTANCES if not (PRISM / "instances" / i).exists()]
    results.add("doctor", "Prism instances", not missing, "missing: " + ", ".join(missing) if missing else ", ".join(INSTANCES))

    reach = []
    for host in ("meta.fabricmc.net", "api.modrinth.com"):
        try:
            socket.create_connection((host, 443), timeout=5).close()
        except OSError as e:
            reach.append(f"{host} ({e.__class__.__name__})")
    cached = [t for t in BANDS + ["tbs"] if (ROOT / "build" / "prodtest" / t / "mods").exists()]
    results.add("doctor", "downloads reachable", not reach,
                ", ".join(reach) if reach else f"cached targets: {', '.join(cached) or 'none (first gate run downloads the modset)'}")
    return results.ok


# ---- tiers

def tier_unit(results, bands):
    for band in bands:
        code, text = run([ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"),
                          f":versions:{band}:test", "--console=plain"],
                         results.out_dir / f"unit-{band}.log", env=gradle_env())
        results.add("unit", f"JUnit {band}", code == 0, "" if code == 0 else tail(text))


def tier_build(results):
    code, text = run([ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"), "buildAll", "--console=plain"],
                     results.out_dir / "buildAll.log", env=gradle_env())
    # Only the current version counts: build/release/ keeps older releases' jars too.
    version = re.search(r"mod_version=(\S+)", (ROOT / "gradle.properties").read_text()).group(1)
    jars = sorted(p.name for p in (ROOT / "build" / "release").glob(f"slashslabs-{version}+*.jar"))
    results.add("build", "buildAll", code == 0 and len(jars) == len(BANDS), ", ".join(jars) if code == 0 else tail(text))


def tier_selftest(results, band):
    """One band: fresh world, defaults, self-test -- plus the `info` assertion the self-test
    itself cannot make."""
    reset_config(band)
    err = assert_world_gone(band)
    if err:
        results.add("selftest", f"{band} clean world", False, err)
        return
    code, text = run([sys.executable, ROOT / "scripts" / "devserver.py", "--band", band, "--fresh",
                      "-c", "slashslabs info", "-c", "slashslabs selftest"],
                     results.out_dir / f"selftest-{band}.log")
    mismatch = assert_info(text, band)
    results.add("selftest", f"{band} config", mismatch is None, mismatch or "worldgen on, step height off")
    m = re.search(r"SELFTEST (PASS|FAIL) (\d+)/(\d+)", text)
    results.add("selftest", f"{band} self-test", code == 0 and bool(m) and m.group(1) == "PASS",
                f"{m.group(2)}/{m.group(3)}" if m else tail(text))
    teardown(f"selftest {band}", results)


def tier_gate(results, targets):
    code, text = run([sys.executable, ROOT / "scripts" / "prodtest.py"] + targets,
                     results.out_dir / "prodtest.log")
    for t in targets:
        m = re.search(rf"^\[{re.escape(t)}\] (PASS|FAIL): (.*)$", text, re.M)
        results.add("gate", f"prodtest {t}", bool(m) and m.group(1) == "PASS",
                    m.group(2) if m else "no result line — see prodtest.log")
    if not targets:
        results.add("gate", "prodtest", code == 0, "")
    teardown("gate", results)


def tier_props(results, radius, survey):
    code, text = run([sys.executable, ROOT / "scripts" / "determinism.py", str(radius)],
                     results.out_dir / "determinism.log")
    pairs = re.findall(r"rows vs (\w+): .*?\((\d+\.\d+)% of union\)", text)
    for name, pct in pairs:
        # Below ~95% is an ordering regression; the remainder is vanilla's own tree and
        # leaf-litter placement, which differs by generation order with SlashSlabs off too.
        results.add("props", f"determinism rows vs {name}", float(pct) >= 95.0, f"{pct}% identical")
    if not pairs:
        results.add("props", "determinism", False, tail(text))
    cost = re.search(r"hook ([\d.]+) ms/chunk", text)
    if cost:
        results.note(f"smoothing cost {cost.group(1)} ms/chunk")
    if survey:
        results.note("survey: run `devserver.py --band 26.1.2 --keep --config worldgen=false` "
                     "then `python scripts/survey.py 5` by hand — it has no pass/fail")
    # determinism.py leaves det-a/b/c/off behind and ends with worldgen=false in the run config.
    swept = sweep_determinism_worlds()
    for band in BANDS:
        reset_config(band)
    results.note(f"swept {len(swept)} determinism world(s), reset run configs")
    teardown("props", results)


def tier_client(results, band):
    code, text = run([sys.executable, ROOT / "scripts" / "clienttest.py", "--band", band,
                      "--out", str(results.out_dir / "client")],
                     results.out_dir / "clienttest.log")
    for m in re.finditer(r"^\[(PASS|FAIL)\] (.+?)(?: -- (.*))?$", text, re.M):
        results.add("client", m.group(2), m.group(1) == "PASS", m.group(3) or "")
    if not re.search(r"^\[(PASS|FAIL)\]", text, re.M):
        results.add("client", "clienttest", code == 0, tail(text))
    snaps = sorted((results.out_dir / "client").glob("*.png"))
    if snaps:
        results.note(f"{len(snaps)} visual check snap(s) in {results.out_dir / 'client'} — read them")
    teardown("client", results)


def tail(text, n=12):
    return " / ".join(l.strip() for l in text.strip().splitlines()[-n:] if l.strip())[:400]


# ---- main

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("verb", choices=["doctor", "quick", "bands", "gate", "props", "client", "full"])
    ap.add_argument("--band", default="26.1.2", help="band for quick/client (default 26.1.2)")
    ap.add_argument("--no-tbs", action="store_true", help="skip the TBS modset target in the gate")
    ap.add_argument("--radius", type=int, default=8, help="determinism radius (default 8)")
    ap.add_argument("--survey", action="store_true", help="print how to run the M2 survey after props")
    ap.add_argument("--client", action="store_true", help="include the attended client tier in full")
    ap.add_argument("--skip-doctor", action="store_true")
    ap.add_argument("--reset-config", action="store_true", help="delete drifted run configs")
    ap.add_argument("--kill-stale", action="store_true", help="kill leftover JVMs from this checkout")
    a = ap.parse_args(argv)

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    results = Results(ROOT / "build" / "regression" / stamp)
    print(f"SlashSlabs regression: {a.verb} → {results.out_dir}\n")

    if not a.skip_doctor:
        if not doctor(results, a.reset_config, a.kill_stale) and a.verb != "doctor":
            print("\ndoctor failed — fix the above (or pass --skip-doctor) before running a tier.")
            print(f"\nreport: {results.write()}")
            return 2
        print()

    if a.verb in ("quick",):
        tier_unit(results, [a.band])
        tier_selftest(results, a.band)
    elif a.verb in ("bands", "full"):
        tier_build(results)
        tier_unit(results, BANDS)
        for band in BANDS:
            tier_selftest(results, band)
    if a.verb == "gate":
        tier_build(results)
    if a.verb in ("gate", "full"):
        tier_gate(results, BANDS + ([] if a.no_tbs else ["tbs"]))
    if a.verb in ("props", "full"):
        tier_props(results, a.radius, a.survey)
    if a.verb == "client" or (a.verb == "full" and a.client):
        tier_client(results, a.band)

    path = results.write()
    print(f"\n{'PASS' if results.ok else 'FAIL'} — {sum(1 for r in results.rows if r[2])}/{len(results.rows)} checks")
    print(f"report: {path}")
    return 0 if results.ok else 1


if __name__ == "__main__":
    sys.exit(main())
