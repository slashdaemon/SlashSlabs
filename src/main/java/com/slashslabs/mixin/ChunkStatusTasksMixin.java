package com.slashslabs.mixin;

import com.slashslabs.worldgen.TerrainSmoother;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Default smoothing hook: the start of a chunk's LIGHT step. LIGHT requires every neighbour at
 * INITIALIZE_LIGHT, i.e. past FEATURES, so no neighbour can still write into this chunk and the
 * result does not depend on generation order. Same signature on 26.1.2, 26.2 and 26.3.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    @Inject(method = "light", at = @At("HEAD"))
    private static void slashslabs$smooth(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
                                          ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        TerrainSmoother.onLightStep(context, step, chunks, chunk);
    }
}
