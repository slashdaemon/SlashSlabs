"""Writes the static data and assets of SlashSlabs' plain slab materials.

    python scripts/gen_material_data.py

Plain materials are the ones with no behaviour of their own (sand, red sand, the badlands
terracottas); keep MATERIALS in step with ModBlocks.PLAIN. Per material it writes the three
recipes, the loot table for both loot variants (compat/loot-legacy, compat/loot-263), and the
BlueMap blockstate and models. It also writes the files every material shares: the #slabs and
#mineable tags and the jar's en_us.json. Grass and dirt keep their hand-written recipes and loot
tables (Silk Touch, grass spread), but they are listed in the shared files.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"
ASSETS = RES / "assets/slashslabs"
DATA = RES / "data"

# (slab id, vanilla full block, mined with)
MATERIALS = [
    ("sand_slab", "sand", "shovel"),
    ("red_sand_slab", "red_sand", "shovel"),
    ("terracotta_slab", "terracotta", "pickaxe"),
    ("orange_terracotta_slab", "orange_terracotta", "pickaxe"),
    ("yellow_terracotta_slab", "yellow_terracotta", "pickaxe"),
    ("brown_terracotta_slab", "brown_terracotta", "pickaxe"),
    ("red_terracotta_slab", "red_terracotta", "pickaxe"),
    ("white_terracotta_slab", "white_terracotta", "pickaxe"),
    ("light_gray_terracotta_slab", "light_gray_terracotta", "pickaxe"),
]
# Special materials, listed in the shared tag and lang files only.
SPECIAL = [("grass_slab", "Grass Slab", "shovel"), ("dirt_slab", "Dirt Slab", "shovel")]


def minified(path: Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, separators=(",", ":")) + "\n", encoding="utf-8", newline="\n")


def pretty(path: Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1), encoding="utf-8", newline="\n")


def title(full: str) -> str:
    return " ".join(w.capitalize() for w in full.split("_"))


def face(uv, texture, cull):
    f = {"uv": uv, "texture": texture}
    if cull:
        f["cullface"] = cull
    return f


def slab_model(texture: str, upper: bool):
    faces = {
        "down": face([0, 0, 16, 16], "#bottom", None if upper else "down"),
        "up": face([0, 0, 16, 16], "#top", "up" if upper else None),
    }
    for d in ("north", "south", "west", "east"):
        faces[d] = face([0, 0, 16, 8], "#side", d)
    return {
        "parent": "minecraft:block/block",
        "textures": {"particle": texture, "top": texture, "side": texture, "bottom": texture},
        "elements": [{"from": [0, 8 if upper else 0, 0], "to": [16, 16 if upper else 8, 16], "faces": faces}],
    }


def material(slab: str, full: str):
    item, block = f"slashslabs:{slab}", f"minecraft:{full}"
    recipe = DATA / "slashslabs/recipe"
    minified(recipe / f"{slab}.json", {"type": "minecraft:crafting_shaped", "category": "building",
                                       "key": {"#": block}, "pattern": ["###"], "result": {"count": 6, "id": item}})
    minified(recipe / f"{full}_from_slabs.json", {"type": "minecraft:crafting_shapeless", "category": "building",
                                                   "ingredients": [item, item], "result": {"id": block}})
    minified(recipe / f"{slab}_from_stonecutting.json", {"type": "minecraft:stonecutting", "ingredient": block,
                                                          "result": {"count": 2, "id": item}})

    seq = f"slashslabs:blocks/{slab}"
    minified(ROOT / f"compat/loot-legacy/resources/data/slashslabs/loot_table/blocks/{slab}.json",
             {"type": "minecraft:block", "pools": [{"rolls": 1.0, "bonus_rolls": 0.0, "entries": [{"type": "minecraft:item", "name": item}],
                                                    "conditions": [{"condition": "minecraft:survives_explosion"}]}],
              "random_sequence": seq})
    minified(ROOT / f"compat/loot-263/resources/data/slashslabs/loot_table/blocks/{slab}.json",
             {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": item}],
                                                    "condition": {"type": "minecraft:survives_explosion"}}],
              "random_sequence": seq})

    texture = f"minecraft:block/{full}"
    pretty(ASSETS / f"models/block/bluemap/{slab}_bottom.json", slab_model(texture, False))
    pretty(ASSETS / f"models/block/bluemap/{slab}_top.json", slab_model(texture, True))
    variants = {}
    for kind in ("bottom", "top", "double"):
        for wl in ("false", "true"):
            model = f"slashslabs:block/bluemap/{slab}_{kind}" if kind != "double" else f"minecraft:block/{full}"
            variants[f"type={kind},waterlogged={wl}"] = {"model": model}
    pretty(ASSETS / f"blockstates/{slab}.json", {"variants": variants})


def shared():
    everything = [(s, n, t) for s, n, t in SPECIAL] + [(s, title(f) + " Slab", t) for s, f, t in MATERIALS]
    ids = [f"slashslabs:{s}" for s, _, _ in everything]
    tags = DATA / "minecraft/tags"
    minified(tags / "block/slabs.json", {"replace": False, "values": ids})
    minified(tags / "item/slabs.json", {"replace": False, "values": ids})
    for tool in ("shovel", "pickaxe"):
        minified(tags / f"block/mineable/{tool}.json",
                 {"replace": False, "values": [f"slashslabs:{s}" for s, _, t in everything if t == tool]})
    lang = {f"block.slashslabs.{s}": n for s, n, _ in everything}
    lang["itemGroup.slashslabs.slabs"] = "SlashSlabs"
    pretty(ASSETS / "lang/en_us.json", lang)


if __name__ == "__main__":
    for slab, full, _ in MATERIALS:
        material(slab, full)
    shared()
    print(f"wrote {len(MATERIALS)} plain materials + shared tags and lang")
