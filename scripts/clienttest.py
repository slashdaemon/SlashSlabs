"""Replays the M0 vanilla-client checks (docs/TESTING.md) against a running dev server, asserting
on RCON and log evidence rather than pixels.

    # start the server first, in another shell:
    python scripts/devserver.py --band 26.1.2 --fresh --keep --autohost -c "slashslabs info"
    python scripts/clienttest.py [--band 26.1.2] [--out DIR] [--asserted-only] [--only walk,mine]

Prints one `[PASS] name -- detail` / `[FAIL] name -- detail` line per check (regress.py parses
these) and drops the visual checks' snaps in --out for a human or an agent to look at.

⚠ This drives the real keyboard and mouse: client.py refuses to send input unless the Minecraft
window holds the foreground, so nothing else can use the PC while it runs.

Design notes that are easy to get wrong:
  * Block assertions use `execute if block`; `data get block` needs a BlockEntity, which a slab
    has not, so it only ever returns ERROR_NOT_A_BLOCK_ENTITY.
  * devserver.py sets gamemode=creative, where left-click breaks instantly -- the mining check
    switches to survival and puts it back.
  * Aim is set server-side with `/tp <player> x y z <yaw> <pitch>` and the click is sent with
    `client.py mousehold`, which does not move the cursor (the game grabs the mouse, so moving
    it would turn the camera).
  * The fixture is placed with `execute positioned`, because the bare command builds at the
    command source's position + (2,0,2) and RCON sits at world spawn.
"""
import argparse, json, math, os, re, subprocess, sys, time
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from rcon import Rcon

ROOT = Path(__file__).resolve().parent.parent
CLIENT = ROOT / "scripts" / "client.py"
PRISM = Path.home() / "AppData/Roaming/PrismLauncher/instances"
RCON_PORT, RCON_PASS, GAME_PORT = 25581, "slashslabs", 25580
EYE = 1.62

# TestField.PROFILE -- the fixture's height along x. Expectations are derived from this, not
# copied from docs/TESTING.md, so a fixture change moves the assertions with it.
PROFILE = [0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 5, 5, 5, 5]
STRIP, STRIPS, SLAB_X = 5, 8, 3   # strip width (z), strip count, and a column carrying a grass slab
LENGTH = len(PROFILE)
SM = (0, 120, 0)               # smoothed fixture origin        -- relocated by pick_site()
RAW = (0, 120, 100)            # unsmoothed control fixture origin -- relocated by pick_site()

# Where precipitation is SNOW the smoother turns *every* soil step into a snow layer
# (TerrainSmoother: `choice.kind() == SNOW || (snowy && soil)`), so grass and dirt strips
# never receive a slab. Building at the command source's spawn therefore makes the material
# assertions seed-dependent -- on the 26.1.2 dev seed spawn is frozen_ocean and `mine` can
# never pass. pick_site() moves both fixtures into one of these instead.
TEMPERATE = ["minecraft:plains", "minecraft:sunflower_plains", "minecraft:forest",
             "minecraft:birch_forest", "minecraft:meadow", "minecraft:savanna"]


def first_rise(profile):
    """x of the first 1-block step, and x of the first step of 2+ (the cliff)."""
    step = cliff = None
    for i in range(1, len(profile)):
        d = profile[i] - profile[i - 1]
        if d == 1 and step is None:
            step = i
        if d >= 2 and cliff is None:
            cliff = i
    return step, cliff


STEP_X, CLIFF_X = first_rise(PROFILE)          # 4 and 12


class Checks:
    def __init__(self):
        self.failed = 0

    def __call__(self, name, ok, detail=""):
        if not ok:
            self.failed += 1
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" -- {detail}" if detail else ""), flush=True)
        return ok

    def skip(self, name, why):
        print(f"[SKIP] {name} -- {why}", flush=True)


def cli(*args, timeout=180):
    return subprocess.run([sys.executable, str(CLIENT)] + [str(a) for a in args],
                          capture_output=True, text=True, timeout=timeout,
                          encoding="utf-8", errors="replace").stdout.strip()


