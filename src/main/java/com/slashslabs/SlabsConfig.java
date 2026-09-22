package com.slashslabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config/slashslabs.json. Read once at startup (block states and Polymer slots are fixed then);
 * worldgen reads it from many threads, so nothing here is mutated after load.
 */
public final class SlabsConfig {
    /** Smooth terrain in newly generated chunks. */
    public boolean worldgen = true;
    /**
     * When worldgen smoothing runs: "light" (default; start of the chunk's LIGHT step, after all
     * neighbours finished features, so the result is order-independent) or "features" (end of
     * the chunk's own feature pass).
     */
    public String hookStage = "light";
    /** Also treat a rise that only touches diagonally as a step. */
    public boolean diagonals = false;
    /** Dimensions to smooth. */
    public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld"));
    /** Biomes never smoothed (ids). */
    public List<String> excludeBiomes = new ArrayList<>();
    /** Skip columns inside structure pieces (villages, ruins …). */
    public boolean structureGuard = true;
    /** Deepest water a waterlogged slab may be placed in (0 disables underwater slabs). */
    public int maxWaterDepth = 1;
    /** Snow layer count used at a rise in snowy biomes (5 = 0.5 step; see RESEARCH C7). */
    public int snowLayers = 5;

    /**
     * Grass slab palette, one Polymer slot each (max 3). Each generated or placed grass slab takes
     * the entry nearest to the biome's grass colour. Default fitted by the M2 survey (forest, birch
     * forest, taiga greens; docs/SURVEY.md): mean CIE76 error 4.7 over 67k rise-edge samples.
     */
    public List<String> grassPalette = new ArrayList<>(List.of("#79C05A", "#88BB67", "#86B783"));
    /** Spend a slot on a real sand slab. Otherwise sand falls back to smooth sandstone slabs. */
    public boolean sandSlab = false;

    /**
     * Per-surface-block overrides of the material map, e.g. {"minecraft:gravel": "minecraft:andesite_slab"}
     * or {"minecraft:podzol": "none"}. Values: a slab block id, "snow", or "none".
     */
    public Map<String, String> materialOverrides = new LinkedHashMap<>();

    /** Companion module: raise player step height to 1.0 (PLAN §8). */
    public boolean stepHeight = false;
    /** With stepHeight on, fall back to vanilla step height while sneaking. */
    public boolean stepHeightOffWhileSneaking = true;
    /** Let animals spawn on grass slabs (D6). Vanilla slabs allow none. */
    public boolean animalSpawnsOnGrassSlabs = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static SlabsConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("slashslabs.json");
        SlabsConfig cfg = new SlabsConfig();
        try {
            if (Files.exists(path)) {
                SlabsConfig read = GSON.fromJson(Files.readString(path), SlabsConfig.class);
                if (read != null) cfg = read;
            }
            cfg.sanitize();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(cfg));
        } catch (IOException | RuntimeException e) {
            SlashSlabs.LOGGER.error("Could not read {}; using defaults", path, e);
            cfg = new SlabsConfig();
            cfg.sanitize();
        }
        return cfg;
    }

    private void sanitize() {
        if (grassPalette == null || grassPalette.isEmpty()) grassPalette = new ArrayList<>(List.of("#91BD59"));
        int maxGrass = sandSlab ? 2 : 3;
        if (grassPalette.size() > maxGrass) {
            SlashSlabs.LOGGER.warn("grassPalette has {} entries but only {} slots are free; using the first {}",
                    grassPalette.size(), maxGrass, maxGrass);
            grassPalette = new ArrayList<>(grassPalette.subList(0, maxGrass));
        }
        if (!"features".equals(hookStage)) hookStage = "light";
        snowLayers = Math.max(1, Math.min(8, snowLayers));
        maxWaterDepth = Math.max(0, maxWaterDepth);
        if (dimensions == null) dimensions = new ArrayList<>();
        if (excludeBiomes == null) excludeBiomes = new ArrayList<>();
        if (materialOverrides == null) materialOverrides = new LinkedHashMap<>();
    }

    public int[] paletteRgb() {
        int[] out = new int[grassPalette.size()];
        for (int i = 0; i < out.length; i++) out[i] = parseColor(grassPalette.get(i));
        return out;
    }

    static int parseColor(String s) {
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        return Integer.parseInt(t, 16) & 0xFFFFFF;
    }
}
