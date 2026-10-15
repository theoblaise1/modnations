package io.github.theoblaise1.sovereignfrontier.engineering;

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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recettes de la Station d'ingénierie, rechargées avec les datapacks (rejet strict des fichiers invalides, tri par identifiant). */
public final class EngineeringRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile EngineeringRegistry server = new EngineeringRegistry(List.of());

    private final Map<ResourceLocation, EngineeringRecipe> byId = new LinkedHashMap<>();

    public EngineeringRegistry(Collection<EngineeringRecipe> recipes) {
        // Ordre stable par identifiant (serveur et client) : celui des datapacks dépend d'un HashMap.
        List<EngineeringRecipe> sorted = new ArrayList<>(recipes);
        sorted.sort(Comparator.comparing(EngineeringRecipe::id));
        for (EngineeringRecipe r : sorted) {
            byId.put(r.id(), r);
        }
    }

    public static EngineeringRegistry server() {
        return server;
    }

    @Nullable
    public EngineeringRecipe get(ResourceLocation id) {
        return byId.get(id);
    }

    public Collection<EngineeringRecipe> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public List<EngineeringRecipe> tab(EngineeringTab tab) {
        List<EngineeringRecipe> out = new ArrayList<>();
        for (EngineeringRecipe r : byId.values()) {
            if (r.definition().tab() == tab) {
                out.add(r);
            }
        }
        return out;
    }

    public static EngineeringRegistry build(Map<ResourceLocation, JsonElement> files) {
        List<EngineeringRecipe> recipes = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : files.entrySet()) {
            DataResult<EngineeringRecipe.Definition> result = EngineeringRecipe.Definition.CODEC.parse(JsonOps.INSTANCE, e.getValue());
            result.error().ifPresent(err -> LOGGER.error("Recette d'ingénierie {} ignorée : {}", e.getKey(), err.message()));
            result.result().ifPresent(def -> recipes.add(new EngineeringRecipe(e.getKey(), def)));
        }
        return new EngineeringRegistry(recipes);
    }

    /** Chargeur {@code data/<ns>/engineering/*.json}. */
    public static final class ReloadListener extends SimpleJsonResourceReloadListener {
        public ReloadListener() {
            super(new GsonBuilder().create(), "engineering");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            server = build(files);
            LOGGER.info("Sovereign Frontier : {} recette(s) d'ingénierie chargée(s)", server.byId.size());
        }
    }
}