def look_at(px, py, pz, tx, ty, tz):
    """Yaw/pitch that puts a player's crosshair on a point, from the eye at py + 1.62."""
    dx, dy, dz = tx - px, ty - (py + EYE), tz - pz
    yaw = -math.degrees(math.atan2(dx, dz))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    return round(yaw, 2), round(pitch, 2)


class Server:
    def __init__(self, band):
        self.band = band
        self.r = Rcon(RCON_PORT, RCON_PASS, timeout=300)
        self.log = ROOT / "versions" / band / "run" / "logs" / "latest.log"

    def cmd(self, c):
        return self.r.cmd(c).replace("§r", "")

    def players(self):
        m = re.search(r"players online:\s*(.*)$", self.cmd("list"), re.M)
        return [p.strip() for p in m.group(1).split(",") if p.strip()] if m else []

    def block_is(self, pos, block):
        return "Test passed" in self.cmd(f"execute if block {pos[0]} {pos[1]} {pos[2]} {block}")

    def biome_is(self, pos, biome):
        return "Test passed" in self.cmd(f"execute if biome {pos[0]} {pos[1]} {pos[2]} {biome}")

    def pos_of(self, player):
        # "<player> has the following entity data: [0.5d, 121.0d, 2.5d]"
        out = self.cmd(f"data get entity {player} Pos")
        nums = re.findall(r"(-?\d+\.?\d*)d", out)
        return tuple(float(n) for n in nums[:3]) if len(nums) >= 3 else None

    def build(self, origin, raw=False):
        x, y, z = origin
        return self.cmd(f"execute positioned {x - 2} {y} {z - 2} run slashslabs test-field" + (" raw" if raw else ""))

    def log_since(self, mark):
        text = self.log.read_text(encoding="utf-8", errors="replace") if self.log.exists() else ""
        return text[mark:], len(text)

    def log_mark(self):
        return len(self.log.read_text(encoding="utf-8", errors="replace")) if self.log.exists() else 0


def client_log(instance):
    for sub in ("minecraft", ".minecraft"):
        p = PRISM / instance / sub / "logs" / "latest.log"
        if p.exists():
            return p
    return None


