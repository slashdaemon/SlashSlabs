package com.slashslabs;

import com.slashslabs.block.ModBlocks;
import com.slashslabs.color.GrassColors;
import com.slashslabs.command.SlabsCommand;
import com.slashslabs.pack.PackGenerator;
import com.slashslabs.worldgen.MaterialMap;
import com.slashslabs.worldgen.StructureGuard;
import com.slashslabs.worldgen.TerrainSmoother;
import eu.pb4.polymer.soundpatcher.api.SoundPatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.SoundType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SlashSlabs implements ModInitializer {
    public static final String MOD_ID = "slashslabs";
    public static final Logger LOGGER = LoggerFactory.getLogger("SlashSlabs");

    public static SlabsConfig CONFIG = new SlabsConfig();
    public static int[] PALETTE = new int[]{0x91BD59};

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        CONFIG = SlabsConfig.load();
        PALETTE = CONFIG.paletteRgb();

        ModBlocks.register();
        MaterialMap.build(CONFIG);
        StructureGuard.init();
        TerrainSmoother.configure(CONFIG);
        PackGenerator.register();

        // Footsteps, falls and mining hits come from the client's backing state (sculk sensor for
        // bottoms, copper for tops); the sound patcher blanks those sounds in the pack and replays
        // each block's real SoundType from the server (RESEARCH C5, §2.6). Real copper blocks and
        // sculk sensors become server-played too.
        SoundPatcher.convertIntoServerSound(SoundType.COPPER);
        SoundPatcher.convertIntoServerSound(SoundType.SCULK_SENSOR);

        ServerLifecycleEvents.SERVER_STARTING.register(server -> GrassColors.ensureLoaded());
        CommandRegistrationCallback.EVENT.register(SlabsCommand::register);
        StepHeight.register();

        // D6: animals may spawn on grass slabs only when enabled. The tag entry that
        // Animal.checkAnimalSpawnRules needs ships as a built-in datapack, registered only then.
        if (CONFIG.animalSpawnsOnGrassSlabs) {
            FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(mod ->
                    ResourceLoader.registerBuiltinPack(id("animal_spawns"), mod, PackActivationType.ALWAYS_ENABLED));
        }
    }
}
