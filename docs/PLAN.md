# SlashSlabs — Plan

A **server-side-only** Fabric mod that generates terrain with half-slab steps (grass, dirt,
sand, snow, stone) so walking across the world does not require jumping at every one-block
rise, and adds those slabs as placeable blocks. A **stock vanilla client** joins and sees
real-looking grass slabs — no client mod — because every custom block is shown to the
client as a spare vanilla slab state that a server resource pack retextures.

First consumer: **The Block Survival (TBS)** world reset. Second: a standalone release in the
Block Academy mod portfolio (a vanilla-client terrain-slab mod is not a common offering).

---

## 1. Goals and non-goals

**Goals**

- Terrain smoothing at world generation: wherever the surface rises by exactly one block,
  a bottom slab of the matching material sits on the lower block.
- Vanilla-client safe: a vanilla client connects, walks, mines, and places without desync.
  Only a server resource pack (auto-delivered) is needed for correct visuals.
- Works on top of any terrain generator (vanilla, Geophilic, Tectonic, Terralith …) because
  it reads only the final surface.
- Placeable slab items with vanilla-like behaviour (grass spreading, shovel paths, silk touch).
- Clean removal path: a command that converts every SlashSlabs block back to vanilla.

**Non-goals**

- NeoForge / Forge builds. Polymer is Fabric-only; so is SlashSlabs.
- Retrofitting already-generated chunks by default. Offered only as an explicit admin command.
- Terrain stairs, vertical slabs, or smoothing cliffs taller than one block (possible later
  extensions, see §11).
- Bedrock visuals (Geyser) — a separate, optional milestone (§9, M8).

---

## 2. The core mechanism

A vanilla client knows only vanilla blocks. For each custom block the server sends a vanilla
block state that the client already understands. The client uses **that** state for collision,
movement prediction, and the model it draws. So a grass slab has to be shipped as a vanilla
state that:

1. has **bottom-slab collision** (so walking and stepping match the server exactly), and
2. is **visually unused** in normal play (so the resource pack can retexture it freely).

**Polymer** (Patbox; `polymer-blocks` module) provides exactly this as a managed pool.
Verified against Polymer source (`DefaultModelData.addSlabs`, branch `dev/1.21.11`):

| Polymer model type | Backing vanilla states |
|---|---|
| `BOTTOM_SLAB`, `TOP_SLAB` (+ `_WATERLOGGED`) | `WAXED_CUT_COPPER_SLAB`, `WAXED_EXPOSED_CUT_COPPER_SLAB`, `WAXED_WEATHERED_CUT_COPPER_SLAB`, `WAXED_OXIDIZED_CUT_COPPER_SLAB`, `PETRIFIED_OAK_SLAB` |

Polymer already remaps the **real** waxed copper slabs to their unwaxed look for clients
(`SPECIAL_REMAPS`), and petrified oak slab cannot be obtained in survival. Each slab model
type (bottom, top, bottom-waterlogged, top-waterlogged) is its own pool of five states, but
`PolymerBlockResourceUtils.requestBlock` never hands out the last free state of a pool: it is
reserved as the shared "empty" state (`requestEmpty`). Copper states are handed out first, so
the petrified oak state is always the reserved one. Result: **four slab "looks" are
available**, all waxed copper, each with bottom / top / waterlogged variants.

**The slot budget is the defining design constraint.** One custom slab material consumes one
slot in each of the four slab pools (bottom, top, and their waterlogged variants). Four
custom materials maximum, shared with any other Polymer slab mod on the same server (TBS
runs none today). Double slabs are never custom: a double grass slab becomes a vanilla
`grass_block`.

> **Revised by research (`RESEARCH.md` C16, §2.6).** The four-material ceiling binds **top
> slabs only**. Polymer's `SCULK_SENSOR` pools (150 states, + waterlogged) have exactly the
> bottom-slab shape, so bottom slabs can carry as many materials as the palette needs. Top
> slabs keep the four copper slots.

