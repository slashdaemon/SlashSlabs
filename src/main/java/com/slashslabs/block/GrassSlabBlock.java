package com.slashslabs.block;

import com.slashslabs.SlashSlabs;
import com.slashslabs.color.ColorMath;
import com.slashslabs.color.GrassColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Grass slab. {@link #TINT} picks the palette texture (one Polymer slot each); it is chosen from
 * the biome colour when the slab is generated or placed. Spreads and decays like grass.
 */
public class GrassSlabBlock extends TerrainSlabBlock {
    public static final int MAX_TINTS = 3;
    public static final IntegerProperty TINT = IntegerProperty.create("tint", 0, MAX_TINTS - 1);

    public GrassSlabBlock(Properties properties, Supplier<BlockState> breakState) {
        super(properties, () -> Blocks.GRASS_BLOCK.defaultBlockState(), breakState);
        registerDefaultState(defaultBlockState().setValue(TINT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(TINT);
    }

    @Override
    protected int variant(BlockState state) {
        return state.getValue(TINT);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState s = super.getStateForPlacement(context);
        if (s == null || !s.is(this)) return s;
        return s.setValue(TINT, tintAt(context.getLevel(), context.getClickedPos()));
    }

    @Override
    protected BlockState completeDouble(BlockPlaceContext context) {
        BlockState above = context.getLevel().getBlockState(context.getClickedPos().above());
        return Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyBlock.SNOWY, above.is(Blocks.SNOW) || above.is(Blocks.POWDER_SNOW) || above.is(Blocks.SNOW_BLOCK));
    }

    /** The palette entry nearest the colour a vanilla client would draw on real grass here. */
    public static int tintAt(LevelReader level, BlockPos pos) {
        int[] palette = SlashSlabs.PALETTE;
        if (palette.length <= 1 || !GrassColors.available()) return 0;
        return ColorMath.nearest(GrassColors.blended(level, pos), palette);
    }

    // ---- spreading / decay (vanilla SpreadingSnowyBlock rules, with slab-aware targets) ----

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!canStayAlive(state, level, pos)) {
            level.setBlockAndUpdate(pos, copyShape(state, ModBlocks.DIRT_SLAB.defaultBlockState()));
            return;
        }
        if (level.getMaxLocalRawBrightness(pos.above()) < 9) return;
        for (int i = 0; i < 4; i++) {
            BlockPos t = pos.offset(random.nextInt(3) - 1, random.nextInt(5) - 3, random.nextInt(3) - 1);
            BlockState target = level.getBlockState(t);
            if (target.is(Blocks.DIRT)) {
                BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
                if (canPropagate(grass, level, t)) {
                    BlockState above = level.getBlockState(t.above());
                    level.setBlockAndUpdate(t, grass.setValue(SnowyBlock.SNOWY, above.is(Blocks.SNOW)));
                }
            } else if (target.is(ModBlocks.DIRT_SLAB)) {
                BlockState grass = grassFrom(target, level, t);
                if (canPropagate(grass, level, t)) level.setBlockAndUpdate(t, grass);
            }
        }
    }

    /** A grass slab of the same shape as {@code dirtSlab}, tinted for {@code pos}. */
    public static BlockState grassFrom(BlockState dirtSlab, LevelReader level, BlockPos pos) {
        return copyShape(dirtSlab, ModBlocks.GRASS_SLAB.defaultBlockState()).setValue(TINT, tintAt(level, pos));
    }

    public static boolean canStayAlive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.hasProperty(WATERLOGGED) && state.getValue(WATERLOGGED)) return false;
        BlockState above = level.getBlockState(pos.above());
        if (above.is(Blocks.SNOW) && above.getValue(SnowLayerBlock.LAYERS) == 1) return true;
        if (above.getFluidState().isFull()) return false;
        return lightBlockInto(state, above, Direction.UP, above.getLightDampening()) < 15;
    }

    public static boolean canPropagate(BlockState state, LevelReader level, BlockPos pos) {
        return canStayAlive(state, level, pos) && !level.getFluidState(pos.above()).is(FluidTags.WATER);
    }

    /** LightEngine.getLightBlockInto, inlined: its name differs between 26.1 and 26.2 (RESEARCH §4.3). */
    static int lightBlockInto(BlockState from, BlockState to, Direction dir, int simpleOpacity) {
        boolean fromEmpty = !from.canOcclude() || !from.useShapeForLightOcclusion();
        boolean toEmpty = !to.canOcclude() || !to.useShapeForLightOcclusion();
        if (fromEmpty && toEmpty) return simpleOpacity;
        VoxelShape a = fromEmpty ? Shapes.empty() : from.getOcclusionShape();
        VoxelShape b = toEmpty ? Shapes.empty() : to.getOcclusionShape();
        return Shapes.mergedFaceOccludes(a, b, dir) ? 16 : simpleOpacity;
    }
}
