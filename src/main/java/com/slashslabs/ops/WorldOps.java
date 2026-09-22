package com.slashslabs.ops;

import com.slashslabs.SlashSlabs;
import com.slashslabs.block.GrassSlabBlock;
import com.slashslabs.block.ModBlocks;
import com.slashslabs.block.TerrainSlabBlock;
import com.slashslabs.worldgen.MaterialMap;
import com.slashslabs.worldgen.TerrainSmoother;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/** Live-world operations behind the commands: retrofit, survey, purge, determinism dump. */
public final class WorldOps {
    private WorldOps() {}

    public static List<ChunkPos> square(ChunkPos centre, int radius) {
        List<ChunkPos> out = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++)
            for (int dx = -radius; dx <= radius; dx++)
                out.add(new ChunkPos(centre.x() + dx, centre.z() + dz));
        return out;
    }

    /** Smooth (or with {@code dryRun}, survey) already generated chunks. */
    public static TerrainSmoother.Stats smooth(ServerLevel level, List<ChunkPos> chunks, boolean dryRun, @Nullable BoundingBox limit) {
        TerrainSmoother.Stats stats = new TerrainSmoother.Stats();
        for (ChunkPos cp : chunks) {
            LevelChunk chunk = level.getChunk(cp.x(), cp.z());
            int[] grid = TerrainSmoother.scanGrid(level, cp);
            List<BoundingBox> guard = SlashSlabs.CONFIG.structureGuard
                    ? TerrainSmoother.structureBoxes(chunk, level.structureManager(), cp) : List.of();
            TerrainSmoother.apply(level, cp, grid, guard, SlashSlabs.CONFIG, level.getSeaLevel(), stats, dryRun, limit);
        }
        return stats;
    }

    /**
     * Converts every SlashSlabs block to vanilla (RISK R7): the slab becomes air (or water if it
     * was waterlogged, or {@code standIn} with the same shape), and dirt under a removed bottom
     * grass slab becomes grass again. Chunks that must be generated for this stay unsmoothed.
     */
    public static int purge(ServerLevel level, List<ChunkPos> chunks, @Nullable Block standIn, @Nullable BoundingBox limit) {
        boolean was = TerrainSmoother.suspended;
        TerrainSmoother.suspended = true;
        int n = 0;
        try {
            for (ChunkPos cp : chunks) {
                LevelChunk chunk = level.getChunk(cp.x(), cp.z());
                List<BlockPos> found = new ArrayList<>();
                forEachOurs(level, chunk, found::add);
                for (BlockPos p : found) {
                    if (limit != null && !limit.isInside(p)) continue;
                    BlockState s = level.getBlockState(p);
                    boolean wl = s.getValue(SlabBlock.WATERLOGGED);
                    BlockState repl;
                    if (standIn != null && standIn.defaultBlockState().hasProperty(SlabBlock.TYPE)) {
                        repl = TerrainSlabBlock.copyShape(s, standIn.defaultBlockState());
                    } else {
                        repl = wl ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    }
                    level.setBlock(p, repl, Block.UPDATE_CLIENTS);
                    if (standIn == null && s.is(ModBlocks.GRASS_SLAB) && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM
                            && level.getBlockState(p.below()).is(Blocks.DIRT)) {
                        level.setBlock(p.below(), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    n++;
                }
            }
        } finally {
            TerrainSmoother.suspended = was;
        }
        return n;
    }

    /** Every position in the chunk holding a SlashSlabs block, sections skipped via their palette. */
    public static void forEachOurs(ServerLevel level, LevelChunk chunk, Consumer<BlockPos> out) {
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection sec = sections[si];
            if (sec.hasOnlyAir() || !sec.maybeHas(s -> s.getBlock() instanceof TerrainSlabBlock)) continue;
            int y0 = level.getSectionYFromSectionIndex(si) << 4;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (sec.getBlockState(x, y, z).getBlock() instanceof TerrainSlabBlock)
                            out.accept(new BlockPos(x0 + x, y0 + y, z0 + z));
        }
    }

    /** Result of {@link #dump}: what smoothing produced in an area, for determinism comparisons. */
    public record Dump(int positions, String sha256, List<String> lines) {}

    /**
     * Lists every block smoothing could have placed (SlashSlabs slabs, bottom vanilla slabs from
     * the material map, and snow at the configured layer count) as "x y z id", sorted.
     */
    public static Dump dump(ServerLevel level, List<ChunkPos> chunks) throws Exception {
        List<String> lines = new ArrayList<>();
        int layers = SlashSlabs.CONFIG.snowLayers;
        for (ChunkPos cp : chunks) {
            LevelChunk chunk = level.getChunk(cp.x(), cp.z());
            LevelChunkSection[] sections = chunk.getSections();
            for (int si = 0; si < sections.length; si++) {
                LevelChunkSection sec = sections[si];
                if (sec.hasOnlyAir() || !sec.maybeHas(WorldOps::isSmoothingOutput)) continue;
                int y0 = level.getSectionYFromSectionIndex(si) << 4;
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            BlockState s = sec.getBlockState(x, y, z);
                            if (!isSmoothingOutput(s)) continue;
                            if (s.is(Blocks.SNOW) && s.getValue(SnowLayerBlock.LAYERS) != layers) continue;
                            String extra = s.is(ModBlocks.GRASS_SLAB) ? "/t" + s.getValue(GrassSlabBlock.TINT) : "";
                            lines.add((cp.getMinBlockX() + x) + " " + (y0 + y) + " " + (cp.getMinBlockZ() + z) + " "
                                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath() + extra);
                        }
            }
        }
        lines.sort(null);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String l : lines) md.update((l + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Dump(lines.size(), HexFormat.of().formatHex(md.digest()), lines);
    }

    private static boolean isSmoothingOutput(BlockState s) {
        if (s.getBlock() instanceof TerrainSlabBlock) return true;
        if (s.is(Blocks.SNOW)) return true;
        return s.getBlock() instanceof SlabBlock && MaterialMap.isSmoothingSlab(s.getBlock()) && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }
}
