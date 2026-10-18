// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.outpost;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Règles spatiales des avant-postes, en O(1) (index par chunk, jamais de parcours ni de chargement de chunk).
 */
public final class OutpostRules {
    /** Structures d'avant-poste ({@code /locate structure #sovereignfrontier:avant_postes}, garde de revendication, J7). */
    public static final TagKey<Structure> OUTPOST_STRUCTURES = TagKey.create(Registries.STRUCTURE,
            ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "avant_postes"));

    private OutpostRules() {
    }

    /**
     * Motif du refus de revendiquer ce chunk, ou {@code null} si rien ne s'y oppose. Appelé par
     * {@code NationManager.create} (chunk capitale) et {@code NationManager.claim} :
     * <ol>
     *   <li>l'index des avant-postes enregistrés (3 × 3 chunks autour de chaque comptoir) ;</li>
     *   <li>sinon, si le chunk est déjà chargé ({@code getChunkNow}, qui ne charge ni ne génère rien) : une référence
     *       de structure du tag {@link #OUTPOST_STRUCTURES} (avant-poste généré mais pas encore lié).</li>
     * </ol>
     */
    @Nullable
    public static Component claimBlockReason(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        if (OutpostData.get(server).claimBlocked(dimension, chunk.toLong())) {
            return Msg.err("outpost.claim.refused");
        }
        ServerLevel level = server.getLevel(dimension);
        LevelChunk loaded = level == null ? null : level.getChunkSource().getChunkNow(chunk.x, chunk.z);
        if (loaded != null) {
            for (Holder<Structure> structure : level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                    .getTagOrEmpty(OUTPOST_STRUCTURES)) {
                if (!loaded.getReferencesForStructure(structure.value()).isEmpty()) {
                    return Msg.err("outpost.claim.refused");
                }
            }
        }
        return null;
    }

    /**
     * Identifiant de l'avant-poste dont l'ancre est à {@code radius} blocs au plus de {@code pos}, ou {@code null}.
     * Utilisé par les contrats d'exploration (WP3).
     */
    @Nullable
    public static UUID outpostAt(ServerLevel level, BlockPos pos, int radius) {
        Outpost outpost = OutpostData.get(level.getServer()).nearest(level.dimension(), pos, radius);
        return outpost == null ? null : outpost.id();
    }

    /** Avant-poste dont la boîte protégée contient {@code pos}, ou {@code null}. */
    @Nullable
    public static Outpost boxAt(ServerLevel level, BlockPos pos) {
        return OutpostData.get(level.getServer()).boxAt(level.dimension(), pos);
    }
}
