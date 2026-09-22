package com.slashslabs.worldgen;

import com.slashslabs.SlabsConfig;
import com.slashslabs.SlashSlabs;
import com.slashslabs.block.ModBlocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Surface block → what goes on top of it at a one-block rise (PLAN §3). Built once after
 * registration and never mutated, so worldgen threads read it freely.
 */
public final class MaterialMap {
    public enum Kind { GRASS, CUSTOM, VANILLA, SNOW, NONE }

    public record Choice(Kind kind, Block slab) {
        static final Choice NONE = new Choice(Kind.NONE, null);
        static final Choice SNOW = new Choice(Kind.SNOW, null);
        static final Choice GRASS = new Choice(Kind.GRASS, null);
    }

    private static Map<Block, Choice> map = Map.of();
    /** Single-block plants a generated slab may replace. */
    private static Set<Block> replaceablePlants = Set.of();
    /** Blocks that count as terrain for the retrofit/survey surface scan. */
    private static Set<Block> terrain = Set.of();
    /** Vanilla slabs the map can place (a smoothed column scans through them). */
    private static Set<Block> smoothingSlabs = Set.of();

    public static void build(SlabsConfig cfg) {
        Map<Block, Choice> m = new HashMap<>();
        m.put(Blocks.GRASS_BLOCK, Choice.GRASS);
        Choice dirt = new Choice(Kind.CUSTOM, ModBlocks.DIRT_SLAB);
        for (Block b : new Block[]{Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MYCELIUM})
            m.put(b, dirt);
        m.put(Blocks.SAND, ModBlocks.sandTextured ? new Choice(Kind.CUSTOM, ModBlocks.SAND_SLAB) : vanilla(Blocks.SMOOTH_SANDSTONE_SLAB));
        m.put(Blocks.RED_SAND, vanilla(Blocks.SMOOTH_RED_SANDSTONE_SLAB));
        m.put(Blocks.STONE, vanilla(Blocks.STONE_SLAB));
        m.put(Blocks.COBBLESTONE, vanilla(Blocks.COBBLESTONE_SLAB));
        m.put(Blocks.MOSSY_COBBLESTONE, vanilla(Blocks.MOSSY_COBBLESTONE_SLAB));
        m.put(Blocks.DEEPSLATE, vanilla(Blocks.COBBLED_DEEPSLATE_SLAB));
        m.put(Blocks.TUFF, vanilla(Blocks.TUFF_SLAB));
        m.put(Blocks.ANDESITE, vanilla(Blocks.ANDESITE_SLAB));
        m.put(Blocks.DIORITE, vanilla(Blocks.DIORITE_SLAB));
        m.put(Blocks.GRANITE, vanilla(Blocks.GRANITE_SLAB));
        m.put(Blocks.BLACKSTONE, vanilla(Blocks.BLACKSTONE_SLAB));
        m.put(Blocks.SANDSTONE, vanilla(Blocks.SANDSTONE_SLAB));
        m.put(Blocks.RED_SANDSTONE, vanilla(Blocks.RED_SANDSTONE_SLAB));
        m.put(Blocks.MUD, vanilla(Blocks.MUD_BRICK_SLAB));
        m.put(Blocks.SNOW_BLOCK, Choice.SNOW);

        for (var e : cfg.materialOverrides.entrySet()) {
            Optional<Block> key = lookup(e.getKey());
            if (key.isEmpty()) {
                SlashSlabs.LOGGER.warn("materialOverrides: unknown block {}", e.getKey());
                continue;
            }
            String v = e.getValue().trim();
            if (v.equals("none")) m.put(key.get(), Choice.NONE);
            else if (v.equals("snow")) m.put(key.get(), Choice.SNOW);
            else if (v.equals("slashslabs:grass_slab")) m.put(key.get(), Choice.GRASS);
            else lookup(v).ifPresentOrElse(b -> m.put(key.get(), b instanceof com.slashslabs.block.TerrainSlabBlock
                            ? new Choice(Kind.CUSTOM, b) : vanilla(b)),
                    () -> SlashSlabs.LOGGER.warn("materialOverrides: unknown slab {}", v));
        }
        map = Map.copyOf(m);
        Set<Block> slabs = new HashSet<>();
        for (Choice c : m.values()) if (c.kind() == Kind.VANILLA) slabs.add(c.slab());
        smoothingSlabs = Set.copyOf(slabs);

        Set<Block> plants = new HashSet<>();
        for (String id : new String[]{"short_grass", "fern", "short_dry_grass", "tall_dry_grass", "bush", "dead_bush", "leaf_litter", "seagrass"})
            lookup("minecraft:" + id).ifPresent(plants::add);
        replaceablePlants = Set.copyOf(plants);

        Set<Block> t = new HashSet<>(m.keySet());
        for (Block b : new Block[]{Blocks.GRAVEL, Blocks.CLAY, Blocks.TERRACOTTA, Blocks.CALCITE, Blocks.MOSS_BLOCK,
                Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.POWDER_SNOW, Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL, Blocks.DRIPSTONE_BLOCK,
                Blocks.SMOOTH_BASALT, Blocks.PALE_MOSS_BLOCK})
            t.add(b);
        for (Block b : BuiltInRegistries.BLOCK) {
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (path.endsWith("terracotta") && !path.contains("glazed")) t.add(b);
        }
        terrain = Set.copyOf(t);
    }

    private static Choice vanilla(Block slab) {
        return new Choice(Kind.VANILLA, slab);
    }

    private static Optional<Block> lookup(String id) {
        Identifier i = Identifier.tryParse(id);
        return i == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(i);
    }

    public static Choice choiceFor(Block surface) {
        return map.getOrDefault(surface, Choice.NONE);
    }

    public static boolean isReplaceablePlant(Block b) {
        return replaceablePlants.contains(b);
    }

    public static boolean isSmoothingSlab(Block b) {
        return smoothingSlabs.contains(b);
    }

    public static boolean isTerrain(Block b) {
        return terrain.contains(b);
    }
}
