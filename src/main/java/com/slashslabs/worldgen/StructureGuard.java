package com.slashslabs.worldgen;

import com.slashslabs.SlashSlabs;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Carries a chunk's structure-piece boxes from its FEATURES step (where structure starts up to 8
 * chunks away are readable) to its LIGHT step (where only direct neighbours are), as a
 * persistent chunk attachment so a save in between loses nothing. Removed once used.
 */
public final class StructureGuard {
    private StructureGuard() {}

    public static final AttachmentType<List<BoundingBox>> BOXES = AttachmentRegistry.create(SlashSlabs.id("structure_guard"),
            b -> b.persistent(BoundingBox.CODEC.listOf()));

    public static void init() {
        // class load registers the attachment type
    }

    /** Called at the end of FEATURES. */
    public static void record(ChunkAccess chunk, StructureManager structures) {
        chunk.setAttached(BOXES, List.copyOf(TerrainSmoother.structureBoxes(chunk, structures, chunk.getPos())));
    }

    /**
     * The boxes recorded for this chunk, removing them. A chunk decorated without SlashSlabs has
     * none: if it references any structure, the whole chunk is guarded (null result).
     */
    public static List<BoundingBox> take(ChunkAccess chunk) {
        List<BoundingBox> boxes = chunk.removeAttached(BOXES);
        if (boxes != null) return boxes;
        return chunk.getAllReferences().isEmpty() ? List.of() : null;
    }
}
