"""Release gate: runs the SHIPPED jars from build/release/ on real Fabric servers (Fabric's own
server launcher, no Gradle) and drives /slashslabs selftest + a worldgen dump over RCON.

    python scripts/prodtest.py [target ...]      targets: 26.1.2 26.2 26.3 tbs   (default: all)
    python scripts/prodtest.py tbs --keep        leave the server running (e.g. for a client)

"tbs" is 26.1.2 with the full TBS server modset from Projects/TBS/TBS-server/TBS-Server-*.mrpack
(Geophilic, structure packs, C2ME/Lithium/Krypton, Voxy World Gen, BlueMap, Geyser …) plus its
server overrides, i.e. the M3 compatibility run. Servers live in build/prodtest/<target>/.
Exit code 0 only if every target's self-test passed and worldgen placed blocks.
"""
import glob, json, os, re, shutil, subprocess, sys, time, urllib.request, zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from rcon import Rcon
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
# A JDK 25: JAVA25_HOME, else JAVA_HOME, else Prism Launcher's bundled runtime.
JAVA = Path(os.environ.get("JAVA25_HOME") or os.environ.get("JAVA_HOME")
            or Path.home() / "AppData/Roaming/PrismLauncher/java/java-runtime-epsilon") / "bin" / ("java.exe" if os.name == "nt" else "java")
RCON_PORT, RCON_PASS, GAME_PORT = 25596, "slashslabs", 25595
LOADER = "0.19.5"
TBS_PACK = ROOT.parent / "TBS" / "TBS-server"
UA = {"User-Agent": "slashdaemon/SlashSlabs-prodtest (https://github.com/slashdaemon/SlashSlabs)"}


def fetch(url, dest: Path):
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)
    return dest


def modrinth_file(project, mc, loader="fabric"):
    url = (f"https://api.modrinth.com/v2/project/{project}/version?loaders=%5B%22{loader}%22%5D"
           f"&game_versions=%5B%22{mc}%22%5D")
    req = urllib.request.Request(url, headers=UA)
    versions = json.load(urllib.request.urlopen(req, timeout=60))
    f = next(f for f in versions[0]["files"] if f.get("primary")) if versions else None
    return (f["url"], f["filename"]) if f else (None, None)


def installer_version():
    req = urllib.request.Request("https://meta.fabricmc.net/v2/versions/installer", headers=UA)
    return next(v["version"] for v in json.load(urllib.request.urlopen(req, timeout=60)) if v["stable"])


def prepare(target):
    mc = "26.1.2" if target == "tbs" else target
    d = ROOT / "build" / "prodtest" / target
    mods = d / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for old in mods.glob("slashslabs-*.jar"):
        old.unlink()
    # The current version only: build/release/ keeps older releases' jars too.
    version = re.search(r"mod_version=(\S+)", (ROOT / "gradle.properties").read_text()).group(1)
    jar = ROOT / "build" / "release" / f"slashslabs-{version}+mc{mc}-fabric.jar"
    if not jar.exists():
        sys.exit(f"{jar.name} not found; run ./gradlew buildAll")
    shutil.copy(jar, mods / jar.name)
    loader = LOADER
    if target == "tbs":
        pack = sorted(TBS_PACK.glob("TBS-Server-*.mrpack"))[-1]
        z = zipfile.ZipFile(pack)
        idx = json.loads(z.read("modrinth.index.json"))
        loader = idx["dependencies"].get("fabric-loader", LOADER)
        for f in idx["files"]:
            if f.get("env", {}).get("server") == "unsupported":
                continue
            fetch(f["downloads"][0], d / f["path"])
        for name in z.namelist():
            for prefix in ("overrides/", "server-overrides/"):
                if name.startswith(prefix) and not name.endswith("/"):
                    out = d / name[len(prefix):]
                    if not out.exists():
                        out.parent.mkdir(parents=True, exist_ok=True)
                        out.write_bytes(z.read(name))
    else:
        url, fn = modrinth_file("fabric-api", mc)
        for old in mods.glob("fabric-api-*.jar"):
            if old.name != fn:
                old.unlink()
        fetch(url, mods / fn)
    launcher = d / f"fabric-server-{mc}-{loader}.jar"
    fetch(f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/{installer_version()}/server/jar", launcher)
    (d / "eula.txt").write_text("eula=true\n")
    (d / "server.properties").write_text(
        f"enable-rcon=true\nrcon.port={RCON_PORT}\nrcon.password={RCON_PASS}\nserver-port={GAME_PORT}\nserver-ip=127.0.0.1\n"
        f"level-name=prodtest-world\nlevel-seed=slashslabs\nonline-mode=false\nspawn-protection=0\nmax-tick-time=-1\n"
        f"view-distance=8\nsimulation-distance=6\npause-when-empty-seconds=0\nenforce-secure-profile=false\n")
    shutil.rmtree(d / "prodtest-world", ignore_errors=True)
    (d / "config").mkdir(exist_ok=True)
    cfg = d / "config" / "slashslabs.json"
    if cfg.exists():
        cfg.unlink()
    poly = d / "config" / "polymer"
    poly.mkdir(exist_ok=True)
    (poly / "auto-host.json").write_text(json.dumps({"enabled": True, "required": True, "type": "polymer:automatic"}))
    return d, launcher


def stop(proc):
    try:
        Rcon(RCON_PORT, RCON_PASS, timeout=30).cmd("stop")
    except Exception:
        pass
    try:
        proc.wait(timeout=240)
    except subprocess.TimeoutExpired:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True)


