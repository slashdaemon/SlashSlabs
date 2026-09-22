package com.slashslabs.command;

import com.slashslabs.SlashSlabs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Command permission checks. With a permissions API present (lucko's fabric-permissions-api,
 * which LuckPerms bundles) each command checks a node, {@code slashslabs.command.<name>}, falling
 * back to op level 2; without one it is op level 2. Looked up reflectively, so there is no
 * compile or runtime dependency.
 */
final class Perms {
    private Perms() {}

    private static final Method REQUIRE = find();

    private static Method find() {
        if (!FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0")) return null;
        try {
            return Class.forName("me.lucko.fabric.api.permissions.v0.Permissions").getMethod("require", String.class, int.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            SlashSlabs.LOGGER.warn("fabric-permissions-api present but unusable; using op levels", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Predicate<CommandSourceStack> node(String node) {
        if (REQUIRE != null) {
            try {
                return (Predicate<CommandSourceStack>) REQUIRE.invoke(null, "slashslabs.command." + node, 2);
            } catch (ReflectiveOperationException e) {
                SlashSlabs.LOGGER.warn("Permission node {} unavailable; using op level 2", node, e);
            }
        }
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }
}
