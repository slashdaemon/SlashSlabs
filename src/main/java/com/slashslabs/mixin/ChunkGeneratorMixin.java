package com.slashslabs.mixin;

import com.slashslabs.worldgen.TerrainSmoother;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The tail of feature decoration (RESEARCH C10), after every structure step, freeze_top_layer and
 * all modded features. By default it only records the structure guard for the chunk, which the
 * LIGHT-step hook ({@link ChunkStatusTasksMixin}) smooths later. With hookStage "features" it
 * smooths here instead; neighbours decorated afterwards can then still write into the chunk, so
 * a few percent of the output depends on generation order.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
    private void slashslabs$smooth(WorldGenLevel level, ChunkAccess chunk, StructureManager structures, CallbackInfo ci) {
        if (level instanceof WorldGenRegion region) TerrainSmoother.onFeaturesDone(region, chunk, structures);
    }
}
