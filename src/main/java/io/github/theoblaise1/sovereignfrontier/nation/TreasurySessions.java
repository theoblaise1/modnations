package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mémorise quel bloc Trésor national un joueur a ouvert, pour n'autoriser dépôt et retrait
 * qu'à proximité d'un trésor situé sur le territoire de sa propre nation.
 */
public final class TreasurySessions {
    private static final Map<UUID, GlobalPos> SESSIONS = new HashMap<>();

    private TreasurySessions() {
    }

    public static void open(ServerPlayer player, GlobalPos pos) {
        SESSIONS.put(player.getUUID(), pos);
    }

    public static void close(UUID player) {
        SESSIONS.remove(player);
    }

    public static void clear() {
        SESSIONS.clear();
    }

    public static boolean hasAccess(ServerPlayer player) {
        GlobalPos pos = SESSIONS.get(player.getUUID());
        if (pos == null || !player.level().dimension().equals(pos.dimension())) {
            return false;
        }
        int reach = SFConfig.TREASURY_REACH.get();
        if (player.distanceToSqr(pos.pos().getCenter()) > (double) reach * reach) {
            return false;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!level.isLoaded(pos.pos()) || !level.getBlockState(pos.pos()).is(ModBlocks.TREASURY.get())) {
            return false;
        }
        NationData data = NationData.get(player.server);
        Nation owner = data.ownerAt(pos.dimension(), pos.pos());
        return owner != null && owner == data.nationOf(player.getUUID());
    }
}
