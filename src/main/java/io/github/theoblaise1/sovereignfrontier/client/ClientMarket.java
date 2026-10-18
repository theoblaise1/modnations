// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.network.TradeView;
import io.github.theoblaise1.sovereignfrontier.network.TradeViewS2C;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/** Dernier étal reçu et ouverture de l'écran de commerce. Ébauche du socle : la vue est seulement mémorisée. */
@OnlyIn(Dist.CLIENT)
public final class ClientMarket {
    @Nullable
    private static TradeView view;

    private ClientMarket() {
    }

    @Nullable
    public static TradeView get() {
        return view;
    }

    public static void apply(TradeViewS2C payload) {
        view = payload.view();
    }

    public static void clear() {
        view = null;
    }
}
