"""M3 determinism + cost test. Generates the same area of the same seed in several chunk orders
(fresh world each time) and compares what smoothing placed; then times generation with
smoothing off for the cost comparison.
    python scripts/determinism.py [radius] [x z]"""
import os, re, subprocess, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
radius = int(sys.argv[1]) if len(sys.argv) > 1 else 8
x, z = (sys.argv[2], sys.argv[3]) if len(sys.argv) > 3 else ("4000", "4000")
dumps = os.path.join(ROOT, "versions/26.1.2/run/slashslabs")

def run(world, order, worldgen=True):
    out = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/devserver.py"), "--band", "26.1.2", "--fresh", "--world", world,
                          "--seed", "determinism", "--config", f"worldgen={'true' if worldgen else 'false'}",
                          "-c", f"execute positioned {x} 70 {z} run slashslabs gen {radius} {order}",
                          "-c", f"execute positioned {x} 70 {z} run slashslabs dump {radius - 1} {world}",
                          "-c", "slashslabs info"], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    gen = re.search(r"GEN (\d+) chunks (\d+) ms \(([\d.]+) ms/chunk", out)
    hook = re.search(r"worldgen: (\d+) chunks smoothed, (\d+) blocks placed, ([\d.]+) ms/chunk", out)
    dump = re.search(r"DUMP (\d+) ([0-9a-f]+)", out)
    print(f"{world:8s} order={order:8s} worldgen={worldgen}: gen {gen.group(3) if gen else '?'} ms/chunk; "
          f"hook {hook.group(3) + ' ms/chunk over ' + hook.group(1) + ' chunks' if hook else '?'}; "
          f"dump {dump.group(1) + ' ' + dump.group(2)[:16] if dump else '?'}")
    if not gen: print(out[-3000:])
    return os.path.join(dumps, f"dump-{world}.txt")

a = run("det-a", "rows")
b = run("det-b", "reverse")
c = run("det-c", "shuffle")
off = run("det-off", "rows", worldgen=False)
A = set(open(a).read().split("\n")) - {""}
for name, p in (("reverse", b), ("shuffle", c)):
    B = set(open(p).read().split("\n")) - {""}
    same = len(A & B)
    print(f"rows vs {name}: {len(A)} / {len(B)} entries, {same} identical ({100 * same / max(1, len(A | B)):.3f}% of union); "
          f"only-rows {len(A - B)}, only-{name} {len(B - A)}")
    for l in sorted(A ^ B)[:10]: print("   diff:", l, "(rows)" if l in A else f"({name})")
O = set(open(off).read().split("\n")) - {""}
print(f"worldgen off: {len(O)} matching blocks exist naturally (vanilla slabs / snow layers)")
