package com.slashslabs.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.slashslabs.SlashSlabs;
import com.slashslabs.block.ModBlocks;
import com.slashslabs.color.ColorMath;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.minecraft.core.registries.BuiltInRegistries;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds SlashSlabs' part of the Polymer server pack at pack-build time. Grass textures are
 * derived from the vanilla client jar Polymer already caches, so the mod jar itself carries no
 * Mojang pixels (RESEARCH §5.4-5.5). Dirt and the plain materials reference vanilla textures directly.
 */
public final class PackGenerator {
    private PackGenerator() {}

    public static void register() {
        PolymerResourcePackUtils.markAsRequired();
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(PackGenerator::build);
    }

    static void build(ResourcePackBuilder b) {
        int[] palette = SlashSlabs.PALETTE;
        try {
            Path root = PolymerCommonUtils.getClientJarRoot();
            BufferedImage top = read(root, "grass_block_top");
            BufferedImage side = read(root, "grass_block_side");
            BufferedImage overlay = read(root, "grass_block_side_overlay");
            for (int t = 0; t < palette.length; t++) {
                b.addData("assets/slashslabs/textures/block/grass_slab_top_t" + t + ".png", png(tintTop(top, palette[t])));
                b.addData("assets/slashslabs/textures/block/grass_slab_side_t" + t + ".png", png(tintSide(side, overlay, palette[t])));
            }
        } catch (Exception e) {
            SlashSlabs.LOGGER.error("Could not build grass slab textures from the vanilla client jar", e);
        }

        for (int t = 0; t < palette.length; t++) {
            String top = "slashslabs:block/grass_slab_top_t" + t, side = "slashslabs:block/grass_slab_side_t" + t;
            model(b, "grass_slab_t" + t, "minecraft:block/dirt", top, side, "minecraft:block/dirt", true);
        }
        model(b, "dirt_slab", "minecraft:block/dirt", "minecraft:block/dirt", "minecraft:block/dirt", "minecraft:block/dirt", false);
        for (ModBlocks.Plain m : ModBlocks.PLAIN) {
            String tex = "minecraft:block/" + BuiltInRegistries.BLOCK.getKey(m.full).getPath();
            model(b, m.id, tex, tex, tex, tex, false);
        }

        itemDefinition(b, "grass_slab", "slashslabs:block/grass_slab_t0_bottom");
        itemDefinition(b, "dirt_slab", "slashslabs:block/dirt_slab_bottom");
        for (ModBlocks.Plain m : ModBlocks.PLAIN) itemDefinition(b, m.id, "slashslabs:block/" + m.id + "_bottom");

        JsonObject lang = new JsonObject();
        lang.addProperty("block.slashslabs.grass_slab", "Grass Slab");
        lang.addProperty("block.slashslabs.dirt_slab", "Dirt Slab");
        for (ModBlocks.Plain m : ModBlocks.PLAIN) lang.addProperty("block.slashslabs." + m.id, title(m.id));
        lang.addProperty("itemGroup.slashslabs.slabs", "SlashSlabs");
        b.addStringData("assets/slashslabs/lang/en_us.json", lang.toString());
    }

    /** "light_gray_terracotta_slab" -> "Light Gray Terracotta Slab". */
    static String title(String id) {
        StringBuilder out = new StringBuilder();
        for (String w : id.split("_")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    private static BufferedImage read(Path root, String name) throws IOException {
        try (InputStream in = Files.newInputStream(root.resolve("assets/minecraft/textures/block/" + name + ".png"))) {
            BufferedImage img = ImageIO.read(in);
            BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(img, 0, 0, null);
            return argb;
        }
    }

    static BufferedImage tintTop(BufferedImage grey, int tint) {
        BufferedImage out = new BufferedImage(grey.getWidth(), grey.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < grey.getHeight(); y++)
            for (int x = 0; x < grey.getWidth(); x++)
                out.setRGB(x, y, ColorMath.multiply(grey.getRGB(x, y), tint));
        return out;
    }

    /** The vanilla side texture with its grey overlay tinted on top (what the client composes). */
    static BufferedImage tintSide(BufferedImage side, BufferedImage overlay, int tint) {
        BufferedImage out = new BufferedImage(side.getWidth(), side.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < side.getHeight(); y++)
            for (int x = 0; x < side.getWidth(); x++) {
                int ov = overlay.getRGB(x * overlay.getWidth() / side.getWidth(), y * overlay.getHeight() / side.getHeight());
                out.setRGB(x, y, ColorMath.over(ColorMath.multiply(ov, tint), side.getRGB(x, y)));
            }
        return out;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }

    /**
     * Bottom and top slab models. {@code fringeOnTop}: the side texture's top half (grass fringe)
     * goes on the half-height side, instead of vanilla's position-derived UVs.
     */
    private static void model(ResourcePackBuilder b, String name, String particle, String top, String side, String bottom, boolean fringeOnTop) {
        for (boolean upper : new boolean[]{false, true}) {
            JsonObject m = new JsonObject();
            m.addProperty("parent", "minecraft:block/block");
            JsonObject tex = new JsonObject();
            tex.addProperty("particle", particle);
            tex.addProperty("top", top);
            tex.addProperty("side", side);
            tex.addProperty("bottom", bottom);
            m.add("textures", tex);

            JsonObject el = new JsonObject();
            el.add("from", arr(0, upper ? 8 : 0, 0));
            el.add("to", arr(16, upper ? 16 : 8, 16));
            JsonObject faces = new JsonObject();
            faces.add("down", face(arr(0, 0, 16, 16), "#bottom", upper ? null : "down"));
            faces.add("up", face(arr(0, 0, 16, 16), "#top", upper ? "up" : null));
            JsonArray sideUv = (fringeOnTop || upper) ? arr(0, 0, 16, 8) : arr(0, 8, 16, 16);
            for (String d : new String[]{"north", "south", "west", "east"}) faces.add(d, face(sideUv, "#side", d));
            el.add("faces", faces);
            JsonArray elements = new JsonArray();
            elements.add(el);
            m.add("elements", elements);
            b.addStringData("assets/slashslabs/models/block/" + name + (upper ? "_top" : "_bottom") + ".json", m.toString());
        }
    }

    private static void itemDefinition(ResourcePackBuilder b, String item, String model) {
        JsonObject inner = new JsonObject();
        inner.addProperty("type", "minecraft:model");
        inner.addProperty("model", model);
        JsonObject def = new JsonObject();
        def.add("model", inner);
        b.addStringData("assets/slashslabs/items/" + item + ".json", def.toString());
    }

    private static JsonObject face(JsonArray uv, String texture, String cull) {
        JsonObject f = new JsonObject();
        f.add("uv", uv);
        f.addProperty("texture", texture);
        if (cull != null) f.addProperty("cullface", cull);
        return f;
    }

    private static JsonArray arr(int... v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }
}
