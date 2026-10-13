package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/** Dernier état national reçu du serveur (lu par l'écran national). */
@OnlyIn(Dist.CLIENT)
public final class ClientNationState {
    @Nullable
    private static NationSnapshot snapshot;

    private ClientNationState() {
    }

    @Nullable
    public static NationSnapshot get() {
        return snapshot;
    }

    public static void set(@Nullable NationSnapshot value) {
        snapshot = value;
    }

    /** À la déconnexion : on oublie l'état du serveur précédent. */
    public static void clear() {
        snapshot = null;
    }
}
