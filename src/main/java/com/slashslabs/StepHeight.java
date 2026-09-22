package com.slashslabs;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Optional companion module (PLAN §8): raises {@code minecraft:step_height} from 0.6 to 1.0 with a
 * transient modifier. Checked every tick, which also covers join, respawn and dimension changes
 * (each gives a player entity without the modifier) and the sneak toggle. Bedrock players via
 * Geyser are skipped: Geyser cannot translate the attribute and they would rubber-band.
 */
public final class StepHeight {
    private StepHeight() {}

    static final Identifier ID = SlashSlabs.id("step_height");
    private static final AttributeModifier MODIFIER = new AttributeModifier(ID, 0.4, AttributeModifier.Operation.ADD_VALUE);

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SlashSlabs.CONFIG.stepHeight) return;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) update(p);
        });
    }

    static void update(ServerPlayer p) {
        AttributeInstance inst = p.getAttribute(Attributes.STEP_HEIGHT);
        if (inst == null) return;
        boolean want = !(SlashSlabs.CONFIG.stepHeightOffWhileSneaking && p.isShiftKeyDown()) && !PolymerCommonUtils.isBedrockPlayer(p);
        boolean has = inst.hasModifier(ID);
        if (want && !has) inst.addOrUpdateTransientModifier(MODIFIER);
        else if (!want && has) inst.removeModifier(ID);
    }
}
