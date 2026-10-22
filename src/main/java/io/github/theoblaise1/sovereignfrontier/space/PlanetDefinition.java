package io.github.theoblaise1.sovereignfrontier.space;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Planète : contenu d'un fichier {@code data/<ns>/planets/<nom>.json} (docs/conception/J5.md §2.1). Le nom de la
 * planète est celui du fichier ({@code lune}) ; son nom affiché est {@code sovereignfrontier.planet.<nom>}.
 * <p>
 * L'analyse est stricte : champ inconnu, champ obligatoire absent, type ou borne invalide, objet inconnu ou clé de
 * déblocage mal formée font rejeter le fichier entier (journal en français). Les bornes sont des constantes de code
 * (§1.11), jamais lues dans {@code SFConfig} : la configuration du serveur n'est pas chargée au premier chargement
 * des datapacks. Les validations qui exigent les tags liés et le registre de recherche (clés connues, tag de minerai
 * non vide, module dans {@code #restreint/fusee_finale}) sont faites par {@link PlanetRegistry#validated} ;
 * {@code tools/check_space.py} applique les mêmes règles en CI.
 *
 * @param requires        clés de déblocage exigées de la nation pour un vol aller (le retour n'exige rien)
 * @param outbound        options de carburant d'un vol aller, essayées dans l'ordre
 * @param returnTrip      options de carburant d'un vol retour (champ {@code return})
 * @param radius          rayon de la bordure souple autour de (0, 0), en blocs
 * @param gasReserve      réserve de gaz finie de la dimension (litres) ; absente : {@code gasPerDimension}
 * @param solarMultiplier facteur de production des panneaux solaires (la pluie terrestre est ignorée)
 * @param module          composant final exigé dans la soute (membre de {@code #restreint/fusee_finale})
 * @param mining          minerais de la planète et clé d'extraction
 */
public record PlanetDefinition(ResourceLocation id, ResourceKey<Level> dimension, List<String> requires,
                               List<FuelOption> outbound, List<FuelOption> returnTrip, int radius, Environment environment,
                               OptionalLong gasReserve, double solarMultiplier, Optional<Item> module,
                               Optional<Mining> mining) {
    public static final int RADIUS_MIN = 256;
    public static final int RADIUS_MAX = 30_000;
    public static final int OPTIONS_MAX = 4;
    public static final int COUNT_MAX = 64;
    public static final long GAS_RESERVE_MAX = 1_000_000_000_000L;
    public static final double SOLAR_MAX = 4.0D;
    public static final int REQUIRES_MAX = 8;
    /** Nombre maximal de planètes publiées. */
    public static final int MAX_PLANETS = 8;
    /** Destination réservée au retour vers l'Overworld : aucune planète ne peut porter ce nom. */
    public static final String EARTH = "terre";
    public static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,32}");
    /** Format des clés du catalogue de docs/recherche.md (identifiant pointé en minuscules). */
    public static final Pattern UNLOCK_KEY = Pattern.compile("[a-z0-9_]+(?:\\.[a-z0-9_]+)*");

    static final Set<String> FIELDS = Set.of("dimension", "requires", "outbound", "return", "radius", "environment",
            "gas_reserve", "solar_multiplier", "module", "mining");
    static final Set<String> OPTION_FIELDS = Set.of("item", "count");
    static final Set<String> ENVIRONMENT_FIELDS = Set.of("oxygen", "cold");
    static final Set<String> MINING_FIELDS = Set.of("unlock", "blocks");

    /** Une option de carburant : {@code count} objets intacts de {@code item} dans la soute. */
    public record FuelOption(Item item, int count) {
    }

    /** Contraintes d'environnement : oxygène (les trois planètes du J5) et froid (planète glacée). */
    public record Environment(boolean oxygen, boolean cold) {
    }

    /** Minerais de la planète ({@code #blocks}, tag de blocs) et clé de recherche qui permet de les casser. */
    public record Mining(String unlock, TagKey<Block> blocks) {
    }

    public PlanetDefinition {
        requires = List.copyOf(requires);
        outbound = List.copyOf(outbound);
        returnTrip = List.copyOf(returnTrip);
    }

    /** Nom de la planète (chemin de l'identifiant) : destination de {@code /rocket launch}. */
    public String name() {
        return id.getPath();
    }

    /** Clé de traduction du nom : {@code sovereignfrontier.planet.<nom>}. */
    public static String translationKey(String name) {
        return "planet." + name;
    }

    /** Nom affiché (identifiant brut si aucune traduction : planète d'un datapack tiers). */
    public MutableComponent displayName() {
        return Component.translatableWithFallback(Msg.key(translationKey(name())), id.toString());
    }

    /** Options de carburant du sens demandé ({@code outbound} vers la planète, sinon {@code return}). */
    public List<FuelOption> options(boolean towardPlanet) {
        return towardPlanet ? outbound : returnTrip;
    }

    // ------------------------------------------------------------------ analyse

    /** Analyse stricte d'un fichier ; l'erreur décrit le premier problème rencontré. */
    public static DataResult<PlanetDefinition> parse(ResourceLocation id, JsonElement json) {
        try {
            return DataResult.success(read(id, json));
        } catch (Invalid e) {
            return DataResult.error(e::getMessage);
        }
    }

    private static PlanetDefinition read(ResourceLocation id, JsonElement json) {
        if (!NAME.matcher(id.getPath()).matches()) {
            throw new Invalid("nom de fichier « " + id.getPath() + " » invalide ([a-z0-9_], 32 caractères au plus, "
                    + "sans sous-dossier)");
        }
        if (EARTH.equals(id.getPath())) {
            throw new Invalid("nom « " + EARTH + " » réservé au retour vers l'Overworld");
        }
        Obj root = Obj.of(json, "", FIELDS);
        ResourceLocation dimId = location(root.require("dimension"), "dimension");
        if (dimId.equals(Level.OVERWORLD.location())) {
            throw new Invalid("dimension : « " + dimId + " » ne peut pas être une planète");
        }
        List<String> requires = requires(root);
        List<FuelOption> outbound = options(root, "outbound");
        List<FuelOption> returnTrip = options(root, "return");
        int radius = root.intField("radius", RADIUS_MIN, RADIUS_MAX);
        Obj env = root.obj("environment", ENVIRONMENT_FIELDS);
        Environment environment = new Environment(env.bool("oxygen"), env.bool("cold"));
        OptionalLong gas = root.has("gas_reserve") ? OptionalLong.of(root.longField("gas_reserve", 0L, GAS_RESERVE_MAX))
                : OptionalLong.empty();
        double solar = root.optDouble("solar_multiplier", 1.0D, 0.0D, SOLAR_MAX);
        Optional<Item> module = root.has("module") ? Optional.of(item(root.require("module"), "module")) : Optional.empty();
        Optional<Mining> mining = Optional.empty();
        if (root.has("mining")) {
            Obj m = root.obj("mining", MINING_FIELDS);
            String unlock = unlockKey(m.require("unlock"), m.path("unlock"));
            mining = Optional.of(new Mining(unlock, TagKey.create(Registries.BLOCK, blockTag(m.require("blocks"), m.path("blocks")))));
        }
        return new PlanetDefinition(id, ResourceKey.create(Registries.DIMENSION, dimId), requires, outbound, returnTrip,
                radius, environment, gas, solar, module, mining);
    }

    private static List<String> requires(Obj root) {
        JsonArray raw = root.array("requires");
        if (raw.isEmpty() || raw.size() > REQUIRES_MAX) {
            throw new Invalid("requires : de 1 à " + REQUIRES_MAX + " clés attendues, " + raw.size() + " reçue(s)");
        }
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String key = unlockKey(raw.get(i), "requires[" + i + "]");
            if (keys.contains(key)) {
                throw new Invalid("requires[" + i + "] : clé « " + key + " » en double");
            }
            keys.add(key);
        }
        return keys;
    }

    private static List<FuelOption> options(Obj root, String key) {
        JsonArray raw = root.array(key);
        if (raw.isEmpty() || raw.size() > OPTIONS_MAX) {
            throw new Invalid(key + " : de 1 à " + OPTIONS_MAX + " options de carburant attendues, " + raw.size()
                    + " reçue(s)");
        }
        List<FuelOption> out = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            Obj o = Obj.of(raw.get(i), key + "[" + i + "].", OPTION_FIELDS);
            Item item = item(o.require("item"), o.path("item"));
            if (!seen.add(item)) {
                throw new Invalid(o.path("item") + " : objet « " + BuiltInRegistries.ITEM.getKey(item) + " » en double");
            }
            out.add(new FuelOption(item, o.intField("count", 1, COUNT_MAX)));
        }
        return out;
    }

    // ------------------------------------------------------------------ lecture JSON

    private static ResourceLocation location(JsonElement e, String where) {
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            throw new Invalid(where + " : identifiant attendu");
        }
        ResourceLocation id = ResourceLocation.tryParse(p.getAsString());
        if (id == null) {
            throw new Invalid(where + " : identifiant invalide « " + p.getAsString() + " »");
        }
        return id;
    }

    private static Item item(JsonElement e, String where) {
        ResourceLocation id = location(e, where);
        Item item = BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new Invalid(where + " : objet inconnu « " + id + " »"));
        if (item == Items.AIR) {
            throw new Invalid(where + " : « minecraft:air » interdit");
        }
        return item;
    }

    private static String unlockKey(JsonElement e, String where) {
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            throw new Invalid(where + " : clé de déblocage attendue (chaîne)");
        }
        String key = p.getAsString();
        if (!UNLOCK_KEY.matcher(key).matches()) {
            throw new Invalid(where + " : clé de déblocage « " + key + " » mal formée (identifiant pointé en minuscules)");
        }
        return key;
    }

    private static ResourceLocation blockTag(JsonElement e, String where) {
        if (!(e instanceof JsonPrimitive p) || !p.isString() || !p.getAsString().startsWith("#")) {
            throw new Invalid(where + " : tag de blocs « #espace:chemin » attendu");
        }
        ResourceLocation id = ResourceLocation.tryParse(p.getAsString().substring(1));
        if (id == null) {
            throw new Invalid(where + " : tag invalide « " + p.getAsString() + " »");
        }
        return id;
    }

    /** Erreur d'analyse (message en français, chemin du champ en tête). */
    private static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /** Objet JSON avec son chemin, dont les champs inconnus sont refusés. */
    private record Obj(JsonObject json, String prefix) {
        static Obj of(JsonElement e, String prefix, Set<String> fields) {
            if (e == null || !e.isJsonObject()) {
                throw new Invalid((prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1) + " : ") + "objet JSON attendu");
            }
            Set<String> unknown = new TreeSet<>(e.getAsJsonObject().keySet());
            unknown.removeAll(fields);
            if (!unknown.isEmpty()) {
                throw new Invalid(prefix + "champ(s) inconnu(s) : " + String.join(", ", unknown));
            }
            return new Obj(e.getAsJsonObject(), prefix);
        }

        String path(String key) {
            return prefix + key;
        }

        boolean has(String key) {
            return json.has(key) && !json.get(key).isJsonNull();
        }

        JsonElement require(String key) {
            if (!has(key)) {
                throw new Invalid(path(key) + " : champ obligatoire absent");
            }
            return json.get(key);
        }

        Obj obj(String key, Set<String> fields) {
            return of(require(key), path(key) + ".", fields);
        }

        JsonArray array(String key) {
            JsonElement e = require(key);
            if (!e.isJsonArray()) {
                throw new Invalid(path(key) + " : liste attendue");
            }
            return e.getAsJsonArray();
        }

        boolean bool(String key) {
            JsonElement e = require(key);
            if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) {
                throw new Invalid(path(key) + " : booléen attendu, reçu " + e);
            }
            return p.getAsBoolean();
        }

        int intField(String key, int lo, int hi) {
            return (int) longField(key, lo, hi);
        }

        long longField(String key, long lo, long hi) {
            JsonElement e = require(key);
            if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
                throw new Invalid(path(key) + " : nombre entier attendu, reçu " + e);
            }
            java.math.BigDecimal v = p.getAsBigDecimal();
            if (v.stripTrailingZeros().scale() > 0 || v.compareTo(java.math.BigDecimal.valueOf(lo)) < 0
                    || v.compareTo(java.math.BigDecimal.valueOf(hi)) > 0) {
                throw new Invalid(path(key) + " : entier de " + lo + " à " + hi + " attendu, reçu " + e);
            }
            return v.longValueExact();
        }

        double optDouble(String key, double def, double lo, double hi) {
            if (!has(key)) {
                return def;
            }
            JsonElement e = json.get(key);
            if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
                throw new Invalid(path(key) + " : nombre attendu, reçu " + e);
            }
            double v = p.getAsDouble();
            if (!Double.isFinite(v) || v < lo || v > hi) {
                throw new Invalid(path(key) + " : nombre de " + lo + " à " + hi + " attendu, reçu " + e);
            }
            return v;
        }
    }
}
