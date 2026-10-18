package io.github.theoblaise1.sovereignfrontier.outpost;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pools marchands publiés ({@code data/<ns>/merchants/*.json}). Chargement en deux temps (publication atomique) :
 * <ol>
 *   <li>{@link ReloadListener#apply} analyse les fichiers et ne construit qu'un <b>candidat</b> (constantes de code) ;</li>
 *   <li>au {@code TagsUpdatedEvent} ({@code SERVER_DATA_LOAD}), {@link #publishCandidate} rejette les pools qui
 *       contiennent un objet de {@code #interdit_caisses} puis applique l'écart global, et remplace le registre publié
 *       d'un seul coup. Le registre précédent reste actif jusque-là.</li>
 * </ol>
 * L'étal d'un avant-poste est un instantané : un {@code /reload} ne modifie jamais l'étal en cours.
 */
public final class MerchantRegistry {
    public static final String DIRECTORY = "merchants";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final MerchantRegistry EMPTY = new MerchantRegistry(Map.of());

    private static volatile MerchantRegistry published = EMPTY;
    private static volatile Map<ResourceLocation, MerchantPool> candidate = Map.of();

    private final Map<ResourceLocation, MerchantPool> pools;

    public MerchantRegistry(Map<ResourceLocation, MerchantPool> pools) {
        this.pools = Collections.unmodifiableMap(new LinkedHashMap<>(pools));
    }

    /** Registre publié (jamais {@code null}, vide avant le premier chargement). */
    public static MerchantRegistry get() {
        return published;
    }

    @Nullable
    public MerchantPool pool(ResourceLocation id) {
        return pools.get(id);
    }

    public Collection<MerchantPool> all() {
        return pools.values();
    }

    public Set<ResourceLocation> ids() {
        return pools.keySet();
    }

    public int size() {
        return pools.size();
    }

    /**
     * Analyse les fichiers (ordre des identifiants) : un fichier invalide est ignoré avec une ligne de journal en
     * français. Public pour les GameTests.
     */
    public static Map<ResourceLocation, MerchantPool> parse(Map<ResourceLocation, JsonElement> files, DynamicOps<JsonElement> ops) {
        Map<ResourceLocation, MerchantPool> out = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : new TreeMap<>(files).entrySet()) {
            DataResult<MerchantPool> result = MerchantPool.parse(e.getKey(), e.getValue(), ops);
            result.error().ifPresent(err -> LOGGER.error("Pool marchand {} ignoré : {}", e.getKey(), err.message()));
            result.result().ifPresent(pool -> out.put(e.getKey(), pool));
        }
        return out;
    }

    /**
     * Validations qui demandent les tags (valables après la liaison des tags) : un pool qui contient un objet de
     * {@code #sovereignfrontier:interdit_caisses} est rejeté en entier, puis l'écart global est appliqué.
     */
    public static MerchantRegistry validate(Map<ResourceLocation, MerchantPool> pools) {
        Map<ResourceLocation, MerchantPool> kept = new LinkedHashMap<>();
        for (MerchantPool pool : pools.values()) {
            MerchantOffer banned = null;
            for (MerchantOffer offer : pool.offers()) {
                if (RestrictedItems.isBannedFromLoot(offer.item())) {
                    banned = offer;
                    break;
                }
            }
            if (banned != null) {
                LOGGER.error("Pool marchand {} ignoré : {} appartient à #{} (interdit dans les ventes et rachats des marchands)",
                        pool.id(), BuiltInRegistries.ITEM.getKey(banned.item()), RestrictedItems.BANNED_FROM_LOOT.location());
            } else {
                kept.put(pool.id(), pool);
            }
        }
        return new MerchantRegistry(MarketRules.enforceSpread(kept, LOGGER::error));
    }

    /** {@code TagsUpdatedEvent} ({@code SERVER_DATA_LOAD}) : valide le candidat et le publie d'un seul coup. */
    public static void publishCandidate() {
        MerchantRegistry next = validate(candidate);
        published = next;
        LOGGER.info("Sovereign Frontier : {} pool(s) marchand(s) publié(s) {}", next.size(), next.ids());
    }

    /** Remplace le registre publié et renvoie le précédent (GameTests : simulation d'un {@code /reload}). */
    public static MerchantRegistry swap(MerchantRegistry registry) {
        MerchantRegistry previous = published;
        published = registry;
        return previous;
    }

    /** Arrêt du serveur : un monde suivant (solo) recharge ses propres datapacks. */
    public static void clear() {
        published = EMPTY;
        candidate = Map.of();
    }

    /** Écouteur de rechargement des pools marchands. */
    public static final class ReloadListener extends SimpleJsonResourceReloadListener {
        public ReloadListener() {
            super(GSON, DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
            DynamicOps<JsonElement> ops = getRegistryLookup().createSerializationContext(JsonOps.INSTANCE);
            candidate = parse(files, ops);
            LOGGER.info("Sovereign Frontier : {} pool(s) marchand(s) lu(s), publication à la liaison des tags", candidate.size());
        }
    }
}
