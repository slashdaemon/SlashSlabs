# SlashSlabs

**Walk the world without jumping.** SlashSlabs is a server-side Fabric mod that puts a half-slab
step wherever generated terrain rises by exactly one block. Grass, dirt, sand, red sand,
terracotta, stone and snow all get matching steps, and the new slabs (grass, dirt, sand, red sand
and seven terracottas) become real, placeable blocks.

Players need **no client mod**. A stock vanilla client connects and sees real-looking grass slabs:
each custom slab is sent to the client as a spare vanilla slab state, which a server resource pack
retextures. The server delivers that pack automatically.

| | |
|---|---|
| Minecraft | **26.1.2 · 26.2 · 26.3** (one jar per line) |
| Loader | Fabric Loader ≥ 0.18.4, **Fabric API** required |
| Client | Vanilla, or any client; nothing to install |
| Bundled | [Polymer](https://github.com/Patbox/polymer) (LGPL-3.0), nested in the jar |
| Side | Server only (dedicated servers; single-player is untested) |

---

## Contents

- [What it does](#what-it-does)
- [How it works](#how-it-works)
- [Installing on a server](#installing-on-a-server)
- [Configuration](#configuration)
- [Commands and permissions](#commands-and-permissions)
- [Blocks, items and recipes](#blocks-items-and-recipes)
- [Compatibility](#compatibility)
- [Limitations](#limitations)
- [Removing SlashSlabs](#removing-slashslabs)
- [Building from source](#building-from-source)
- [Testing](#testing)
- [Project layout](#project-layout)
- [FAQ](#faq)
- [Licence](#licence)

---

## What it does

In every newly generated chunk, SlashSlabs finds each column whose neighbour (north, south,
east or west) is exactly one block higher. It puts a bottom slab on top of the lower column, so
the rise becomes two half steps, which any player walks up.

| Surface at a one-block rise | Step placed |
|---|---|
| Grass block | **Grass slab**, textured in the nearest of three greens for the biome |
| Dirt, coarse dirt, rooted dirt, podzol, mycelium | **Dirt slab** |
| Sand · red sand | **Sand slab** · **red sand slab** |
| Terracotta: plain, orange, yellow, brown, red, white, light gray (the badlands colours) | The matching **terracotta slab** |
| Stone, cobblestone, mossy cobblestone, deepslate, tuff, andesite, diorite, granite, blackstone, sandstone, red sandstone, mud | The matching vanilla slab (mud → mud brick) |
| Grass or dirt in a snowy biome, snow blocks | Snow, 5 layers (a 0.5 step each way) |
| Shallow water on the lower side | The waterlogged slab (shorelines) |

It leaves alone:

- cliffs (rises of two blocks or more);
- structures such as villages, ruins and towers (every structure piece is guarded);
- columns with a flower, sapling or anything solid on top;
- deep water, and anything next to lava.

Short grass, ferns, leaf litter and other small single plants on the step are removed. Grass
under a new slab turns into dirt, as vanilla grass would under a covering block.

Only the finished surface is read, so it works on top of any terrain generator or worldgen
datapack.

## How it works

- **Vanilla-client blocks.** A vanilla client only knows vanilla blocks. Polymer keeps pools of
  vanilla states nobody sees in normal play, and shows the real blocks those states belong to
  with a lookalike state instead. SlashSlabs borrows two kinds, each with exactly a slab's shape:
  - bottom slabs use **sculk sensor** states with an unused redstone power level (150 of them);
  - top slabs use the four **waxed copper slab** states.

  The client uses the borrowed state for collision and movement, so walking, sprinting and
  jumping stay in sync with the server. The resource pack draws each borrowed state as the right
  slab.
- **Server-side mining.** Polymer runs mining on the server, so a grass slab breaks at grass
  speed, not copper speed, with the crack animation and no ghost blocks.
- **Sounds.** Break particles and sounds come from the real block (grass, gravel, sand,
  terracotta). Footstep, fall and hit sounds are replayed by the server with the right sound,
  because the client would otherwise play sculk sensor or copper sounds.
- **Textures without Mojang pixels.** The mod jar contains no Minecraft textures. At startup the
  server takes the vanilla client jar (which Polymer downloads from Mojang), tints the grass
  textures to the three palette greens and writes them into the server pack.
- **Grass colour.** Each generated or placed grass slab picks the palette green nearest (in CIELAB)
  to the colour vanilla would draw on real grass at that spot. Biome blending, biome overrides
  and the swamp and dark-forest modifiers are included.
- **Deterministic worldgen.** Smoothing runs when a chunk reaches its LIGHT step. By then every
  neighbouring chunk has finished placing features, so nothing else writes into the chunk and
  the result doesn't depend on the order chunks were generated in. The cost is about 1 ms per
  chunk, around 2% of generation time.

## Installing on a server

1. Download the jar matching your Minecraft line (`slashslabs-<version>+mc<mc>-fabric.jar`) and put
   it in `mods/` next to Fabric API.
2. Start the server once. It writes `config/slashslabs.json` and `config/polymer/auto-host.json`.
3. **Turn on the resource pack host** in `config/polymer/auto-host.json`:

   ```json
   {
     "enabled": true,
     "required": true,
     "type": "polymer:automatic",
     "message": "This server needs its resource pack for terrain slabs."
   }
   ```

   `polymer:automatic` serves the pack over the **game port**, so no extra port or web host is
   needed. Behind a proxy (Velocity, BungeeCord, TCPShield), same-port hosting doesn't work; use
   `polymer:http_server` (a separate port) or `polymer:external` (your own URL). See
   [Polymer's documentation](https://polymer.pb4.eu/).
4. Adjust `config/slashslabs.json` if needed (see below) and restart.

With `"required": true`, a player who declines the pack is disconnected with a clear message.
Without the pack, bottom slabs look like sculk sensors and top slabs like copper slabs.

**Existing worlds:** only chunks generated after installing are smoothed. To smooth terrain you've
already generated, stand in it and run `/slashslabs smooth <radius>`. It skips any column capped by
something other than natural terrain and natural cover, so player builds, paths and floors are left
alone.

## Configuration

`config/slashslabs.json`. Block states and resource slots are fixed at startup, so every change
needs a restart.

| Key | Default | Meaning |
|---|---|---|
| `worldgen` | `true` | Smooth newly generated chunks |
| `hookStage` | `"light"` | `"light"`: smooth at each chunk's LIGHT step (order-independent). `"features"`: at the end of the chunk's own feature pass (earlier; neighbours decorated later can still overwrite a few steps) |
| `diagonals` | `false` | Also step rises that only touch diagonally |
| `dimensions` | `["minecraft:overworld"]` | Dimension ids to smooth |
| `excludeBiomes` | `[]` | Biome ids never smoothed, e.g. `["minecraft:mushroom_fields"]` |
| `structureGuard` | `true` | Skip columns inside structure pieces |
| `maxWaterDepth` | `1` | Deepest water a waterlogged step may be placed in (`0` = never under water) |
| `snowLayers` | `5` | Snow layers used at a rise in snowy biomes (5 gives a half step both ways) |
| `grassPalette` | `["#79C05A", "#88BB67", "#86B783"]` | Grass slab greens (at most 3) |
| `materialOverrides` | `{}` | Per surface block: a slab id, `"snow"` or `"none"`. Example: `{"minecraft:gravel": "minecraft:andesite_slab", "minecraft:podzol": "none"}` |
| `stepHeight` | `false` | Companion module: players step up full blocks (step height 1.0) |
| `stepHeightOffWhileSneaking` | `true` | …except while sneaking |
| `animalSpawnsOnGrassSlabs` | `false` | Let animals spawn on grass slabs (vanilla spawns nothing on slabs) |

**Choosing the greens.** The default palette was fitted to 67,000 rise-edge samples across 14
biomes (see [`docs/SURVEY.md`](docs/SURVEY.md)). If your world is mostly savanna, jungle or swamp,
run `/slashslabs survey 8` in typical terrain. It prints the best-fitting 1-, 2- and 3-colour
palettes for that area, which you can paste into `grassPalette`.

**Step height module.** With `stepHeight` on, players walk up any one-block rise, including player
builds and unsmoothed terrain. It is a vanilla attribute, so vanilla clients honour it. Bedrock
players (via Geyser/Floodgate) are skipped, because Geyser can't translate it.

## Commands and permissions

All commands need **op level 2**. With LuckPerms, or any provider of `fabric-permissions-api`,
they check the node `slashslabs.command.base` plus the sub-command's node, falling back to op
level 2.

| Command | Node | What it does |
|---|---|---|
| `/slashslabs info` | `info` | Slot use, palette, and worldgen counters (chunks, blocks, ms per chunk) |
| `/slashslabs smooth <radius>` | `smooth` | Smooth already generated chunks around you (radius in chunks, max 64) |
| `/slashslabs purge <radius> [stand_in]` | `purge` | Turn every SlashSlabs block back into vanilla, or into a vanilla slab `stand_in` (e.g. `minecraft:mud_brick_slab`) |
| `/slashslabs survey <radius>` | `survey` | Rise-edge surface statistics and fitted palettes; JSON report in `slashslabs/` |
| `/slashslabs test-field [raw]` | `test_field` | Build a stepped test patch next to you (`raw` = without slabs) |
| `/slashslabs selftest` | `selftest` | Run the server-side self-test (about 730 checks) |
| `/slashslabs dump`, `dumpcols`, `gen` | `dump`, `dumpcols`, `gen` | Diagnostics used by the determinism and cost tests |

Vanilla clients open the SlashSlabs creative tab with Polymer's `/polymer creative`.

## Blocks, items and recipes

| Block | Behaviour |
|---|---|
| **Grass slab** | Spreads to dirt and dirt slabs, and dies to a dirt slab under a covering block or under water, like grass. Drops a dirt slab, or itself with Silk Touch. Mines at grass speed with a shovel. Bone meal, hoes and shovels do nothing. Plants can't be placed on it (as on any slab) |
| **Dirt slab** | Picks up grass from nearby grass blocks under the same light rules as vanilla dirt. Drops itself |
| **Sand slab**, **red sand slab** | Don't fall. Mine with a shovel |
| **Terracotta slabs** (7 colours) | Bottom half only: clicking the upper half of a block still places a bottom slab. Need a pickaxe to drop, like terracotta |

- Placing follows vanilla slab rules (top or bottom half by where you click, waterlogging),
  except that terracotta slabs are bottom-only. Placing a second slab of the same kind on a slab
  gives the **vanilla full block** (grass block, dirt, sand, terracotta …), so no double-slab block
  ever exists.
- Top grass slabs show the first palette green whatever the biome (only four top slots exist).
- The grass slab picks its green from the biome where it is placed.
- Mobs don't spawn on the slabs by default.

| Recipe | |
|---|---|
| 3 grass blocks / dirt / sand / red sand / terracotta in a row | 6 slabs |
| 2 slabs | 1 block |
| Stonecutter: dirt, sand, red sand or terracotta | 2 slabs |

## Compatibility

Tested with the shipped jars on real servers (see [`docs/TESTING.md`](docs/TESTING.md)):

- **Minecraft 26.1.2, 26.2, 26.3** on Fabric.
- A large 26.1.2 modpack. Performance: C2ME, Lithium, Krypton, FerriteCore. Worldgen: Geophilic,
  Explorify, Structory, Towns & Towers, Dungeons & Taverns, Moog's structures, Katters Structures,
  Sparse Structures, Incendium, Nullscape, Amplified Nether. Also Voxy World Gen V2, BlueMap,
  Geyser/Floodgate, LuckPerms, Ledger, Chunky.
- **BlueMap** renders the slabs from the models inside the jar; no extra BlueMap pack needed.
- **Voxy** LODs on the client show the retextured slabs.
- **Other Polymer mods**: fine, as long as they don't need the same slots (see below).

## Limitations

- **Slots, shared.** Borrowed states are shared by every Polymer mod on the server. Bottom slabs
  take 13 of Polymer's 150 sculk sensor states. Top slabs take all four copper slots: dirt, sand,
  red sand, and one grass green. A material that gets no slot falls back to a vanilla lookalike
  slab (mud brick for dirt, smooth sandstone for sand, smooth red sandstone for red sand), or is
  not smoothed at all (terracotta). `/slashslabs info` and `/polymer blocks_module_state_report`
  show who has what.
- **Faint glow.** Sculk sensors give off light level 1 on the client. After a slab is placed or
  changes while a player watches, the client may show a barely visible glow next to it. Chunks
  that load normally show the server's light and don't glow.
- **Colour steps.** A grass slab has one of three fixed greens, while real grass shades smoothly.
  Where a biome's green is far from all three (savanna and badlands yellows, swamp olive, jungle
  green), a slab can look slightly off next to the grass beside it.
- **Copper and sculk sensor sounds.** To give slabs their proper footstep sounds, the step, hit
  and fall sounds of copper blocks and sculk sensors are played by the server instead of the
  client: same sounds, with network latency.
- **Resource pack needed.** Without it, players see sculk sensors (bottom slabs) and copper slabs
  (top slabs). Bedrock players (Geyser) see the same for now.
- **Upgrading from 0.1.0.** Every slab now uses a different borrowed state. Clients' Voxy LODs of
  already-explored terrain show the old look until Voxy rebuilds them. Remove the `sandSlab`
  option from `config/slashslabs.json`; it is ignored, and sand always gets a real slab now.
- **One-block rises only.** Two-block rises, diagonal corners (unless `diagonals`) and structure
  paths stay as they are.
- **Existing chunks** are only smoothed on request (`/slashslabs smooth`).

## Removing SlashSlabs

A world saved with SlashSlabs contains `slashslabs:*` blocks. Without the mod they load as air,
which leaves half-block holes. To remove it cleanly:

1. Run `/slashslabs purge <radius>` over the areas players have explored (grass slabs become air
   and the grass below is restored; waterlogged slabs become water). Or give a stand-in, e.g.
   `/slashslabs purge 32 minecraft:mud_brick_slab`, to keep the steps as vanilla slabs.
2. Stop the server and remove the jar.

`purge` never smooths the chunks it has to load or generate.

## Building from source

Requirements: **JDK 25**. The Gradle wrapper (9.6.1) and Fabric Loom (1.17.21) download
themselves.

```bash
git clone https://github.com/slashdaemon/SlashSlabs.git
cd SlashSlabs
./gradlew buildAll            # all three jars -> build/release/
./gradlew :versions:26.3:build   # a single Minecraft line
```

One source tree builds every line. The only per-version difference is the loot-table format,
kept in `compat/loot-legacy` (26.1.2, 26.2) and `compat/loot-263` (26.3).

## Testing

```bash
python scripts/regress.py doctor    # preflight: JDK, ports, stale JVMs, jars, run config
python scripts/regress.py quick     # unit tests + one band's self-test
python scripts/regress.py gate      # the release gate: shipped jars on real Fabric servers
python scripts/regress.py full      # everything unattended; add --client for the client checks
```

`regress.py` front-ends the individual scripts, which can still be run directly:

```bash
./gradlew :versions:26.1.2:test                                     # unit tests
python scripts/devserver.py --band 26.3 --fresh -c "slashslabs selftest"   # dev server self-test
python scripts/prodtest.py 26.1.2 26.2 26.3                          # shipped jars on real Fabric servers
python scripts/determinism.py 8                                      # generation-order independence + cost
python scripts/clienttest.py                                         # vanilla-client checks (attended)
```

Run them by hand and two traps are yours to avoid: `determinism.py` leaves `worldgen=false` in
`versions/26.1.2/run/config/slashslabs.json`, and the self-test still reports 399/399 with the
worldgen hook off — so read the `/slashslabs info` line. And `--fresh` silently keeps a world
Windows has locked. `regress.py` handles both.

`JAVA25_HOME` (or `JAVA_HOME`) must point at a JDK 25. The scripts run servers on
`127.0.0.1` with RCON for automation; they're for local testing, not production.
[`docs/TESTING.md`](docs/TESTING.md) records what was tested, including vanilla-client movement,
mining and placement checks, and what still needs a person. The ordering rules, the change→tier
mapping and how to read a failure are in
[`.claude/skills/slashslabs-testkit/SKILL.md`](.claude/skills/slashslabs-testkit/SKILL.md).

## Project layout

| Path | |
|---|---|
| `src/main/java/com/slashslabs/` | The mod: blocks, worldgen, pack generation, commands |
| `src/main/resources/` | Mod metadata, tags, recipes, BlueMap models, optional datapack |
| `compat/` | Per-version data (loot tables) |
| `versions/<mc>/` | One Gradle subproject per Minecraft line |
| `scripts/` | Dev/prod test harness (Python, standard library only) |
| `docs/PLAN.md`, `docs/RESEARCH.md` | Design and the research behind it (§10: what building it changed) |
| `docs/SURVEY.md`, `docs/TESTING.md` | Terrain survey and test record |

## FAQ

**Do players need to install anything?** No. They accept the server resource pack when they join.

**Does it work in single-player?** It isn't tested there. SlashSlabs is built and tested as a
dedicated-server mod.

**Why copper?** Polymer shows real waxed copper slabs to players as normal copper, which frees the
waxed states to act as custom slabs. Players can still use copper slabs normally.

**Can I change the greens later?** Yes. Change `grassPalette` and restart; existing slabs keep
their slot (tint 0–2) and simply show the new colour.

**Will it smooth my builds?** Worldgen only touches new terrain. `/slashslabs smooth` only touches
natural ground and skips any column with player blocks on it.

## Licence

Copyright © 2026 The Block Academy LLC. Licensed under
[Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/)
(CC BY 4.0, see [`LICENSE`](LICENSE)) from 0.2.1 on. The bundled Polymer libraries are LGPL-3.0;
see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
