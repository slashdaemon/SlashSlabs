# SlashSlabs — Research

Companion to `PLAN.md`. Everything needed to build SlashSlabs for **MC 26.1.2, 26.2 and 26.3**
(Fabric only). Sources: decompiled/`javap` official unobfuscated jars for all three versions,
Polymer source (`dev/26.1`, `dev/26.2`, `dev/26.3` branches), Fabric API jars, C2ME `dev/26.1.2`,
Maven/Modrinth metadata (verified 2026-09-21), and the upstream docs/issue trackers cited inline.

Confidence: **H** read in source / official metadata · **M** inferred from source or docs ·
**U** unverified — goes on the M0 test list.

---

## 0. Corrections to PLAN.md

| # | PLAN.md says | Reality | Conf. |
|---|---|---|---|
| C1 | Five slab slots | **Four.** `requestBlock` refuses the last free state of a pool until someone takes `requestEmpty`; `getBlocksLeft` starts at 4. The petrified-oak state is always the one left (copper is handed out first), so it becomes the shared "empty" state. | H |
| C2 | "Prefer the oak slot for the most-walked material" | Not reachable through the public API (only via reflection into `PolymerBlockResourceUtils.CREATOR`). Petrified oak also has stone sounds, not wood. Drop the idea; §2.4 solves footsteps properly. | H |
| C3 | Model types `BOTTOM_SLAB`, `TOP_SLAB` | Real names `SLAB_BOTTOM`, `SLAB_TOP`, `SLAB_BOTTOM_WATERLOGGED`, `SLAB_TOP_WATERLOGGED`. Pools are **per model type**: one material costs one slot in each of the four pools, and the four states may land on different backing blocks. | H |
| C4 | R2 (break speed) is a kill risk | **Handled by Polymer.** Every Polymer block is mined server-side by default (`handleMiningOnServer` → true): the client's `BLOCK_BREAK_SPEED` is set to −9999 and the server drives progress with the real hardness and sends crack stages. | H |
| C5 | R3 footsteps: accept, or remap copper globally | **`polymer-sound-patcher`** fixes it: copper step/hit/fall sounds are blanked in the pack and replayed from the server using each block's *real* SoundType, including for the player walking. Side effect: real copper blocks' sounds also become server-played (same sound, network latency). | H |
| C6 | Break sounds are server-played with grass SoundType | Break sound and particles come from level event 2001, which each client plays from the state ID it receives (copper). Override `getPolymerBreakEventBlockState` → `Blocks.GRASS_BLOCK` and Polymer rewrites 2001 for every viewer. | H |
| C7 | Snow layers = 4 at a rise | **Layers = 5.** Collision is (layers−1)·2/16. Freeze leaves layers=1 (collision 0) on the upper block, so layers=4 needs a 0.625 step up the far side (> 0.6 step height). Layers=5 gives 0.5 + 0.5. Cost: land pathfinding refuses layers ≥ 5, so mobs path around it. | H |
| C8 | Material follows the higher neighbour's surface block | Neighbour-chunk blocks are **order-dependent** at feature time. Take heights from the `*_WG` heightmaps (fixed before features) and the material from the **lower column's own block**, which sits in the centre chunk and is final. | H |
| C9 | Server computes grass colour from the bundled colormap | Also: a dedicated server's `GrassColor.pixels` is all zeros (the server jar has no colormap PNGs), so `Biome.getGrassColor` returns 0 for any biome without a `grass_color` override. SlashSlabs must load `grass.png` itself. Get it from the vanilla client jar Polymer already caches at runtime (§5.4), not from the mod jar. | H |
| C10 | Hook: BiomeModifications feature vs mixin (decide in M3) | **Decided: mixin at TAIL of `ChunkGenerator.applyBiomeDecoration`.** Its signature is identical on all three versions. The Feature API was rewritten on 26.3 (Feature is an interface, config flattened, registries renamed, datapack folder moved), so a feature would need three variants. | H |
| C11 | Shovel → no-op unless a path slot; hoe → no-op | On 26.3 the `ShovelItem`/`HoeItem`/`AxeItem` classes are gone (block transformers, a **client-synced** registry). Keep the slabs out of the tool tags and add no transformer entries, and shovel/hoe are no-ops on all three with no code. | H |
| C12 | TBS resource pack via `require-resource-pack=true` | `server.properties` `require-resource-pack` only applies to the `server.properties` pack. For Polymer's pack, set `"required": true` in `config/polymer/auto-host.json` (or `markAsRequired()`). AutoHost is **disabled by default** outside dev. | H |
| C13 | Separate BlueMap pack in `config/bluemap/packs/` | Probably unnecessary: BlueMap scans mod jars (`scan-for-mod-resources: true`). Ship real `assets/slashslabs/...` in the jar plus `blockColors.json` → `@grass` for true per-biome tint on the map. Keep a `packs/` zip only as a fallback. | M |
| C14 | M8 Bedrock depends on TBS 26.2+ | Geyser-Fabric only tracks the newest Java version. The last 26.1.2 build (2.10.1-b1184) is what TBS runs. Current Geyser (2.11.3) is 26.2 only, and there is **no Geyser-Fabric for 26.3 yet**. TBS on 26.1.2 is a dead end for new Bedrock clients regardless of SlashSlabs, which feeds decision D1. | H |
| C15 | Voxy verified in M0 (visual only) | New risk **R11**: the Voxy World Gen V2 server mod (in the TBS pack) serializes chunk sections outside Polymer's per-player packet context. Its large-palette path (> 256 distinct states in a section) would send raw server IDs (§6.2). | M |
| C16 | "Four custom materials maximum" is the defining constraint | **Only for top slabs.** Polymer has a second bottom-slab-shaped pool, `SCULK_SENSOR` (+ `_WATERLOGGED`), of 150 states each, so bottom slabs — everything worldgen places — are not slot-limited. Top slabs stay at four. §2.6 | H |

---

## 1. Toolchain coordinates

Verified against `maven.fabricmc.net`, `maven.nucleoid.xyz`, Mojang piston-meta and Gradle
services. Latest at time of check; re-pin before each build.

