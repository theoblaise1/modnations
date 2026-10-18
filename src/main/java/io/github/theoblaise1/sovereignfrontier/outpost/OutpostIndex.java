package io.github.theoblaise1.sovereignfrontier.outpost;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Index dérivé des avant-postes (reconstruit au chargement, jamais persisté) : par dimension, chunk → avant-postes
 * dont la zone de 3 × 3 chunks autour du comptoir couvre ce chunk ; et ancre → avant-poste. Toutes les requêtes
 * sont des recherches de table, sans parcours des avant-postes ni chargement de chunk.
 */
public final class OutpostIndex {
    private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<List<UUID>>> chunks = new HashMap<>();
    private final Map<GlobalPos, UUID> anchors = new HashMap<>();

    void add(Outpost outpost) {
        anchors.put(outpost.anchor(), outpost.id());
        Long2ObjectOpenHashMap<List<UUID>> map = chunks.computeIfAbsent(outpost.dimension(), d -> new Long2ObjectOpenHashMap<>());
        forEachChunk(outpost.pos(), key -> {
            List<UUID> ids = map.computeIfAbsent(key, k -> new ArrayList<>(1));
            if (!ids.contains(outpost.id())) {
                ids.add(outpost.id());
            }
        });
    }

    void remove(Outpost outpost) {
        anchors.remove(outpost.anchor(), outpost.id());
        Long2ObjectOpenHashMap<List<UUID>> map = chunks.get(outpost.dimension());
        if (map == null) {
            return;
        }
        forEachChunk(outpost.pos(), key -> {
            List<UUID> ids = map.get(key);
            if (ids != null) {
                ids.remove(outpost.id());
                if (ids.isEmpty()) {
                    map.remove(key);
                }
            }
        });
    }

    void clear() {
        chunks.clear();
        anchors.clear();
    }

    private interface ChunkConsumer {
        void accept(long chunk);
    }

    private static void forEachChunk(BlockPos anchor, ChunkConsumer consumer) {
        int cx = anchor.getX() >> 4;
        int cz = anchor.getZ() >> 4;
        int r = MarketRules.CLAIM_RADIUS_CHUNKS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                consumer.accept(ChunkPos.asLong(cx + dx, cz + dz));
            }
        }
    }

    /** Avant-postes dont la zone de 3 × 3 chunks couvre ce chunk (liste vide le plus souvent). */
    public List<UUID> at(ResourceKey<Level> dimension, long chunk) {
        Long2ObjectOpenHashMap<List<UUID>> map = chunks.get(dimension);
        List<UUID> ids = map == null ? null : map.get(chunk);
        return ids == null ? List.of() : ids;
    }

    /** Vrai si ce chunk est dans la zone non revendicable d'un avant-poste. */
    public boolean covers(ResourceKey<Level> dimension, long chunk) {
        return !at(dimension, chunk).isEmpty();
    }

    @Nullable
    public UUID anchorAt(GlobalPos anchor) {
        return anchors.get(anchor);
    }
}
