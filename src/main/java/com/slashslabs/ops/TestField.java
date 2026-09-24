package com.slashslabs.ops;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Stepped-terrain fixture for manual testing and the self-test (PLAN M0). Eight strips, 5 wide
 * (z) and 20 long (x), each a staircase with one-block steps and a two-block cliff:
 * grass, dirt, sand, stone, snow block, grass with shallow water over the low half, red sand
 * and terracotta. New strips go at the end so earlier strips keep their positions.
 */
public final class TestField {
    private TestField() {}

    public static final int LENGTH = 20, STRIP = 5, STRIPS = 8, CLEAR = 12;
    /** Height profile along x (relative to the base): 1-block steps, then a 2-block cliff at x=12. */
    static final int[] PROFILE = {0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 5, 5, 5, 5};
    static final Block[] GROUND = {Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.SAND, Blocks.STONE, Blocks.SNOW_BLOCK, Blocks.GRASS_BLOCK,
            Blocks.RED_SAND, Blocks.TERRACOTTA};
    /** What fills each strip below its surface block. */
    static final Block[] UNDER = {Blocks.DIRT, Blocks.DIRT, Blocks.SANDSTONE, Blocks.DIRT, Blocks.DIRT, Blocks.DIRT,
            Blocks.RED_SANDSTONE, Blocks.TERRACOTTA};

    public record Field(BlockPos origin, BoundingBox box) {
        /** World position of strip {@code s}, profile step {@code x}, row {@code z}, at the ground top. */
        public BlockPos ground(int s, int x, int z) {
            return origin.offset(x, PROFILE[x], s * (STRIP + 1) + z);
        }

        public List<ChunkPos> chunks() {
            List<ChunkPos> out = new ArrayList<>();
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++)
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++)
                    out.add(new ChunkPos(cx, cz));
            return out;
        }
    }

    /** Builds the fixture with its lowest ground layer at {@code origin} (flags: no drops, clients updated). */
    public static Field build(ServerLevel level, BlockPos origin) {
        int depth = STRIPS * (STRIP + 1) - 1;
        BoundingBox box = new BoundingBox(origin.getX(), origin.getY() - 2, origin.getZ(),
                origin.getX() + LENGTH - 1, origin.getY() + CLEAR, origin.getZ() + depth - 1);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = box.minX() - 1; x <= box.maxX() + 1; x++)
            for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++)
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    p.set(x, y, z);
                    set(level, p, y < origin.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
        for (int s = 0; s < STRIPS; s++) {
            BlockState ground = GROUND[s].defaultBlockState();
            for (int x = 0; x < LENGTH; x++)
                for (int z = 0; z < STRIP; z++) {
                    int top = PROFILE[x];
                    for (int y = -1; y <= top; y++) {
                        p.set(origin.getX() + x, origin.getY() + y, origin.getZ() + s * (STRIP + 1) + z);
                        set(level, p, y == top ? ground : UNDER[s].defaultBlockState());
                    }
                }
            if (s == 5) {
                // Shallow pond: one block of water over the lowest step, walled in with glass (not
                // terrain, so the walls do not read as a rise).
                for (int x = 0; x < 4; x++)
                    for (int z = -1; z <= STRIP; z++) {
                        p.set(origin.getX() + x, origin.getY() + 1, origin.getZ() + s * (STRIP + 1) + z);
                        boolean wall = z < 0 || z >= STRIP;
                        set(level, p, (wall ? Blocks.GLASS : Blocks.WATER).defaultBlockState());
                    }
                for (int z = -1; z <= STRIP; z++) {
                    p.set(origin.getX() - 1, origin.getY() + 1, origin.getZ() + s * (STRIP + 1) + z);
                    set(level, p, Blocks.GLASS.defaultBlockState());
                }
            }
        }
        return new Field(origin.immutable(), box);
    }

    private static void set(ServerLevel level, BlockPos p, BlockState s) {
        level.setBlock(p, s, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
    }
}
