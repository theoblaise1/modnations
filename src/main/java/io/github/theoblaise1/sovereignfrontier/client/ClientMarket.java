// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.client.screen.TradeScreen;
import io.github.theoblaise1.sovereignfrontier.network.TradeView;
import io.github.theoblaise1.sovereignfrontier.network.TradeViewS2C;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/** Dernier étal reçu et ouverture (ou rafraîchissement) de l'écran de commerce. */
@OnlyIn(Dist.CLIENT)
public final class ClientMarket {
    @Nullable
    private static TradeView view;
    /** Instant de réception (ms), pour décompter le réassort sans nouveau paquet. */
    private static long receivedAt;

    private ClientMarket() {
    }

    @Nullable
    public static TradeView get() {
        return view;
    }

    /** Millisecondes écoulées depuis la réception de l'étal courant. */
    public static long ageMs() {
        return Math.max(0L, Util.getMillis() - receivedAt);
    }

    public static void apply(TradeViewS2C payload) {
        view = payload.view();
        receivedAt = Util.getMillis();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TradeScreen screen) {
            screen.refresh();
        } else if (payload.open() && mc.player != null && !(mc.screen instanceof AbstractContainerScreen<?>)) {
            // Jamais par-dessus un conteneur : remplacé sans closeContainer(), son menu resterait ouvert côté serveur.
            mc.setScreen(new TradeScreen());
        }
    }

    public static void clear() {
        view = null;
    }
}
