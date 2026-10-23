package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.server.MinecraftServer;

import java.util.Map;

/**
 * Boucle de guerre (J7, docs/conception/J7.md §4) : fait avancer préparation → guerre, guerre échue → délai, trêve
 * échue → paix. Une passe par {@value #INTERVAL_TICKS} ticks (CLAUDE.md : ≤ 1 passage / 20 ticks), sur les relations
 * seules : aucun parcours des nations ni des chunks.
 */
public final class WarTicker {
    /** Intervalle entre deux passes (ticks). */
    public static final int INTERVAL_TICKS = 20;

    private WarTicker() {
    }

    /** Appelée à chaque tick serveur ; ne fait une passe que tous les {@link #INTERVAL_TICKS} ticks. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % INTERVAL_TICKS != 0) {
            return;
        }
        pass(server);
    }

    /** Une passe sur les relations, sans cadence (tests et {@link #tick}). */
    public static void pass(MinecraftServer server) {
        WarData data = WarData.get(server);
        NationData nations = NationData.get(server);
        long now = TimeUtil.now();
        // Copie : les transitions modifient la relation parcourue.
        for (Map.Entry<WarData.RelationKey, WarData.Relation> entry : Map.copyOf(data.relations()).entrySet()) {
            WarData.RelationKey key = entry.getKey();
            WarData.Relation relation = entry.getValue();
            if (nations.byId(key.low()) == null || nations.byId(key.high()) == null) {
                // Une nation dissoute ne peut plus être en guerre : la relation disparaît.
                data.remove(key.low(), key.high());
                continue;
            }
            if (now < relation.untilEpochMs()) {
                continue;
            }
            switch (relation.state()) {
                case PREPARATION -> WarTransitions.startWar(server, key);
                case GUERRE -> WarVictory.onTimeout(server, key);
                case TREVE -> data.remove(key.low(), key.high());
                case PAIX -> {
                }
            }
        }
    }
}
