// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.contract;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Modèles de contrat publiés ({@code data/<ns>/contracts/*.json}), immuables.
 * <ul>
 *   <li>{@link ReloadListener#apply} analyse les fichiers et ne construit qu'un <b>candidat</b> (fichier invalide ignoré en
 *       entier, avec une ligne de journal en français) ;</li>
 *   <li>au {@code TagsUpdatedEvent} ({@code SERVER_DATA_LOAD}), {@link #publishCandidate} fait les validations qui exigent
 *       les tags liés et le registre de recherche (cibles {@code MONSTER}, tags non vides, biomes et dimensions connus,
 *       objets interdits, prérequis), puis remplace atomiquement le registre publié. Le précédent reste actif
 *       jusque-là.</li>
 * </ul>
 * Les contrats déjà générés n'en dépendent pas (instantanés dans le NBT de la nation) : {@code /reload} est sûr.
 */
public final class ContractRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String DIRECTORY = "contracts";
    public static final ContractRegistry EMPTY = new ContractRegistry(List.of());

    private static volatile ContractRegistry published = EMPTY;
    @Nullable
    private static volatile ContractRegistry candidate;

    private final Map<ResourceLocation, ContractTemplate> byId = new LinkedHashMap<>();

    /** Modèles dans l'ordre de leurs identifiants (tirages reproductibles à graine égale). */
    public ContractRegistry(List<ContractTemplate> templates) {
        List<ContractTemplate> sorted = new ArrayList<>(templates);
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        for (ContractTemplate t : sorted) {
            byId.put(t.id(), t);
        }
    }

    /** Registre publié (serveur). */
    public static ContractRegistry get() {
        return published;
    }

    @Nullable
    public ContractTemplate get(ResourceLocation id) {
        return byId.get(id);
    }

    public List<ContractTemplate> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public int size() {
        return byId.size();
    }

    /** Nombre de modèles par type. */
    public Map<ContractKind, Integer> countByKind() {
        Map<ContractKind, Integer> counts = new EnumMap<>(ContractKind.class);
        for (ContractTemplate t : byId.values()) {
            counts.merge(t.kind(), 1, Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------ analyse (sans tags)

    /** Analyse les fichiers du dossier {@value #DIRECTORY} (tous espaces de noms). Public pour les GameTests. */
    public static ContractRegistry parse(Map<ResourceLocation, JsonElement> files) {
        List<ContractTemplate> parsed = new ArrayList<>();
        // Ordre stable des messages (celui des datapacks dépend d'un HashMap).
        for (ResourceLocation id : new TreeSet<>(files.keySet())) {
            DataResult<ContractTemplate> result = ContractTemplate.parse(id, files.get(id));
            result.error().ifPresent(err -> LOGGER.error("Modèle de contrat {} ignoré : {}", id, err.message()));
            result.result().ifPresent(parsed::add);
        }
        return new ContractRegistry(parsed);
    }

    // ------------------------------------------------------------------ validation (tags, registres, recherche)

    /**
     * Registre sans les modèles qui ne passent pas les validations liées aux tags, aux registres dynamiques (biomes,
     * dimensions) et au registre de recherche (journal en français pour chaque rejet). Public pour les GameTests.
     */
    public ContractRegistry validated(RegistryAccess registries, ResearchRegistry research) {
        List<ContractTemplate> kept = new ArrayList<>();
        for (ContractTemplate t : byId.values()) {
            String problem = problem(t, registries, research);
            if (problem != null) {
                LOGGER.error("Modèle de contrat {} ignoré : {}", t.id(), problem);
            } else {
                kept.add(t);
            }
        }
        return new ContractRegistry(kept);
    }

    /** Motif du rejet d'un modèle, ou {@code null} s'il est valide. */
    @Nullable
    public static String problem(ContractTemplate t, RegistryAccess registries, ResearchRegistry research) {
        for (ResourceLocation req : t.requires()) {
            if (research.get(req) == null) {
                return "prérequis de recherche inconnu « " + req + " »";
            }
        }
        String objective = switch (t.objective()) {
            case ContractObjective.Deliver d -> null;
            case ContractObjective.Kill k -> killProblem(k.entity());
            case ContractObjective.Explore e -> exploreProblem(e.target(), registries);
        };
        if (objective != null) {
            return objective;
        }
        for (ContractTemplate.ItemReward item : t.reward().items()) {
            // J5 : #interdit_caisses sauf #restreint/fusee_finale (module cryogénique) ; J6 : sauf palier_3 et palier_4.
            if (RestrictedItems.isBannedFromContracts(item.item())) {
                return "récompense interdite « " + BuiltInRegistries.ITEM.getKey(item.item()) + " » (#"
                        + RestrictedItems.BANNED_FROM_LOOT.location() + ", seuls #" + RestrictedItems.FINAL_ROCKET.location()
                        + ", #" + RestrictedItems.TIER_3.location() + " et #" + RestrictedItems.TIER_4.location()
                        + " sont permis dans un contrat)";
            }
        }
        return null;
    }

    /** La cible doit exister et ne contenir que des monstres ({@code MobCategory.MONSTER}). */
    @Nullable
    private static String killProblem(ExtraCodecs.TagOrElementLocation entity) {
        if (!entity.tag()) {
            Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(entity.id());
            if (type.isEmpty()) {
                return "type d'entité inconnu « " + entity.id() + " »";
            }
            return type.get().getCategory() == MobCategory.MONSTER ? null
                    : "cible « " + entity.id() + " » hors de la catégorie MONSTER (" + type.get().getCategory().getName() + ")";
        }
        TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, entity.id());
        Optional<HolderSet.Named<EntityType<?>>> set = BuiltInRegistries.ENTITY_TYPE.getTag(tag);
        if (set.isEmpty() || set.get().size() == 0) {
            return "tag d'entités « #" + entity.id() + " » inconnu ou vide";
        }
        for (Holder<EntityType<?>> holder : set.get()) {
            if (holder.value().getCategory() != MobCategory.MONSTER) {
                return "le tag « #" + entity.id() + " » contient « " + BuiltInRegistries.ENTITY_TYPE.getKey(holder.value())
                        + " », hors de la catégorie MONSTER";
            }
        }
        return null;
    }

    @Nullable
    private static String exploreProblem(ContractObjective.ExploreTarget target, RegistryAccess registries) {
        if (target instanceof ContractObjective.BiomeTarget b) {
            Optional<Registry<Biome>> biomes = registries.registry(Registries.BIOME);
            if (biomes.isEmpty()) {
                return null;
            }
            if (b.biome().tag()) {
                Optional<HolderSet.Named<Biome>> set = biomes.get().getTag(TagKey.create(Registries.BIOME, b.biome().id()));
                return set.isEmpty() || set.get().size() == 0 ? "tag de biome « #" + b.biome().id() + " » inconnu ou vide" : null;
            }
            return biomes.get().containsKey(b.biome().id()) ? null : "biome inconnu « " + b.biome().id() + " »";
        }
        if (target instanceof ContractObjective.DimensionTarget d) {
            // J5 : une dimension déclarée par une planète est acceptée même si le serveur ne l'a pas (GameTests).
            if (PlanetRegistry.get().byDimension(ResourceKey.create(Registries.DIMENSION, d.dimension())) != null) {
                return null;
            }
            return registries.registry(Registries.LEVEL_STEM).map(stems -> stems.containsKey(d.dimension()) ? null
                    : "dimension inconnue « " + d.dimension() + " »").orElse(null);
        }
        return null;
    }

    // ------------------------------------------------------------------ publication

    /**
     * {@code TagsUpdatedEvent} ({@code SERVER_DATA_LOAD}) : tous les écouteurs de rechargement ont fini et les tags sont
     * liés. Valide le candidat puis le publie ; sans candidat, rien ne change.
     */
    public static void publishCandidate(RegistryAccess registries) {
        ContractRegistry next = candidate;
        if (next == null) {
            return;
        }
        candidate = null;
        published = next.validated(registries, ResearchRegistry.server());
        LOGGER.info("Sovereign Frontier : {} modèle(s) de contrat publié(s) {}", published.size(), published.countByKind());
    }

    /** Chargeur {@code data/<ns>/contracts/*.json} : construit le candidat, publié à la mise à jour des tags. */
    public static final class ReloadListener extends SimpleJsonResourceReloadListener {
        public ReloadListener() {
            super(new GsonBuilder().create(), DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            candidate = parse(files);
        }
    }
}
