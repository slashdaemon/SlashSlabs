package com.slashslabs.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

/**
 * Dirt slab. Vanilla grass only spreads onto {@code minecraft:dirt}, so a dirt slab pulls grass
 * from nearby real grass blocks on its own random tick instead (no mixin). The source window is
 * the mirror of vanilla's target window, and grass slabs push on their own, so the spread rate
 * matches vanilla.
 */
public class DirtSlabBlock extends TerrainSlabBlock {
    public DirtSlabBlock(Properties properties, Supplier<BlockState> breakState) {
        super(properties, () -> Blocks.DIRT.defaultBlockState(), breakState);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(WATERLOGGED)) return;
        BlockState grass = GrassSlabBlock.grassFrom(state, level, pos);
        if (!GrassSlabBlock.canPropagate(grass, level, pos)) return;
        for (int i = 0; i < 4; i++) {
            BlockPos src = pos.offset(random.nextInt(3) - 1, random.nextInt(5) - 1, random.nextInt(3) - 1);
            if (level.getBlockState(src).is(Blocks.GRASS_BLOCK) && level.getMaxLocalRawBrightness(src.above()) >= 9) {
                level.setBlockAndUpdate(pos, grass);
                return;
            }
        }
    }
}
