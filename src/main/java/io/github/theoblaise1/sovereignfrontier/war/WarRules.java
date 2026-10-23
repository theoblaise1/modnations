package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Règle du PvP entre joueurs (J7, docs/conception/J7.md §1.2), appelée par {@code TerritoryEvents.onIncomingDamage}.
 * <p>
 * Un joueur frappe un autre joueur seulement si les deux appartiennent à des nations <b>en guerre</b> ; deux membres
 * d'une même nation ne se frappent jamais (tir ami interdit) ; un joueur sans nation n'est jamais frappé ni ne frappe
 * hors de cette règle. Une frappe refusée envoie le motif à l'attaquant dans la barre d'action.
 */
public final class WarRules {
    private WarRules() {
    }

    /**
     * Vrai si {@code attacker} peut blesser {@code victim}. Lecture seule sur les nations et la guerre : aucun scan.
     */
    public static boolean mayHurt(MinecraftServer server, Player attacker, Player victim) {
        if (attacker.getUUID().equals(victim.getUUID())) {
            return true;
        }
        NationData nations = NationData.get(server);
        Nation attackerNation = nations.nationOf(attacker.getUUID());
        Nation victimNation = nations.nationOf(victim.getUUID());
        if (attackerNation != null && victimNation != null) {
            if (attackerNation.id().equals(victimNation.id())) {
                // Tir ami : interdit entre membres d'une même nation, sans message (règle existante).
                return false;
            }
            if (WarQueries.atWar(WarData.get(server), attackerNation.id(), victimNation.id())) {
                return true;
            }
            refuse(attacker, Msg.err("war.pvp.denied", victimNation.name()));
            return false;
        }
        if (victimNation == null) {
            refuse(attacker, Msg.err("war.pvp.unaffiliated", victim.getName()));
        } else {
            refuse(attacker, Msg.err("war.pvp.denied", victimNation.name()));
        }
        return false;
    }

    /** Motif de refus dans la barre d'action (remplacé à chaque tentative, sans spam dans le chat). */
    private static void refuse(Player attacker, Component message) {
        if (attacker instanceof ServerPlayer player) {
            player.displayClientMessage(message, true);
        }
    }
}
