"""Fly the test client to a biome, find the grass slab nearest its centre and snapshot it
next to real grass (tint seam check, RESEARCH §5.3). Needs a dev server with --keep and a client.
    python scripts/biome_view.py <x> <z> <label> <out_dir>"""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(__file__))
from rcon import Rcon
x, z, label, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
r = Rcon(25581, "slashslabs")
r.cmd("gamemode spectator slashdaemon")
r.cmd(f"tp slashdaemon {x} 150 {z}")
time.sleep(6)
print(r.cmd(f"execute positioned {x} 70 {z} run slashslabs dump 1 {label}"))
lines = open(os.path.join(ROOT, "versions/26.1.2/run/slashslabs", f"dump-{label}.txt")).read().split("\n")
best = None
for l in lines:
    p = l.split()
    if len(p) == 4 and p[3].startswith("grass_slab"):
        d = (int(p[0]) - x) ** 2 + (int(p[2]) - z) ** 2
        if best is None or d < best[0]:
            best = (d, int(p[0]), int(p[1]), int(p[2]), p[3])
if not best:
    print("no grass slab here"); sys.exit(1)
_, bx, by, bz, what = best
print("slab", bx, by, bz, what, r.cmd(f"execute positioned {bx} {by} {bz} run locate biome minecraft:plains")[:0])
r.cmd(f"tp slashdaemon {bx + 3.5} {by + 3} {bz + 0.5} 90 35")
time.sleep(5)
subprocess.run([sys.executable, os.path.join(ROOT, "scripts/client.py"), "key", "f1"])
subprocess.run([sys.executable, os.path.join(ROOT, "scripts/client.py"), "snap", os.path.join(out, f"biome-{label}.png")])
subprocess.run([sys.executable, os.path.join(ROOT, "scripts/client.py"), "key", "f1"])