def run(target, keep=False):
    d, launcher = prepare(target)
    log = d / "console.log"
    with open(log, "w", encoding="utf8") as lf:
        proc = subprocess.Popen([str(JAVA), "-Xmx6G", "-jar", launcher.name, "--nogui"], cwd=d, stdout=lf, stderr=subprocess.STDOUT)
    t0 = time.time()
    while True:
        text = log.read_text(encoding="utf8", errors="replace")
        if "RCON running" in text:
            break
        if proc.poll() is not None or "Crash Report" in text or time.time() - t0 > 1200:
            print(f"[{target}] server failed to start\n" + "\n".join(text.splitlines()[-40:]))
            stop(proc)
            return False
        time.sleep(3)
    print(f"[{target}] up in {time.time() - t0:.0f}s")
    r = Rcon(RCON_PORT, RCON_PASS, timeout=1800)
    out = {}
    for c in ["slashslabs info", "slashslabs selftest", "execute positioned 2000 80 2000 run slashslabs gen 6 rows",
              "execute positioned 2000 80 2000 run slashslabs dump 5 prod", "slashslabs info", "polymer generate-pack"]:
        out[c] = r.cmd(c)
        print(f"[{target}] > {c}\n    " + out[c].replace("\n", "\n    ")[:1500])
    passed = "SELFTEST PASS" in out["slashslabs selftest"]
    m = re.search(r"DUMP (\d+)", out["execute positioned 2000 80 2000 run slashslabs dump 5 prod"])
    placed = int(m.group(1)) if m else 0
    if keep:
        print(f"[{target}] left running (pid {proc.pid}), game port {GAME_PORT}")
    else:
        stop(proc)
    text = log.read_text(encoding="utf8", errors="replace")
    bad = [l for l in text.splitlines() if re.search(r"(ERROR|Exception)", l) and re.search(r"slashslabs|SlashSlabs|polymer|Polymer|Mixin", l)]
    unsafe = len(re.findall(r"unsafe terrain read|setBlock in a far chunk", text))
    for l in bad[:25]:
        print(f"[{target}] LOG {l}")
    ok = passed and placed > 0 and not bad and unsafe == 0
    print(f"[{target}] {'PASS' if ok else 'FAIL'}: selftest={'pass' if passed else 'FAIL'}, worldgen blocks={placed}, "
          f"slashslabs/polymer errors={len(bad)}, unsafe-worldgen warnings={unsafe}")
    return ok


def main(argv):
    keep = "--keep" in argv
    targets = [a for a in argv if not a.startswith("--")] or ["26.1.2", "26.2", "26.3", "tbs"]
    results = {t: run(t, keep and t == targets[-1]) for t in targets}
    print("\n".join(f"{t:8s} {'PASS' if ok else 'FAIL'}" for t, ok in results.items()))
    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
