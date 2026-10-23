// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Score de guerre (J7, docs/conception/J7.md §1.3, D2) : un point par joueur d'une nation ennemie tué pendant une guerre
 * ouverte. Atteindre {@code killsToWin} points donne la victoire immédiate. Hors guerre, rien ne change.
 */
public final class WarScore {
    private WarScore() {
    }

    /**
     * Enregistre un meurtre de joueur de {@code victimNation} par {@code killerNation}. Sans effet hors {@code GUERRE}.
     * Le point est persisté par {@link WarData#put} (qui marque les données sauvegardées) ; à {@code killsToWin} points,
     * la nation tueuse reçoit la victoire (clé militaire), puis la guerre se termine.
     */
    public static void recordKill(MinecraftServer server, UUID killerNation, UUID victimNation) {
        WarData data = WarData.get(server);
        WarData.Relation relation = data.relation(killerNation, victimNation);
        if (relation == null || !WarQueries.atWar(data, killerNation, victimNation)) {
            return;
        }
        WarData.RelationKey key = WarData.RelationKey.of(killerNation, victimNation);
        int score = relation.scoreOf(key, killerNation) + 1;
        WarData.Relation updated = relation.withScoreOf(key, killerNation, score);
        data.put(killerNation, victimNation, updated);
        int victimScore = updated.scoreOf(key, victimNation);

        NationData nations = NationData.get(server);
        Nation killer = nations.byId(killerNation);
        Nation victim = nations.byId(victimNation);
        if (killer != null && victim != null) {
            Component text = Msg.info("war.point", killer.name(), victim.name(), score, victimScore);
            NationManager.broadcast(server, killer, text);
            NationManager.broadcast(server, victim, text);
        }
        if (score >= SFConfig.KILLS_TO_WIN.get()) {
            // La clé avant la fin : endWar écrit le journal war_won et les messages de victoire (WP1).
            WarVictory.award(server, killerNation);
            WarTransitions.endWar(server, key, killerNation, WarTransitions.WarEnd.VICTORY);
        }
    }
}
