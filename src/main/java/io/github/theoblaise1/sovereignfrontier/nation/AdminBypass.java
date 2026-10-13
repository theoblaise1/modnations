package io.github.theoblaise1.sovereignfrontier.nation;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Opérateurs ayant activé {@code /sfadmin bypass} : ignorent la protection du territoire (non persistant). */
public final class AdminBypass {
    private static final Set<UUID> ACTIVE = new HashSet<>();

    private AdminBypass() {
    }

    public static boolean toggle(ServerPlayer player) {
        if (!ACTIVE.remove(player.getUUID())) {
            ACTIVE.add(player.getUUID());
            return true;
        }
        return false;
    }

    public static boolean isActive(ServerPlayer player) {
        return ACTIVE.contains(player.getUUID()) && player.hasPermissions(2);
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
