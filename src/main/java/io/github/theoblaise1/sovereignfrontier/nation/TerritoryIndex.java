package io.github.theoblaise1.sovereignfrontier.nation;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache par chunk : (dimension, chunk) → nation propriétaire. Consulté par tous les événements
 * de bloc/interaction/apparition en O(1), sans jamais parcourir la liste des nations.
 * Il est reconstruit au chargement et invalidé entrée par entrée à chaque claim/unclaim.
 */
public final class TerritoryIndex {
    private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Nation>> byDimension = new HashMap<>();

    @Nullable
    public Nation ownerAt(ResourceKey<Level> dimension, long chunk) {
        Long2ObjectOpenHashMap<Nation> map = byDimension.get(dimension);
        return map == null ? null : map.get(chunk);
    }

    void put(ResourceKey<Level> dimension, long chunk, Nation nation) {
        byDimension.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>()).put(chunk, nation);
    }

    void invalidate(ResourceKey<Level> dimension, long chunk) {
        Long2ObjectOpenHashMap<Nation> map = byDimension.get(dimension);
        if (map != null) {
            map.remove(chunk);
        }
    }

    void rebuild(Collection<Nation> nations) {
        byDimension.clear();
        for (Nation nation : nations) {
            for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> e : nation.claims().entrySet()) {
                for (long chunk : e.getValue()) {
                    put(e.getKey(), chunk, nation);
                }
            }
        }
    }

    public int size() {
        int n = 0;
        for (Long2ObjectOpenHashMap<Nation> map : byDimension.values()) {
            n += map.size();
        }
        return n;
    }
}
