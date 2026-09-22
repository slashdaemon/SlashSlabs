"""Boots a Loom dev server for one band, runs commands over RCON, and stops it.

    python scripts/devserver.py --band 26.1.2 [--world NAME] [--fresh] [--seed S] [--flat]
                                [--keep] [--config KEY=VALUE ...] -c "slashslabs selftest" -c ...

Each -c is sent in order; its reply is printed. "@wait N" sleeps N seconds between commands.
Exit code 1 if any reply contains "SELFTEST FAIL", or the server fails to start.
--keep leaves the server running after the commands (for client tests); stop it with -c stop.
Never run a Gradle build in this checkout while a dev server runs (it can kill the daemon).
"""
import argparse, json, os, re, shutil, subprocess, sys, time
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from rcon import Rcon

ROOT = Path(__file__).resolve().parent.parent
# A JDK 25: JAVA25_HOME, else JAVA_HOME, else Prism Launcher's bundled runtime.
JAVA25 = str(os.environ.get("JAVA25_HOME") or os.environ.get("JAVA_HOME")
             or Path.home() / "AppData/Roaming/PrismLauncher/java/java-runtime-epsilon")
RCON_PORT, RCON_PASS, GAME_PORT = 25581, "slashslabs", 25580

PROPS = """enable-rcon=true
rcon.port={rcon}
rcon.password={pw}
server-port={port}
server-ip=127.0.0.1
level-name={world}
level-seed={seed}
level-type={ltype}
online-mode=false
gamemode=creative
difficulty=peaceful
spawn-monsters=false
spawn-protection=0
max-tick-time=-1
view-distance=8
simulation-distance=6
motd=SlashSlabs dev
pause-when-empty-seconds=0
allow-flight=true
enforce-secure-profile=false
"""


def stop(proc):
    """Stops the server over a fresh RCON connection; kills the whole process tree if it hangs."""
    try:
        Rcon(RCON_PORT, RCON_PASS, timeout=30).cmd("stop")
    except Exception:
        pass
    try:
        proc.wait(timeout=180)
    except subprocess.TimeoutExpired:
        pass
    # gradlew.bat exits before its java child on Windows; make sure nothing keeps the world lock.
    subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True)
    for _ in range(30):
        try:
            Rcon(RCON_PORT, RCON_PASS, timeout=2)
        except Exception:
            return
        time.sleep(2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--band", default="26.1.2")
    ap.add_argument("--world", default="dev-world")
    ap.add_argument("--fresh", action="store_true", help="delete the world first")
    ap.add_argument("--seed", default="slashslabs")
    ap.add_argument("--flat", action="store_true")
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--config", action="append", default=[], help="slashslabs.json override KEY=JSONVALUE")
    ap.add_argument("--autohost", action="store_true", help="enable Polymer AutoHost (required pack)")
    ap.add_argument("--gradle", action="append", default=[], help="extra Gradle arg, e.g. -Pbluemap=5.27-fabric")
    ap.add_argument("-c", "--cmd", action="append", default=[])
    a = ap.parse_args()

    run = ROOT / "versions" / a.band / "run"
    run.mkdir(parents=True, exist_ok=True)
    (run / "eula.txt").write_text("eula=true\n")
    (run / "server.properties").write_text(PROPS.format(rcon=RCON_PORT, pw=RCON_PASS, port=GAME_PORT, world=a.world, seed=a.seed,
                                                        ltype="minecraft\\:flat" if a.flat else "minecraft\\:normal"))
    cfg_dir = run / "config"
    cfg_dir.mkdir(exist_ok=True)
    cfg_path = cfg_dir / "slashslabs.json"
    cfg = json.loads(cfg_path.read_text()) if cfg_path.exists() else {}
    for kv in a.config:
        k, v = kv.split("=", 1)
        cfg[k] = json.loads(v)
    if a.config or not cfg_path.exists():
        cfg_path.write_text(json.dumps(cfg, indent=2))
    poly = cfg_dir / "polymer"
    poly.mkdir(exist_ok=True)
    (poly / "auto-host.json").write_text(json.dumps({
        "enabled": bool(a.autohost), "required": True, "type": "polymer:automatic",
        "message": "SlashSlabs needs its resource pack for grass slabs.",
        "disconnect_message": "The SlashSlabs resource pack is required."}, indent=2))
    if a.fresh:
        shutil.rmtree(run / a.world, ignore_errors=True)

    log = run / "devserver-console.log"
    env = dict(os.environ, JAVA_HOME=JAVA25)
    env["PATH"] = str(Path(JAVA25) / "bin") + os.pathsep + env["PATH"]
    gradlew = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    with open(log, "w", encoding="utf8") as lf:
        proc = subprocess.Popen([gradlew, f":versions:{a.band}:runServer", "--console=plain"] + a.gradle, cwd=ROOT, env=env,
                                stdout=lf, stderr=subprocess.STDOUT)
    t0 = time.time()
    while True:
        text = log.read_text(encoding="utf8", errors="replace") if log.exists() else ""
        if "RCON running" in text:
            break
        if proc.poll() is not None or re.search(r"BUILD FAILED|---- Minecraft Crash Report|Failed to start the minecraft server", text):
            print("server failed to start")
            print("\n".join(text.splitlines()[-60:]))
            return 2
        if time.time() - t0 > 900:
            print("timeout waiting for server")
            proc.kill()
            return 2
        time.sleep(2)
    print(f"server up in {time.time() - t0:.0f}s")

    failed = False
    r = Rcon(RCON_PORT, RCON_PASS)
    for c in a.cmd:
        if c.startswith("@wait"):
            time.sleep(float(c.split()[1]))
            continue
        print(f"> {c}")
        try:
            reply = r.cmd(c)
        except Exception as e:  # stop closes the socket
            reply = f"(no reply: {e})"
        print(reply.replace("\u00a7r", ""))
        if "SELFTEST FAIL" in reply:
            failed = True
    if not a.keep:
        stop(proc)
        text = log.read_text(encoding="utf8", errors="replace")
        errs = [l for l in text.splitlines() if re.search(r"ERROR|Exception|SlashSlabs.*(WARN|FAIL)", l)]
        if errs:
            print("--- log errors/warnings ---")
            print("\n".join(errs[:80]))
    else:
        print(f"server left running (pid {proc.pid}); log {log}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
