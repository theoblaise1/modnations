package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/** Copie client des définitions de recherche, reçue à la connexion et après chaque /reload. */
@OnlyIn(Dist.CLIENT)
public final class ClientResearchState {
    private static ResearchRegistry registry = new ResearchRegistry(List.of(), 0);
    private static int generation;

    private ClientResearchState() {
    }

    public static ResearchRegistry registry() {
        return registry;
    }

    public static void set(List<ResearchTier> tiers) {
        registry = new ResearchRegistry(tiers, ++generation);
    }

    public static void clear() {
        registry = new ResearchRegistry(List.of(), ++generation);
    }
}
