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
    /** Instant de réception (horloge client), pour décompter la recherche en cours. */
    private static long receivedAtMs;

    private ClientNationState() {
    }

    @Nullable
    public static NationSnapshot get() {
        return snapshot;
    }

    public static void set(@Nullable NationSnapshot value) {
        snapshot = value;
        receivedAtMs = System.currentTimeMillis();
    }

    /** Temps restant estimé de la recherche active, en tenant compte du temps écoulé depuis la réception. */
    public static long researchRemainingMs() {
        if (snapshot == null || snapshot.research().active().isEmpty()) {
            return 0L;
        }
        return Math.max(0L, snapshot.research().activeRemainingMs() - (System.currentTimeMillis() - receivedAtMs));
    }

    /** À la déconnexion : on oublie l'état du serveur précédent. */
    public static void clear() {
        snapshot = null;
    }
}
