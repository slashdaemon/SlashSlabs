package com.slashslabs.block;

import com.slashslabs.SlashSlabs;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import eu.pb4.polymer.core.api.item.PolymerCreativeModeTabUtils;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ModBlocks {
    private ModBlocks() {}

    /**
     * A slab with no behaviour of its own: half of {@code full}, with that block's hardness, sound
     * and tool. {@code topSlot}: takes one of the four copper top slots; without one the slab is
     * bottom-only. {@code fallbackSlab} backs it if Polymer has no state left, and worldgen places
     * that vanilla slab instead only if {@code smoothWhenUnslotted}. Static data comes from
     * {@code scripts/gen_material_data.py}; keep the two lists in step.
     */
    public static final class Plain {
        public final String id;
        public final Block full;
        public final Block fallbackSlab;
        public final boolean topSlot;
        public final boolean smoothWhenUnslotted;
        public TerrainSlabBlock block;
        public Item item;
        /** Received Polymer states (false = shown as {@code fallbackSlab}). */
        public boolean textured;

        Plain(String id, Block full, Block fallbackSlab, boolean topSlot, boolean smoothWhenUnslotted) {
            this.id = id;
            this.full = full;
            this.fallbackSlab = fallbackSlab;
            this.topSlot = topSlot;
            this.smoothWhenUnslotted = smoothWhenUnslotted;
        }
    }

    private static Plain terracotta(String id, Block full) {
        // Vanilla has no terracotta slab, so an unslotted terracotta is not smoothed at all.
        return new Plain(id, full, Blocks.GRANITE_SLAB, false, false);
    }

    /** Registration and slot order; append only. */
    public static final List<Plain> PLAIN = List.of(
            new Plain("sand_slab", Blocks.SAND, Blocks.SMOOTH_SANDSTONE_SLAB, true, true),
            new Plain("red_sand_slab", Blocks.RED_SAND, Blocks.SMOOTH_RED_SANDSTONE_SLAB, true, true),
            terracotta("terracotta_slab", Blocks.TERRACOTTA),
            terracotta("orange_terracotta_slab", Blocks.ORANGE_TERRACOTTA),
            terracotta("yellow_terracotta_slab", Blocks.YELLOW_TERRACOTTA),
            terracotta("brown_terracotta_slab", Blocks.BROWN_TERRACOTTA),
            terracotta("red_terracotta_slab", Blocks.RED_TERRACOTTA),
            terracotta("white_terracotta_slab", Blocks.WHITE_TERRACOTTA),
            terracotta("light_gray_terracotta_slab", Blocks.LIGHT_GRAY_TERRACOTTA));

    public static GrassSlabBlock GRASS_SLAB;
    public static DirtSlabBlock DIRT_SLAB;
    public static TerrainSlabBlock SAND_SLAB;
    public static Item GRASS_SLAB_ITEM, DIRT_SLAB_ITEM, SAND_SLAB_ITEM;
    /** Every SlashSlabs block: grass, dirt, then {@link #PLAIN}. */
    public static final List<TerrainSlabBlock> ALL = new ArrayList<>();

    /** Materials that received real Polymer slots (false = shown as a vanilla fallback slab). */
    public static boolean dirtTextured;
    public static int grassTintsTextured;
    public static final List<String> SLOT_REPORT = new ArrayList<>();

    public static void register() {
        // Polymer reads the client state while a block registers (shape cache), so the slots
        // are claimed first and handed to each block before it is registered.
        BlockState[][][][] backing = requestBacking();

        GRASS_SLAB = block("grass_slab", backing[0], p -> new GrassSlabBlock(p
                        .mapColor(MapColor.GRASS).strength(0.6F).sound(SoundType.GRASS).randomTicks()
                        .isValidSpawn((s, l, pos, type) -> SlashSlabs.CONFIG.animalSpawnsOnGrassSlabs && type.getCategory() == MobCategory.CREATURE),
                () -> Blocks.GRASS_BLOCK.defaultBlockState()));
        DIRT_SLAB = block("dirt_slab", backing[1], p -> new DirtSlabBlock(p
                        .mapColor(MapColor.DIRT).strength(0.5F).sound(SoundType.GRAVEL).randomTicks(),
                () -> Blocks.DIRT.defaultBlockState()));
        for (int i = 0; i < PLAIN.size(); i++) {
            Plain m = PLAIN.get(i);
            BlockState full = m.full.defaultBlockState();
            m.block = block(m.id, backing[2 + i], p -> {
                p.mapColor(m.full.defaultMapColor()).strength(m.full.defaultDestroyTime(), m.full.getExplosionResistance())
                        .sound(full.getSoundType());
                if (full.requiresCorrectToolForDrops()) p.requiresCorrectToolForDrops();
                return new TerrainSlabBlock(p, () -> full, () -> full);
            });
            if (m.textured && !m.topSlot) m.block.setBottomOnly();
        }
        SAND_SLAB = PLAIN.get(0).block;

        GRASS_SLAB_ITEM = item("grass_slab", GRASS_SLAB);
        DIRT_SLAB_ITEM = item("dirt_slab", DIRT_SLAB);
        for (Plain m : PLAIN) m.item = item(m.id, m.block);
        SAND_SLAB_ITEM = PLAIN.get(0).item;

        Identifier tabId = SlashSlabs.id("slabs");
        PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(tabId, PolymerCreativeModeTabUtils.builder()
                .title(Component.translatable("itemGroup.slashslabs.slabs"))
                .icon(() -> new ItemStack(GRASS_SLAB_ITEM))
                .displayItems((params, out) -> {
                    out.accept(GRASS_SLAB_ITEM);
                    out.accept(DIRT_SLAB_ITEM);
                    for (Plain m : PLAIN) if (m.textured) out.accept(m.item);
                })
                .build());
    }

    /** The plain material that is half of {@code full}, or null. */
    public static @Nullable Plain plain(Block full) {
        for (Plain m : PLAIN) if (m.full == full) return m;
        return null;
    }

    private static <B extends TerrainSlabBlock> B block(String name, BlockState[][][] backing, Function<BlockBehaviour.Properties, B> factory) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, SlashSlabs.id(name));
        B block = factory.apply(BlockBehaviour.Properties.of().setId(key));
        block.setBacking(backing);
        ALL.add(block);
        return Registry.register(BuiltInRegistries.BLOCK, key, block);
    }

    private static Item item(String name, Block block) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, SlashSlabs.id(name));
        return Registry.register(BuiltInRegistries.ITEM, key,
                new PolymerBlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
    }

    /**
     * Claims Polymer states. Bottoms come from the sculk-sensor pools (150 each), tops from the
     * four copper slab slots (RESEARCH §2.6). The order is fixed and append-only (dirt, sand,
     * grass tints, then the rest of {@link #PLAIN}) so a material's backing state does not move
     * between restarts. A pool that runs dry leaves the material on a vanilla lookalike slab.
     * Returns the {grass, dirt, PLAIN...} backing tables.
     */
    private static BlockState[][][][] requestBacking() {
        BlockState[][] dirt = request("dirt_slab", Blocks.MUD_BRICK_SLAB, true, fallback(Blocks.MUD_BRICK_SLAB)[1]);
        dirtTextured = dirt != null;
        BlockState[][][] dirtTable = {dirt != null ? dirt : fallback(Blocks.MUD_BRICK_SLAB)};

        BlockState[][][][] out = new BlockState[2 + PLAIN.size()][][][];
        out[1] = dirtTable;
        out[2] = requestPlain(PLAIN.get(0));

        int tints = SlashSlabs.PALETTE.length;
        List<BlockState[][]> grass = new ArrayList<>();
        for (int t = 0; t < tints; t++) {
            // Only tint 0 takes a copper top; the other tints share it (tops are player-placed).
            BlockState[] topFallback = grass.isEmpty() ? dirtTable[0][1] : grass.get(0)[1];
            BlockState[][] g = request("grass_slab_t" + t, null, t == 0, topFallback);
            if (g == null) break;
            grass.add(g);
        }
        grassTintsTextured = grass.size();
        if (grass.isEmpty()) grass.add(dirtTable[0]);
        out[0] = grass.toArray(new BlockState[0][][]);

        for (int i = 1; i < PLAIN.size(); i++) out[2 + i] = requestPlain(PLAIN.get(i));

        long plainTextured = PLAIN.stream().filter(m -> m.textured).count();
        SlashSlabs.LOGGER.info("SlashSlabs slots: dirt={}, grass tints={}/{}, other materials={}/{}; {} top slot(s) left, {} sculk",
                dirtTextured, grassTintsTextured, tints, plainTextured, PLAIN.size(),
                PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.SLAB_TOP),
                PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.SCULK_SENSOR));
        return out;
    }

    private static BlockState[][][] requestPlain(Plain m) {
        BlockState[][] fb = fallback(m.fallbackSlab);
        BlockState[][] got = request(m.id, m.smoothWhenUnslotted ? m.fallbackSlab : null, m.topSlot, m.topSlot ? fb[1] : null);
        m.textured = got != null;
        return new BlockState[][][]{got != null ? got : fb};
    }

    /**
     * A sculk-sensor state for the bottom (dry and waterlogged), or null (nothing claimed) if the
     * pools are full. {@code wantsTop}: also claim a copper top slot; without one, or when the
     * copper pools are full, the top shows {@code topFallback}, or the bottom when that is null
     * (a bottom-only material).
     */
    private static BlockState[][] request(String model, Block fallbackForLog, boolean wantsTop, BlockState @Nullable [] topFallback) {
        if (PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.SCULK_SENSOR) <= 0
                || PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.SCULK_SENSOR_WATERLOGGED) <= 0) {
            SlashSlabs.LOGGER.warn("No free Polymer sculk slot for {}{}", model,
                    fallbackForLog == null ? "; it is not smoothed" : "; it falls back to " + BuiltInRegistries.BLOCK.getKey(fallbackForLog));
            SLOT_REPORT.add(model + ": no slot");
            return null;
        }
        BlockState[][] out = new BlockState[2][2];
        PolymerBlockModel bottom = PolymerBlockModel.of(SlashSlabs.id("block/" + model + "_bottom"));
        out[0][0] = PolymerBlockResourceUtils.requestBlock(BlockModelType.SCULK_SENSOR, bottom);
        out[0][1] = PolymerBlockResourceUtils.requestBlock(BlockModelType.SCULK_SENSOR_WATERLOGGED, bottom);
        boolean topSlot = wantsTop && PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.SLAB_TOP) > 0
                && PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.SLAB_TOP_WATERLOGGED) > 0;
        String topNote = "";
        if (topSlot) {
            PolymerBlockModel top = PolymerBlockModel.of(SlashSlabs.id("block/" + model + "_top"));
            out[1][0] = PolymerBlockResourceUtils.requestBlock(BlockModelType.SLAB_TOP, top);
            out[1][1] = PolymerBlockResourceUtils.requestBlock(BlockModelType.SLAB_TOP_WATERLOGGED, top);
        } else if (topFallback != null) {
            out[1] = topFallback.clone();
            topNote = " (shared)";
        } else {
            out[1] = out[0].clone();
            topNote = " (bottom-only)";
        }
        SLOT_REPORT.add(model + " -> " + BuiltInRegistries.BLOCK.getKey(out[0][0].getBlock()) + " / top "
                + BuiltInRegistries.BLOCK.getKey(out[1][0].getBlock()) + topNote);
        return out;
    }

    private static BlockState[][] fallback(Block slab) {
        BlockState[][] out = new BlockState[2][2];
        for (int top = 0; top < 2; top++) {
            for (int wl = 0; wl < 2; wl++) {
                out[top][wl] = slab.defaultBlockState()
                        .setValue(SlabBlock.TYPE, top == 1 ? SlabType.TOP : SlabType.BOTTOM)
                        .setValue(SlabBlock.WATERLOGGED, wl == 1);
            }
        }
        return out;
    }
}
