// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.outpost;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Règles spatiales des avant-postes, en O(1) (index par chunk, jamais de parcours ni de chargement de chunk).
 * Ébauche du socle : aucun avant-poste n'existe encore.
 */
public final class OutpostRules {
    private OutpostRules() {
    }

    /**
     * Motif du refus de revendiquer ce chunk (3×3 chunks autour d'un comptoir, ou chunk chargé portant une
     * référence de structure d'avant-poste), ou {@code null} si rien ne s'y oppose. Appelé par
     * {@code NationManager.create} (chunk capitale) et {@code NationManager.claim}.
     */
    @Nullable
    public static Component claimBlockReason(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        return null;
    }

    /**
     * Identifiant de l'avant-poste dont l'ancre est à {@code radius} blocs au plus de {@code pos}, ou {@code null}.
     * Utilisé par les contrats d'exploration (WP3).
     */
    @Nullable
    public static UUID outpostAt(ServerLevel level, BlockPos pos, int radius) {
        return null;
    }
}
