# CLAUDE.md — SlashSlabs

Server-side-only Fabric mod: half-slab steps at one-block terrain rises, placeable grass/dirt
slabs, vanilla clients supported through Polymer (each custom slab is sent as a spare waxed-copper
slab state and retextured by the auto-hosted server pack). First consumer: TBS. Design:
`docs/PLAN.md`, background and corrections: `docs/RESEARCH.md` (§10 = what the build changed),
evidence: `docs/TESTING.md`, `docs/SURVEY.md`, TBS steps: `docs/TBS-INTEGRATION.md`.

## Layout

| Path | What |
|---|---|
| `src/main/java/com/slashslabs/` | All mod logic, shared by every band |
| `…/block/` | `TerrainSlabBlock` (Polymer backing, double → vanilla block), `GrassSlabBlock` (tint property, spread/decay), `DirtSlabBlock` (pulls grass), `ModBlocks` (registration + slot requests, fixed order dirt → sand → grass tints) |
| `…/worldgen/` | `TerrainSmoother` (hooks + the shared placement pass + surface scan), `RiseDetector` (pure), `MaterialMap`, `StructureGuard` (FEATURES → LIGHT attachment) |
| `…/mixin/` | `ChunkStatusTasksMixin` (HEAD of `light`, default hook), `ChunkGeneratorMixin` (tail of `applyBiomeDecoration`) |
| `…/color/` | `ColorMath`, `PaletteFit` (pure, unit tested), `GrassColors` (feeds the vanilla colormap to `GrassColor.init`) |
| `…/pack/PackGenerator` | Server-pack models, tinted textures (from Polymer's cached client jar), item definitions, lang |
| `…/ops/` | `WorldOps` (smooth/purge/dump), `TestField`, `SelfTest` |
| `…/command/` | `/slashslabs`, `Perms` (reflective fabric-permissions-api) |
| `src/main/resources/assets/slashslabs/` | BlueMap-only models/blockstates (clients never get `slashslabs:*`) + icon |
| `compat/<variant>/` | The only per-band differences: `loot-legacy` (26.1.2, 26.2) and `loot-263` |
| `versions/<mc>/` | One band = `build.gradle` (its compat list) + `gradle.properties` (coordinates) |
| `gradle/band.gradle` | Band composer (Loom non-remapping plugin, Polymer nesting) |
| `scripts/` | `devserver.py`, `prodtest.py`, `determinism.py`, `survey.py`, `client.py`, `biome_view.py`, `rcon.py` |

A band compiles the shared tree plus its `compat/` directories; a variant must never shadow a
shared file. Keep the slot request order fixed — changing it moves materials to other backing
states and invalidates clients' Voxy LODs.

## Build / test

JDK 25 (`java-runtime-epsilon`), Gradle 9.6.1 wrapper, Loom 1.17.21.

```bash
export JAVA_HOME="/c/Users/slash/AppData/Roaming/PrismLauncher/java/java-runtime-epsilon"
./gradlew buildAll                          # 3 jars -> build/release/
./gradlew :versions:26.1.2:test             # JUnit (pure code)
python scripts/devserver.py --band 26.3 --fresh -c "slashslabs selftest"
python scripts/prodtest.py                  # release gate: shipped jars on real servers + TBS modset
```

- **Publishing:** `python scripts/publish-{curseforge,modrinth}.py --version <v> [--dry-run]`
  upload every `build/release/slashslabs-<v>+mc*-fabric.jar` (ported from SlashRails). Tokens and
  project ids come from `.env` (`devenv pull SlashSlabs`). CurseForge files are tagged Server only;
  Modrinth declares Fabric API required and Polymer embedded. Never publish without the owner's go.
- **`prodtest.py` is the release gate.** Dev runs can't see packaging bugs (a nested Polymer
  module missing a dependency is dropped silently only in production).
- Never run Gradle in this checkout while a dev server runs. Dev servers use RCON 25581 (game
  25580); prodtest uses 25596/25595.
- `versions/<b>/run/config/slashslabs.json` keeps `--config` overrides between runs; delete it
  to get defaults back.
- `scripts/client.py` drives the Prism instances `SlashSlabs-Vanilla-26.1.2` (stock vanilla) and
  `SlashSlabs-Voxy-26.1.2`; it opens a game window. Prism must be restarted to see a newly
  created instance folder. Quick-play shows the pack prompt (Proceed ≈ (400, 488) in snap space).
- Vanilla RCON drops the connection if two packets arrive in one read; `rcon.py` sends its end
  marker only after the first reply part.

## Gotchas

- Polymer reads a block's client state during registration: request slots before registering.
- Worldgen heights come from `TerrainSmoother.scanSurface`, not `*_WG` heightmaps (missing on
  FULL neighbours → re-primed from current blocks → order-dependent).
- The LIGHT-step region may only write the centre chunk and must read neighbours via their
  chunks (26.2+ logs "unsafe terrain read" otherwise).
- Loot tables are the only data that differs on 26.3; a 26.1-format table loads there with its
  conditions silently dropped.
- Licence is undecided (D5): `LICENSE` is all-rights-reserved; Polymer's LGPL notice is in
  `THIRD_PARTY_NOTICES.md` and shipped in the jar.
