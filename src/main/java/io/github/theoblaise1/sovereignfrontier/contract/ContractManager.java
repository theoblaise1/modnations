// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.contract;

import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.server.level.ServerPlayer;

/**
 * Contrats nationaux : génération du tableau, acceptation, livraison, progression, réussite et échéances.
 * Ébauche du socle : aucun contrat, toute action est refusée.
 */
public final class ContractManager {
    private ContractManager() {
    }

    /**
     * Action reçue de l'écran ({@code ModPayloads}, thread principal) : {@code action} est l'une des constantes
     * de {@code ContractActionC2S} (déjà validée) ; la réponse est envoyée par l'appelant.
     */
    public static ActionResult handleAction(ServerPlayer player, byte action, int id) {
        return ActionResult.fail(Msg.err("gui.economy.unavailable"));
    }

    /** Partie « contrats » de la vue Économie de ce joueur (membre de {@code nation}). */
    public static EconomyView.ContractsView view(ServerPlayer player, Nation nation) {
        return EconomyView.ContractsView.EMPTY;
    }
}
