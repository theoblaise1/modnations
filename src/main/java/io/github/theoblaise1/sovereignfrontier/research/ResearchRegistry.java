package io.github.theoblaise1.sovereignfrontier.research;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ensemble immuable de paliers validés. Le serveur en détient un (rechargé avec les datapacks),
 * le client une copie synchronisée. {@link #generation()} change à chaque rechargement pour
 * invalider les caches de bonus.
 */
public final class ResearchRegistry {
    private static volatile ResearchRegistry server = new ResearchRegistry(List.of(), 0);

    private final Map<ResourceLocation, ResearchTier> byId = new LinkedHashMap<>();
    private final EnumMap<ResearchDomain, List<ResearchTier>> byDomain = new EnumMap<>(ResearchDomain.class);
    private final int generation;

    public ResearchRegistry(Collection<ResearchTier> tiers, int generation) {
        this.generation = generation;
        for (ResearchDomain d : ResearchDomain.values()) {
            byDomain.put(d, new ArrayList<>());
        }
        List<ResearchTier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparing(ResearchTier::domain).thenComparingInt(ResearchTier::tier));
        for (ResearchTier t : sorted) {
            byId.put(t.id(), t);
            byDomain.get(t.domain()).add(t);
        }
    }

    public static ResearchRegistry server() {
        return server;
    }

    static void setServer(ResearchRegistry registry) {
        server = registry;
    }

    public int generation() {
        return generation;
    }

    @Nullable
    public ResearchTier get(ResourceLocation id) {
        return byId.get(id);
    }

    public Collection<ResearchTier> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public List<ResearchTier> domain(ResearchDomain domain) {
        return Collections.unmodifiableList(byDomain.get(domain));
    }

    public int size() {
        return byId.size();
    }

    /** Palier {@code n-1} du même domaine (prérequis implicite), ou {@code null} s'il n'existe pas. */
    @Nullable
    public ResearchTier previous(ResearchTier tier) {
        for (ResearchTier t : byDomain.get(tier.domain())) {
            if (t.tier() == tier.tier() - 1) {
                return t;
            }
        }
        return null;
    }

    /** Prérequis complets : palier précédent du domaine + {@code requires}. */
    public List<ResourceLocation> prerequisites(ResearchTier tier) {
        List<ResourceLocation> list = new ArrayList<>();
        ResearchTier prev = previous(tier);
        if (prev != null) {
            list.add(prev.id());
        }
        for (ResourceLocation r : tier.definition().requires()) {
            if (!list.contains(r)) {
                list.add(r);
            }
        }
        return list;
    }
}
