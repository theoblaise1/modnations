// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Modèle de contrat : contenu d'un fichier {@code data/<ns>/contracts/<id>.json} (docs/conception/J4.md §2.3).
 * <p>
 * L'analyse est stricte : champ inconnu, champ obligatoire absent, type ou borne invalide, objectif incohérent avec
 * {@code kind} ou exploration à zéro ou plusieurs cibles font rejeter le fichier entier (journal en français). Les
 * bornes sont des constantes de code (§1.11), jamais lues dans {@code SFConfig} : la configuration du serveur n'est pas
 * encore chargée au premier chargement des datapacks. Les validations qui exigent les tags liés (cible
 * {@code MONSTER}, tags non vides, objets interdits, prérequis de recherche) sont faites par
 * {@link ContractRegistry#validated}. {@code tools/economy_checks/contracts.py} applique les mêmes règles en CI.
 *
 * @param weight        poids du tirage parmi les modèles éligibles
 * @param requires      paliers de recherche à terminer pour que le modèle soit proposé
 * @param minPopulation population effective minimale de la nation
 * @param durationHours délai accordé après l'acceptation (heures réelles)
 */
public record ContractTemplate(ResourceLocation id, ContractKind kind, int weight, List<ResourceLocation> requires,
                               int minPopulation, int durationHours, ContractObjective objective, Reward reward) {
    public static final int WEIGHT_MAX = 10_000;
    public static final int DURATION_HOURS_MAX = 720;
    public static final int MIN_POPULATION_MAX = 10_000;
    public static final int AMOUNT_MAX = 10_000;
    public static final int DISTANCE_MAX = 1_000_000;
    public static final int WAYPOINT_MAX = 100_000;
    public static final int RADIUS_MAX = 1_024;
    public static final long MONEY_MAX = 1_000_000L;
    /** Plafond de la part « par unité » : {@code money_per_unit × max × 2,5}. */
    public static final double MONEY_PER_UNIT_CAP = 100_000.0D;
    /** Plafond des points d'un contrat : {@code points + points_per_unit × max × 2,5}. */
    public static final long POINTS_MAX = 1_000L;
    /** Multiplicateur de taille maximal pris en compte par les plafonds (§1.11). */
    public static final double SIZE_CAP_BOUND = 2.5D;
    public static final int MAX_KEYS = CrateKind.values().length;
    public static final int KEY_COUNT_MAX = 64;
    public static final int MAX_ITEMS = 8;
    public static final int ITEM_COUNT_MAX = 64;

    static final Set<String> FIELDS = Set.of("kind", "weight", "requires", "min_population", "duration_hours", "objective", "reward");
    static final Set<String> DELIVER_FIELDS = Set.of("item", "amount");
    static final Set<String> KILL_FIELDS = Set.of("entity", "amount");
    static final Set<String> EXPLORE_FIELDS = Set.of("biome", "min_distance", "waypoint", "outposts", "dimension");
    static final Set<String> AMOUNT_FIELDS = Set.of("min", "max", "step");
    static final Set<String> WAYPOINT_FIELDS = Set.of("min", "max", "radius");
    static final Set<String> OUTPOSTS_FIELDS = Set.of("min", "max");
    static final Set<String> REWARD_FIELDS = Set.of("money", "money_per_unit", "points", "points_per_unit", "keys", "items");
    static final Set<String> KEY_FIELDS = Set.of("crate", "count", "chance");
    static final Set<String> ITEM_FIELDS = Set.of("item", "count");

    /**
     * Récompense : argent {@code M = round(money × (1 + 0,02 × paliers terminés)) + round(money_per_unit × quantité)},
     * points {@code points + floor(points_per_unit × quantité)}, clés tirées à la génération, objets fixes.
     */
    public record Reward(long money, double moneyPerUnit, long points, double pointsPerUnit, List<KeyChance> keys,
                         List<ItemReward> items) {
        public Reward {
            keys = List.copyOf(keys);
            items = List.copyOf(items);
        }
    }

    /** {@code count} clés du type {@code crate}, accordées avec la probabilité {@code chance} (tirée à la génération). */
    public record KeyChance(CrateKind crate, int count, double chance) {
    }

    /** Objet remis à la réussite (jamais d'objet de {@code #interdit_caisses}). */
    public record ItemReward(Item item, int count) {
    }

    public ContractTemplate {
        requires = List.copyOf(requires);
    }

    /** Clé de traduction du nom : {@code sovereignfrontier.contract.template.<chemin>} ({@code /} → {@code .}). */
    public static String translationKey(ResourceLocation template) {
        return "contract.template." + template.getPath().replace('/', '.');
    }

    /** Nom affiché d'un modèle (identifiant brut si aucune traduction : modèle d'un datapack tiers). */
    public static MutableComponent displayName(ResourceLocation template) {
        return Component.translatableWithFallback(Msg.key(translationKey(template)), template.toString());
    }

    public MutableComponent displayName() {
        return displayName(id);
    }

    /** Quantité maximale avant mise à l'échelle par le nombre de membres. */
    public int maxAmount() {
        return objective.amount().max();
    }

    /** Quantité maximale possible après mise à l'échelle (borne des plafonds de récompense). */
    public double maxScaledAmount() {
        return objective.scalesWithMembers() ? maxAmount() * SIZE_CAP_BOUND : maxAmount();
    }

    // ------------------------------------------------------------------ analyse

    /** Analyse stricte d'un fichier ; l'erreur décrit le premier problème rencontré. */
    public static DataResult<ContractTemplate> parse(ResourceLocation id, JsonElement json) {
        try {
            return DataResult.success(read(id, json));
        } catch (Invalid e) {
            return DataResult.error(e::getMessage);
        }
    }

    private static ContractTemplate read(ResourceLocation id, JsonElement json) {
        Obj root = Obj.of(json, "", FIELDS);
        String rawKind = root.string("kind");
        ContractKind kind = ContractKind.byId(rawKind);
        if (kind == null) {
            throw new Invalid("kind : type inconnu « " + rawKind + " » (deliver, kill ou explore)");
        }
        int weight = root.optInt("weight", 1, 1, WEIGHT_MAX);
        List<ResourceLocation> requires = new ArrayList<>();
        JsonArray reqs = root.optArray("requires");
        for (int i = 0; i < reqs.size(); i++) {
            requires.add(location(reqs.get(i), "requires[" + i + "]"));
        }
        int minPopulation = root.optInt("min_population", 0, 0, MIN_POPULATION_MAX);
        int duration = root.intField("duration_hours", 1, DURATION_HOURS_MAX);
        ContractObjective objective = switch (kind) {
            case DELIVER -> deliver(root.obj("objective", DELIVER_FIELDS));
            case KILL -> kill(root.obj("objective", KILL_FIELDS));
            case EXPLORE -> explore(root.obj("objective", EXPLORE_FIELDS));
        };
        ContractTemplate template = new ContractTemplate(id, kind, weight, requires, minPopulation, duration, objective,
                reward(root.obj("reward", REWARD_FIELDS)));
        checkCaps(template);
        return template;
    }

    private static ContractObjective.Deliver deliver(Obj o) {
        ResourceLocation itemId = location(o.require("item"), o.path("item"));
        Item item = BuiltInRegistries.ITEM.getOptional(itemId)
                .orElseThrow(() -> new Invalid(o.path("item") + " : objet inconnu « " + itemId + " »"));
        if (item == Items.AIR) {
            throw new Invalid(o.path("item") + " : « minecraft:air » interdit");
        }
        return new ContractObjective.Deliver(item, amount(o.obj("amount", AMOUNT_FIELDS)));
    }

    private static ContractObjective.Kill kill(Obj o) {
        ExtraCodecs.TagOrElementLocation entity = tagOrElement(o.require("entity"), o.path("entity"));
        if (!entity.tag() && !BuiltInRegistries.ENTITY_TYPE.containsKey(entity.id())) {
            throw new Invalid(o.path("entity") + " : type d'entité inconnu « " + entity.id() + " »");
        }
        return new ContractObjective.Kill(entity, amount(o.obj("amount", AMOUNT_FIELDS)));
    }

    private static ContractObjective.Explore explore(Obj o) {
        List<String> targets = new ArrayList<>();
        for (String key : List.of("biome", "waypoint", "outposts", "dimension")) {
            if (o.has(key)) {
                targets.add(key);
            }
        }
        if (targets.size() != 1) {
            throw new Invalid(o.here() + "exactement une cible d'exploration attendue (biome, waypoint, outposts ou "
                    + "dimension), " + targets.size() + " trouvée(s)" + (targets.isEmpty() ? "" : " : " + String.join(", ", targets)));
        }
        if (o.has("min_distance") && !o.has("biome")) {
            throw new Invalid(o.path("min_distance") + " : réservé à la cible « biome »");
        }
        ContractObjective.ExploreTarget target = switch (targets.get(0)) {
            case "biome" -> new ContractObjective.BiomeTarget(tagOrElement(o.require("biome"), o.path("biome")),
                    o.optInt("min_distance", 0, 0, DISTANCE_MAX));
            case "waypoint" -> {
                Obj w = o.obj("waypoint", WAYPOINT_FIELDS);
                int min = w.intField("min", 1, WAYPOINT_MAX);
                int max = w.intField("max", min, WAYPOINT_MAX);
                yield new ContractObjective.WaypointTarget(min, max, w.intField("radius", 1, RADIUS_MAX));
            }
            case "outposts" -> {
                Obj p = o.obj("outposts", OUTPOSTS_FIELDS);
                int min = p.intField("min", 1, ContractInstance.MAX_VISITED);
                yield new ContractObjective.OutpostsTarget(min, p.intField("max", min, ContractInstance.MAX_VISITED));
            }
            default -> new ContractObjective.DimensionTarget(location(o.require("dimension"), o.path("dimension")));
        };
        return new ContractObjective.Explore(target);
    }

    private static ContractObjective.Amount amount(Obj o) {
        int min = o.intField("min", 1, AMOUNT_MAX);
        int max = o.intField("max", min, AMOUNT_MAX);
        int step = o.optInt("step", 1, 1, AMOUNT_MAX);
        return new ContractObjective.Amount(min, max, step);
    }

    private static Reward reward(Obj o) {
        long money = o.optInt("money", 0, 0, (int) MONEY_MAX);
        double moneyPerUnit = o.optDouble("money_per_unit", 0.0D, 0.0D, MONEY_PER_UNIT_CAP);
        long points = o.optInt("points", 0, 0, (int) POINTS_MAX);
        double pointsPerUnit = o.optDouble("points_per_unit", 0.0D, 0.0D, POINTS_MAX);
        List<KeyChance> keys = new ArrayList<>();
        JsonArray rawKeys = o.optArray("keys");
        if (rawKeys.size() > MAX_KEYS) {
            throw new Invalid(o.path("keys") + " : " + rawKeys.size() + " entrées (maximum " + MAX_KEYS + ")");
        }
        for (int i = 0; i < rawKeys.size(); i++) {
            Obj k = Obj.of(rawKeys.get(i), o.path("keys[" + i + "]") + ".", KEY_FIELDS);
            String crateId = k.string("crate");
            CrateKind crate = CrateKind.byId(crateId);
            if (crate == null || !crate.id().equals(crateId)) {
                throw new Invalid(k.path("crate") + " : type de caisse inconnu « " + crateId + " »");
            }
            keys.add(new KeyChance(crate, k.optInt("count", 1, 1, KEY_COUNT_MAX), k.optDouble("chance", 1.0D, 0.0D, 1.0D)));
            if (keys.get(i).chance() <= 0.0D) {
                throw new Invalid(k.path("chance") + " : doit être strictement positive");
            }
        }
        List<ItemReward> items = new ArrayList<>();
        JsonArray rawItems = o.optArray("items");
        if (rawItems.size() > MAX_ITEMS) {
            throw new Invalid(o.path("items") + " : " + rawItems.size() + " entrées (maximum " + MAX_ITEMS + ")");
        }
        for (int i = 0; i < rawItems.size(); i++) {
            Obj it = Obj.of(rawItems.get(i), o.path("items[" + i + "]") + ".", ITEM_FIELDS);
            ResourceLocation itemId = location(it.require("item"), it.path("item"));
            Item item = BuiltInRegistries.ITEM.getOptional(itemId)
                    .orElseThrow(() -> new Invalid(it.path("item") + " : objet inconnu « " + itemId + " »"));
            if (item == Items.AIR) {
                throw new Invalid(it.path("item") + " : « minecraft:air » interdit");
            }
            if (CrateKind.byKey(item) != null) {
                throw new Invalid(it.path("item") + " : clé de caisse « " + itemId + " » : utiliser « keys »");
            }
            for (ItemReward previous : items) {
                if (previous.item() == item) {
                    throw new Invalid(it.path("item") + " : objet en double « " + itemId + " »");
                }
            }
            items.add(new ItemReward(item, it.optInt("count", 1, 1, ITEM_COUNT_MAX)));
        }
        if (money == 0L && moneyPerUnit == 0.0D && points == 0L && pointsPerUnit == 0.0D && keys.isEmpty() && items.isEmpty()) {
            throw new Invalid("reward : récompense vide (ni argent, ni points, ni clés, ni objets)");
        }
        return new Reward(money, moneyPerUnit, points, pointsPerUnit, keys, items);
    }

    /** Plafonds §1.11 : part par unité et points, à la taille maximale possible. */
    private static void checkCaps(ContractTemplate t) {
        double scaled = t.maxScaledAmount();
        if (t.reward.moneyPerUnit() * scaled > MONEY_PER_UNIT_CAP + 1e-9D) {
            throw new Invalid("reward.money_per_unit : " + t.reward.moneyPerUnit() + " × " + scaled + " dépasse "
                    + (long) MONEY_PER_UNIT_CAP + " cr");
        }
        if (t.reward.points() + t.reward.pointsPerUnit() * scaled > POINTS_MAX + 1e-9D) {
            throw new Invalid("reward.points : jusqu'à " + (t.reward.points() + t.reward.pointsPerUnit() * scaled)
                    + " points (maximum " + POINTS_MAX + ")");
        }
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

    private static ExtraCodecs.TagOrElementLocation tagOrElement(JsonElement e, String where) {
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            throw new Invalid(where + " : identifiant ou #tag attendu");
        }
        String raw = p.getAsString();
        boolean tag = raw.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? raw.substring(1) : raw);
        if (id == null) {
            throw new Invalid(where + " : identifiant invalide « " + raw + " »");
        }
        return new ExtraCodecs.TagOrElementLocation(id, tag);
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

        /** « objective : » (début de message pour l'objet lui-même). */
        String here() {
            return prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1) + " : ";
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

        String string(String key) {
            JsonElement e = require(key);
            if (!(e instanceof JsonPrimitive p) || !p.isString()) {
                throw new Invalid(path(key) + " : chaîne attendue");
            }
            return p.getAsString();
        }

        JsonArray optArray(String key) {
            if (!has(key)) {
                return new JsonArray();
            }
            if (!json.get(key).isJsonArray()) {
                throw new Invalid(path(key) + " : liste attendue");
            }
            return json.getAsJsonArray(key);
        }

        int intField(String key, int lo, int hi) {
            JsonElement e = require(key);
            double v = number(key, e);
            if (v != Math.floor(v) || v < lo || v > hi) {
                throw new Invalid(path(key) + " : entier de " + lo + " à " + hi + " attendu, reçu " + e);
            }
            return (int) v;
        }

        int optInt(String key, int def, int lo, int hi) {
            return has(key) ? intField(key, lo, hi) : def;
        }

        double optDouble(String key, double def, double lo, double hi) {
            if (!has(key)) {
                return def;
            }
            JsonElement e = json.get(key);
            double v = number(key, e);
            if (v < lo || v > hi) {
                throw new Invalid(path(key) + " : nombre de " + lo + " à " + hi + " attendu, reçu " + e);
            }
            return v;
        }

        private double number(String key, JsonElement e) {
            if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
                throw new Invalid(path(key) + " : nombre attendu, reçu " + e);
            }
            double v = p.getAsDouble();
            if (!Double.isFinite(v)) {
                throw new Invalid(path(key) + " : nombre fini attendu");
            }
            return v;
        }
    }
}
