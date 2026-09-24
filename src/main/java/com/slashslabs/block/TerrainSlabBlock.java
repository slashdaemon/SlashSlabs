package com.slashslabs.block;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A server-side terrain slab. The world stores the real block; each client receives a spare
 * vanilla state that the server pack retextures: an inactive sculk sensor for a bottom slab,
 * a waxed-copper slab for a top (RESEARCH §2.6). A double slab never exists as this block:
 * completing one places the vanilla full block. A material without a top slot is bottom-only.
 */
public class TerrainSlabBlock extends SlabBlock implements PolymerTexturedBlock {
    private final Supplier<BlockState> fullBlock;
    private final Supplier<BlockState> breakState;
    /** [variant][0 bottom, 1 top][0 dry, 1 waterlogged]; variant is the grass tint, 0 for others. */
    private BlockState[][][] backing;
    private boolean bottomOnly;

    public TerrainSlabBlock(Properties properties, Supplier<BlockState> fullBlock, Supplier<BlockState> breakState) {
        super(properties);
        this.fullBlock = fullBlock;
        this.breakState = breakState;
    }

    public void setBacking(BlockState[][][] backing) {
        this.backing = backing;
    }

    /** Players can only place the bottom half; a top state (only /setblock makes one) shows the bottom backing. */
    public void setBottomOnly() {
        this.bottomOnly = true;
    }

    public boolean isBottomOnly() {
        return bottomOnly;
    }

    /** Which backing variant a state uses. */
    protected int variant(BlockState state) {
        return 0;
    }

    public BlockState fullBlock() {
        return fullBlock.get();
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        SlabType type = state.getValue(TYPE);
        if (type == SlabType.DOUBLE) return fullBlock.get();
        int v = Math.min(variant(state), backing.length - 1);
        return backing[v][type == SlabType.TOP ? 1 : 0][state.getValue(WATERLOGGED) ? 1 : 0];
    }

    /** Break sound and particles (level event 2001) come from the vanilla source block, not copper. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return breakState.get();
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState replaced = context.getLevel().getBlockState(context.getClickedPos());
        if (replaced.is(this)) return completeDouble(context);
        BlockState s = super.getStateForPlacement(context);
        return bottomOnly && s != null && s.getValue(TYPE) == SlabType.TOP ? s.setValue(TYPE, SlabType.BOTTOM) : s;
    }

    /** The full block that replaces a slab completed into a double. */
    protected BlockState completeDouble(BlockPlaceContext context) {
        return fullBlock.get();
    }

    /** Same slab shape, other block (keeps type and waterlogging). */
    public static BlockState copyShape(BlockState from, BlockState to) {
        return to.setValue(TYPE, from.getValue(TYPE)).setValue(WATERLOGGED, from.getValue(WATERLOGGED));
    }
}
