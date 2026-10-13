package io.github.theoblaise1.sovereignfrontier.research;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Charge {@code data/<ns>/research/*.json} à chaque (re)chargement des datapacks. Les fichiers
 * invalides sont ignorés avec une erreur explicite dans le journal ; les paliers dont un prérequis
 * manque ou forme un cycle sont écartés, ainsi que ceux qui rompent la séquence d'un domaine.
 */
public final class ResearchReloadListener extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "research";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static int generation;

    public ResearchReloadListener() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        ResearchRegistry registry = build(files);
        ResearchRegistry.setServer(registry);
        LOGGER.info("Sovereign Frontier : {} palier(s) de recherche chargé(s)", registry.size());
    }

    /** Analyse et valide ; public pour les GameTests. */
    public static ResearchRegistry build(Map<ResourceLocation, JsonElement> files) {
        Map<ResourceLocation, ResearchTier> parsed = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : files.entrySet()) {
            // Rejet strict : un résultat partiel (ex. récompense ou objet invalide retiré d'une liste)
            // chargerait silencieusement un palier amputé.
            DataResult<ResearchDefinition> result = ResearchDefinition.CODEC.parse(JsonOps.INSTANCE, e.getValue());
            result.error().ifPresent(err -> LOGGER.error("Palier de recherche {} ignoré : {}", e.getKey(), err.message()));
            result.result().ifPresent(def -> parsed.put(e.getKey(), new ResearchTier(e.getKey(), def)));
        }

        // Unicité (domaine, palier).
        Map<ResearchDomain, Map<Integer, ResearchTier>> slots = new EnumMap<>(ResearchDomain.class);
        for (ResearchTier t : new ArrayList<>(parsed.values())) {
            ResearchTier clash = slots.computeIfAbsent(t.domain(), d -> new HashMap<>()).putIfAbsent(t.tier(), t);
            if (clash != null) {
                LOGGER.error("Palier de recherche {} ignoré : {} occupe déjà {} palier {}", t.id(), clash.id(), t.domain().id(), t.tier());
                parsed.remove(t.id());
            }
        }

        // Continuité 1..n de chaque domaine et prérequis existants, puis élimination des cycles.
        boolean changed = true;
        while (changed) {
            changed = false;
            ResearchRegistry candidate = new ResearchRegistry(parsed.values(), 0);
            for (ResearchTier t : candidate.all()) {
                if (t.tier() > 1 && candidate.previous(t) == null) {
                    LOGGER.error("Palier de recherche {} ignoré : le palier {} de {} manque", t.id(), t.tier() - 1, t.domain().id());
                    parsed.remove(t.id());
                    changed = true;
                    continue;
                }
                for (ResourceLocation req : candidate.prerequisites(t)) {
                    if (!parsed.containsKey(req)) {
                        LOGGER.error("Palier de recherche {} ignoré : prérequis inconnu {}", t.id(), req);
                        parsed.remove(t.id());
                        changed = true;
                        break;
                    }
                }
            }
            if (!changed) {
                ResourceLocation cyclic = findCycle(candidate);
                if (cyclic != null) {
                    LOGGER.error("Palier de recherche {} ignoré : cycle de prérequis", cyclic);
                    parsed.remove(cyclic);
                    changed = true;
                }
            }
        }
        return new ResearchRegistry(parsed.values(), ++generation);
    }

    private static ResourceLocation findCycle(ResearchRegistry registry) {
        Set<ResourceLocation> done = new HashSet<>();
        for (ResearchTier t : registry.all()) {
            ResourceLocation found = visit(registry, t.id(), new HashSet<>(), done);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ResourceLocation visit(ResearchRegistry registry, ResourceLocation id, Set<ResourceLocation> stack,
                                          Set<ResourceLocation> done) {
        if (done.contains(id)) {
            return null;
        }
        if (!stack.add(id)) {
            return id;
        }
        ResearchTier t = registry.get(id);
        if (t != null) {
            for (ResourceLocation req : registry.prerequisites(t)) {
                ResourceLocation found = visit(registry, req, stack, done);
                if (found != null) {
                    return found;
                }
            }
        }
        stack.remove(id);
        done.add(id);
        return null;
    }
}