| | 26.1.2 | 26.2 | 26.3 |
|---|---|---|---|
| JDK | 25 (`java-runtime-epsilon`) | 25 | 25 |
| Fabric Loader | 0.19.5 (Polymer's own floor 0.18.4) | 0.19.5 | 0.19.5 |
| Fabric API (latest) | `0.155.3+26.1.2` | `0.161.0+26.2` | `0.161.0+26.3` |
| Polymer | `0.16.5+26.1.2` | `0.17.5+26.2` | `0.18.2+26.3` |
| Resource pack format | 84.0 | 88.0 | 97.1 |
| Data pack format | 101.1 | 107.1 | 121.0 |
| Protocol | 775 | 776 | 777 |

- **Loom 1.18.2** (current; requires Gradle ≥ 9.7.0 with the daemon on JDK 25) or 1.17.21 on
  Gradle 9.6.x. Gradle current: 9.7.1. Plugin id `net.fabricmc.fabric-loom`, the non-remapping
  variant: plain `implementation`/`compileOnly`, `include` attaches to `jar`, no `remapJar`, no
  mappings.
- Mixin JSON `compatibilityLevel: JAVA_25`. IntelliJ 2025.3+ for mixins in dev.
- `fabric.mod.json`: depend on `fabric-api` (the `fabric` mod id is gone) and
  `"minecraft": ">=26.1.2 <26.2"` / `">=26.2 <26.3"` / `">=26.3 <26.4"`.
- Local jars for `javap`/decompiling: `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/
  minecraft-merged-deobf/<v>/`. SlashRails/SlashLoot already have working 26.x nested builds to
  copy from (`SlashRails/versions/26.3/`).

**Polymer packaging** (repo `https://maven.nucleoid.xyz/`, group `eu.pb4`; every module ships at
each version above):

| Module | Use | Build config |
|---|---|---|
| `polymer-core` | PolymerBlock/Item, packet remapping, server-side mining | `implementation` + `include` |
| `polymer-blocks` | `PolymerTexturedBlock`, slab pools, `PolymerBlockResourceUtils` | `implementation` + `include` |
| `polymer-resource-pack` | pack generation | `implementation` + `include` |
| `polymer-sound-patcher` | footstep fix (bundled in `polymer-bundled` but **not declared** in its POM) | `implementation` + `include` |
| `polymer-autohost` | serves the pack | `include` |
| `polymer-resource-pack-extras` | optional | `include` |

Core nests `reg-sync-manipulator` and `networking`; resource-pack nests `common`. Fabric Loader
picks the newest copy if a server also installs `polymer-bundled` from Modrinth. Fabric API is a
hard runtime dependency. Polymer's own branch pins (Fabric API 0.149.0 / 0.150.2 / 0.160.5) are
floors; newer is fine.

---

## 2. Polymer — the parts SlashSlabs uses

### 2.1 Block API (identical across the three branches for our surface)

```java
// eu.pb4.polymer.core.api.block.PolymerBlock
BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context); // required
default BlockState getPolymerBreakEventBlockState(BlockState, PacketContext);        // break sound + particles
default boolean handleMiningOnServer(ItemStack tool, BlockState, BlockPos, ServerPlayer); // true
default boolean overridePlayerCollisionsWithPolymer(BlockGetter, BlockPos, BlockState, ServerPlayer); // true
// 26.2+ adds default forceLightInsideBlock
```

- **Implement `eu.pb4.polymer.blocks.api.PolymerTexturedBlock`**, not bare `PolymerBlock`.
  Otherwise the waxed→unwaxed remap turns the copper state you return back into plain copper. (H)
- Request states once at init:
  ```java
  @Nullable BlockState PolymerBlockResourceUtils.requestBlock(BlockModelType, PolymerBlockModel...);
  int getBlocksLeft(BlockModelType);
  BlockState requestEmpty(BlockModelType);           // shared invisible state; throws if exhausted
  BlockModelType.getSlab(SlabType, boolean wl);      // DOUBLE → FULL_BLOCK (never used by us)
  record PolymerBlockModel(Identifier model, int x, int y, boolean uvLock, int weight) // PolymerBlockModel.of(...)
  ```
  `requestBlock` **returns null when the pool is empty** and does not throw. Plan a fallback,
  for example a vanilla lookalike slab (`mud_brick_slab`, `granite_slab`) or "material disabled" so
  worldgen skips it. `getPolymerBlockState` must never return null. (H)
- Pools (`DefaultModelData.addSlabs`), per model type, in hand-out order: waxed cut copper,
  waxed exposed, waxed weathered, waxed oxidized, then petrified oak (always the "empty" one). On 26.2+
  vanilla's copper blocks are a family object (`Blocks.CUT_COPPER_SLAB.waxed()`); code that names
  copper blocks directly needs a per-band variant. SlashSlabs doesn't need to. (H)
- Which backing block a material lands on depends on mod init order. The world stores
  `slashslabs:*`, so only visuals move, but a change invalidates clients' cached Voxy LODs. Keep
  registration order fixed. (M)
- Collision and outline: Polymer forces the server-side shape of a Polymer block to the client
  state's shape, so server and client agree exactly (R1 is structurally closed). (H)
- `/polymer blocks_module_state_report` prints the slots left. Use it in M0. (H)
- **The four-slot ceiling of the copper pools is the same on every band, and no Polymer release
  lifts it.** It binds top slabs only; bottom slabs have the sculk-sensor pool (§2.6). Measured
  2026-09-22 by booting each band's shipped server and reading the report:

  | Band | Polymer | `SLAB_BOTTOM` / `SLAB_TOP` (+ waterlogged) |
  |---|---|---|
  | 26.1.2 | 0.16.5 | cap **5** |
  | 26.2 | 0.17.5 | cap **5** |
  | 26.3 | 0.18.2 | cap **5** |

  The pool is built at runtime from the live registry, so there are no block ids to read
  statically — only a running server answers this. Four usable (C1), and SlashSlabs takes all
  four as dirt + three grass tints. (H)
- **A substitute pool must have exactly the slab's shape.** Of the 189 pools in the report, the
  four `SLAB_*` are the only ones SlashSlabs exhausts: `FULL_BLOCK` has 1555/1556 free,
  `TRAPDOOR_BOTTOM` 83/84, each of the 96 `STAIRS_*` 3/4. Those are unusable, and not for a
  rendering reason — `overridePlayerCollisionsWithPolymer` makes the block's effective shape
  *be* the backing state's shape, so a trapdoor backing yields a real 3-pixel lip and a
  `FULL_BLOCK` backing a real full cube. The pack can retexture a state; it cannot reshape it.
  `SelfTest` pins the invariant: the backing must be a `SlabBlock` with matching `TYPE` /
  `WATERLOGGED` and byte-identical collision AABBs. **Correction (2026-09-23):** this bullet
  used to conclude that four materials is a hard ceiling for the whole matrix. It missed the
  `SCULK_SENSOR` pools, whose states have exactly the bottom-slab shape; `SelfTest`'s
  `SlabBlock` check is a design choice, not the invariant. The ceiling holds for top slabs
  only. §2.6. (H)

### 2.2 Mining (R2)

Server-side mining for every Polymer block. On `START_DESTROY_BLOCK` the client gets
`BLOCK_BREAK_SPEED = −9999`, and the server adds `state.getDestroyProgress` each tick from the
**real** block, sends `ClientboundBlockDestructionPacket` stages, then `destroyAndAck` and resends
the block. The modifier is restored on abort/finish, with a 5-tick cooldown after each break.
Result: dirt-speed breaking with the crack overlay driven by the server, slightly laggy at high
ping, and no ghost blocks. Bedrock via Geyser may drift (Geyser PR #4557). (H / M for Bedrock)

### 2.3 Items

- `PolymerBlockItem(Block, Item.Properties)` defaults to base item `TRIAL_KEY` with
  `useModel=true`. The client can't predict a placement from it, so there is no ghost placement
  and no copper place sound. The server sends the place sound to the placer too. (H)
- `PolymerItem#getPolymerItemModel` returns `DataComponents.ITEM_MODEL`, which defaults to the
  item id. Ship `assets/slashslabs/items/<id>.json`; no CustomModelData. (H)
- The item icon *can* be tinted (`"tints":[{"type":"minecraft:constant","value":…}]` or
  `minecraft:grass` with temperature/downfall). The block cannot. (H)
- Creative tab: `PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(Identifier, tab)`;
  vanilla clients open it with `/polymer creative`. A vanilla client builds its own creative
  menu from registries it owns, so a server-registered tab can never appear there — the Polymer
  command is the only route, and `/give slashslabs:<id>` the other. (H)
- **Middle-click pick-block already returns the real item; nothing to implement.** Since 1.21.4
  pick-block is server-authoritative, and the client resolves nothing locally:

  ```
  client  Minecraft.pickBlock -> MultiPlayerGameMode.handlePickItemFromBlock(BlockPos, boolean)
          new ServerboundPickItemFromBlockPacket(pos, includeData); send()   <- whole method body
  server  ServerGamePacketListenerImpl.handlePickItemFromBlock
          packet.pos() -> isWithinBlockInteractionRange -> level.isLoaded
          -> ServerLevel.getBlockState(pos)          <- the real slashslabs:* block
          -> BlockState.getCloneItemStack(level, pos, includeData) -> tryPickItem(stack)
  ```

  The packet carries a position, not a block, so the disguise is irrelevant: the server looks up
  what is really there. We override no `getCloneItemStack`, and `PolymerBlockItem` is registered
  as each block's item, so the vanilla default returns `slashslabs:<id>`. Polymer is not involved
  — `FallbackServerPacketHandler.handlePickItemFromBlock` disassembles to a bare `return`, but it
  is a fallback listener off this path, and Polymer's only `getCloneItemStack` references are
  client-side Jade/WTHIT compat. (H, read from `javap` of the 26.1.2 jars and Polymer 0.16.5;
  **in-game confirmation still outstanding** — place a grass slab, middle-click, expect
  "Grass Slab" rather than a copper slab.)
- 26.2 deprecates one `PolymerItemUtils.getRealItemStack` overload. Avoid it. (H)

### 2.4 Sounds

| Sound | Who plays it by default | Fix |
|---|---|---|
| Break (level event 2001) | Every client, from the state ID it receives (copper) | `getPolymerBreakEventBlockState` → grass/dirt/sand state |
| Place | Server → everyone including the placer (PolymerBlockItem) | none needed |
| Step / fall / mining hit | Client-local from the copper state | `SoundPatcher.convertIntoServerSound(SoundType.COPPER)` (plus the other copper SoundTypes in use), module `polymer-sound-patcher`, `@ApiStatus.Experimental` |

The sound patcher's global config is `config/polymer/sound-patch.json` (`force_disable`,
`always_handle_vanilla_block_sounds`). Precedent: `craftycorvid/wool-polymer`. (H)

**The patcher only reaches Polymer blocks, so a vanilla lookalike keeps the vanilla sound.**
This is the hidden cost of every `vanilla(...)` entry in `MaterialMap`: the block placed really
is that vanilla block, so no rewriting happens and the player hears whatever it actually is.
Read out of `Blocks.<clinit>` — an explicit `.sound()` three lines before the field store —
with `SoundType.STONE` confirmed as the `BlockBehaviour.Properties` default, which is why the
stone family never calls `.sound()` at all:

| Terrain block | Its sound | Fallback slab | Slab's sound | |
|---|---|---|---|---|
| `SAND` | `SAND` | `SMOOTH_SANDSTONE_SLAB` | `STONE` (default) | **mismatch** |
| `RED_SAND` | `SAND` | `SMOOTH_RED_SANDSTONE_SLAB` | `STONE` (default) | **mismatch** |
| `MUD` | `MUD` | `MUD_BRICK_SLAB` | `MUD_BRICKS` | mild — both mud-family |
| `GRASS_BLOCK` | `GRASS` | *custom* `GRASS_SLAB` | `GRASS` | matches |
| `DIRT` | `GRAVEL` | *custom* `DIRT_SLAB` | `GRAVEL` | matches |
| `STONE` etc. | `STONE` | `STONE_SLAB` etc. | `STONE` | matches |
| `SNOW_BLOCK` | `SNOW` | `SNOW` layers | `SNOW` | matches |

So sand and red sand were breaking and stepping like stone in every desert and on every beach.
Found by ear during M0 manual testing, 2026-09-22 — no automated tier covers sound, and the
self-test cannot: it compares backing states and collision, never `SoundType`. Fixed by mapping
both to `none` (see below) rather than by spending a slot. A custom sand slab *would* sound
right (`ModBlocks` builds `SAND_SLAB` with `.sound(SoundType.SAND)` and the patcher then applies,
because it would be copper-backed) — but only with `sandSlab: true`, which costs a grass tint.
With `sandSlab: false` even `/give slashslabs:sand_slab` sounds wrong, because the fallback
backing is a genuine sandstone state the patcher never touches. (H) With the sculk pools
(§2.6) a sand bottom slab no longer costs a grass tint, which makes this the preferred fix.

### 2.5 Resource pack and AutoHost

- `PolymerResourcePackUtils.addModAssets("slashslabs")` and `markAsRequired()`, or no code at
  all: `"custom": {"polymer:resource_pack_include": true, "polymer:resource_pack_require": true}` in
  `fabric.mod.json`. Events `RESOURCE_PACK_CREATION_EVENT` and `…AFTER_INITIAL_CREATION_EVENT` are
  where generated textures go (§5.4). The output is `<gameDir>/polymer/resource_pack.zip`; rebuild
  with `/polymer generate-pack` (and `generate-pack reload` re-pushes it). The pack format is taken
  from the running server, so nothing is hardcoded per version. (H)
- Polymer writes `assets/minecraft/blockstates/waxed_*_slab.json` itself. Don't ship them. (H/M)
- **Possible Polymer bug (U):** the generated `waxed_cut_copper_slab.json` contains only the
  requested states, so a real **double** waxed copper slab a player places may render as
  missing-texture for pack users. M0 test; the fix is to add the double states to
  `BlockExtBlockMapper.INSTANCE.stateMap` or add variants to the generated file.
- AutoHost `config/polymer/auto-host.json`: `enabled` (default **false**), `required`, `message`,
  `disconnect_message`, `type`, `settings.forced_address`, `external_resource_packs`,
  `delay_player_list_motd_until_generated`. Type **`polymer:automatic`** (alias
  `same_port`/`netty`) serves HTTP **on the game port**: a `ProtocolSwitcher` spots
  `GET /eu.pb4.polymer.autohost/...` and swaps that connection's pipeline. The URL is built from
  the handshake host and port. (H)
  - Bloom.host (one Pterodactyl allocation, no proxy): expected to work with no extra port. Bloom's
    only requirement is DNS-only (no Cloudflare proxy); SRV records are fine. SRV resolution in
    the URL is **U**; the fallback is `forced_address`.
  - Behind any proxy (Velocity, TCPShield) same-port HTTP breaks. Use `polymer:http_server`
    (a separate port; issue #277, its thread pool can wedge) or `polymer:external`.
- Declining a required pack disconnects the player. A server-list entry set to "Disabled"
  auto-declines. Geyser auto-accepts for Bedrock players, so they are not kicked. (H)

### 2.6 Beyond four materials (C16)

Researched 2026-09-23 against Polymer 0.16.5 / 0.17.5 / 0.18.2 and the 26.1.2 / 26.2 / 26.3
jars, by booting each band's server and comparing every registered block state's collision and
outline shape to both slab boxes. Built in 0.2.0 (`ModBlocks.request`, bottom-only terracotta);
slot layout and results in `TESTING.md`.

**Which states have a slab's shape (H, full registry, identical on all three bands):**

| Shape | States with collision *and* outline equal to it |
|---|---|
| Bottom slab `(0,0,0)–(16,8,16)` | slabs; all 96 `sculk_sensor` states; all 384 `calibrated_sculk_sensor` states |
| Top slab `(0,8,0)–(16,16,16)` | slabs only |

Near misses: `sculk_shrieker` (collision a bottom slab, outline a full cube); `snow` layers=5
(collision 8, outline 10) and layers=4 (outline 8, collision 6); `daylight_detector` 6 px,
`soul_campfire` 7, `stonecutter` 9.

**Polymer already pools them.** `BlockModelType` has `SCULK_SENSOR`, `SCULK_SENSOR_WATERLOGGED`,
`SCULK_SENSOR_ACTIVE` and `SCULK_SENSOR_ACTIVE_WATERLOGGED` (`getSculkSensor(active, wl)`).
`SCULK_SENSOR` holds 150 states — both sensors, phase `inactive` or `cooldown`, `power` 1–15, the
calibrated one in all four facings — and the waterlogged pool the same 150. Real sensors never
sit at those states visually: `SPECIAL_REMAPS` has 450 entries sending power N to power 0,
switched on the first time a state of that block is requested, the same mechanism as waxed →
unwaxed copper. The client's blockstate JSONs key only on `sculk_sensor_phase` (and `facing`),
so `power` is invisible and the pack may retexture every spare. Precedent:
`DrexHD/polymer-patch-bundle`'s `SlabPolymerBlock` backs its bottom slabs on this pool. (H)

**Behaviour of a sculk-backed slab on a vanilla client:**

| Aspect | Result | Conf. |
|---|---|---|
| Occlusion, face culling, AO | Same as a slab: `canOcclude`, bottom-half occlusion shape, `useShapeForLightOcclusion`; no `skipRendering` / `getShadeBrightness` override | H |
| Client-predicted movement | Same: friction 0.6, speed/jump 1.0, base `fallOn`; `stepOn` runs server-side only | H |
| Particles | None: `animateTick` returns unless phase is `ACTIVE`, and `SCULK_SENSOR` holds no active states. Never use the `_ACTIVE` pools | H |
| Light | **Mismatch.** Every sensor state emits light 1 on the client; the server block emits 0. Chunks that load show the server's light, but a slab placed, spread or decayed in view is relit client-side and glows faintly. Visible only in near-darkness. Don't give the real block light 1 to match — hostile mobs would stop spawning on smoothed terrain | H / M |
| Step / hit / fall sounds | Sculk-sensor sounds unless `SoundPatcher.convertIntoServerSound(SoundType.SCULK_SENSOR)` (as Filament does); real sensors then also become server-played, like copper (§2.4) | H |
| Mining | Server-side (§2.2), so the client's hardness is irrelevant | H |
| Block entity | The client creates an idle `SculkSensorBlockEntity` (null client ticker, no renderer, no data from the server) | M |
| Redstone dust beside it | May flash a connection (the sensor is a signal source) until the server's update arrives | M |
| No pack / Bedrock via Geyser | Shows sculk sensors instead of copper | H / U |

**Resulting layout (recommended):** every material's bottom slab (dry + waterlogged) on the
sculk pools; the four copper slots only for the top slabs of the materials most likely to be
placed upside-down by players. A player-placed top slab of any other material falls back to a
vanilla lookalike or is refused. Worldgen places bottom slabs only, so smoothing coverage is
limited by textures and palette, not slots: real sand and red sand slabs (§2.4, §4.12),
terracotta, more grass tints, Nether/End materials. `SelfTest`'s `SlabBlock` check becomes
"collision and outline AABBs identical". Moving a material to a new backing invalidates
clients' Voxy LODs once (§2.1).

**TBS (checked 2026-09-23, TBS-Server 1.5.0, the modset `prodtest.py tbs` runs):** SlashSlabs is
the only mod that nests or references Polymer (`eu/pb4/polymer` appears in no other jar or
`fabric.mod.json`), so neither the sculk nor the copper pools are shared and nothing on TBS calls
`requestEmpty`. A Polymer mod added later would draw from the same pools in mod-init order.

**Routes that don't work or aren't worth it:**

- **Biome-tinted grass from one slot — impossible on vanilla clients (H).** Block tints are
  registered in Java (`BlockColors.createDefault`); `tintindex` only indexes that list, and 26.1–26.3
  add no pack-side block tint. Every biome-tinted block has the wrong shape. Blockstates select by
  property and position-seeded random weight only; nothing carries biome or material data.
- **Core shaders (H/M).** `terrain.vsh/fsh` are still pack-overridable (26.3 needs a rewritten set:
  ShaderC, `#include`), but the shader has no biome input and Sodium/Iris/Voxy ignore it.
- **Display entities (H/M).** One shared collision state plus an `item_display` per slab removes
  the limit and even allows exact per-column tint (`custom_model_data` tint source), but at ~51
  slabs per chunk that is ~25k entities per player at view distance 12, rendered only within ~64
  blocks, with flat lighting and nothing in Voxy LODs. Every shipped precedent (PolyFactory,
  Oraxen, Nova, ItemsAdder) keeps display-entity blocks sparse. A hybrid for rare materials only
  still fails in large deserts/badlands (~43 per chunk) and doesn't fix sounds.
- **Sacrificing vanilla slabs (H).** An operator-chosen vanilla slab can be remapped to a lookalike
  (via `BlockExtBlockMapper.INSTANCE.stateMap` or a `BlockMapper.DEFAULT_MAPPER_EVENT` listener)
  and its states added to the pool (reflection into `PolymerBlockResourceUtils.CREATOR`, or a
  mixin — no public API). Near-invisible and in no structure: `quartz_slab` → smooth quartz,
  `cut_sandstone_slab` → smooth sandstone, `cut_red_sandstone_slab` and `red_sandstone_slab` →
  smooth red sandstone. Taking petrified oak as well makes nine top-slab materials, but breaks
  any later mod's `requestEmpty(SLAB_*)` (polymer-patch-bundle, wool-polymer, Filament
  `virtual`). Only worth it if top slabs ever need more than four materials.

---

## 3. World generation

### 3.1 Pipeline (H)

- 26.1.2 / 26.2 statuses: `empty, structure_starts, structure_references, biomes, noise, surface,
  carvers, features, initialize_light, light, spawn, full`.
- 26.3: `noise+surface+carvers` merge into **`terrain`**.
- FEATURES requires `STRUCTURE_STARTS` at radius 8 and `CARVERS`/`TERRAIN` at radius 1;
  `blockStateWriteRadius = 1`. Writes more than 1 chunk out are refused ("Detected setBlock in a
  far chunk").
- Neighbours are at CARVERS/TERRAIN or later but never past INITIALIZE_LIGHT. Their blocks and
  final heightmaps depend on generation order. Their **`OCEAN_FLOOR_WG` / `WORLD_SURFACE_WG` do
  not**: `ProtoChunk.setBlockState` stops updating the WG heightmaps once status reaches
  CARVERS/TERRAIN, and they survive save/reload. They include noise, surface rules, carvers and
  structure terrain adaptation, but not structure blocks.

### 3.2 The hook (C10)

```java
@Mixin(ChunkGenerator.class)
@Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
// public void applyBiomeDecoration(WorldGenLevel, ChunkAccess, StructureManager) — identical 26.1.2/26.2/26.3
// Only DebugLevelSource overrides it. Guard: level instanceof WorldGenRegion.
```

It runs after every structure step, `freeze_top_layer` and all modded features. No datapack
JSON and no Feature registration, so it sidesteps the 26.3 Feature rewrite entirely.

For reference only (not the chosen route): the 26.1.2/26.2 Feature is
`abstract class Feature<FC>` + `worldgen/configured_feature/`. The 26.3 Feature is
`interface Feature { MapCodec<? extends Feature> codec(); boolean place(WorldGenLevel,
ChunkGenerator, RandomSource, BlockPos); }`, with registry `FEATURE_TYPE` and the flattened JSON in
`worldgen/feature/`. The Fabric `BiomeModifications.addFeature` signature is unchanged. Its
`BiomeFilter` checks the biome at **world min Y** (a cave biome), a trap for biome predicates.

### 3.3 Deterministic algorithm (revises PLAN §4)

1. Build an 18×18 height grid (centre chunk + 1-column border) from
   `region.getChunk(cx,cz).getHeight(OCEAN_FLOOR_WG, lx, lz) - 1` (top solid; ignores water).
   Deterministic.
2. Rise test on the centre 16×16 only: any 4-neighbour at exactly `y+1`. Two or more is skipped.
   Diagonals are a config option.
3. Live-block guards on the centre chunk: `y` is still a terrain material (own tag
   `slashslabs:terrain` = `#substrate_overworld` + `#base_stone_overworld` + `#sand` +
   `#terracotta` + gravel/sandstone…, because `#overworld_carver_replaceables` is **gone on 26.3**),
   and `y+1` is air, replaceable vegetation (remove short grass/fern) or snow. A flower, sapling, log
   or anything else skips the column.
4. Material: the **lower column's own surface block** (centre chunk, final) mapped through PLAN §3.
   Snowiness comes from biome climate (`biome.getPrecipitationAt(pos, seaLevel) == SNOW`), not from
   neighbour snow blocks, which are order-dependent → snow layers=5 (C7).
5. Water at `y+1`: waterlogged variant.
6. Set grass under the slab to dirt (the random tick would decay it anyway: a bottom slab gives
   light dampening 15, H).
7. Leftover non-determinism is benign: a neighbour decorated later may write into this chunk (a
   tree over the border) and simply overwrite or avoid the slab.

Structure guard, done once per chunk: collect the pieces of the starts referenced by the centre
chunk and its 8 neighbours (`chunk.getAllReferences()`, then `structureManager.startsForStructure`
— `(ChunkPos, Predicate)` on 26.1/26.2, **`(int, int, Predicate)` on 26.3**, the only per-band
difference in the hook), keep the bounding boxes that intersect this chunk, inflate by 1, and test
columns against that short list. `hasAnyStructureAt` is gone on 26.3. (H)

### 3.4 C2ME / threading (H)

C2ME runs each generation step under a lock on the step's write radius (3×3 for FEATURES), so the
tail mixin owns its area. Rules:
- Global state (config, colormap, tag sets) must be immutable or concurrent.
- Never touch `ServerLevel` chunks from the region (deadlock risk).
- Never use `level.getRandom()`: C2ME flags off-thread use. Use a position hash.

No C2ME code touches `applyBiomeDecoration`. Lithium not checked against source (M).

### 3.5 Grass colour server-side (C9)

- The definition didn't move: `BiomeSpecialEffects.grass_color` / `grass_color_modifier` in the
  biome JSON on all three versions. Formula: `ColorMapColorUtil.get(temp, rain)`, where
  `rain *= temp; idx = ((int)((1-rain)*255))<<8 | (int)((1-temp)*255)`.
- `DARK_FOREST`: `((c & 0xFEFEFE) + 0x28340A) >> 1`.
- `SWAMP`: `BIOME_INFO_NOISE` at `(x*0.0225, z*0.0225)` `< -0.1 ? 0x4C763C : 0x6A7039`. This is
  `.getValue(x,z,false)` on 26.1/26.2 and `.get(x,z)` on 26.3, a per-band call.
- The client averages over `biomeBlendRadius` (default 2, a 5×5 area). Average the same 25 samples,
  only on columns that get a grass slab.

---

## 4. Blocks, items, data

### 4.1 Block class — one source for all three (H)

Extend **`SlabBlock`** and never write a `codec()` override. 26.1/26.2 inherit a concrete one,
and on 26.3 codecs no longer exist. (`SpreadingSnowyBlock.codec()` is abstract on 26.1/26.2, which
is why not to extend it.)

```java
ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("slashslabs", "grass_slab"));
Block b = Registry.register(BuiltInRegistries.BLOCK, key,
    new GrassSlabBlock(BlockBehaviour.Properties.of().strength(0.6F).sound(SoundType.GRASS).randomTicks().setId(key)));
```

- Avoid the `Properties` methods that drift: `emissiveRendering`, `isViewBlocking`,
  `bounceRestitution`, `fallDistanceReduction`.
- Don't override `playerDestroy` (it takes `ServerLevel, ServerPlayer` on 26.3) or
  `spawnDestroyParticles`.
- Use literal **2001** for the break level event: on 26.3 `PARTICLES_DESTROY_BLOCK` now means 2014
  (no sound).
- Strengths: grass 0.6, dirt/sand 0.5, no `requiresCorrectToolForDrops`, tag
  `#minecraft:mineable/shovel`.

### 4.2 Double slab → vanilla full block (H)

Override `getStateForPlacement`. If the clicked state `.is(this)`, return
`Blocks.GRASS_BLOCK` (with `SNOWY` from above) / `DIRT` / `SAND`; otherwise `super`. `BlockItem.place`
handles the rest: one item consumed, grass place sound, `isUnobstructed` entity check. The client
predicts nothing (TRIAL_KEY base), and the server's block update corrects it.

### 4.3 Grass spread (H)

- Replicate `SpreadingSnowyBlock.randomTick`: decay if `!canStayAlive`, else when brightness above
  is ≥ 9, 4 tries at `(±1, −3..+1, ±1)`. Targets are dirt and dirt slab, keeping TYPE and WATERLOGGED.
- Re-implement the light check inline: `LightEngine.getLightBlockInto` was renamed
  `getLightDampeningInto` in 26.2. The body uses only stable calls
  (`Shapes.mergedFaceOccludes`, `canOcclude`, `useShapeForLightOcclusion`, `getLightDampening`).
- Vanilla grass will **not** spread onto a dirt slab (`is(Blocks.DIRT)`). Two options:
  - A `@WrapOperation` on that `is` call in `SpreadingSnowyBlock.randomTick`, guarded by
    `this == Blocks.GRASS_BLOCK` (mycelium shares the class).
  - Dirt slabs "pull" grass from nearby sources on their own random tick (no mixin).

  Pick in M1.
- Waterlogged grass slab behaviour has no vanilla precedent. It's a design choice (likely: decays
  like grass under water).

### 4.4 Loot tables — per-band data (H)

The path `data/slashslabs/loot_table/blocks/<id>.json` is the same everywhere; the **format
differs on 26.3**. A 26.1-format file loads on 26.3 **silently with its conditions dropped** (the
grass slab would always drop itself).

26.1.2 / 26.2:
```json
{"type":"minecraft:block","pools":[{"rolls":1.0,"entries":[{"type":"minecraft:alternatives","children":[
 {"type":"minecraft:item","name":"slashslabs:grass_slab","conditions":[{"condition":"minecraft:match_tool","predicate":{"predicates":{"minecraft:enchantments":[{"enchantments":"minecraft:silk_touch","levels":{"min":1}}]}}}]},
 {"type":"minecraft:item","name":"slashslabs:dirt_slab","conditions":[{"condition":"minecraft:survives_explosion"}]}]}]}],
 "random_sequence":"slashslabs:blocks/grass_slab"}
```
26.3 (singular `condition`, reference predicates, int `rolls`, `block_state_property` → `match_block`):
```json
{"type":"minecraft:block","pools":[{"rolls":1,"entries":[{"type":"minecraft:alternatives","children":[
 {"type":"minecraft:item","condition":"minecraft:tool/can_silk_touch","name":"slashslabs:grass_slab"},
 {"type":"minecraft:item","condition":{"type":"minecraft:survives_explosion"},"name":"slashslabs:dirt_slab"}]}]}],
 "random_sequence":"slashslabs:blocks/grass_slab"}
```
No slab-doubling function is needed (a double slab never exists as our block).

### 4.5 Recipes — same on all three (H)

`data/slashslabs/recipe/`, string ingredients, `result: {id, count}`:
```json
{"type":"minecraft:crafting_shaped","category":"building","key":{"#":"minecraft:dirt"},"pattern":["###"],"result":{"count":6,"id":"slashslabs:dirt_slab"}}
{"type":"minecraft:crafting_shapeless","category":"building","ingredients":["slashslabs:dirt_slab","slashslabs:dirt_slab"],"result":{"id":"minecraft:dirt"}}
{"type":"minecraft:stonecutting","ingredient":"minecraft:dirt","result":{"count":2,"id":"slashslabs:dirt_slab"}}
```

### 4.6 Tags (H)

- **Add:** `#minecraft:mineable/shovel`, `#minecraft:slabs`. `slabs` is required on 26.3:
  `slabs → blocks_motion_no_leaves → blocks_motion → blocks_motion_in_heightmap` drives heightmaps,
  rain and snow placement, spawning and fluid blocking now that `blocksMotion()` is gone.
  `petrified_oak_slab` is in `#slabs` on every version.
- **Never add:** `#dirt`, `#grass_blocks`, `#substrate_overworld` (these feed
  `supports_vegetation` and `convertible_to_mud`), `#turns_into_dirt_path`, `#turns_into_farmland`,
  `#valid_spawn`. Add `#animals_spawnable_on` only behind the D6 flag.
- **U:** whether Polymer filters `slashslabs:*` entries out of tags synced to vanilla clients.
  Expected yes (reg-sync-manipulator), but M0 must join a vanilla client with the tags in place.

### 4.7 Spawning, plants, snow (H)

- The default `isValidSpawn` needs a sturdy top face, which a bottom slab fails. D6 flag:
  `Properties.isValidSpawn((s,l,p,t) -> Config.animalSpawns && …)` plus
  `#animals_spawnable_on` membership, supplied conditionally or through a mixin on
  `Animal.checkAnimalSpawnRules`.
- `VegetationBlock.mayPlaceOn` = `#supports_vegetation`, so staying out of the tags above blocks
  flowers and saplings. Vanilla clients also refuse, since they see copper.
- Snow won't sit on a bottom slab (the top face isn't full) but will on a top slab.

