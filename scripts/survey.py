"""M2 surface survey: on a running dev server started with worldgen=false, surveys an area in
each of several biomes and aggregates the reports (rise-edge surface materials and the grass
colours a palette must cover). Prints a summary and a combined palette fit.
    python scripts/survey.py [radius] [biome ...]"""
import glob, json, os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
from rcon import Rcon

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
radius = int(sys.argv[1]) if len(sys.argv) > 1 else 6
biomes = sys.argv[2:] or ["plains", "forest", "birch_forest", "taiga", "savanna", "jungle", "swamp", "badlands",
                          "dark_forest", "meadow", "cherry_grove", "sunflower_plains", "windswept_hills", "old_growth_spruce_taiga"]
r = Rcon(25581, "slashslabs", timeout=1800)
reports = os.path.join(ROOT, "versions/26.1.2/run/slashslabs")
before = set(glob.glob(os.path.join(reports, "survey-*.json")))
for b in biomes:
    loc = r.cmd(f"execute positioned 0 70 0 run locate biome minecraft:{b}")
    m = re.search(r"at \[(-?\d+), (-?\d+|~), (-?\d+)\]", loc)
    if not m:
        print(b, "not found:", loc[:80]); continue
    x, z = m.group(1), m.group(3)
    out = r.cmd(f"execute positioned {x} 70 {z} run slashslabs survey {radius}")
    print(f"== {b} @ {x},{z}\n{out}")
files = sorted(set(glob.glob(os.path.join(reports, "survey-*.json"))) - before)
ground, result, colors = {}, {}, []
for f in files:
    d = json.load(open(f))
    for k, v in d["byGround"].items(): ground[k] = ground.get(k, 0) + v
    for k, v in d["byResult"].items(): result[k] = result.get(k, 0) + v
    colors += d["grassColors"]
total = sum(ground.values())
print(f"\n=== combined over {len(files)} areas: {total} rise columns")
for k, v in sorted(ground.items(), key=lambda e: -e[1])[:15]:
    print(f"  {k:40s} {v:7d}  {100 * v / total:5.1f}%")
print("would place:")
tr = sum(result.values())
for k, v in sorted(result.items(), key=lambda e: -e[1])[:15]:
    print(f"  {k:40s} {v:7d}  {100 * v / tr:5.1f}%")
json.dump({"ground": ground, "result": result, "grassColors": colors}, open(os.path.join(reports, "survey-combined.json"), "w"))
print("combined grass colour samples:", len(colors))
