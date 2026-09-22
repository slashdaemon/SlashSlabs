# SlashSlabs — test record

Companion to `PLAN.md` (M0, M3, M4) and `RESEARCH.md` §9. What was run, how to rerun it, and
what it showed. Everything here ran on MC 26.1.2 unless a band is named.

## Harness

| Tool | What it does |
|---|---|
| `./gradlew :versions:26.1.2:test` | JUnit: rise detection, colour maths, palette fitting (12 tests) |
| `python scripts/devserver.py --band <b> --fresh -c "slashslabs selftest"` | Loom dev server + RCON commands |
| `/slashslabs selftest` | 399 server-side checks: smoothing output on the six-strip fixture (grass, dirt, sand, stone, snow, shallow water; one-block steps and a two-block cliff), idempotence, Polymer backing state and collision for every block state, placement (bottom/top/double → vanilla block), drops with and without Silk Touch, recipes, tags, grass spread/pull/decay (dry and waterlogged), the D6 tag, purge |
| `python scripts/prodtest.py [26.1.2 26.2 26.3 tbs]` | Release gate: the **shipped** jars on real Fabric servers (Fabric server launcher, Fabric API from Modrinth); `tbs` adds the whole TBS 1.5.0 server modset |
| `python scripts/determinism.py 8` | Same seed, three generation orders + one run with smoothing off |
| `python scripts/survey.py 5` | M2 survey (see `SURVEY.md`) |
| `python scripts/client.py …` | Drives a Prism client on this PC: launch/auto-join, snap, keys, chat, clicks |

Prism instances made for this: `SlashSlabs-Vanilla-26.1.2` (stock vanilla, no loader) and
`SlashSlabs-Voxy-26.1.2` (Fabric API + Sodium + Voxy 0.2.18).

## Results

### Release gate (`prodtest.py`)

| Target | Self-test | Worldgen blocks in 11×11 chunks | SlashSlabs/Polymer errors | Unsafe-worldgen warnings |
|---|---|---|---|---|
| 26.1.2 | 398/398 | 3339 | 0 | 0 |
| 26.2 | 398/398 | 3339 | 0 | 0 |
| 26.3 | 398/398 | 3337 | 0 | 0 |
| tbs (26.1.2 + TBS modset: C2ME, Lithium, Krypton, Voxy World Gen V2, Geophilic, Explorify, Structory (+Towers), Towns & Towers, Dungeons & Taverns, Moog's, Katters, Sparse Structures, Incendium, Nullscape, Amplified Nether, BlueMap, Geyser/Floodgate, LuckPerms, Ledger …) | 398/398 | 3345 | 0 | 0 |

The first run caught a packaging bug that dev runs cannot see: `polymer-sound-patcher` declares a
dependency on `polymer-resource-pack-extras` in its `fabric.mod.json` but not its POM. Fabric
Loader silently drops a nested mod whose dependency is missing, and startup then failed with
`NoClassDefFoundError`. The extras module is now nested too.

### M0 — vanilla client (stock 26.1.2 client, no mods; AutoHost same-port pack)

| Check | Result |
|---|---|
| Join, pack download over the game port | Joined; client log has 0 errors (R14: our tags and registry entries don't reach the client) |
| Walk across the smoothed fixture (W only, no jump) | Climbs two one-block rises, stops at the two-block cliff (x 0 → 11.7, y 121 → 123) |
| Control: same fixture without slabs | Stops at the first rise (x 3.7) |
| Sprint, sprint-jump across the field | Same end point; 0 "moved wrongly/too quickly" warnings (R1) |
| Mine a grass slab by hand | Still there after 0.45 s, broken within 1.3 s (grass speed; copper by hand would take far longer). Drops a dirt slab, no ghost block (R2) |
| Place dirt slab on a block, then a second on it | Bottom slab, then a vanilla dirt block; one item used per click |
| Item name and icon | "Dirt Slab" with the pack model |
| Real waxed copper slabs (bottom, top, double) with the pack | Render as normal copper, no missing texture (R12 does not reproduce) |
| Decline the required pack | Disconnected: "This server requires a custom resource pack" (R6) |
| Generated terrain, plains/forest/savanna | Terraced grass slabs with textures; tint seams subtle at close range |
| Step-height module | Attribute 1.0 on the vanilla client. Walks the *unsmoothed* fixture up to the cliff; with sneak held stops at the first rise; still 1.0 after death and respawn |
| Voxy LODs (Voxy client, beyond render distance) | Smoothed plains, snow terraces and the village render green with the pack textures; no copper or black LODs |
| R11 — Voxy World Gen V2 on the server, Voxy client joined | 0 missing-packet-context warnings while flying over smoothed terrain |

The Voxy client could join the full TBS server only with StreamCraft and the Dungeons nether mod
set aside. Fabric API's registry sync refuses a Fabric client that lacks those two mods' entries.
That's a TBS client-pack matter, unrelated to SlashSlabs, whose Polymer entries are hidden.

### M3 — determinism and cost (`determinism.py`, 17×17 chunks at 4000,4000)

Same area generated in row order, reverse order and shuffled, each in a fresh world:

| Hook / height source | rows vs reverse | rows vs shuffle |
|---|---|---|
| end of FEATURES, WG heightmaps (the RESEARCH plan) | 96.6% identical | 97.9% |
| LIGHT step, WG heightmaps | 91.9% | 95.0% |
| **LIGHT step, natural-cover surface scan (shipped)** | **97.1%** | **97.9%** |

All remaining differences are columns where vanilla itself generated differently: a tree trunk,
canopy or flower stands there in one world and not the other. Vanilla's own tree and leaf-litter
placement depends on generation order; 21% of all surface columns differ with SlashSlabs off.
In every differing column the terrain heights are identical.

Cost: the hook takes 0.9–1.2 ms per chunk (0.44 ms under C2ME on the TBS modset) against about
48 ms per chunk for generation. Generating with it on vs off: 50.4 vs 50.2 ms/chunk.

### M4 — BlueMap (TBS modset, BlueMap 5.20)

Rendered the 128×128 area and decoded the hires tiles. All 553 generated slabs have their top
face at half height, as do floating marker slabs. BlueMap reads the models from the mod jar
(`scan-for-mod-resources`), so the separate `packs/` zip (RESEARCH C13) isn't needed. Note:
BlueMap hires tiles sit on a +2 block grid offset (the column at x lives in tile ⌊(x−2)/32⌋).

Not verified: whether BlueMap applies `blockColors.json` (`@grass`) from the jar. That needs a
look at the web map; Chrome on this machine couldn't open the local web server.

## Open items needing a person

- **Sounds** (D3): break/place/step/fall with the sound patcher, heard by the actor and an
  observer. The code path is in place (copper sounds converted to server sounds, break event
  mapped to grass/dirt/sand), but nobody has listened.
- **Sneak at slab edges**: injected keys slow the player but don't trigger vanilla's edge stop,
  on vanilla stone slabs and full blocks alike, so this needs a real keyboard. Our slabs share
  the vanilla slab collision exactly (self-test compares every state).
- **Boat / horse / minecart over slabs**: same collision as vanilla slabs, not ridden.
- **Mining feel at 100+ ms ping** (server-side mining).
- **Tint seams** in savanna, swamp, jungle, badlands edge: judged by eye, not only by ΔE.
- **Bedrock via Geyser** (M8): not tested; Bedrock players would see copper slabs.
- **AutoHost via the real TBS hostname/SRV record** on Bloom.host.