Polymer builds exist for every target line (Modrinth, `polymer` project):

| MC | Polymer |
|---|---|
| 26.1.2 | `0.16.5+26.1.2` |
| 26.2 | `0.17.5+26.2` |
| 26.3 | `0.18.2+26.3` |

---

## 3. Material strategy — spend slots only where vanilla has no slab

| Surface | Source | Slot cost |
|---|---|---|
| Stone, cobblestone, deepslate, tuff, andesite/diorite/granite, blackstone | Real vanilla slabs | 0 |
| Sandstone, red sandstone, mud (→ mud brick slab) | Real vanilla slabs | 0 |
| Snow | Vanilla **snow layers** (see note) | 0 |
| **Grass** | Custom, one slot per tint palette | 1–3 |
| **Dirt** (coarse dirt, rooted dirt, podzol fall back here) | Custom | 1 |
| **Sand** / red sand / gravel / path | Custom or fallback, decided by survey (M2) | 0–1 |

**Allocation is data-driven.** A survey command (M2) samples generated chunks on each target
seed/modset and counts the surface material at every one-block rise. The four slots go to the
highest-frequency materials that vanilla cannot cover; the rest fall back (podzol → dirt slab,
gravel → skip, path → skip).

**Snow note.** Snow layer collision is one layer lower than its visual height
(`layers=4` renders at 8/16 but collides at 6/16). Both step fine; pick the layer count that
looks right at a rise (likely 4). Snow cannot rest on a bottom slab, so snowy biomes use
snow layers instead of grass slabs.

### Grass tint

The client tints grass by biome only for real grass blocks; a retextured copper slab gets one
fixed colour. Mitigation:

- Bake a small **palette** of grass-slab textures (e.g. temperate, dry/warm, lush/cold).
- At generation, compute each column's vanilla grass colour server-side (bundled colormap +
  biome temperature/downfall, honouring biome `grass_color` overrides) and pick the nearest
  palette entry.
- Palette size trades against other materials in the four-slot budget (decided in M2).
- Accepted residual: a visible colour step where a slab meets a full grass block in a biome
  whose green is far from every palette entry. Tested explicitly in M0.

---

## 4. Terrain-smoothing algorithm

Runs once per chunk at the end of feature generation (after trees, structures, and the
top-layer freeze).

1. **Surface snapshot.** For each column in the chunk plus a one-column border, find the
   terrain surface: the highest block that is a terrain material (stone, dirt family, sand,
   gravel, snow …), ignoring logs, leaves, plants, and fluids. This is feature-independent,
   so the result is deterministic regardless of neighbour decoration order.
2. **Rise test.** For column `c` with surface `y`: if any of the four horizontal neighbours has
   surface exactly `y + 1`, mark `c` for a slab at `y + 1`. Rises of two or more (cliffs) are
   skipped. Diagonal-only rises: configurable, default off.
3. **Placement guards.** Skip when the target block at `y + 1` is not air or replaceable
   vegetation (short grass/fern are removed; flowers and saplings skip the column); when the
   column is inside any structure piece's bounding box; when directly under a tree canopy
   trunk; when adjacent to lava.
4. **Material pick.** The slab material follows the **higher neighbour's** surface block (the
   slab visually extends that step), mapped through §3. Grass picks its palette entry by the
   column's biome colour. Snowy surfaces use snow layers.
5. **Water.** A target block that is water gets the waterlogged variant (shorelines).
6. **Below the slab.** A grass block under a new slab is set to dirt (vanilla behaviour for grass
   under a full-face covering; avoids a random-tick flip later).
7. Single pass over the snapshot — slabs never create new rises that trigger more slabs.

**Hook choice** (decided in M3): a Fabric `BiomeModifications` feature at
`TOP_LAYER_MODIFICATION` versus a mixin at the end of the features chunk status. The latter
guarantees it runs after `freeze_top_layer` and every modded feature; the former is simpler
and mod-friendly. Prefer whichever gives deterministic ordering with the TBS worldgen set.