def pick_site(s, player, y=120):
    """Move both fixtures into a single temperate biome and load their chunks.

    Rebinds the SM/RAW globals so every check picks the new origin up. Returns False when
    no candidate biome covers a whole footprint -- the material assertions are meaningless
    under snow precipitation, so the tier should abort rather than report a bogus failure.
    """
    global SM, RAW
    gap = LENGTH + 12                       # control field sits beside the smoothed one, in +x
    for b in TEMPERATE:
        m = re.search(r"\[(-?\d+), *(?:~|-?\d+), *(-?\d+)]", s.cmd(f"locate biome {b}"))
        if not m:
            continue
        bx, bz = int(m.group(1)), int(m.group(2))
        for dx, dz in ((0, 0), (48, 0), (0, 48), (48, 48), (-48, 0), (0, -48)):
            x, z = bx + dx, bz + dz
            # Stand between the fixtures: the biome probes and the build both need the
            # chunks loaded, and view distance comfortably covers the compact footprint.
            s.cmd(f"tp {player} {x + gap // 2} {y + 30} {z + STRIP}")
            time.sleep(2)
            # Only the strips the asserted checks actually touch must share a biome:
            # strip 0 (walk, mine, visual), strip 1 (place), and the control field.
            need = [(x + SLAB_X, y, z + STRIP // 2),
                    (x + 2, y, z + (STRIP + 1) + STRIP // 2),
                    (x + LENGTH - 4, y, z + STRIP // 2),
                    (x + gap + SLAB_X, y, z + STRIP // 2)]
            if not all(s.biome_is(c, b) for c in need):
                continue
            SM, RAW = (x, y, z), (x + gap, y, z)
            outer = sum(s.biome_is((x + SLAB_X, y, z + st * (STRIP + 1) + STRIP // 2), b)
                        for st in range(STRIPS))
            print(f"fixture site: {b} at {x} {y} {z}"
                  + ("" if outer == STRIPS else
                     f"  ({outer}/{STRIPS} strips in-biome — the tint-seam snapshot may "
                     "span a boundary)"))
            return True
    return False


# ---- checks

def check_join(ck, s, player, instance):
    ck("join: player online", bool(player), player or "nobody joined — is the client connected?")
    p = client_log(instance)
    if not p:
        return ck("join: client log", False, "no client latest.log found")
    errs = [l for l in p.read_text(encoding="utf-8", errors="replace").splitlines()
            if re.search(r"/ERROR\]|Exception", l)]
    ck("join: client log clean", not errs, f"{len(errs)} error line(s)" if errs else "0 errors")


def walk(s, player, origin, seconds=7):
    """Drops the player at the low end of strip 0 facing +x and holds W."""
    x, y, z = origin
    s.cmd(f"gamemode survival {player}")
    s.cmd(f"tp {player} {x + 0.5} {y + 1} {z + STRIP / 2} -90 0")
    time.sleep(1.5)
    cli("hold", "w", seconds)
    time.sleep(1.0)
    return s.pos_of(player)


def check_walk(ck, s, player):
    # PROFILE indices are origin-relative, so compare the walked distance, not world x --
    # these read the same only while the fixture sits at x=0, which it no longer does.
    end_y = SM[1] + PROFILE[CLIFF_X - 1] + 1          # feet on the highest step before the cliff
    p = walk(s, player, SM)
    dx = p[0] - SM[0] if p else None
    ok = p and CLIFF_X - 1.5 <= dx < CLIFF_X and abs(p[1] - end_y) < 0.1
    ck("walk: climbs the smoothed rises, stops at the cliff", bool(ok),
       f"dx={dx:.1f} y={p[1]:.1f} (want dx in [{CLIFF_X - 1.5}, {CLIFF_X}), y={end_y})" if p else "no position")

    p = walk(s, player, RAW)
    dx = p[0] - RAW[0] if p else None
    ok = p and STEP_X - 1.5 <= dx < STEP_X and abs(p[1] - (RAW[1] + 1)) < 0.1
    ck("walk: control — unsmoothed field stops at the first rise", bool(ok),
       f"dx={dx:.1f} y={p[1]:.1f} (want dx in [{STEP_X - 1.5}, {STEP_X}), y={RAW[1] + 1})" if p else "no position")


def check_sprint(ck, s, player):
    x, y, z = SM
    s.cmd(f"gamemode survival {player}")
    s.cmd(f"tp {player} {x + 0.5} {y + 1} {z + STRIP / 2} -90 0")
    time.sleep(1.5)
    mark = s.log_mark()
    cli("hold2", "w", "ctrl", 6)
    time.sleep(1.5)
    text, _ = s.log_since(mark)
    bad = re.findall(r"moved (?:wrongly|too quickly)", text)
    ck("sprint: no movement rejections", not bad, f"{len(bad)} warning(s)" if bad else "0 warnings")


def check_step_height(ck, s, player, info):
    if "step height on" not in info:
        ck.skip("step height", "module off — restart the server with --config stepHeight=true")
        return
    out = s.cmd(f"attribute {player} minecraft:step_height get")
    m = re.search(r"([\d.]+)$", out.strip())
    ck("step height: attribute is 1.0", bool(m) and abs(float(m.group(1)) - 1.0) < 0.01, out.strip()[:80])
    p = walk(s, player, RAW)
    end_y = RAW[1] + PROFILE[CLIFF_X - 1] + 1
    dx = p[0] - RAW[0] if p else None                 # origin-relative, as in check_walk
    ok = p and CLIFF_X - 1.5 <= dx < CLIFF_X and abs(p[1] - end_y) < 0.1
    ck("step height: walks the unsmoothed field to the cliff", bool(ok),
       f"dx={dx:.1f} y={p[1]:.1f}" if p else "no position")
    s.cmd(f"kill {player}")
    time.sleep(2)
    s.cmd(f"spawnpoint {player} {SM[0]} {SM[1] + 1} {SM[2]}")
    time.sleep(2)
    out = s.cmd(f"attribute {player} minecraft:step_height get")
    m = re.search(r"([\d.]+)$", out.strip())
    ck("step height: survives death and respawn", bool(m) and abs(float(m.group(1)) - 1.0) < 0.01, out.strip()[:80])


def check_mine(ck, s, player):
    """A grass slab sits on the column just below each rise; STEP_X - 1 is the first."""
    sx, sy, sz = SM[0] + SLAB_X, SM[1] + PROFILE[SLAB_X] + 1, SM[2] + STRIP // 2
    if not s.block_is((sx, sy, sz), "slashslabs:grass_slab"):
        return ck("mine: grass slab present to mine", False, f"no grass slab at {sx} {sy} {sz}")
    px, py, pz = sx - 2 + 0.5, SM[1] + PROFILE[SLAB_X] + 1, sz + 0.5
    yaw, pitch = look_at(px, py, pz, sx + 0.5, sy + 0.25, sz + 0.5)
    s.cmd(f"gamemode survival {player}")
    s.cmd(f"clear {player}")
    s.cmd(f"tp {player} {px} {py} {pz} {yaw} {pitch}")
    time.sleep(1.5)
    cli("mousehold", 0.4)
    time.sleep(0.5)
    still = s.block_is((sx, sy, sz), "slashslabs:grass_slab")
    ck("mine: still there after 0.4 s (not instant)", still, "" if still else "broke too fast — creative?")
    gone = False
    for _ in range(2):                                  # PowerShell spawn latency, not the hold itself
        cli("mousehold", 1.6)
        time.sleep(0.6)
        gone = not s.block_is((sx, sy, sz), "slashslabs:grass_slab")
        if gone:
            break
    ck("mine: breaks at grass speed, not copper speed", gone,
       "" if gone else "still there after 2 x 1.6 s — backing block's hardness is leaking through")
    s.cmd(f"gamemode creative {player}")


def check_place(ck, s, player):
    """Strip 1 (dirt) has a flat run at x 0-3; place onto the top face of one of its blocks."""
    bx, by, bz = SM[0] + 2, SM[1] + PROFILE[2], SM[2] + (STRIP + 1) + STRIP // 2
    stand_x = SM[0] + STEP_X                            # one step up, two across: a clean downward ray
    px, py, pz = stand_x + 0.5, SM[1] + PROFILE[STEP_X] + 1, bz + 0.5
    s.cmd(f"gamemode creative {player}")
    s.cmd(f"clear {player}")
    s.cmd(f"give {player} slashslabs:dirt_slab 4")
    time.sleep(0.5)
    cli("key", "1")
    yaw, pitch = look_at(px, py, pz, bx + 0.5, by + 0.99, bz + 0.5)
    s.cmd(f"tp {player} {px} {py} {pz} {yaw} {pitch}")
    time.sleep(1.5)
    cli("mousehold", 0.1, "right")
    time.sleep(0.8)
    one = s.block_is((bx, by + 1, bz), "slashslabs:dirt_slab[type=bottom]")
    ck("place: click on a top face gives a bottom dirt slab", one,
       "" if one else f"nothing placed at {bx} {by + 1} {bz}")
    if one:
        yaw, pitch = look_at(px, py, pz, bx + 0.5, by + 1.49, bz + 0.5)
        s.cmd(f"tp {player} {px} {py} {pz} {yaw} {pitch}")
        time.sleep(1.0)
        cli("mousehold", 0.1, "right")
        time.sleep(0.8)
        two = s.block_is((bx, by + 1, bz), "minecraft:dirt")
        ck("place: a second slab completes into a vanilla dirt block", two,
           "" if two else "second click did not complete the block")


def visual(ck, s, player, out: Path):
    """Snaps for the checks a machine cannot judge. Paths are printed; read the PNGs."""
    out.mkdir(parents=True, exist_ok=True)
    shots = []

    s.cmd(f"gamemode creative {player}")
    s.cmd(f"clear {player}")
    s.cmd(f"give {player} slashslabs:dirt_slab 1")
    time.sleep(0.5)
    cli("key", "1")
    time.sleep(0.5)
    cli("snap", str(out / "item-name-and-icon.png"))
    shots.append("item-name-and-icon.png — the held item should read “Dirt Slab” with the pack model")

    # Real waxed copper slabs next to ours: the pack must not break vanilla copper (R12).
    bx, by, bz = SM[0] + 16, SM[1] + PROFILE[16] + 1, SM[2] + STRIP // 2
    for i, state in enumerate(["waxed_copper_slab[type=bottom]", "waxed_copper_slab[type=top]",
                               "waxed_copper_slab[type=double]"]):
        s.cmd(f"setblock {bx + i} {by} {bz} minecraft:{state}")
    yaw, pitch = look_at(bx - 3 + 0.5, by, bz + 0.5, bx + 1.5, by + 0.5, bz + 0.5)
    s.cmd(f"tp {player} {bx - 3 + 0.5} {by} {bz + 0.5} {yaw} {pitch}")
    time.sleep(2)
    cli("snap", str(out / "real-copper-slabs.png"))
    shots.append("real-copper-slabs.png — these must render as normal copper, not a missing texture")

    s.cmd(f"tp {player} {SM[0] - 6} {SM[1] + 8} {SM[2] + 16} -55 30")
    time.sleep(2)
    cli("key", "f1")
    cli("snap", str(out / "smoothed-fixture.png"))
    cli("key", "f1")
    shots.append("smoothed-fixture.png — terracing and tint seams across all six strips")

    print("\nvisual checks (read these):")
    for s_ in shots:
        print(f"  {out / s_.split(' — ')[0]}  :: {s_.split(' — ')[1]}")


# ---- main

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--band", default="26.1.2")
    ap.add_argument("--instance", default="SlashSlabs-Vanilla-26.1.2")
    ap.add_argument("--out", default=str(ROOT / "build" / "regression" / "client"))
    ap.add_argument("--asserted-only", action="store_true", help="skip the snap-and-read checks")
    ap.add_argument("--only", default="", help="comma-separated: join,walk,sprint,step,mine,place")
    ap.add_argument("--no-launch", action="store_true", help="assume the client is already in game")
    a = ap.parse_args(argv)
    want = set(a.only.split(",")) if a.only else None

    try:
        s = Server(a.band)
    except OSError as e:
        print(f"no dev server on RCON {RCON_PORT} ({e}). Start one first:\n"
              f"  python scripts/devserver.py --band {a.band} --fresh --keep --autohost "
              f"-c \"slashslabs info\"")
        return 2

    info = s.cmd("slashslabs info")
    print(info.splitlines()[0] if info else "(no info)")

    if not s.players() and not a.no_launch:
        print(f"launching {a.instance} …")
        cli("launch", "--instance", a.instance, "--server", f"127.0.0.1:{GAME_PORT}", timeout=420)
        for _ in range(60):                       # the AutoHost pack prompt may be in the way
            time.sleep(5)
            if s.players():
                break
        else:
            print("client never joined — if the resource-pack prompt is up, accept it and re-run "
                  "with --no-launch")
    players = s.players()
    player = players[0] if players else None

    ck = Checks()
    if player:
        s.cmd(f"gamemode creative {player}")
        if not pick_site(s, player):
            print("no temperate biome found within the locate radius — every soil step would "
                  "be a snow layer, so the material checks cannot mean anything here.")
            return 2
        s.build(SM)
        s.build(RAW, raw=True)
        time.sleep(1)

    if want is None or "join" in want:
        check_join(ck, s, player, a.instance)
    if not player:
        print("\nno player online — the remaining checks need a connected client.")
        return 1
    if want is None or "walk" in want:
        check_walk(ck, s, player)
    if want is None or "sprint" in want:
        check_sprint(ck, s, player)
    if want is None or "step" in want:
        check_step_height(ck, s, player, info)
    if want is None or "mine" in want:
        check_mine(ck, s, player)
    if want is None or "place" in want:
        check_place(ck, s, player)
    if not a.asserted_only and want is None:
        visual(ck, s, player, Path(a.out))

    print(f"\n{'PASS' if ck.failed == 0 else 'FAIL'} — {ck.failed} failing check(s)")
    print("Still needs a person: sounds, sneak at slab edges, boats/horses/minecarts over slabs, "
          "mining at 100+ ms ping, Bedrock via Geyser.")
    return 0 if ck.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
