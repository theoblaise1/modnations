// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.enterprise;

import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Entreprises nationales : affectation des habitants et production à chaque cycle de revenu.
 * Ébauche du socle : aucune production, toute affectation est refusée.
 */
public final class EnterpriseManager {
    private EnterpriseManager() {
    }

    /** Affectation absolue de {@code workers} habitants à {@code sector} (permission {@code economie}). */
    public static ActionResult assign(ServerPlayer player, Sector sector, int workers) {
        return ActionResult.fail(Msg.err("gui.economy.unavailable"));
    }

    /** Partie « entreprises » de la vue Économie de ce joueur (membre de {@code nation}). */
    public static EconomyView.EnterprisesView view(ServerPlayer player, Nation nation) {
        return EconomyView.EnterprisesView.EMPTY;
    }

    /**
     * Production d'un cycle, appelée par {@code NationManager.tickIncome} pour chaque nation, juste AVANT le calcul
     * du revenu passif (habitants libres) : revenu et production sont évalués au même instant.
     */
    public static void produceCycle(MinecraftServer server, Nation nation) {
    }
}
