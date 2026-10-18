// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.enterprise;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Secteurs d'entreprise publiés ({@code data/sovereignfrontier/enterprises/<secteur>.json}), immuables.
 * <ul>
 *   <li>{@link ReloadListener#apply} analyse les fichiers et ne construit qu'un <b>candidat</b> (rejet strict : un
 *       fichier invalide est ignoré en entier, avec une ligne de journal en français) ;</li>
 *   <li>au {@code TagsUpdatedEvent} ({@code SERVER_DATA_LOAD}), {@link #publishCandidate} fait les validations qui
 *       exigent les tags liés et le registre de recherche (objet interdit, prérequis inconnu : secteur rejeté en
 *       entier), puis remplace atomiquement le registre publié. Le précédent reste actif jusque-là.</li>
 * </ul>
 * Un secteur absent ou rejeté est « indisponible » : on ne peut plus y affecter d'habitants et il ne produit rien.
 * Aucune donnée persistante n'en dépend (habitants affectés et fractions sont indexés par secteur et par objet) :
 * un {@code /reload} est sûr à tout moment.
 */
public final class SectorRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String DIRECTORY = "enterprises";
    public static final SectorRegistry EMPTY = new SectorRegistry(Map.of());

    private static volatile SectorRegistry published = EMPTY;
    @Nullable
    private static volatile SectorRegistry candidate;

    private final EnumMap<Sector, SectorDefinition> sectors = new EnumMap<>(Sector.class);

    public SectorRegistry(Map<Sector, SectorDefinition> sectors) {
        this.sectors.putAll(sectors);
    }

    /** Registre publié (serveur). */
    public static SectorRegistry get() {
        return published;
    }

    /** Définition du secteur, ou {@code null} s'il est indisponible (fichier absent ou rejeté). */
    @Nullable
    public SectorDefinition get(Sector sector) {
        return sectors.get(sector);
    }

    public Map<Sector, SectorDefinition> all() {
        return Collections.unmodifiableMap(sectors);
    }

    public int size() {
        return sectors.size();
    }

    // ------------------------------------------------------------------ analyse (sans tags)

    /**
     * Analyse les fichiers du dossier {@value #DIRECTORY}. Seuls {@code sovereignfrontier:<id de Sector>} sont lus ;
     * tout autre fichier est ignoré avec un avertissement. Public pour les GameTests.
     */
    public static SectorRegistry parse(Map<ResourceLocation, JsonElement> files, DynamicOps<JsonElement> ops) {
        EnumMap<Sector, SectorDefinition> parsed = new EnumMap<>(Sector.class);
        // Ordre stable des messages (celui des datapacks dépend d'un HashMap).
        for (ResourceLocation id : new TreeSet<>(files.keySet())) {
            Sector sector = sectorOf(id);
            if (sector == null) {
                LOGGER.warn("Fichier d'entreprise {} ignoré : seuls {}:agricole, minier, logistique et industriel sont lus",
                        id, SovereignFrontier.MODID);
                continue;
            }
            JsonElement json = files.get(id);
            String fields = unknownFields(json);
            if (fields != null) {
                LOGGER.error("Secteur d'entreprise {} ignoré : {}", id, fields);
                continue;
            }
            // Rejet strict : un résultat partiel (produit invalide retiré de la liste) chargerait un secteur amputé.
            DataResult<SectorDefinition> result = SectorDefinition.CODEC.parse(ops, json);
            result.error().ifPresent(err -> LOGGER.error("Secteur d'entreprise {} ignoré : {}", id, err.message()));
            result.result().ifPresent(def -> parsed.put(sector, def));
        }
        return new SectorRegistry(parsed);
    }

    @Nullable
    private static Sector sectorOf(ResourceLocation id) {
        if (!id.getNamespace().equals(SovereignFrontier.MODID)) {
            return null;
        }
        Sector sector = Sector.byId(id.getPath());
        return sector != null && sector.id().equals(id.getPath()) ? sector : null;
    }

    /** Champs inconnus (fichier ou produit) : description du problème, ou {@code null} si tout est connu. */
    @Nullable
    private static String unknownFields(JsonElement json) {
        if (!json.isJsonObject()) {
            return "objet JSON attendu";
        }
        JsonObject root = json.getAsJsonObject();
        Set<String> unknown = new TreeSet<>(root.keySet());
        unknown.removeAll(SectorDefinition.FIELDS);
        if (!unknown.isEmpty()) {
            return "champ(s) inconnu(s) : " + String.join(", ", unknown);
        }
        if (root.get("products") instanceof JsonArray products) {
            for (int i = 0; i < products.size(); i++) {
                if (products.get(i) instanceof JsonObject product) {
                    Set<String> extra = new TreeSet<>(product.keySet());
                    extra.removeAll(SectorDefinition.Product.FIELDS);
                    if (!extra.isEmpty()) {
                        return "products[" + i + "] : champ(s) inconnu(s) : " + String.join(", ", extra);
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ validation (tags et recherche)

    /**
     * Registre sans les secteurs qui ne passent pas les validations liées aux tags et au registre de recherche
     * (journal en français pour chaque rejet). Public pour les GameTests.
     */
    public SectorRegistry validated(ResearchRegistry research) {
        EnumMap<Sector, SectorDefinition> kept = new EnumMap<>(Sector.class);
        for (Map.Entry<Sector, SectorDefinition> e : sectors.entrySet()) {
            String problem = problem(e.getValue(), research);
            if (problem != null) {
                LOGGER.error("Secteur d'entreprise {}:{} ignoré : {}", SovereignFrontier.MODID, e.getKey().id(), problem);
            } else {
                kept.put(e.getKey(), e.getValue());
            }
        }
        return new SectorRegistry(kept);
    }

    @Nullable
    private static String problem(SectorDefinition def, ResearchRegistry research) {
        for (ResourceLocation req : def.requires()) {
            if (research.get(req) == null) {
                return "prérequis de recherche inconnu « " + req + " »";
            }
        }
        List<String> problems = new ArrayList<>();
        for (SectorDefinition.Product p : def.products()) {
            ResourceLocation item = BuiltInRegistries.ITEM.getKey(p.item());
            if (RestrictedItems.isBannedFromLoot(p.item())) {
                problems.add("produit interdit « " + item + " » (#" + RestrictedItems.BANNED_FROM_LOOT.location() + ")");
            }
            if (CrateKind.byKey(p.item()) != null) {
                problems.add("clé de caisse « " + item + " » : aucune source automatique de clés");
            }
            for (ResourceLocation req : p.requires()) {
                if (research.get(req) == null) {
                    problems.add("prérequis de recherche inconnu « " + req + " » pour « " + item + " »");
                }
            }
        }
        return problems.isEmpty() ? null : String.join(" ; ", problems);
    }

    // ------------------------------------------------------------------ publication

    /**
     * {@code TagsUpdatedEvent} ({@code SERVER_DATA_LOAD}) : tous les écouteurs de rechargement ont fini et les tags
     * sont liés. Valide le candidat puis le publie ; sans candidat (aucun rechargement depuis la dernière
     * publication), rien ne change.
     */
    public static void publishCandidate() {
        SectorRegistry next = candidate;
        if (next == null) {
            return;
        }
        candidate = null;
        published = next.validated(ResearchRegistry.server());
        for (Sector s : Sector.values()) {
            if (published.get(s) == null) {
                LOGGER.warn("Secteur d'entreprise {} indisponible : data/{}/{}/{}.json absent ou invalide", s.id(),
                        SovereignFrontier.MODID, DIRECTORY, s.id());
            }
        }
        LOGGER.info("Sovereign Frontier : {} secteur(s) d'entreprise publié(s) sur {}", published.size(),
                Sector.values().length);
    }

    /** Chargeur {@code data/<ns>/enterprises/*.json} : construit le candidat, publié à la mise à jour des tags. */
    public static final class ReloadListener extends SimpleJsonResourceReloadListener {
        public ReloadListener() {
            super(new GsonBuilder().create(), DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            candidate = parse(files, getRegistryLookup().createSerializationContext(JsonOps.INSTANCE));
        }
    }
}