**Cost.** ~256 columns × short vertical scans + 4 neighbour lookups per chunk — negligible next
to noise generation. Confirmed under Chunky pregen with Spark in M3.

---

## 5. Block behaviour

Each custom slab is a Polymer block (server logic real, client sees the backing state).

| Behaviour | Rule |
|---|---|
| Placement | Vanilla slab rules: bottom/top by click position; second slab of same material → the vanilla full block (`grass_block`, `dirt`, `sand`). |
| Grass spread | Grass slab spreads to dirt slabs and dirt blocks under light rules equal to vanilla; grass under an opaque cover decays to dirt slab. |
| Tools | Shovel on grass/dirt slab → no path slab unless a slot is allocated (else no-op). Hoe → no-op (no farmland slab). |
| Drops | Grass slab drops dirt slab without Silk Touch, itself with Silk Touch. Mining speed matches the vanilla source block. |
| Bone meal | No effect (plants cannot sit on a half-height top). |
| Plants | Not in `#minecraft:dirt`, so flowers/saplings cannot be placed on slabs (client also refuses, because it sees a copper/oak slab). |
| Mob spawning | Off by default (matches vanilla bottom slabs); config flag to allow animal spawns on grass slabs. |
| Sounds | Server-played sounds (break/place) use grass/gravel/sand. Client-local footsteps follow the backing block — see §7 risk R3. |
| Grass colour change in items | Item icon uses the temperate palette entry. |

Items are Polymer items on a vanilla base with the `item_model` component pointing at a
resource-pack model (no `CustomModelData` needed on 26.x). Recipes: 3 blocks → 6 slabs,
stonecutter for dirt/sand; 2 slabs → 1 block.

---

## 6. Resource pack delivery

- Generated by `polymer-resource-pack` from SlashSlabs assets (backing-state models + textures,
  item models, optional sound remaps).
- Served by **Polymer AutoHost**. Preferred mode serves the pack over the game connection so
  Bloom.host needs no extra port (verified in M0; fallback: a static URL on
  theblock.academy/CDN with `server.properties` `resource-pack=`/`resource-pack-sha1=`).
- TBS sets `require-resource-pack=true` with a prompt message. A vanilla client still joins
  with zero installs; it just must accept the pack.
- **Contract update for TBS:** this is the Polymer path already sanctioned in
  `TBS-mod-strategy.md` ("Translates new content into vanilla packets via Polymer"). The
  strategy doc and TBS `CLAUDE.md` gain one line: *a server resource pack is required and
  auto-delivered; no client mod is.*
- **BlueMap** renders from world data, where blocks are saved as `slashslabs:*`. Ship a BlueMap
  resource pack (same assets under the `slashslabs` namespace) in `config/bluemap/packs/`.
- **Voxy** (TBS-Client LOD) renders from client block states + active resource packs; verified
  in M0.

---

## 7. Risks and unknowns

