package io.github.theoblaise1.sovereignfrontier.space;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Propriétaire d'un chunk (identifiant de nation), lu dans l'index O(1) de {@link NationData#ownerAt}. Les fonctions
 * de l'espace (zones, points de retour, sites d'atterrissage) le reçoivent en paramètre : les GameTests leur passent un
 * territoire simulé.
 */
@FunctionalInterface
public interface ChunkOwners {
    /** Nation propriétaire du chunk ({@code ChunkPos#toLong}), ou {@code null} s'il est libre. */
    @Nullable
    UUID ownerAt(ResourceKey<Level> dimension, long chunk);

    /** Territoire réel du serveur. */
    static ChunkOwners of(MinecraftServer server) {
        NationData data = NationData.get(server);
        return (dimension, chunk) -> {
            Nation owner = data.ownerAt(dimension, chunk);
            return owner == null ? null : owner.id();
        };
    }
}
