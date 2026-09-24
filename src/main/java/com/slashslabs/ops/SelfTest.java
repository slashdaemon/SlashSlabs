package com.slashslabs.ops;

import com.slashslabs.SlashSlabs;
import com.slashslabs.block.GrassSlabBlock;
import com.slashslabs.block.ModBlocks;
import com.slashslabs.block.TerrainSlabBlock;
import com.slashslabs.worldgen.MaterialMap;
import com.slashslabs.worldgen.TerrainSmoother;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Server-side self-test (/slashslabs selftest): builds the test field in the sky above the world
 * origin, smooths it, and checks placement, materials, water, Polymer backing and collision,
 * placement rules, drops, recipes, tags, grass spread/decay and purge. Prints one line per check
 * and a final "SELFTEST PASS n/n" or "SELFTEST FAIL k/n" line (scripts grep for it).
 */
public final class SelfTest {
    private SelfTest() {}

    private final List<String> failures = new ArrayList<>();
    private int checks;
    private Consumer<String> out;

    public static boolean run(ServerLevel level, Consumer<String> out) {
        SelfTest t = new SelfTest();
        t.out = out;
        try {
            t.runAll(level);
        } catch (Throwable e) {
            SlashSlabs.LOGGER.error("Self-test crashed", e);
            t.fail("crash: " + e);
        }
        String summary = t.failures.isEmpty()
                ? "SELFTEST PASS " + t.checks + "/" + t.checks
                : "SELFTEST FAIL " + t.failures.size() + "/" + t.checks;
        out.accept(summary);
        SlashSlabs.LOGGER.info(summary);
        return t.failures.isEmpty();
    }

    private void check(boolean ok, String what) {
        checks++;
        if (!ok) fail(what);
    }

    private void fail(String what) {
        failures.add(what);
        out.accept("FAIL " + what);
        SlashSlabs.LOGGER.warn("Self-test FAIL {}", what);
    }

    private void runAll(ServerLevel level) {
        int y = Math.min(level.getMaxY() - TestField.CLEAR - 8, 250);
        BlockPos origin = new BlockPos(1000, y, 1000);
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
                String.format("fillbiome %d %d %d %d %d %d minecraft:plains", origin.getX() - 8, y - 8, origin.getZ() - 8,
                        origin.getX() + 40, y + 24, origin.getZ() + 48));
        TestField.Field f = TestField.build(level, origin);
        TerrainSmoother.Stats stats = WorldOps.smooth(level, f.chunks(), false, f.box());
        out.accept("smoothed test field: " + stats.placed + " placed of " + stats.rises + " rises");

