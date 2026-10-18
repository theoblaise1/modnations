package io.github.theoblaise1.sovereignfrontier.reward;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Tables de caisse ({@code data/sovereignfrontier/crates/<type>.json}, le nom du fichier est l'identifiant du
 * {@link CrateKind}). Chargement strict en deux temps :
 * <ol>
 *   <li>{@link ReloadListener#apply} analyse (avec les registres, pour les composants) et construit un candidat ;
 *       un fichier invalide est ignoré avec un message en français ;</li>
 *   <li>{@link #publish()} (au {@code TagsUpdatedEvent} de chargement des données serveur, tags liés) rejette en entier
 *       toute table qui contient un objet de {@code #interdit_caisses}, puis échange atomiquement le registre publié.
 *       Le registre précédent reste actif jusque-là.</li>
 * </ol>
 * La pitié et les ouvertures en cours ne dépendent que d'identifiants stables : {@code /reload} est sûr à tout moment.
 */
public final class CrateRegistry {
    public static final String DIRECTORY = "crates";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Map<CrateKind, CrateTable> published = Map.of();
    private static volatile Map<CrateKind, CrateTable> candidate = Map.of();

    private CrateRegistry() {
    }

    /** Table publiée du type, ou {@code null} (caisse indisponible). */
    @Nullable
    public static CrateTable get(CrateKind kind) {
        return published.get(kind);
    }

    /** Tables publiées (lecture seule). */
    public static Map<CrateKind, CrateTable> all() {
        return published;
    }

    /** Analyse et validation structurelle d'un fichier de table. */
    public static DataResult<CrateTable> parse(JsonElement json, HolderLookup.Provider registries) {
        return CrateTable.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), json);
    }

    /** Candidat à partir des fichiers chargés : un fichier mal nommé ou invalide est ignoré (journal en français). */
    public static Map<CrateKind, CrateTable> build(Map<ResourceLocation, JsonElement> files, HolderLookup.Provider registries) {
        Map<CrateKind, CrateTable> out = new EnumMap<>(CrateKind.class);
        for (Map.Entry<ResourceLocation, JsonElement> e : files.entrySet()) {
            ResourceLocation id = e.getKey();
            CrateKind kind = SovereignFrontier.MODID.equals(id.getNamespace()) ? CrateKind.byId(id.getPath()) : null;
            if (kind == null || !kind.id().equals(id.getPath())) {
                LOGGER.warn("Caisse {} ignorée : le fichier doit s'appeler data/{}/{}/<type>.json (commune, militaire, "
                        + "industrielle ou spatiale)", id, SovereignFrontier.MODID, DIRECTORY);
                continue;
            }
            DataResult<CrateTable> result = parse(e.getValue(), registries);
            result.error().ifPresent(err -> LOGGER.error("Caisse {} ignorée : {}", id, err.message()));
            result.result().ifPresent(table -> out.put(kind, table));
        }
        return out;
    }

    /**
     * Retire les tables qui contiennent un objet interdit (tags liés requis) ; chaque rejet est journalisé.
     *
     * @return copie filtrée
     */
    public static Map<CrateKind, CrateTable> rejectBanned(Map<CrateKind, CrateTable> tables) {
        Map<CrateKind, CrateTable> out = new EnumMap<>(CrateKind.class);
        for (Map.Entry<CrateKind, CrateTable> e : tables.entrySet()) {
            List<ResourceLocation> banned = e.getValue().bannedItems();
            if (banned.isEmpty()) {
                out.put(e.getKey(), e.getValue());
            } else {
                LOGGER.error("Caisse {}:{} ignorée : objet(s) interdit(s) dans les caisses (#{}:interdit_caisses) : {}",
                        SovereignFrontier.MODID, e.getKey().id(), SovereignFrontier.MODID, banned);
            }
        }
        return out;
    }

    /** Publication atomique du candidat (au {@code TagsUpdatedEvent} de chargement des données serveur). */
    public static void publish() {
        Map<CrateKind, CrateTable> tables = rejectBanned(candidate);
        published = Collections.unmodifiableMap(tables);
        for (CrateKind kind : CrateKind.values()) {
            if (!tables.containsKey(kind)) {
                LOGGER.error("Caisse {}:{} indisponible : aucune table valide", SovereignFrontier.MODID, kind.id());
            }
        }
        LOGGER.info("Sovereign Frontier : {} table(s) de caisse publiée(s)", tables.size());
    }

    /** Chargeur {@code data/<ns>/crates/*.json} : construit seulement le candidat. */
    public static final class ReloadListener extends SimpleJsonResourceReloadListener {
        public ReloadListener() {
            super(new GsonBuilder().create(), DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            candidate = build(files, getRegistryLookup());
        }
    }
}
