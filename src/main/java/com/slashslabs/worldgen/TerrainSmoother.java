package com.slashslabs.worldgen;

import com.slashslabs.SlabsConfig;
import com.slashslabs.SlashSlabs;
import com.slashslabs.block.GrassSlabBlock;
import com.slashslabs.block.ModBlocks;
import com.slashslabs.block.TerrainSlabBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PLAN §4 / RESEARCH §3.3. Worldgen calls {@link #onDecorated} at the tail of
 * {@code ChunkGenerator.applyBiomeDecoration}, after every feature and freeze_top_layer. The
 * retrofit and survey commands run the same pass over live chunks with a scanned surface.
 *
 * <p>Thread safety: runs on worldgen threads (C2ME included). Everything read here is immutable
 * after startup and no level randomness is used.
 */
public final class TerrainSmoother {
    private TerrainSmoother() {}

    private static final AtomicBoolean WARNED = new AtomicBoolean();
    /** Worldgen cost counters for /slashslabs info (chunks smoothed, nanoseconds, blocks placed). */
    public static final java.util.concurrent.atomic.LongAdder GEN_CHUNKS = new java.util.concurrent.atomic.LongAdder(),
            GEN_NANOS = new java.util.concurrent.atomic.LongAdder(), GEN_PLACED = new java.util.concurrent.atomic.LongAdder();
    /** Set while /slashslabs purge runs, so chunks it has to generate stay vanilla. */
    public static volatile boolean suspended;
    private static volatile Set<String> dimensions = Set.of();
    private static volatile Set<String> excluded = Set.of();

    public static void configure(SlabsConfig cfg) {
        dimensions = Set.copyOf(cfg.dimensions);
        excluded = Set.copyOf(cfg.excludeBiomes);
    }

    /** Per-run counters (survey / smooth report). Not thread safe; one per command run. */
    public static final class Stats {
        public int rises, placed, skippedStructure, skippedBlocked, skippedMaterial;
        public final java.util.Map<String, Integer> byGround = new java.util.TreeMap<>();
        public final java.util.Map<String, Integer> byResult = new java.util.TreeMap<>();
        public final List<Integer> grassColors = new ArrayList<>();

        void count(java.util.Map<String, Integer> m, String k) {
            m.merge(k, 1, Integer::sum);
        }
    }

    // ---------------------------------------------------------------- worldgen entry point

    public static void onFeaturesDone(WorldGenRegion region, ChunkAccess chunk, StructureManager structures) {
        SlabsConfig cfg = SlashSlabs.CONFIG;
        if (!cfg.worldgen || suspended || !dimensions.contains(region.getLevel().dimension().identifier().toString())) return;
        if ("features".equals(cfg.hookStage)) {
            smoothGenerating(region, chunk, cfg.structureGuard ? structureBoxes(chunk, structures, chunk.getPos()) : List.of());
        } else if (cfg.structureGuard) {
            try {
                StructureGuard.record(chunk, structures);
            } catch (Throwable t) {
                if (WARNED.compareAndSet(false, true))
                    SlashSlabs.LOGGER.error("Structure guard failed in chunk {} (further errors suppressed)", chunk.getPos(), t);
            }
        }
    }

    public static void onLightStep(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk) {
        if (!"light".equals(SlashSlabs.CONFIG.hookStage) || !SlashSlabs.CONFIG.worldgen || suspended) return;
        // Only chunks being generated: the loading pyramid runs the light task for chunks read
        // from disk too, and those were smoothed when they first passed this step.
        if (!(chunk instanceof ProtoChunk) || chunk instanceof ImposterProtoChunk || chunk.isUpgrading()
                || chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT)) return;
        // A region over the step's chunk cache. The LIGHT step has no block write radius of its
        // own, so writes are allowed in this chunk only; reads of the direct neighbours are safe
        // (they are past FEATURES) and bypass 26.2+'s "unsafe terrain read" check.
        ChunkPos centre = chunk.getPos();
        WorldGenRegion region = new WorldGenRegion(context.level(), chunks, step, chunk) {
            @Override
            public boolean ensureCanWrite(BlockPos pos) {
                return (pos.getX() >> 4) == centre.x() && (pos.getZ() >> 4) == centre.z() && !chunk.isOutsideBuildHeight(pos.getY());
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return getChunk(pos.getX() >> 4, pos.getZ() >> 4).getBlockState(pos);
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return getChunk(pos.getX() >> 4, pos.getZ() >> 4).getFluidState(pos);
            }
        };
        if (!dimensions.contains(region.getLevel().dimension().identifier().toString())) return;
        List<BoundingBox> guard = SlashSlabs.CONFIG.structureGuard ? StructureGuard.take(chunk) : List.of();
        if (guard == null) return; // decorated without SlashSlabs and touched by a structure: leave it
        smoothGenerating(region, chunk, guard);
    }

    private static void smoothGenerating(WorldGenRegion region, ChunkAccess chunk, List<BoundingBox> guard) {
        SlabsConfig cfg = SlashSlabs.CONFIG;
        long t0 = System.nanoTime();
        try {
            ChunkPos cp = chunk.getPos();
            // Heights from the natural-cover scan, not the *_WG heightmaps: a neighbour that has
            // already reached FULL no longer carries them (they are re-primed from its current
            // blocks, leaves and slabs included), which made ~8% of the output order-dependent.
            // The scan looks through leaves, plants, snow and SlashSlabs' own output, so it gives
            // the same terrain height whatever state a neighbour is in.
            int[] grid = new int[RiseDetector.SIZE * RiseDetector.SIZE];
            for (int dz = -1; dz <= 16; dz++) {
                for (int dx = -1; dx <= 16; dx++) {
                    int wx = cp.getMinBlockX() + dx, wz = cp.getMinBlockZ() + dz;
                    ChunkAccess c = (dx >= 0 && dx < 16 && dz >= 0 && dz < 16) ? chunk : region.getChunk(wx >> 4, wz >> 4);
                    grid[RiseDetector.index(dx, dz)] = scanSurface(region, c, wx, wz);
                }
            }
            GEN_PLACED.add(apply(region, cp, grid, guard, cfg, region.getSeaLevel(), null, false));
            GEN_CHUNKS.increment();
            GEN_NANOS.add(System.nanoTime() - t0);
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true))
                SlashSlabs.LOGGER.error("Terrain smoothing failed in chunk {} (further errors suppressed)", chunk.getPos(), t);
        }
    }

    /** Bounding boxes (inflated by 1) of every structure piece that reaches into this chunk. */
    public static List<BoundingBox> structureBoxes(ChunkAccess chunk, StructureManager structures, ChunkPos cp) {
        List<BoundingBox> out = new ArrayList<>();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ(), x1 = x0 + 15, z1 = z0 + 15;
        for (var e : chunk.getAllReferences().entrySet()) {
            structures.fillStartsForStructure(e.getKey(), e.getValue(), start -> {
                for (var piece : start.getPieces()) {
                    BoundingBox bb = piece.getBoundingBox();
                    if (bb.intersects(x0 - 1, z0 - 1, x1 + 1, z1 + 1)) out.add(bb.inflatedBy(1));
                }
            });
        }
        return out;
    }

    // ---------------------------------------------------------------- shared pass

    /**
     * Places slabs on every rise column of the chunk. With {@code stats} set, counts what it sees;
     * with {@code dryRun}, changes nothing (survey); {@code limit} (nullable) restricts the columns.
     */
    public static int apply(LevelAccessor level, ChunkPos cp, int[] grid, List<BoundingBox> guard, SlabsConfig cfg,
                            int seaLevel, Stats stats, boolean dryRun) {
        return apply(level, cp, grid, guard, cfg, seaLevel, stats, dryRun, null);
    }

    public static int apply(LevelAccessor level, ChunkPos cp, int[] grid, List<BoundingBox> guard, SlabsConfig cfg,
                            int seaLevel, Stats stats, boolean dryRun, BoundingBox limit) {
        boolean[] rises = RiseDetector.detect(grid, cfg.diagonals);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos up = new BlockPos.MutableBlockPos();
        int placed = 0;
        for (int i = 0; i < 256; i++) {
            if (!rises[i]) continue;
            int lx = i & 15, lz = i >> 4;
            int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz, y = grid[RiseDetector.index(lx, lz)];
            if (limit != null && (x < limit.minX() || x > limit.maxX() || z < limit.minZ() || z > limit.maxZ())) continue;
            if (stats != null) stats.rises++;
            pos.set(x, y, z);
            up.set(x, y + 1, z);
            if (inGuard(guard, x, y + 1, z)) {
                if (stats != null) stats.skippedStructure++;
                continue;
            }
            BlockState ground = level.getBlockState(pos);
            if (stats != null) stats.count(stats.byGround, key(ground.getBlock()));
            BlockState result = decide(level, pos, up, ground, cfg, seaLevel, stats);
            if (result == null || level.getBlockState(up).equals(result)) continue;
            if (stats != null) stats.count(stats.byResult, key(result.getBlock()) + (result.is(ModBlocks.GRASS_SLAB) ? "[tint=" + result.getValue(GrassSlabBlock.TINT) + "]" : ""));
            if (dryRun) continue;
            level.setBlock(up, result, Block.UPDATE_CLIENTS);
            fixGround(level, pos, ground, result);
            placed++;
        }
        if (stats != null) stats.placed += placed;
        return placed;
    }

    private static boolean inGuard(List<BoundingBox> guard, int x, int y, int z) {
        for (BoundingBox bb : guard) if (bb.isInside(x, y, z)) return true;
        return false;
    }

    /** What to put at {@code up}, or null to leave the column alone. */
    static BlockState decide(LevelAccessor level, BlockPos pos, BlockPos up, BlockState ground, SlabsConfig cfg, int seaLevel, Stats stats) {
        MaterialMap.Choice choice = MaterialMap.choiceFor(ground.getBlock());
        if (choice.kind() == MaterialMap.Kind.NONE) {
            if (stats != null) stats.skippedMaterial++;
            return null;
        }
        BlockState above = level.getBlockState(up);
        boolean water;
        if (above.isAir() || above.is(Blocks.SNOW)) {
            water = false;
        } else if (MaterialMap.isReplaceablePlant(above.getBlock())) {
            water = above.getFluidState().is(FluidTags.WATER);
        } else if (above.is(Blocks.WATER) && above.getFluidState().isSource()) {
            water = true;
        } else {
            if (stats != null) stats.skippedBlocked++;
            return null;
        }
        if (water) {
            if (cfg.maxWaterDepth <= 0 || level.getFluidState(up.above(cfg.maxWaterDepth)).is(FluidTags.WATER)) {
                if (stats != null) stats.skippedBlocked++;
                return null;
            }
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getFluidState(up.relative(d)).is(FluidTags.LAVA)) {
                if (stats != null) stats.skippedBlocked++;
                return null;
            }
        }
        Holder<Biome> biome = level.getBiome(up);
        if (!excluded.isEmpty() && biome.unwrapKey().map(k -> excluded.contains(k.identifier().toString())).orElse(false)) {
            if (stats != null) stats.skippedMaterial++;
            return null;
        }

        boolean snowy = !water && biome.value().getPrecipitationAt(up, seaLevel) == Biome.Precipitation.SNOW;
        boolean soil = choice.kind() == MaterialMap.Kind.GRASS || choice.kind() == MaterialMap.Kind.SNOW
                || (choice.kind() == MaterialMap.Kind.CUSTOM && choice.slab() == ModBlocks.DIRT_SLAB);
        if (choice.kind() == MaterialMap.Kind.SNOW || (snowy && soil)) {
            if (water) return null;
            return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, cfg.snowLayers);
        }
        if (choice.kind() == MaterialMap.Kind.GRASS) {
            if (stats != null) stats.grassColors.add(com.slashslabs.color.GrassColors.blended(level, up));
            return ModBlocks.GRASS_SLAB.defaultBlockState()
                    .setValue(GrassSlabBlock.TINT, GrassSlabBlock.tintAt(level, up))
                    .setValue(SlabBlock.WATERLOGGED, water);
        }
        BlockState s = choice.slab().defaultBlockState();
        if (!s.hasProperty(SlabBlock.TYPE)) return null;
        return s.setValue(SlabBlock.TYPE, SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED, water);
    }

    /** Grass covered by the new block follows vanilla's light rule now instead of on a random tick. */
    private static void fixGround(LevelAccessor level, BlockPos pos, BlockState ground, BlockState placed) {
        if (!ground.is(Blocks.GRASS_BLOCK)) return;
        if (!GrassSlabBlock.canStayAlive(ground, level, pos)) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        } else if (placed.is(Blocks.SNOW)) {
            level.setBlock(pos, ground.setValue(SnowyBlock.SNOWY, true), Block.UPDATE_CLIENTS);
        }
    }

    private static String key(Block b) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString();
    }

    // ---------------------------------------------------------------- live-world surface (retrofit, survey)

    /**
     * Top terrain block of a finished column, looking down through natural cover only. Returns
     * {@link RiseDetector#NONE} for a column capped by anything else (a build, a path), so the
     * retrofit leaves player-modified ground alone.
     */
    public static int scanSurface(LevelAccessor level, ChunkAccess chunk, int x, int z) {
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        int minY = level.getMinY();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top + 1, z);
        for (int y = top + 1; y >= Math.max(minY, top - 40); y--) {
            p.setY(y);
            BlockState s = chunk.getBlockState(p);
            if (MaterialMap.isTerrain(s.getBlock())) return y;
            if (!isNaturalCover(s)) return RiseDetector.NONE;
        }
        return RiseDetector.NONE;
    }

    static boolean isNaturalCover(BlockState s) {
        if (s.isAir() || s.is(Blocks.SNOW) || s.is(Blocks.ICE) || s.is(Blocks.VINE)) return true;
        FluidState f = s.getFluidState();
        if (!f.isEmpty() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return true;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.REPLACEABLE_BY_TREES) || s.is(BlockTags.REPLACEABLE)
                || s.is(BlockTags.FLOWERS)) return true; // saplings are VegetationBlocks (#saplings is gone on 26.2)
        if (s.getBlock() instanceof VegetationBlock) return true;
        if (s.is(Blocks.BAMBOO) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.CACTUS) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)
                || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.BEE_NEST) || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT)) return true;
        // Our own slabs and the vanilla slabs smoothing places: a smoothed column scans as before.
        if (s.getBlock() instanceof TerrainSlabBlock) return true;
        return s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM && MaterialMap.isSmoothingSlab(s.getBlock());
    }

    /** The 18×18 grid for a finished chunk (neighbour chunks are loaded as needed). */
    public static int[] scanGrid(net.minecraft.server.level.ServerLevel level, ChunkPos cp) {
        int[] grid = new int[RiseDetector.SIZE * RiseDetector.SIZE];
        for (int dz = -1; dz <= 16; dz++) {
            for (int dx = -1; dx <= 16; dx++) {
                int wx = cp.getMinBlockX() + dx, wz = cp.getMinBlockZ() + dz;
                ChunkAccess c = level.getChunk(wx >> 4, wz >> 4);
                grid[RiseDetector.index(dx, dz)] = scanSurface(level, c, wx, wz);
            }
        }
        return grid;
    }
}
