package io.github.theoblaise1.sovereignfrontier.space;

import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Permis de voyage vers une planète (docs/conception/J5.md §3.3) : ensemble d'UUID posé par le vol en fusée juste
 * avant {@code changeDimension} et retiré juste après. La garde de {@code EntityTravelToDimensionEvent} (WP1) accepte
 * un voyage vers une planète si l'entité porte un permis ou si c'est un opérateur. En mémoire seulement : vidé à
 * l'arrêt du serveur.
 */
public final class TravelPermits {
    private static final Set<UUID> PERMITS = ConcurrentHashMap.newKeySet();

    private TravelPermits() {
    }

    public static void grant(UUID entity) {
        PERMITS.add(entity);
    }

    public static void revoke(UUID entity) {
        PERMITS.remove(entity);
    }

    public static boolean has(UUID entity) {
        return PERMITS.contains(entity);
    }

    /** Exécute {@code action} avec un permis pour {@code entity}, retiré ensuite quoi qu'il arrive. */
    public static <T> T with(Entity entity, Supplier<T> action) {
        UUID id = entity.getUUID();
        grant(id);
        try {
            return action.get();
        } finally {
            revoke(id);
        }
    }

    public static int size() {
        return PERMITS.size();
    }

    public static void clear() {
        PERMITS.clear();
    }
}
