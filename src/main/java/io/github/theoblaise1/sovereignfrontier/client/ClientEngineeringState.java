package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRecipe;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRegistry;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchReward;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Copie client des recettes d'ingénierie et calcul des déblocages à partir du snapshot national. */
@OnlyIn(Dist.CLIENT)
public final class ClientEngineeringState {
    private static EngineeringRegistry registry = new EngineeringRegistry(List.of());

    private ClientEngineeringState() {
    }

    public static EngineeringRegistry registry() {
        return registry;
    }

    public static void set(List<EngineeringRecipe> recipes) {
        registry = new EngineeringRegistry(recipes);
    }

    /** Clés de déblocage accordées par les paliers terminés de la nation du joueur. */
    public static Set<String> unlocks() {
        Set<String> keys = new HashSet<>();
        NationSnapshot s = ClientNationState.get();
        if (s == null || !s.hasNation()) {
            return keys;
        }
        ResearchRegistry research = ClientResearchState.registry();
        for (ResourceLocation id : s.research().completed()) {
            ResearchTier tier = research.get(id);
            if (tier != null) {
                for (ResearchReward r : tier.definition().rewards()) {
                    if (r.type() == ResearchReward.Type.UNLOCK) {
                        keys.add(r.key());
                    }
                }
            }
        }
        return keys;
    }
}