| # | Risk | Mitigation / test |
|---|---|---|
| R1 | Collision or movement desync (rubber-banding on slab edges) | M0 test matrix: walk, sprint, sprint-jump, sneak edges, boat/horse over slabs. Backing states share bottom-slab collision, so this is expected to pass. |
| R2 | Client-predicted mining speed follows the backing block (copper = hardness 3) | Confirm Polymer's break-speed handling in M0. If insufficient: server-side break progress + mining-fatigue masking. |
| R3 | Footsteps play client-side from the backing block (copper or wood sounds) | Options: accept; remap copper step sounds in the pack (also changes real copper, common since trial chambers). (The oak slot is not assignable: it is Polymer's reserved empty state.) Decided after hearing it in M0. |
| R4 | Grass tint mismatch at slab/grass seams | Palette + nearest-colour pick (§3). Judged visually across biomes in M0/M2. |
| R5 | Four-slot ceiling | Survey-driven allocation; vanilla slabs and snow layers cover the rest. Document the ceiling for other servers (conflicts with other Polymer slab mods). Top slabs only since C16: bottom slabs move to the sculk pools. |
| R6 | Players declining the pack see copper/oak slabs | `require-resource-pack=true`. For the public release, a documented config default. |
| R7 | Removal leaves half-block holes (unknown blocks load as air) | `/slashslabs purge <radius|all-loaded>` converts every custom slab to a vanilla stand-in (grass slab → air + grass below restored; or a chosen vanilla slab). |
| R8 | Worldgen ordering conflicts with other worldgen mods | Feature-independent surface probe; structure-bbox guard; M3 test against the full TBS worldgen set. |
| R9 | Polymer API differs across 26.1.2 / 26.2 / 26.3 | Single band for the TBS target first; multi-band only for the public release (M7). |
| R10 | Grass slab under snowfall / weather | Snow layers do not rest on bottom slabs, so snowy biomes use snow layers at generation; verify random snowfall does not leave odd tops. |

**Kill / pivot criterion (M0):** if R1 or R2 cannot be made clean with a vanilla client, stop
and fall back to the player **step-height** attribute alone (§8), which needs no custom blocks.

---

## 8. Companion: player step height

Independent of slabs, the vanilla `minecraft:step_height` attribute (player default 0.6) can be
raised to 1.0, letting players walk up any one-block rise. SlashSlabs includes this as an
optional module (config `stepHeight`, default off) applied as an attribute modifier on join,
with an option to disable while sneaking. Slabs make the landscape *look* smooth; step height
makes all terrain *feel* smooth, including player builds. Verify the vanilla client honours the
synced attribute in M0; Bedrock via Geyser likely does not.

---

## 9. Milestones (dependency-ordered)

### M0 — Feasibility spike
Depends on: nothing. Target: the TBS target MC version (see §10 D1).

- Gradle/Loom project, Polymer (`polymer-core`, `polymer-blocks`, `polymer-resource-pack`,
  `polymer-autohost`) as dependencies.
- One block: `slashslabs:grass_slab` (single palette entry) on a `BOTTOM_SLAB` state; one item.
- Resource pack via AutoHost.
- `/slashslabs test-field` places a patch of stepped terrain for manual testing.
- LocalServer + stock vanilla client, **test matrix**:
  - Walking, sprinting, sprint-jumping, sneaking off edges, riding horse/boat/minecart across.
  - Mining by hand and with a shovel: time-to-break and no ghost blocks.
  - Placement of top/bottom/double; double becomes `grass_block`.
  - Visuals with the pack accepted and declined; tint next to real grass in plains, jungle,
    savanna, swamp, badlands edge.
  - Footstep and break sounds.
  - Voxy LOD view (TBS-Client instance); BlueMap render.
  - Step-height attribute on a vanilla client.
- **Exit:** R1/R2 clean → M1. Otherwise pivot (§7).

### M1 — Block family and behaviour
Depends on: M0.
- Custom slab base class: bottom/top/waterlogged states, double-to-vanilla conversion.
- Grass (one palette entry) and dirt slabs with full §5 behaviour.
- Loot tables, recipes, tags, creative tab / `/give` coverage.
- Server-played sounds.

### M2 — Surface survey and palette
Depends on: M1 (blocks exist to allocate), the TBS worldgen set chosen.
- `/slashslabs survey <radius>` over generated chunks: histogram of rise-edge surface materials
  and grass-colour distribution per biome.
- Allocate the four slots (grass palette count vs dirt/sand/other) from the data.
- Palette textures; nearest-colour selection; remaining custom materials.

### M3 — Terrain-smoothing feature
Depends on: M2 (material map + palette).
- §4 algorithm; hook choice made; config (enable, diagonals, per-dimension, biome
  include/exclude, material overrides).
- Determinism test: same seed, different generation order → identical slabs.
- Compatibility pass with the full TBS worldgen set (Geophilic, Explorify, Structory,
  Towns & Towers, Dungeons & Taverns, Moog's, Katters, Sparse Structures, any terrain pack
  added for the reset).
- Performance: Chunky pregen of a fixed radius with and without SlashSlabs, Spark profile.

### M4 — Operations tooling
Depends on: M3.
- `/slashslabs smooth <radius>` — explicit retrofit of already-generated chunks (skips
  player-modified areas using Ledger-free heuristics: only natural-terrain columns).
- `/slashslabs purge` — removal path (R7).
- BlueMap pack output; config documentation; LuckPerms permission nodes for commands.

### M5 — TBS integration
Depends on: M4, the TBS reset decisions (target version, contract wording).
- Add SlashSlabs + Polymer to `TBS-server` (packwiz), resource-pack settings, BlueMap pack.
- Update `TBS-mod-strategy.md` and TBS `CLAUDE.md` contract line (§6).
- Enable step height if chosen.
- LocalServer rehearsal of the reset world: seed scouting with SlashSlabs active, pregen.
- Production deploy only with explicit owner approval.

### M6 — Hardening from live play
Depends on: M5 live.
- Collect edge cases (odd placements, tint seams, sound complaints) and fix.

### M7 — Public release
Depends on: M6 (proven on TBS).
- Multi-band build for 26.1.2 / 26.2 / 26.3 (one source tree, band composition in the style of
  SlashRails; Fabric only).
- Store pages (Modrinth + CurseForge), gallery, license, config docs, the slot-ceiling note.
- Release notes and listing copy go to the owner for sign-off before any publish step.

### M8 — Bedrock visuals (optional)
Depends on: M5, TBS on MC 26.2+ (Geyser 2.11.x).
- Geyser custom block mappings for the backing states + a Bedrock resource pack, so Bedrock
  players see grass slabs instead of copper/oak.

---

## 10. Decisions needed

- **D1 — Target MC version for the TBS reset** (26.1.2, 26.2, or 26.3). Sets the M0 band.
  26.2+ unblocks Bedrock crossplay; SoulCraft is the known holdout on 26.1.2.
- **D2 — Resource pack policy on TBS:** `require-resource-pack=true` (recommended) vs optional.
- **D3 — Footstep sounds:** accept, or remap copper step sounds globally (after M0 listening test).
- **D4 — Step-height module on TBS:** on, off, or on-except-sneaking.
- **D5 — Public release license** (SlashRails uses CC-BY-4.0; a closed license is also possible).
- **D6 — Mob spawning on grass slabs:** vanilla (none) vs allow animals.

---

## 11. Possible extensions

- Terrain **stairs** on diagonal/corner rises (Polymer has `STAIRS` model types; separate slot
  pool).
- Two-block rises smoothed with slab + full block steps.
- Path slabs for village paths generated by structures.
- Nether/End smoothing (netherrack, end stone) — bottom slabs on the sculk pools (C16), or vanilla-slab stand-ins.

---

## 12. Repo layout (initial)

```
SlashSlabs/
  docs/PLAN.md            this file
  build.gradle, settings.gradle, gradle.properties
  src/main/java/…         blocks, items, worldgen feature, commands, config
  src/main/resources/
    fabric.mod.json       environment: "*"  (server-side; clients never need it)
    assets/slashslabs/    models, textures (fed to polymer-resource-pack)
    data/slashslabs/      recipes, loot tables, tags, worldgen feature
```

JDK 25 for the 26.x line (Prism `java-runtime-epsilon`). Multi-band restructuring waits for M7.

---

## References

- Polymer: <https://github.com/Patbox/polymer> — `polymer-blocks/.../BlockModelType.java`,
  `polymer-blocks/.../impl/DefaultModelData.java`
- Polymer versions: <https://modrinth.com/mod/polymer>
- TBS contract: `Projects/TBS/CLAUDE.md`, `Projects/TBS/TBS-mod-strategy.md`
- Multi-band precedent: `Projects/SlashRails/CLAUDE.md`
