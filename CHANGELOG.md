# Changelog

All notable changes to SlashSlabs. Versions follow `MAJOR.MINOR.PATCH`; each release ships one
jar per Minecraft line (`slashslabs-<version>+mc<mc>-fabric.jar`).

## 0.1.0

First release, for Minecraft **26.1.2, 26.2 and 26.3** (Fabric; Fabric API required; Fabric
Loader 0.18.4 or newer).

### Added

- **Terrain smoothing at world generation.** A half-slab step at every one-block rise, with
  material from the lower column:
  - grass → grass slab;
  - dirt family → dirt slab;
  - sand → smooth sandstone slab;
  - stone family → matching vanilla slab;
  - snowy biomes → 5 snow layers;
  - shallow water → waterlogged slab.

  Cliffs, structures, flowers, saplings, deep water and lava edges are skipped. Smoothing runs at
  each chunk's LIGHT step, so the result doesn't depend on generation order; it costs about 1 ms
  per chunk.
- **Grass slab and dirt slab blocks** (plus an optional sand slab) that vanilla clients see
  without any mod, through Polymer:
  - grass spreads, decays and gets pulled onto dirt slabs;
  - Silk Touch drops;
  - recipes and stonecutting;
  - two slabs make the vanilla full block;
  - mining runs server-side at grass/dirt speed;
  - correct break and footstep sounds.
- **Three grass greens**, chosen per block from the biome colour; default palette fitted to a
  67,000-sample terrain survey.
- **Server resource pack** generated at startup from the vanilla client jar (the mod jar contains
  no Minecraft textures) and served on the game port by Polymer AutoHost.
- **Commands** under `/slashslabs`: `info`, `smooth` (retrofit existing terrain), `purge`
  (removal), `survey` (terrain statistics and palette fitting), `test-field`, `selftest`, plus
  diagnostics. Op level 2, or `slashslabs.command.*` nodes with LuckPerms or another
  fabric-permissions-api provider.
- **Optional step-height module** (step up full blocks; off while sneaking; skipped for Bedrock
  players) and optional animal spawning on grass slabs.
- **BlueMap support** through models inside the jar.

### Known limitations

See [README → Limitations](README.md#limitations): four shared slab slots, three fixed greens,
server-played copper sounds, the resource pack is required for correct visuals, and Bedrock
players see copper slabs.
