package io.github.theoblaise1.sovereignfrontier.space;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Planètes publiées ({@code data/<ns>/planets/*.json}), immuables. Chargement strict et atomique
 * (docs/conception/J5.md §2.3) :
 * <ul>
 *   <li>{@link ReloadListener#apply} analyse les fichiers et ne construit qu'un <b>candidat</b> (fichier invalide
 *       ignoré en entier, avec une ligne ERREUR en français) ;</li>
 *   <li>au {@code TagsUpdatedEvent} ({@code SERVER_DATA_LOAD}), {@link #publishCandidate} fait les validations qui
 *       exigent les tags liés et le registre de recherche (clés connues, tag de minerai non vide, module dans
 *       {@code #restreint/fusee_finale}) puis les contraintes d'ensemble (au plus {@value PlanetDefinition#MAX_PLANETS}
 *       planètes, une dimension et un nom par planète), et remplace d'un coup le registre publié. Le précédent reste
 *       actif jusque-là.</li>
 * </ul>
 * Un compte à rebours relit la planète au tick du transfert : {@code /reload} est sûr.
 */
public final class PlanetRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String DIRECTORY = "planets";
    public static final PlanetRegistry EMPTY = new PlanetRegistry(List.of());

    private static volatile PlanetRegistry published = EMPTY;
    @Nullable
    private static volatile PlanetRegistry candidate;

    private final Map<ResourceLocation, PlanetDefinition> byId = new LinkedHashMap<>();
    private final Map<ResourceKey<Level>, PlanetDefinition> byDimension = new HashMap<>();
    private final Map<String, PlanetDefinition> byName = new HashMap<>();

    /**
     * Planètes dans l'ordre de leurs identifiants. Sans contrôle d'ensemble : une dimension ou un nom en double garde
     * la première ; voir {@link #validated} pour le registre publiable.
     */
    public PlanetRegistry(List<PlanetDefinition> planets) {
        List<PlanetDefinition> sorted = new ArrayList<>(planets);
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        for (PlanetDefinition p : sorted) {
            byId.put(p.id(), p);
            byDimension.putIfAbsent(p.dimension(), p);
            byName.putIfAbsent(p.name(), p);
        }
    }

    /** Registre publié (serveur). */
    public static PlanetRegistry get() {
        return published;
    }

    @Nullable
    public PlanetDefinition byId(ResourceLocation id) {
        return byId.get(id);
    }

    /** Planète liée à cette dimension, ou {@code null} (Overworld, Nether, End, dimension d'un autre mod). */
    @Nullable
    public PlanetDefinition byDimension(ResourceKey<Level> dimension) {
        return byDimension.get(dimension);
    }

    /** Planète de ce nom ({@code lune}), destination de {@code /rocket launch}. */
    @Nullable
    public PlanetDefinition byName(String name) {
        return byName.get(name);
    }

    public List<PlanetDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public int size() {
        return byId.size();
    }

    /** Noms des planètes, dans l'ordre des identifiants (journal, suggestions). */
    public String names() {
        return byId.values().stream().map(PlanetDefinition::name).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ analyse (sans tags)

    /** Analyse les fichiers du dossier {@value #DIRECTORY} (tous espaces de noms). Public pour les GameTests. */
    public static PlanetRegistry parse(Map<ResourceLocation, JsonElement> files) {
        List<PlanetDefinition> parsed = new ArrayList<>();
        // Ordre stable des messages (celui des datapacks dépend d'un HashMap).
        for (ResourceLocation id : new TreeSet<>(files.keySet())) {
            DataResult<PlanetDefinition> result = PlanetDefinition.parse(id, files.get(id));
            result.error().ifPresent(err -> LOGGER.error("Planète {} ignorée : {}", id, err.message()));
            result.result().ifPresent(parsed::add);
        }
        return new PlanetRegistry(parsed);
    }

    // ------------------------------------------------------------------ validation (tags, recherche, ensemble)

    /**
     * Registre publiable : sans les planètes qui échouent aux validations liées aux tags et au registre de recherche,
     * puis sans celles qui partagent une dimension ou un nom (toutes rejetées) et au-delà de
     * {@value PlanetDefinition#MAX_PLANETS} (les premières par identifiant sont gardées). Journal en français pour
     * chaque rejet. Public pour les GameTests.
     */
    public PlanetRegistry validated(ResearchRegistry research) {
        List<PlanetDefinition> kept = new ArrayList<>();
        for (PlanetDefinition p : byId.values()) {
            String problem = problem(p, research);
            if (problem != null) {
                LOGGER.error("Planète {} ignorée : {}", p.id(), problem);
            } else {
                kept.add(p);
            }
        }
        Map<ResourceKey<Level>, List<PlanetDefinition>> perDimension = new LinkedHashMap<>();
        Map<String, List<PlanetDefinition>> perName = new LinkedHashMap<>();
        for (PlanetDefinition p : kept) {
            perDimension.computeIfAbsent(p.dimension(), d -> new ArrayList<>()).add(p);
            perName.computeIfAbsent(p.name(), n -> new ArrayList<>()).add(p);
        }
        List<PlanetDefinition> unique = new ArrayList<>();
        for (PlanetDefinition p : kept) {
            List<PlanetDefinition> sameDim = perDimension.get(p.dimension());
            List<PlanetDefinition> sameName = perName.get(p.name());
            if (sameDim.size() > 1) {
                LOGGER.error("Planète {} ignorée : dimension « {} » partagée avec {}", p.id(), p.dimension().location(),
                        others(sameDim, p));
            } else if (sameName.size() > 1) {
                LOGGER.error("Planète {} ignorée : nom « {} » partagé avec {}", p.id(), p.name(), others(sameName, p));
            } else if (unique.size() >= PlanetDefinition.MAX_PLANETS) {
                LOGGER.error("Planète {} ignorée : au plus {} planètes", p.id(), PlanetDefinition.MAX_PLANETS);
            } else {
                unique.add(p);
            }
        }
        return new PlanetRegistry(unique);
    }

    private static String others(List<PlanetDefinition> group, PlanetDefinition self) {
        return group.stream().filter(o -> o != self).map(o -> o.id().toString()).collect(Collectors.joining(", "));
    }

    /** Motif du rejet d'une planète (tags liés et registre de recherche requis), ou {@code null} si elle est valide. */
    @Nullable
    public static String problem(PlanetDefinition p, ResearchRegistry research) {
        for (String key : p.requires()) {
            if (!research.knowsUnlock(key)) {
                return "requires : clé « " + key + " » accordée par aucun palier de recherche";
            }
        }
        if (p.module().isPresent() && !BuiltInRegistries.ITEM.wrapAsHolder(p.module().get()).is(RestrictedItems.FINAL_ROCKET)) {
            return "module « " + BuiltInRegistries.ITEM.getKey(p.module().get()) + " » hors de #"
                    + RestrictedItems.FINAL_ROCKET.location();
        }
        if (p.mining().isPresent()) {
            PlanetDefinition.Mining mining = p.mining().get();
            if (!research.knowsUnlock(mining.unlock())) {
                return "mining.unlock : clé « " + mining.unlock() + " » accordée par aucun palier de recherche";
            }
            Optional<HolderSet.Named<net.minecraft.world.level.block.Block>> tag = BuiltInRegistries.BLOCK.getTag(mining.blocks());
            if (tag.isEmpty() || tag.get().size() == 0) {
                return "mining.blocks : tag de blocs « #" + mining.blocks().location() + " » inconnu ou vide";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ publication

    /**
     * {@code TagsUpdatedEvent} ({@code SERVER_DATA_LOAD}) : tous les écouteurs de rechargement ont fini, les tags sont
     * liés et le registre de recherche est à jour. Valide le candidat puis le publie ; sans candidat, rien ne change.
     */
    public static void publishCandidate() {
        PlanetRegistry next = candidate;
        if (next == null) {
            return;
        }
        candidate = null;
        published = next.validated(ResearchRegistry.server());
        LOGGER.info("Sovereign Frontier : {} planète(s) publiée(s) : {}", published.size(), published.names());
    }

    /**
     * Remplace le registre publié (GameTests : planète de test liée à une dimension existante) ; renvoie le précédent,
     * à rétablir dans un {@code finally}. Les tests qui l'appellent ont chacun leur lot.
     */
    public static PlanetRegistry replaceForTests(PlanetRegistry next) {
        PlanetRegistry previous = published;
        published = next;
        return previous;
    }

    /** Chargeur {@code data/<ns>/planets/*.json} : construit le candidat, publié à la mise à jour des tags. */
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
