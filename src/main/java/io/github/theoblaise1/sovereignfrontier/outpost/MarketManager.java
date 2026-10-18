// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.outpost;

import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.network.TradeActionC2S;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.server.level.ServerPlayer;

/**
 * Achats et ventes auprès des marchands (revalidation complète de chaque paquet : distance, rotation,
 * stock, prix attendu, fonds, place). Ébauche du socle : tout échange est refusé.
 */
public final class MarketManager {
    private MarketManager() {
    }

    /** Appelé par {@code ModPayloads} (thread principal, {@code lots} déjà borné à 1..64) ; la réponse est envoyée par l'appelant. */
    public static ActionResult handleTrade(ServerPlayer player, TradeActionC2S payload) {
        return ActionResult.fail(Msg.err("gui.economy.unavailable"));
    }
}
