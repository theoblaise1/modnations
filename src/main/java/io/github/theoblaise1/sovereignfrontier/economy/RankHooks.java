package io.github.theoblaise1.sovereignfrontier.economy;

import net.minecraft.server.level.ServerPlayer;

/**
 * Crochets des grades (J8). Jusqu'au J8, aucun grade n'existe : tous les multiplicateurs valent 1. Le J8 lira ici
 * {@code terminalSouverainMultiplier} (1,25) pour les joueurs de grade Souverain.
 */
public final class RankHooks {
    private RankHooks() {
    }

    /** Multiplicateur du gain du Terminal bancaire pour ce joueur. */
    public static double terminalMultiplier(ServerPlayer player) {
        return 1.0D;
    }
}
