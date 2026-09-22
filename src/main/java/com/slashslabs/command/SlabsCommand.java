package com.slashslabs.command;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.slashslabs.SlashSlabs;
import com.slashslabs.block.ModBlocks;
import com.slashslabs.color.ColorMath;
import com.slashslabs.color.GrassColors;
import com.slashslabs.color.PaletteFit;
import com.slashslabs.ops.SelfTest;
import com.slashslabs.ops.TestField;
import com.slashslabs.ops.WorldOps;
import com.slashslabs.worldgen.TerrainSmoother;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** /slashslabs … (op level 2). */
public final class SlabsCommand {
    private SlabsCommand() {}

    private static final int MAX_RADIUS = 64;

    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx, Commands.CommandSelection sel) {
        d.register(Commands.literal("slashslabs")
                .requires(Perms.node("base"))
                .then(Commands.literal("info").requires(Perms.node("info")).executes(SlabsCommand::info))
                .then(Commands.literal("test-field").requires(Perms.node("test_field")).executes(c -> testField(c, true))
                        .then(Commands.literal("raw").executes(c -> testField(c, false))))
                .then(Commands.literal("selftest").requires(Perms.node("selftest")).executes(SlabsCommand::selftest))
                .then(Commands.literal("survey").requires(Perms.node("survey")).then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                        .executes(SlabsCommand::survey)))
                .then(Commands.literal("smooth").requires(Perms.node("smooth")).then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                        .executes(SlabsCommand::smooth)))
                .then(Commands.literal("purge").requires(Perms.node("purge")).then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                        .executes(c -> purge(c, null))
                        .then(Commands.argument("stand_in", StringArgumentType.string())
                                .executes(c -> purge(c, StringArgumentType.getString(c, "stand_in"))))))
                .then(Commands.literal("dump").requires(Perms.node("dump")).then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                        .executes(c -> dump(c, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> dump(c, StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("dumpcols").requires(Perms.node("dumpcols")).then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                        .then(Commands.argument("name", StringArgumentType.word()).executes(SlabsCommand::dumpCols))))
                .then(Commands.literal("gen").requires(Perms.node("gen")).then(Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                        .then(Commands.argument("order", StringArgumentType.word()).executes(SlabsCommand::gen)))));
    }

    private static void say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }

    private static ChunkPos centre(CommandSourceStack src) {
        return ChunkPos.containing(BlockPos.containing(src.getPosition()));
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        say(src, "SlashSlabs " + FabricLoader.getInstance().getModContainer(SlashSlabs.MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?")
                + " | worldgen " + (SlashSlabs.CONFIG.worldgen ? "on" : "off") + " | step height " + (SlashSlabs.CONFIG.stepHeight ? "on" : "off"));
        for (String s : ModBlocks.SLOT_REPORT) say(src, "  slot " + s);
        StringBuilder pal = new StringBuilder("  grass palette:");
        for (int p : SlashSlabs.PALETTE) pal.append(' ').append(ColorMath.hex(p));
        say(src, pal + " (" + ModBlocks.grassTintsTextured + " textured)");
        say(src, "  slab slots left: bottom " + PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.SLAB_BOTTOM)
                + ", top " + PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.SLAB_TOP)
                + " | colormap " + (GrassColors.available() ? "loaded" : "missing"));
        long n = TerrainSmoother.GEN_CHUNKS.sum();
        say(src, String.format("  worldgen: %d chunks smoothed, %d blocks placed, %.3f ms/chunk", n, TerrainSmoother.GEN_PLACED.sum(),
                n == 0 ? 0.0 : TerrainSmoother.GEN_NANOS.sum() / 1e6 / n));
        return 1;
    }

    private static int testField(CommandContext<CommandSourceStack> c, boolean smooth) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition()).offset(2, 0, 2);
        TestField.Field f = TestField.build(level, at);
        if (smooth) {
            TerrainSmoother.Stats s = WorldOps.smooth(level, f.chunks(), false, f.box());
            say(src, "Test field at " + at.toShortString() + ": " + s.placed + " slabs on " + s.rises + " rises");
        } else {
            say(src, "Raw test field at " + at.toShortString() + " (run /slashslabs smooth 1 to smooth it)");
        }
        return 1;
    }

    private static int selftest(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        boolean ok = SelfTest.run(src.getServer().overworld(), line -> say(src, line));
        return ok ? 1 : 0;
    }

    private static int smooth(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        int r = IntegerArgumentType.getInteger(c, "radius");
        long t0 = System.nanoTime();
        TerrainSmoother.Stats s = WorldOps.smooth(src.getLevel(), WorldOps.square(centre(src), r), false, null);
        say(src, String.format("Smoothed %d chunk(s): %d slabs on %d rises (%.0f ms)", (2 * r + 1) * (2 * r + 1), s.placed, s.rises, (System.nanoTime() - t0) / 1e6));
        return s.placed;
    }

    private static int purge(CommandContext<CommandSourceStack> c, String standIn) {
        CommandSourceStack src = c.getSource();
        int r = IntegerArgumentType.getInteger(c, "radius");
        Block stand = null;
        if (standIn != null) {
            Identifier id = Identifier.tryParse(standIn);
            stand = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (stand == null) {
                src.sendFailure(Component.literal("Unknown block " + standIn));
                return 0;
            }
        }
        int n = WorldOps.purge(src.getLevel(), WorldOps.square(centre(src), r), stand, null);
        say(src, "Purged " + n + " SlashSlabs block(s)" + (stand != null ? " (stand-in " + standIn + ")" : ""));
        return n;
    }

    private static int survey(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        int r = IntegerArgumentType.getInteger(c, "radius");
        ServerLevel level = src.getLevel();
        long t0 = System.nanoTime();
        TerrainSmoother.Stats s = WorldOps.smooth(level, WorldOps.square(centre(src), r), true, null);
        say(src, String.format("Survey of %d chunk(s): %d rise columns (%.0f ms)", (2 * r + 1) * (2 * r + 1), s.rises, (System.nanoTime() - t0) / 1e6));
        say(src, "  surface at rises: " + top(s.byGround, 8));
        say(src, "  would place: " + top(s.byResult, 8));
        say(src, "  skipped: structure " + s.skippedStructure + ", blocked " + s.skippedBlocked + ", no material " + s.skippedMaterial);

        JsonObject out = new JsonObject();
        out.addProperty("centre", centre(src).toString());
        out.addProperty("radius", r);
        out.addProperty("rises", s.rises);
        out.add("byGround", toJson(s.byGround));
        out.add("byResult", toJson(s.byResult));
        JsonObject fits = new JsonObject();
        for (int k = 1; k <= 3; k++) {
            PaletteFit.Fit f = PaletteFit.fit(s.grassColors, k);
            JsonArray pal = new JsonArray();
            StringBuilder sb = new StringBuilder();
            for (int p : f.palette()) {
                pal.add(ColorMath.hex(p));
                sb.append(' ').append(ColorMath.hex(p));
            }
            JsonObject fo = new JsonObject();
            fo.add("palette", pal);
            fo.addProperty("meanDeltaE", f.meanError());
            fo.addProperty("maxDeltaE", f.maxError());
            fits.add("k" + k, fo);
            say(src, String.format("  grass palette k=%d:%s  mean dE %.1f, max dE %.1f", k, sb, f.meanError(), f.maxError()));
        }
        out.add("paletteFits", fits);
        JsonArray colors = new JsonArray();
        for (int col : s.grassColors) colors.add(ColorMath.hex(col));
        out.add("grassColors", colors);
        writeReport(src, "survey", out);
        return s.rises;
    }

    private static int dump(CommandContext<CommandSourceStack> c, String name) {
        CommandSourceStack src = c.getSource();
        int r = IntegerArgumentType.getInteger(c, "radius");
        try {
            WorldOps.Dump d = WorldOps.dump(src.getLevel(), WorldOps.square(centre(src), r));
            say(src, "DUMP " + d.positions() + " " + d.sha256());
            if (name != null) {
                Path p = FabricLoader.getInstance().getGameDir().resolve("slashslabs").resolve("dump-" + name + ".txt");
                Files.createDirectories(p.getParent());
                Files.write(p, d.lines());
                say(src, "  written to " + p.getFileName());
            }
            return d.positions();
        } catch (Exception e) {
            src.sendFailure(Component.literal("dump failed: " + e));
            return 0;
        }
    }

    /** Diagnostic: per column "x z y ground above above2", for comparing two worlds' terrain. */
    private static int dumpCols(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        int r = IntegerArgumentType.getInteger(c, "radius");
        ServerLevel level = src.getLevel();
        List<String> lines = new java.util.ArrayList<>();
        for (ChunkPos cp : WorldOps.square(centre(src), r)) {
            var chunk = level.getChunk(cp.x(), cp.z());
            for (int dz = 0; dz < 16; dz++)
                for (int dx = 0; dx < 16; dx++) {
                    int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                    int y = TerrainSmoother.scanSurface(level, chunk, x, z);
                    if (y == com.slashslabs.worldgen.RiseDetector.NONE) {
                        lines.add(x + " " + z + " none");
                        continue;
                    }
                    lines.add(x + " " + z + " " + y + " " + id(level, x, y, z) + " " + id(level, x, y + 1, z) + " " + id(level, x, y + 2, z));
                }
        }
        try {
            Path p = FabricLoader.getInstance().getGameDir().resolve("slashslabs").resolve("cols-" + StringArgumentType.getString(c, "name") + ".txt");
            Files.createDirectories(p.getParent());
            Files.write(p, lines);
        } catch (Exception e) {
            src.sendFailure(Component.literal(e.toString()));
        }
        say(src, "COLS " + lines.size());
        return lines.size();
    }

    private static String id(ServerLevel level, int x, int y, int z) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock()).getPath();
    }

    /** Generates chunks in a chosen order, for the determinism and performance tests. */
    private static int gen(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        int r = IntegerArgumentType.getInteger(c, "radius");
        String order = StringArgumentType.getString(c, "order");
        List<ChunkPos> chunks = WorldOps.square(centre(src), r);
        switch (order) {
            case "reverse" -> java.util.Collections.reverse(chunks);
            case "shuffle" -> java.util.Collections.shuffle(chunks, new java.util.Random(7));
            case "columns" -> chunks.sort(java.util.Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
            default -> {}
        }
        long t0 = System.nanoTime();
        ServerLevel level = src.getLevel();
        for (ChunkPos cp : chunks) level.getChunk(cp.x(), cp.z());
        double ms = (System.nanoTime() - t0) / 1e6;
        say(src, String.format("GEN %d chunks %.0f ms (%.2f ms/chunk, order %s)", chunks.size(), ms, ms / chunks.size(), order));
        return chunks.size();
    }

    private static String top(Map<String, Integer> m, int n) {
        return m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n)
                .map(e -> e.getKey().replace("minecraft:", "") + "=" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("-");
    }

    private static JsonObject toJson(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    private static void writeReport(CommandSourceStack src, String kind, JsonObject json) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("slashslabs");
            Files.createDirectories(dir);
            Path p = dir.resolve(kind + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(json));
            say(src, "  report: slashslabs/" + p.getFileName());
        } catch (Exception e) {
            SlashSlabs.LOGGER.warn("Could not write {} report", kind, e);
        }
    }
}