        checkField(level, f);
        checkIdempotent(level, f);
        checkBacking(level, f.origin());
        checkPlacement(level, f.origin().offset(0, 8, -6));
        checkDrops(level, f.origin());
        checkDataDriven(level);
        checkSpread(level, f.origin().offset(24, 0, 0));
        checkPurge(level, f);
    }

    // ---- smoothing output on the fixture

    private void checkField(ServerLevel level, TestField.Field f) {
        int[] slabX = {3, 7, 15};
        for (int s = 0; s < TestField.STRIPS; s++) {
            for (int z = 0; z < TestField.STRIP; z++) {
                for (int x : slabX) {
                    BlockPos g = f.ground(s, x, z);
                    BlockState at = level.getBlockState(g.above());
                    String where = "strip " + s + " x=" + x + " z=" + z;
                    switch (s) {
                        case 0 -> {
                            check(at.is(ModBlocks.GRASS_SLAB) && at.getValue(SlabBlock.TYPE) == SlabType.BOTTOM && !at.getValue(SlabBlock.WATERLOGGED),
                                    where + ": grass slab expected, got " + at);
                            check(level.getBlockState(g).is(Blocks.DIRT), where + ": grass under the slab becomes dirt");
                        }
                        case 1 -> check(at.is(ModBlocks.DIRT_SLAB), where + ": dirt slab expected, got " + at);
                        case 2 -> check(at.is(ModBlocks.sandTextured ? ModBlocks.SAND_SLAB : Blocks.SMOOTH_SANDSTONE_SLAB), where + ": sand slab expected, got " + at);
                        case 3 -> check(at.is(Blocks.STONE_SLAB) && at.getValue(SlabBlock.TYPE) == SlabType.BOTTOM, where + ": stone slab expected, got " + at);
                        case 4 -> check(at.is(Blocks.SNOW) && at.getValue(SnowLayerBlock.LAYERS) == SlashSlabs.CONFIG.snowLayers, where + ": snow layers expected, got " + at);
                        case 5 -> {
                            boolean wet = x == 3;
                            check(at.is(ModBlocks.GRASS_SLAB) && at.getValue(SlabBlock.WATERLOGGED) == wet,
                                    where + ": " + (wet ? "waterlogged " : "") + "grass slab expected, got " + at);
                        }
                        default -> {}
                    }
                }
                // Cliff (2-block rise at x=12) and flat columns stay untouched.
                for (int x : new int[]{0, 1, 5, 11, 12, 19}) {
                    BlockState at = level.getBlockState(f.ground(s, x, z).above());
                    boolean ok = at.isAir() || (s == 5 && x < 4 && at.is(Blocks.WATER));
                    check(ok, "strip " + s + " x=" + x + " z=" + z + ": nothing expected, got " + at);
                }
            }
        }
        BlockState g = level.getBlockState(f.ground(0, 3, 0).above());
        if (g.is(ModBlocks.GRASS_SLAB)) {
            int t = g.getValue(GrassSlabBlock.TINT);
            check(t >= 0 && t < SlashSlabs.PALETTE.length, "grass slab tint " + t + " within palette");
        }
    }

    private void checkIdempotent(ServerLevel level, TestField.Field f) {
        TerrainSmoother.Stats again = WorldOps.smooth(level, f.chunks(), false, f.box());
        check(again.placed == 0, "second smooth pass places nothing (placed " + again.placed + ")");
    }

    // ---- Polymer backing: every state maps to a client state of exactly the same shape
    //      (a copper slab for tops, an inactive sculk sensor for bottoms)

    private void checkBacking(ServerLevel level, BlockPos pos) {
        for (Block b : new Block[]{ModBlocks.GRASS_SLAB, ModBlocks.DIRT_SLAB, ModBlocks.SAND_SLAB}) {
            TerrainSlabBlock slab = (TerrainSlabBlock) b;
            for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                BlockState client = slab.getPolymerBlockState(s, null);
                String id = s.toString();
                check(client != null, id + ": backing state exists");
                if (client == null) continue;
                if (s.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                    check(!(client.getBlock() instanceof SlabBlock), id + ": double maps to a full block");
                    continue;
                }
                boolean wl = s.getValue(SlabBlock.WATERLOGGED);
                boolean kind = client.getBlock() instanceof SlabBlock
                        ? client.getValue(SlabBlock.TYPE) == s.getValue(SlabBlock.TYPE)
                        : client.getBlock() instanceof SculkSensorBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM
                                && client.getValue(SculkSensorBlock.PHASE) != SculkSensorPhase.ACTIVE;
                check(kind && client.getValue(BlockStateProperties.WATERLOGGED) == wl,
                        id + ": backing " + client + " is a slab of the same type (or an inactive sculk sensor for a bottom) with the same waterlogging");
                check(client.getCollisionShape(level, pos, CollisionContext.empty()).toAabbs()
                                .equals(s.getCollisionShape(level, pos, CollisionContext.empty()).toAabbs()),
                        id + ": client collision equals server collision");
                check(client.getShape(level, pos, CollisionContext.empty()).toAabbs()
                                .equals(s.getShape(level, pos, CollisionContext.empty()).toAabbs()),
                        id + ": client outline equals server outline");
            }
        }
        if (ModBlocks.dirtTextured) {
            BlockState top = ModBlocks.DIRT_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
            check(ModBlocks.DIRT_SLAB.getPolymerBlockState(top, null).getBlock().builtInRegistryHolder()
                    .key().identifier().getPath().contains("copper"), "dirt top slab backed by a waxed copper slab state");
            check(ModBlocks.DIRT_SLAB.getPolymerBlockState(ModBlocks.DIRT_SLAB.defaultBlockState(), null).getBlock() instanceof SculkSensorBlock,
                    "dirt bottom slab backed by a sculk sensor");
        }
        check(ModBlocks.GRASS_SLAB.getPolymerBreakEventBlockState(ModBlocks.GRASS_SLAB.defaultBlockState(), null).is(Blocks.GRASS_BLOCK),
                "grass slab break particles/sound come from grass_block");
        check(ModBlocks.GRASS_SLAB.defaultDestroyTime() == Blocks.GRASS_BLOCK.defaultDestroyTime()
                        && ModBlocks.DIRT_SLAB.defaultDestroyTime() == Blocks.DIRT.defaultDestroyTime(),
                "mining time matches the vanilla source blocks");
    }

    // ---- placement rules: bottom/top by click, second slab of the same kind -> vanilla full block

    private void checkPlacement(ServerLevel level, BlockPos base) {
        level.setBlock(base, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos at = base.above();
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

        place(level, ModBlocks.GRASS_SLAB_ITEM.getDefaultInstance(), base, Direction.UP, new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5));
        BlockState s = level.getBlockState(at);
        check(s.is(ModBlocks.GRASS_SLAB) && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM, "placing on a top face gives a bottom grass slab, got " + s);
        place(level, ModBlocks.GRASS_SLAB_ITEM.getDefaultInstance(), at, Direction.UP, new Vec3(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5));
        s = level.getBlockState(at);
        check(s.is(Blocks.GRASS_BLOCK), "second grass slab completes into grass_block, got " + s);

        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        place(level, ModBlocks.DIRT_SLAB_ITEM.getDefaultInstance(), base, Direction.UP, new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5));
        place(level, ModBlocks.DIRT_SLAB_ITEM.getDefaultInstance(), at, Direction.UP, new Vec3(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5));
        check(level.getBlockState(at).is(Blocks.DIRT), "second dirt slab completes into dirt, got " + level.getBlockState(at));

        // Top slab: click the lower half... of a side face above the middle.
        BlockPos side = at.above(2);
        level.setBlock(side, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos east = side.east();
        level.setBlock(east, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        place(level, ModBlocks.DIRT_SLAB_ITEM.getDefaultInstance(), side, Direction.EAST, new Vec3(side.getX() + 1.0, side.getY() + 0.75, side.getZ() + 0.5));
        s = level.getBlockState(east);
        check(s.is(ModBlocks.DIRT_SLAB) && s.getValue(SlabBlock.TYPE) == SlabType.TOP, "clicking the upper half of a side gives a top slab, got " + s);
        for (BlockPos p : new BlockPos[]{base, at, side, east}) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void place(ServerLevel level, ItemStack stack, BlockPos clicked, Direction face, Vec3 hit) {
        BlockHitResult r = new BlockHitResult(hit, face, clicked, false);
        BlockPlaceContext ctx = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, r) {};
        ((BlockItem) stack.getItem()).place(ctx);
    }

    // ---- drops (loot tables)

    private void checkDrops(ServerLevel level, BlockPos pos) {
        ItemStack shovel = new ItemStack(Items.DIAMOND_SHOVEL);
        ItemStack silk = new ItemStack(Items.DIAMOND_SHOVEL);
        Holder<Enchantment> st = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        silk.enchant(st, 1);
        BlockState grass = ModBlocks.GRASS_SLAB.defaultBlockState();
        check(dropsOnly(Block.getDrops(grass, level, pos, null, null, shovel), ModBlocks.DIRT_SLAB_ITEM), "grass slab drops a dirt slab without Silk Touch");
        check(dropsOnly(Block.getDrops(grass, level, pos, null, null, silk), ModBlocks.GRASS_SLAB_ITEM), "grass slab drops itself with Silk Touch");
        check(dropsOnly(Block.getDrops(ModBlocks.DIRT_SLAB.defaultBlockState(), level, pos, null, null, shovel), ModBlocks.DIRT_SLAB_ITEM), "dirt slab drops itself");
        check(dropsOnly(Block.getDrops(ModBlocks.SAND_SLAB.defaultBlockState(), level, pos, null, null, ItemStack.EMPTY), ModBlocks.SAND_SLAB_ITEM), "sand slab drops itself by hand");
    }

    private static boolean dropsOnly(List<ItemStack> drops, net.minecraft.world.item.Item item) {
        return drops.size() == 1 && drops.get(0).is(item) && drops.get(0).getCount() == 1;
    }

    // ---- recipes and tags

    private void checkDataDriven(ServerLevel level) {
        var rm = level.getServer().getRecipeManager();
        ItemStack d = new ItemStack(Items.DIRT);
        var r = rm.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 1, List.of(d, d, d)), level);
        check(r.isPresent() && r.get().value().assemble(CraftingInput.of(3, 1, List.of(d, d, d))).is(ModBlocks.DIRT_SLAB_ITEM)
                && r.get().value().assemble(CraftingInput.of(3, 1, List.of(d, d, d))).getCount() == 6, "3 dirt craft into 6 dirt slabs");
        ItemStack ds = new ItemStack(ModBlocks.DIRT_SLAB_ITEM);
        var back = rm.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(1, 2, List.of(ds, ds)), level);
        check(back.isPresent() && back.get().value().assemble(CraftingInput.of(1, 2, List.of(ds, ds))).is(Items.DIRT), "2 dirt slabs craft back into dirt");

        for (Block b : new Block[]{ModBlocks.GRASS_SLAB, ModBlocks.DIRT_SLAB, ModBlocks.SAND_SLAB}) {
            BlockState s = b.defaultBlockState();
            String id = BuiltInRegistries.BLOCK.getKey(b).toString();
            check(s.is(BlockTags.MINEABLE_WITH_SHOVEL), id + " in #mineable/shovel");
            check(s.is(BlockTags.SLABS), id + " in #slabs");
            check(!s.is(BlockTags.DIRT) && !s.is(BlockTags.SUPPORTS_VEGETATION), id + " stays out of #dirt / #supports_vegetation");
        }
        check(MaterialMap.choiceFor(Blocks.GRASS_BLOCK).kind() == MaterialMap.Kind.GRASS, "material map: grass");
        check(ModBlocks.GRASS_SLAB.defaultBlockState().is(BlockTags.ANIMALS_SPAWNABLE_ON) == SlashSlabs.CONFIG.animalSpawnsOnGrassSlabs,
                "grass slab in #animals_spawnable_on only when animalSpawnsOnGrassSlabs is on");
    }

    // ---- grass spread and decay

    private void checkSpread(ServerLevel level, BlockPos p) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++)
                for (int y = -1; y <= 4; y++) {
                    m.set(p.getX() + x, p.getY() + y, p.getZ() + z);
                    level.setBlock(m, (y == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_CLIENTS);
                }
        level.setBlock(p, ModBlocks.DIRT_SLAB.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p.east(), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        RandomSource rnd = RandomSource.create(42);
        boolean pulled = false;
        for (int i = 0; i < 400 && !pulled; i++) {
            level.getBlockState(p).randomTick(level, p, rnd);
            pulled = level.getBlockState(p).is(ModBlocks.GRASS_SLAB);
        }
        check(pulled, "dirt slab next to a grass block turns into a grass slab (light " + level.getMaxLocalRawBrightness(p.above()) + ")");

        BlockPos d = p.south(2);
        level.setBlock(d, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(d.north(), ModBlocks.GRASS_SLAB.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(p.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        boolean pushed = false;
        for (int i = 0; i < 400 && !pushed; i++) {
            level.getBlockState(d.north()).randomTick(level, d.north(), rnd);
            pushed = level.getBlockState(d).is(Blocks.GRASS_BLOCK);
        }
        check(pushed, "grass slab spreads onto a neighbouring dirt block");

        BlockPos c = d.north();
        level.setBlock(c.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.getBlockState(c).randomTick(level, c, rnd);
        check(level.getBlockState(c).is(ModBlocks.DIRT_SLAB), "grass slab under an opaque block decays to a dirt slab, got " + level.getBlockState(c));

        BlockState wl = ModBlocks.GRASS_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true);
        level.setBlock(c.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(c, wl, Block.UPDATE_ALL);
        level.getBlockState(c).randomTick(level, c, rnd);
        BlockState after = level.getBlockState(c);
        check(after.is(ModBlocks.DIRT_SLAB) && after.getValue(SlabBlock.WATERLOGGED), "waterlogged grass slab decays to a waterlogged dirt slab, got " + after);
    }

    // ---- purge

    private void checkPurge(ServerLevel level, TestField.Field f) {
        BlockPos g = f.ground(0, 3, 0);
        int n = WorldOps.purge(level, f.chunks(), null, f.box());
        check(n > 0, "purge removed " + n + " blocks");
        List<BlockPos> left = new ArrayList<>();
        for (var cp : f.chunks()) WorldOps.forEachOurs(level, level.getChunk(cp.x(), cp.z()), p -> {
            if (f.box().isInside(p)) left.add(p);
        });
        check(left.isEmpty(), "no SlashSlabs blocks remain after purge (" + left.size() + " left)");
        check(level.getBlockState(g.above()).isAir() && level.getBlockState(g).is(Blocks.GRASS_BLOCK), "purge restores grass under a removed grass slab");
        check(level.getBlockState(f.ground(5, 3, 0).above()).is(Blocks.WATER), "purge leaves water where a waterlogged slab was");
    }
}
