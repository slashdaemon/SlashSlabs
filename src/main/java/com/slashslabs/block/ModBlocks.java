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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ModBlocks {
    private ModBlocks() {}

    public static GrassSlabBlock GRASS_SLAB;
    public static DirtSlabBlock DIRT_SLAB;
    public static TerrainSlabBlock SAND_SLAB;
    public static Item GRASS_SLAB_ITEM, DIRT_SLAB_ITEM, SAND_SLAB_ITEM;

    /** Materials that received real Polymer slots (false = shown as a vanilla fallback slab). */
    public static boolean dirtTextured, sandTextured;
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
        SAND_SLAB = block("sand_slab", backing[2], p -> new TerrainSlabBlock(p
                        .mapColor(MapColor.SAND).strength(0.5F).sound(SoundType.SAND),
                () -> Blocks.SAND.defaultBlockState(), () -> Blocks.SAND.defaultBlockState()));

        GRASS_SLAB_ITEM = item("grass_slab", GRASS_SLAB);
        DIRT_SLAB_ITEM = item("dirt_slab", DIRT_SLAB);
        SAND_SLAB_ITEM = item("sand_slab", SAND_SLAB);

        Identifier tabId = SlashSlabs.id("slabs");
        PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(tabId, PolymerCreativeModeTabUtils.builder()
                .title(Component.translatable("itemGroup.slashslabs.slabs"))
                .icon(() -> new ItemStack(GRASS_SLAB_ITEM))
                .displayItems((params, out) -> {
                    out.accept(GRASS_SLAB_ITEM);
                    out.accept(DIRT_SLAB_ITEM);
                    if (sandTextured) out.accept(SAND_SLAB_ITEM);
                })
                .build());
    }

    private static <B extends TerrainSlabBlock> B block(String name, BlockState[][][] backing, Function<BlockBehaviour.Properties, B> factory) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, SlashSlabs.id(name));
        B block = factory.apply(BlockBehaviour.Properties.of().setId(key));
        block.setBacking(backing);
        return Registry.register(BuiltInRegistries.BLOCK, key, block);
    }

    private static Item item(String name, Block block) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, SlashSlabs.id(name));
        return Registry.register(BuiltInRegistries.ITEM, key,
                new PolymerBlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
    }

    /**
     * Claims Polymer slab slots. The order is fixed (dirt, sand, grass tints) so a material's
     * backing state does not move between restarts; only grass entries shift when the palette
     * size changes. A pool that runs dry leaves the material on a vanilla lookalike slab.
     * Returns the {grass, dirt, sand} backing tables.
     */
    private static BlockState[][][][] requestBacking() {
        BlockState[][] dirt = request("dirt_slab", Blocks.MUD_BRICK_SLAB);
        dirtTextured = dirt != null;
        BlockState[][][] dirtTable = {dirt != null ? dirt : fallback(Blocks.MUD_BRICK_SLAB)};

        BlockState[][] sand = SlashSlabs.CONFIG.sandSlab ? request("sand_slab", Blocks.SMOOTH_SANDSTONE_SLAB) : null;
        sandTextured = sand != null;
        BlockState[][][] sandTable = {sand != null ? sand : fallback(Blocks.SMOOTH_SANDSTONE_SLAB)};

        int tints = SlashSlabs.PALETTE.length;
        List<BlockState[][]> grass = new ArrayList<>();
        for (int t = 0; t < tints; t++) {
            BlockState[][] g = request("grass_slab_t" + t, null);
            if (g == null) break;
            grass.add(g);
        }
        grassTintsTextured = grass.size();
        if (grass.isEmpty()) grass.add(dirtTable[0]);

        SlashSlabs.LOGGER.info("SlashSlabs slots: dirt={}, sand={}, grass tints={}/{}; {} slab slot(s) left",
                dirtTextured, sandTextured, grassTintsTextured, tints,
                PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.SLAB_BOTTOM));
        return new BlockState[][][][]{grass.toArray(new BlockState[0][][]), dirtTable, sandTable};
    }

    /** One slot in each of the four slab pools, or null (nothing claimed) if any pool is full. */
    private static BlockState[][] request(String model, Block fallbackForLog) {
        for (boolean top : new boolean[]{false, true}) {
            for (boolean wl : new boolean[]{false, true}) {
                if (PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.getSlab(!top, wl)) <= 0) {
                    SlashSlabs.LOGGER.warn("No free Polymer slab slot for {}{}", model,
                            fallbackForLog == null ? "; it is disabled" : "; it falls back to " + BuiltInRegistries.BLOCK.getKey(fallbackForLog));
                    SLOT_REPORT.add(model + ": no slot");
                    return null;
                }
            }
        }
        BlockState[][] out = new BlockState[2][2];
        for (int top = 0; top < 2; top++) {
            for (int wl = 0; wl < 2; wl++) {
                PolymerBlockModel m = PolymerBlockModel.of(SlashSlabs.id("block/" + model + (top == 1 ? "_top" : "_bottom")));
                out[top][wl] = PolymerBlockResourceUtils.requestBlock(BlockModelType.getSlab(top == 0, wl == 1), m);
            }
        }
        SLOT_REPORT.add(model + " -> " + BuiltInRegistries.BLOCK.getKey(out[0][0].getBlock()));
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
