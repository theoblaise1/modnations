// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKeys;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Récompense des victoires de guerre (J7, docs/conception/J7.md §1.3, D4) : la nation gagnante reçoit une clé militaire
 * ({@link CrateKeys#giveToNation}), et le délai de guerre est tranché au plus grand score. Le journal et les messages de
 * fin sont écrits par {@link WarTransitions#endWar} (WP1), qui clôt la guerre dans les deux cas : ce fichier ne les
 * duplique pas.
 */
public final class WarVictory {
    private WarVictory() {
    }

    /**
     * Fin du délai de guerre (appelée par la boucle de guerre). Le plus grand score gagne, avec une clé militaire ;
     * égalité : nul, sans clé. La guerre se termine dans les deux cas par {@link WarTransitions#endWar}. Hors guerre :
     * aucun effet.
     */
    public static void onTimeout(MinecraftServer server, WarData.RelationKey key) {
        WarData data = WarData.get(server);
        WarData.Relation relation = data.relation(key.low(), key.high());
        if (relation == null || relation.state() != WarState.GUERRE) {
            return;
        }
        int lowScore = relation.scoreOf(key, key.low());
        int highScore = relation.scoreOf(key, key.high());
        UUID winner = null;
        if (lowScore > highScore) {
            winner = key.low();
        } else if (highScore > lowScore) {
            winner = key.high();
        }
        if (winner != null) {
            award(server, winner);
        }
        WarTransitions.endWar(server, key, winner, WarTransitions.WarEnd.TIMEOUT);
    }

    /**
     * Remet une clé militaire à la nation {@code nation} dans son coffre national. Sans effet si la nation n'existe plus.
     * Si le coffre est plein, la clé est perdue et journalisée par {@link CrateKeys#giveToNation} (aucun joueur de repli) :
     * cas non traité au J7.
     */
    public static void award(MinecraftServer server, UUID nation) {
        Nation winner = NationData.get(server).byId(nation);
        if (winner != null) {
            CrateKeys.giveToNation(server, winner, CrateKind.MILITAIRE, 1, null);
        }
    }
}
