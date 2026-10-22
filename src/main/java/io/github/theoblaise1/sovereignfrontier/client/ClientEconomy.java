package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.client.screen.NationScreen;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/** Dernière vue « Économie » reçue du serveur (contrats et entreprises), lue par l'onglet Économie. */
@OnlyIn(Dist.CLIENT)
public final class ClientEconomy {
    @Nullable
    private static EconomyView view;
    /** Instant de réception (horloge client), pour décompter les échéances et le prochain cycle. */
    private static long receivedAtMs;

    private ClientEconomy() {
    }

    @Nullable
    public static EconomyView get() {
        return view;
    }

    /** Millisecondes écoulées depuis la réception de la vue courante. */
    public static long ageMs() {
        return Math.max(0L, System.currentTimeMillis() - receivedAtMs);
    }

    /** Nouvelle vue : l'écran national ouvert sur l'onglet Économie se reconstruit. */
    public static void apply(EconomyView value) {
        view = value;
        receivedAtMs = System.currentTimeMillis();
        if (Minecraft.getInstance().screen instanceof NationScreen screen) {
            screen.onEconomyUpdated();
        }
    }

    /** À la déconnexion : on oublie l'état du serveur précédent. */
    public static void clear() {
        view = null;
    }
}