### 4.8 Tools on 26.3 (C11)

Block transformers: component `DataComponents.BLOCK_TRANSFORMER`, **client-synced** registry
`block_transformer`, tags `turns_into_dirt_path` / `turns_into_farmland`. Fabric 0.161.0+26.3
dropped `FlattenableBlockRegistry`/`TillableBlockRegistry` in favour of `BlockTransformerHelper`.
Adding a `slashslabs:*` entry there risks breaking vanilla clients (U). If a path slab is ever
wanted, use `UseBlockCallback` server-side on every band.

### 4.9 Client assets (H)

- Slab models: parent `minecraft:block/slab` / `slab_top` with `#bottom/#top/#side`. The double
  state is never ours.
- **No `tintindex`**: copper has no colour provider, so the tint is ignored, and Voxy renders
  untinted `tintindex` as black LODs (voxy#302). Bake the colour into the texture.
- 26.3 model format: the element key `"shade": false` is no longer read (`shade_direction_override`
  replaces it). Don't use `shade`.
- `pack.mcmeta` uses `min_format`/`max_format`. Polymer fills this from the running server.

### 4.10 Step height module (H)

- `Attributes.STEP_HEIGHT` (`minecraft:step_height`, default 0.6, range 0–10, syncable) exists on
  all three.
- Apply with `new AttributeModifier(Identifier, 1.0 - 0.6, Operation.ADD_VALUE)` through
  `addOrUpdateTransientModifier`. Reapply on join, **respawn** and dimension change (a respawn
  resetting it is a known issue).
- Sneak-disable means toggling on the sneak-state change.
- Bedrock (Geyser) cannot translate the attribute: skip Floodgate players
  (`PolymerCommonUtils.isBedrockPlayer`) to avoid rubber-banding.

### 4.11 Commands and permissions (H)

- `Commands.literal/argument`, `.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))`.
  Permission sets are on all three versions.
- `fabric-permission-api-v1` (`PermissionPredicates.require(...)`) exists on **26.2/26.3 only**. On
  26.1.2 use lucko's `fabric-permissions-api` (its LuckPerms bridge on 26.x is U) or vanilla levels.

### 4.12 Material coverage and `materialOverrides` (H)

Three materials could not be served well within the copper pools, for two different reasons.
**Since 0.2.0 both are solved** by the sculk pools (§2.6): sand, red sand and the seven badlands
terracottas have real bottom slabs, and terracotta is bottom-only. The analysis below is kept for
servers that turn a material off, and for the case where another Polymer mod exhausts the pools:

- **Terracotta has no vanilla slab in any colour.** `minecraft:terracotta_slab` and the dyed
  variants are all "Unknown block type" on 26.1.2. Badlands therefore cannot be smoothed by a
  fallback at all — only a custom block, i.e. a slot. It is absent from `MaterialMap` and falls
  through to `Kind.NONE`.
- **Sand and red sand have a vanilla slab that looks and sounds wrong** (§2.4). Smooth sandstone
  is flat and pale where sand is granular, and it breaks and steps like stone.

`materialOverrides` handles the second case without spending a slot (`MaterialMap.build`):
`"none"` → `Kind.NONE`, `"snow"` → `Kind.SNOW`, `"slashslabs:grass_slab"` → `Kind.GRASS`, and any
other block id → a vanilla or custom slab. `Kind.NONE` makes `TerrainSmoother.decide` return
null — the column is left alone and counted as `skippedMaterial`. Shipping default for a server
that dislikes the sandstone stand-in:

```json
"materialOverrides": { "minecraft:sand": "none", "minecraft:red_sand": "none" }
```

Cost: sand 4.4% + red sand 3.3% of placements stop being smoothed (`SURVEY.md`); grass, dirt,
snow and the stone family — about 90% — are unaffected, and all three grass tints are kept.

**Trap: turning a material off strands every slab it already placed.** `WorldOps.purge` selects
via `MaterialMap.isSmoothingSlab`, which is rebuilt from the *current* config, so the moment sand
maps to `none` the smooth sandstone slabs already in the world stop being purgeable and no
in-game command can remove them. Purge **before** changing the override, generously and verified
to zero — a radius-4 purge left ~180 behind in an M0 run and they were unreachable afterwards.
The only remaining remedy is vanilla `/fill … replace`, which is blunt and also eats
player-placed and structure-placed slabs. `MaterialMap.build(CONFIG)` runs once at init
(`SlashSlabs.java:39`) and there is no reload command, so the change needs a restart, and
existing terrain needs `purge` then `smooth` to re-apply.

---

## 5. Textures, palette and licensing

1. **Budget (C1, C16):** 4 materials in the copper pools; shipped 0.1.0 as dirt + 3 grass tints.
   Bottom slabs can move to the sculk pools (§2.6), after which the budget binds top slabs only.
2. **Palette pick:** compute each grass column's blended colour (§3.5) and pick the nearest palette
   entry (the distance metric is an M2 choice; perceptual Lab distance is the safe default).
3. **Top-face seam risk (R4):** the slab's *top* is the tinted grass top, so a seam shows wherever
   the palette is far from the biome's real colour. Test in plains, jungle, savanna, swamp and
   badlands edge.
4. **Generate the textures at pack-build time from the vanilla client jar**, which Polymer
   already downloads (`PolymerCommonUtils.getClientJar()` → `polymer/cached_client_jars/`). Read
   `grass_block_top`, `grass_block_side_overlay`, `dirt`, `sand` and `colormap/grass.png`, tint per
   palette entry, and write into the pack in `RESOURCE_PACK_CREATION_EVENT`. The mod jar then ships
   no Mojang pixels, and the same client jar feeds the server-side colormap (C9).
5. **Mojang guidelines:** a mod must not contain a substantial part of Mojang's content;
   derivatives remain Mojang's. Runtime generation keeps them out of the distributed jar; the pack
   only reaches owners of the game. (Not legal advice.)
6. **Polymer licence:** LGPL-3.0. Jar-in-jar of *unmodified* Polymer under a closed or CC licence is
   a "Combined Work": include the LGPL text and a notice, keep the library replaceable (Fabric's
   newest-nested-jar rule does this), and don't forbid reverse engineering for debugging library
   changes. This is an input to D5; counsel to confirm.

---

## 6. Ecosystem

### 6.1 BlueMap (TBS runs 5.20; latest 5.27 covers 26.1–26.3)

- It renders from world data (`slashslabs:*`) and reads mod-jar assets by default. It **cannot**
  see Polymer's runtime-generated pack. So the jar must carry real
  `assets/slashslabs/blockstates/*.json` + models. For BlueMap they can point at
  `minecraft:block/grass_block_top` with `tintindex: 0`; clients never receive `slashslabs:*`, so
  this doesn't conflict with §4.9.
- `assets/slashslabs/blockColors.json`: `{"slashslabs:grass_slab": "@grass"}` gives true
  per-biome tint on the map. Whether BlueMap reads it from a jar or only from `packs/` is **U**;
  ship a `config/bluemap/packs/` zip as the fallback.
- Run `/bluemap purge <map>` after any pack change.

### 6.2 Voxy (0.2.18-beta on 26.1.2, 0.2.19-beta on 26.2, none for 26.3)

- The client renders from the chunks it received (already Polymer stand-ins) using baked models,
  which include the server pack (pushed in the configuration phase, before the world loads). The
  retexture should appear in LODs (M). Slab height in LODs and re-texturing after a late pack
  accept are **U**.
- **R11 (C15):** Voxy World Gen V2 (`voxy-worldgen` in TBS, 2.2.4) encodes sections server-side
  outside Polymer's packet context. The small-palette path is probably remapped. The large-palette
  path returns raw storage (`PalettedContainerDataMixin`: no player → `warnMissingContextChunkPacket`),
  i.e. Polymer's out-of-range state IDs. Watch for the warning in M0. Mitigations: a mixin that
  wraps its serialization in a `PacketContext`, or disable its server side on TBS.

### 6.3 Geyser / Bedrock (M8)

- Versions: see C14. Floodgate 2.2.7-b69 supports 26.3.
- Geyser custom mappings can override **vanilla** Java states
  (`"minecraft:waxed_cut_copper_slab"` + `state_overrides` + `only_override_states: true`,
  `enable-custom-content`, a Bedrock pack, a slab `collision_box`). Real waxed copper is already
  remapped to unwaxed by Polymer, so only SlashSlabs blocks would carry the override (M).
- **True biome tint on Bedrock** is possible: `material_instances` `tint_method: "grass"`, exposed
  only through the Geyser API (`MaterialInstance.Builder.tintMethod`, API 2.9.1+), so an in-mod
  `GeyserDefineCustomBlocksEvent` handler would do it.
- Cheapest fallback: `getPolymerBlockState` returns a vanilla lookalike slab to Floodgate players.
- Prior art: Bedframe (a pattern only, stale), Rainbow (auto-maps block-state overrides, no tint),
  Hydraulic (not production-ready).
- Bedrock steps slabs natively (Geyser #4472, closed as unreproducible).

### 6.4 Prior art — the server-side niche is empty (H)

| Mod | Loader / MC | Client? | Takeaways |
|---|---|---|---|
| Countered's Terrain Slabs 3.3.3 | Fabric/Forge/NeoForge, 26.1.2/26.2 | required | Hook at `UNDERGROUND_STRUCTURES` via BiomeModifications. Its issues: vertical grass spread under slabs lagged (#46, our dirt-under-slab rule avoids it); village paths needed a `WorldGenRegion.setBlock` mixin (#38); slab lighting darker than full blocks (#19); DH LODs showed brown cubes; a major-version update deleted slabs (purge/migration matters). |
| Dirt Slab (fatlard1993) | Fabric, 1.21.11/26.3 | required | worldgen edge slabs, snow on slabs |
| Snow! Real Magic! | up to 26.1.2 | required | snow on slabs |
| wool-polymer | Polymer, 26.2 | no | sound-patcher and break-state pattern to copy |
| remnants-blocks | Polymer, 26.1 | no | four `requestBlock` calls per slab with a fallback when the pool is exhausted, our exact pattern |

---

## 7. Build layout and per-band variance

The M0 band is D1 (TBS runs 26.1.2 today). All three versions share JDK 25, Loader 0.19.5 and
Loom, so **one Gradle build with three band subprojects** is enough. SlashRails needed nested
builds only because its 1.x bands pin an older Loom. The shared source tree plus variant directories
follow SlashRails' `compat/` convention.

Everything that differs across 26.1.2 / 26.2 / 26.3 for this mod:

| Axis | 26.1.2 | 26.2 | 26.3 | Handling |
|---|---|---|---|---|
| Loot-table JSON | legacy | legacy | new | per-band `data/` |
| `startsForStructure` | `(ChunkPos, …)` | `(ChunkPos, …)` | `(int, int, …)` | compat variant |
| Swamp noise call | `getValue(x,z,false)` | same | `get(x,z)` | compat variant |
| `#blocks_motion*` | — | — | via `#slabs` | tag file on all (harmless) |
| Light helper name | `getLightBlockInto` | `getLightDampeningInto` | same | inline re-implementation, no variant |
| Level event 2001 constant | `PARTICLES_DESTROY_BLOCK` | same | renamed | literal 2001, no variant |
| Fabric permission API | absent | present | present | vanilla levels / lucko's API on 26.1.2 |
| Polymer | 0.16.5 | 0.17.5 | 0.18.2 | our surface is identical |
| Block codec | inherited | inherited | none | extend `SlabBlock`, no override |
| Mixin `applyBiomeDecoration` | ✓ | ✓ | ✓ | shared |

Test harness: LocalServer is MC 1.21.1 and doesn't apply. Follow SlashRails' `scripts/selftest.sh`
pattern: a Loom dev server per band, RCON, flat or seeded world, a `/slashslabs selftest` over
`test-field` fixtures, plus a shipped-jar prodtest on a real Fabric server with Polymer, C2ME,
Lithium and Voxy World Gen.

---

## 8. Risk register additions

| # | Risk | Mitigation / test |
|---|---|---|
| R1 | (was: collision desync) | Structurally closed by Polymer's shape override; keep the M0 walk matrix |
| R2 | (was: break speed) | Closed by server-side mining; M0 checks the feel at ping |
| R3 | (was: footsteps) | Closed by sound-patcher; D3 becomes "accept server-played copper sounds globally" |
| R11 | Voxy World Gen V2 sends raw IDs for large palettes | M0 with voxy-worldgen; a mixin or disabling it on TBS |
| R12 | Real double waxed copper slab renders missing-texture for pack users | M0: place one; patch Polymer's generated blockstate |
| R13 | Pool exhaustion or mod-order drift reshuffles backing states | Fixed registration order; null-safe fallback; `/polymer blocks_module_state_report` |
| R14 | Custom IDs in synced tags or registries (tags, block transformers) | M0 vanilla join with every tag in place; no transformer entries |
| R15 | Slab lighting looks darker than neighbouring full blocks | M0 visual (Terrain Slabs #19) |
| R16 | Village paths and structure-placed dirt paths stay stepped | Out of scope (structure guard); extension §11 |
| R17 | Sculk-backed slabs glow at light 1 client-side after an in-view block update (§2.6) | Vanilla client in darkness: place, spread and decay next to copper-backed slabs and compare |
| R18 | Another Polymer mod shares the sculk or copper pools, or calls `requestEmpty` | Mod-list check before adding one to TBS; `/polymer blocks_module_state_report` |

## 9. M0 test list (only the unverified items)

- Walk/sprint/sneak/boat/horse matrix and mining feel at 100+ ms ping.
- Break, place, step and fall sounds with the sound patcher, heard by the actor *and* an observer.
- AutoHost same-port via the TBS hostname/SRV; pack prompt; decline → kick; Bedrock auto-accept.
- A real waxed copper double slab with the pack loaded (R12).
- Vanilla client joins with `#slabs` / `#mineable/shovel` containing our blocks (R14).
- Voxy LOD view (TBS-Client) + voxy-worldgen large-palette warning (R11).
- BlueMap: slabs render from jar assets; `blockColors.json` read from jar vs `packs/`.
- Tint seams across the five biomes; snow layers=5 look and steppability.
- Step-height attribute on a vanilla client, after respawn and on Bedrock.
- Sculk-backed bottom slab (§2.6): state report shows the pool, texture, light 1 (R17), step and
  break sounds with the patcher, redstone dust beside it, a real sculk sensor still looks and
  works normally, and the no-pack / Geyser view.

---

## 10. Findings during the build

What building and testing SlashSlabs 0.1.0 changed in this document. Evidence in `TESTING.md`
and `SURVEY.md`.

| # | Was | Now | Conf. |
|---|---|---|---|
| F1 | C10: hook at TAIL of `applyBiomeDecoration` | **Default hook is HEAD of `ChunkStatusTasks.light`** (same signature on all three lines). LIGHT requires every neighbour at INITIALIZE_LIGHT, i.e. past FEATURES, so no neighbour writes into the chunk after smoothing. The FEATURES-tail hook stays as config `hookStage: "features"`, and in the default mode only records the structure guard there | H |
| F2 | C8: `*_WG` heightmaps are order-independent | **Not for neighbours that already reached FULL**: a LevelChunk doesn't carry WG heightmaps and `getHeight` re-primes them from current blocks (leaves, slabs, trees). Heights now come from a natural-cover surface scan: top of MOTION_BLOCKING_NO_LEAVES, looking down through air, leaves, logs, plants, snow, fluids and SlashSlabs' own output | H |
| F3 | Structure guard via `startsForStructure` | The LIGHT step's chunk cache reaches only radius 1, but starts can be 8 chunks away. The guard boxes are computed at the FEATURES tail with `fillStartsForStructure` (the same on all three lines; no per-band variant) and carried to LIGHT as a persistent Fabric data attachment, removed once used | H |
| F4 | — | The LIGHT step has write radius −1: the region subclass allows writes to the centre chunk only, and overrides `getBlockState`/`getFluidState` so 26.2+'s "unsafe terrain read" check doesn't fire on the (finished) neighbours | H |
| F5 | C9: load `grass.png` and compute colours ourselves | Feeding the colormap to vanilla's own `GrassColor.init` makes `Biome.getGrassColor` exact on a dedicated server, overrides and swamp/dark-forest modifiers included. The per-band swamp-noise variant is not needed | H |
| F6 | Per-band axes | Only the loot tables differ between 26.1.2/26.2 and 26.3. `#saplings` is gone on 26.2 (saplings are matched as `VegetationBlock`) | H |
| F7 | §1: nest core, blocks, resource-pack, sound-patcher, autohost | **Also nest `polymer-resource-pack-extras`.** The sound patcher depends on it in `fabric.mod.json` only; without it Loader drops the patcher silently on a real server | H |
| F8 | — | Polymer reads a block's client state while it registers (shape cache), so slots are requested before the blocks are registered | H |
| F9 | R12: real waxed double slab may render missing-texture | Doesn't reproduce: real waxed copper slabs of every type render as plain copper with the pack | H |
| F10 | C13: BlueMap maybe needs a `packs/` zip | BlueMap renders the slabs from the jar's `assets/` (every generated slab has a half-height top in the mesh). Blockstate variant keys must list every property; `type=bottom` alone didn't cover the `tint` property. `blockColors.json` from the jar remains U | H / U |
| F11 | R11: Voxy World Gen V2 large-palette path | No missing-context warnings with a Voxy client over smoothed terrain | M |
| F12 | §5.1 slot split | Survey: grass 60% of rise edges, 68% of placements → dirt + 3 grass tints; sand via smooth sandstone. Palette fitted to forest/birch/taiga greens | H |
| F13 | — | Fabric Loader floor lowered to 0.18.4 (Polymer's), since TBS runs 0.19.2 | H |
| F14 | — | Vanilla worldgen itself is order-dependent in ~21% of surface columns (trees, leaf litter); SlashSlabs' residual 2–3% difference between orders is exactly those columns | H |
