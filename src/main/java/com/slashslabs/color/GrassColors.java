package com.slashslabs.color;

import com.slashslabs.SlashSlabs;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.LevelReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side grass colour. A dedicated server has no colormap (GrassColor's pixels are all zero,
 * RESEARCH C9), so the vanilla colormap is read from the client jar Polymer caches and fed to
 * {@link GrassColor#init}; after that {@code Biome.getGrassColor} is exact, biome overrides and the
 * swamp/dark-forest modifiers included.
 */
public final class GrassColors {
    private GrassColors() {}

    private static volatile boolean ready;

    public static synchronized void ensureLoaded() {
        if (ready) return;
        if ((GrassColor.getDefaultColor() & 0xFFFFFF) != 0) {
            ready = true; // a client (integrated server) already loaded it
            return;
        }
        try {
            Path root = PolymerCommonUtils.getClientJarRoot();
            if (root == null) throw new IllegalStateException("no client jar");
            Path png = root.resolve("assets/minecraft/textures/colormap/grass.png");
            BufferedImage img;
            try (InputStream in = Files.newInputStream(png)) {
                img = ImageIO.read(in);
            }
            int w = img.getWidth(), h = img.getHeight();
            int[] px = new int[w * h];
            img.getRGB(0, 0, w, h, px, 0, w);
            GrassColor.init(px);
            SlashSlabs.LOGGER.info("Loaded the grass colormap ({}x{}) from the vanilla client jar", w, h);
        } catch (Exception e) {
            SlashSlabs.LOGGER.error("Could not load the grass colormap; grass slabs use palette entry 0", e);
        }
        ready = true;
    }

    public static boolean available() {
        return (GrassColor.getDefaultColor() & 0xFFFFFF) != 0;
    }

    /** The colour a vanilla client with default biome blend (radius 2) draws on grass at {@code pos}. */
    public static int blended(LevelReader level, BlockPos pos) {
        int[] samples = new int[25];
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                p.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                samples[n++] = level.getBiome(p).value().getGrassColor(p.getX(), p.getZ()) & 0xFFFFFF;
            }
        }
        return ColorMath.average(samples, n);
    }

    public static int single(LevelReader level, BlockPos pos) {
        return level.getBiome(pos).value().getGrassColor(pos.getX(), pos.getZ()) & 0xFFFFFF;
    }
}
