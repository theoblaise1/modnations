// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.contract;

import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Contrat du tableau d'une nation (offre ou contrat en cours) : instantané complet du modèle au moment de la génération
 * (cible, quantité, récompenses déjà tirées, délai), plus l'état d'avancement. Il ne dépend jamais du registre de
 * modèles vivant : un {@code /reload} qui modifie ou retire le modèle n'a aucun effet sur lui.
 * <p>
 * NBT (docs/conception/J4.md §2.1) : {@code {id, tpl, kind, target{…}, minDist, amount, progress, origin?, visited?,
 * money, points, keys:int[4], items, offeredAt, accepted, deadline, by?, contrib, dur}}.
 */
public final class ContractInstance {
    /** Avant-postes distincts mémorisés par un contrat d'exploration. */
    public static final int MAX_VISITED = 8;
    public static final int KEY_KINDS = CrateKind.values().length;
    /** Contributeurs mémorisés au plus (au-delà, les nouveaux ne sont plus comptés : borne de la sauvegarde). */
    public static final int MAX_CONTRIBUTORS = 64;

    /** Nature de la cible ; l'identifiant est la clé du compound {@code target}. */
    public enum TargetType {
        ITEM("item"),
        ENTITY("entity"),
        ENTITY_TAG("tag"),
        BIOME("biome"),
        BIOME_TAG("biomeTag"),
        WAYPOINT("waypoint"),
        OUTPOSTS("outposts"),
        DIMENSION("dimension");

        private final String key;

        TargetType(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /**
     * Cible figée à la génération.
     *
     * @param id          objet, entité, tag, biome ou dimension ({@code null} pour un point de passage ou des avant-postes)
     * @param minDistance distance minimale à l'origine (biome)
     * @param min         distance minimale du point de passage à l'origine
     * @param max         distance maximale du point de passage à l'origine
     * @param radius      rayon de réussite autour du point de passage
     */
    public record Target(TargetType type, @Nullable ResourceLocation id, int minDistance, int min, int max, int radius) {
        public static Target item(Item item) {
            return new Target(TargetType.ITEM, BuiltInRegistries.ITEM.getKey(item), 0, 0, 0, 0);
        }

        public static Target entity(ResourceLocation id, boolean tag) {
            return new Target(tag ? TargetType.ENTITY_TAG : TargetType.ENTITY, id, 0, 0, 0, 0);
        }

        public static Target biome(ResourceLocation id, boolean tag, int minDistance) {
            return new Target(tag ? TargetType.BIOME_TAG : TargetType.BIOME, id, Math.max(0, minDistance), 0, 0, 0);
        }

        public static Target waypoint(int min, int max, int radius) {
            return new Target(TargetType.WAYPOINT, null, 0, min, max, radius);
        }

        public static Target outposts() {
            return new Target(TargetType.OUTPOSTS, null, 0, 0, 0, 0);
        }

        public static Target dimension(ResourceLocation id) {
            return new Target(TargetType.DIMENSION, id, 0, 0, 0, 0);
        }

        /** Cible d'un objectif de modèle. */
        public static Target of(ContractObjective objective) {
            return switch (objective) {
                case ContractObjective.Deliver d -> item(d.item());
                case ContractObjective.Kill k -> entity(k.entity().id(), k.entity().tag());
                case ContractObjective.Explore e -> switch (e.target()) {
                    case ContractObjective.BiomeTarget b -> biome(b.biome().id(), b.biome().tag(), b.minDistance());
                    case ContractObjective.WaypointTarget w -> waypoint(w.min(), w.max(), w.radius());
                    case ContractObjective.OutpostsTarget o -> outposts();
                    case ContractObjective.DimensionTarget dim -> dimension(dim.dimension());
                };
            };
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            if (type == TargetType.WAYPOINT) {
                CompoundTag w = new CompoundTag();
                w.putInt("min", min);
                w.putInt("max", max);
                w.putInt("r", radius);
                tag.put(type.key(), w);
            } else if (type == TargetType.OUTPOSTS) {
                tag.putBoolean(type.key(), true);
            } else if (id != null) {
                tag.putString(type.key(), id.toString());
            }
            return tag;
        }

        @Nullable
        static Target load(CompoundTag tag, int minDistance) {
            for (TargetType type : TargetType.values()) {
                if (!tag.contains(type.key())) {
                    continue;
                }
                if (type == TargetType.WAYPOINT) {
                    CompoundTag w = tag.getCompound(type.key());
                    return waypoint(w.getInt("min"), w.getInt("max"), w.getInt("r"));
                }
                if (type == TargetType.OUTPOSTS) {
                    return outposts();
                }
                ResourceLocation id = ResourceLocation.tryParse(tag.getString(type.key()));
                return id == null ? null : new Target(type, id, type == TargetType.BIOME || type == TargetType.BIOME_TAG
                        ? Math.max(0, minDistance) : 0, 0, 0, 0);
            }
            return null;
        }
    }

    /** Position horizontale dans une dimension (origine d'une exploration, point de passage). */
    public record Spot(ResourceKey<Level> dimension, int x, int z) {
        public long distanceSqr(double px, double pz) {
            double dx = px - x;
            double dz = pz - z;
            return (long) Math.floor(dx * dx + dz * dz);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dim", dimension.location().toString());
            tag.putInt("x", x);
            tag.putInt("z", z);
            return tag;
        }

        @Nullable
        static Spot load(CompoundTag tag) {
            ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dim"));
            return dim == null ? null : new Spot(ResourceKey.create(Registries.DIMENSION, dim), tag.getInt("x"), tag.getInt("z"));
        }
    }

    private final int id;
    private final ResourceLocation template;
    private final ContractKind kind;
    private final Target target;
    private final int amount;
    private final long money;
    private final long points;
    private final int[] keys;
    private final List<ContractTemplate.ItemReward> items;
    private final int durationHours;
    private final long offeredAt;

    private int progress;
    private long acceptedAt;
    private long deadline;
    @Nullable
    private UUID acceptedBy;
    @Nullable
    private Spot origin;
    @Nullable
    private Spot waypoint;
    private final List<UUID> visited = new ArrayList<>();
    private final LinkedHashMap<UUID, Integer> contributions = new LinkedHashMap<>();

    @Nullable
    private TagKey<EntityType<?>> entityTag;
    @Nullable
    private TagKey<Biome> biomeTag;

    /**
     * @param keys          clés déjà tirées, par ordinal de {@link CrateKind} (copiées)
     * @param durationHours délai accordé à l'acceptation
     */
    public ContractInstance(int id, ResourceLocation template, ContractKind kind, Target target, int amount, long money,
                            long points, int[] keys, List<ContractTemplate.ItemReward> items, int durationHours, long offeredAt) {
        this.id = id;
        this.template = template;
        this.kind = kind;
        this.target = target;
        this.amount = Math.max(1, amount);
        this.money = Math.max(0L, money);
        this.points = Math.max(0L, points);
        this.keys = new int[KEY_KINDS];
        for (int i = 0; i < KEY_KINDS && i < keys.length; i++) {
            this.keys[i] = Math.max(0, keys[i]);
        }
        this.items = List.copyOf(items);
        this.durationHours = Math.max(1, durationHours);
        this.offeredAt = offeredAt;
    }

    // ------------------------------------------------------------------ instantané

    public int id() {
        return id;
    }

    public ResourceLocation template() {
        return template;
    }

    public ContractKind kind() {
        return kind;
    }

    public Target target() {
        return target;
    }

    public int amount() {
        return amount;
    }

    public long money() {
        return money;
    }

    public long points() {
        return points;
    }

    /** Clés par ordinal de {@link CrateKind} (copie). */
    public int[] keys() {
        return keys.clone();
    }

    public int keys(CrateKind kind) {
        return keys[kind.ordinal()];
    }

    public boolean hasKeys() {
        return Arrays.stream(keys).anyMatch(k -> k > 0);
    }

    public List<ContractTemplate.ItemReward> items() {
        return items;
    }

    public int durationHours() {
        return durationHours;
    }

    public long durationMs() {
        return durationHours * 3_600_000L;
    }

    public long offeredAt() {
        return offeredAt;
    }

    // ------------------------------------------------------------------ avancement

    public int progress() {
        return progress;
    }

    public int remaining() {
        return Math.max(0, amount - progress);
    }

    public boolean isFull() {
        return progress >= amount;
    }

    /** Ajoute au plus {@code n} unités (bornées au reste) ; renvoie les unités réellement ajoutées. */
    public int advance(int n) {
        int added = Math.min(Math.max(0, n), remaining());
        progress += added;
        return added;
    }

    public long acceptedAt() {
        return acceptedAt;
    }

    public long deadline() {
        return deadline;
    }

    @Nullable
    public UUID acceptedBy() {
        return acceptedBy;
    }

    /** Passage à l'état « en cours » : échéance {@code now + délai}. */
    public void accept(long now, @Nullable UUID by) {
        acceptedAt = now;
        deadline = now + durationMs();
        acceptedBy = by;
    }

    /** Échéance ramenée (administration, GameTests). */
    public void setDeadline(long deadline) {
        this.deadline = deadline;
    }

    @Nullable
    public Spot origin() {
        return origin;
    }

    public void setOrigin(@Nullable Spot origin) {
        this.origin = origin;
    }

    @Nullable
    public Spot waypoint() {
        return waypoint;
    }

    public void setWaypoint(@Nullable Spot waypoint) {
        this.waypoint = waypoint;
    }

    public List<UUID> visited() {
        return Collections.unmodifiableList(visited);
    }

    /** Mémorise un avant-poste visité ; faux s'il l'était déjà (ou si la liste est pleine). */
    public boolean visit(UUID outpost) {
        if (visited.contains(outpost) || visited.size() >= MAX_VISITED) {
            return false;
        }
        visited.add(outpost);
        return true;
    }

    // ------------------------------------------------------------------ contributions

    public Map<UUID, Integer> contributions() {
        return Collections.unmodifiableMap(contributions);
    }

    public int contribution(UUID player) {
        return contributions.getOrDefault(player, 0);
    }

    public void contribute(UUID player, int n) {
        if (n <= 0 || (!contributions.containsKey(player) && contributions.size() >= MAX_CONTRIBUTORS)) {
            return;
        }
        contributions.merge(player, n, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
    }

    /** Plus gros contributeur parmi ceux retenus (à égalité, le premier arrivé), ou {@code null}. */
    @Nullable
    public UUID topContributor(Predicate<UUID> eligible) {
        UUID best = null;
        int bestN = 0;
        for (Map.Entry<UUID, Integer> e : contributions.entrySet()) {
            if (e.getValue() > bestN && eligible.test(e.getKey())) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ cibles

    /** Objet à livrer ({@code minecraft:air} si l'objet a disparu du registre). */
    public Item item() {
        return target.type() == TargetType.ITEM && target.id() != null
                ? BuiltInRegistries.ITEM.getOptional(target.id()).orElse(Items.AIR) : Items.AIR;
    }

    /** Vrai si ce type d'entité est la cible (type exact ou membre du tag). */
    public boolean matchesEntity(EntityType<?> type) {
        if (target.id() == null) {
            return false;
        }
        if (target.type() == TargetType.ENTITY) {
            return BuiltInRegistries.ENTITY_TYPE.getKey(type).equals(target.id());
        }
        if (target.type() == TargetType.ENTITY_TAG) {
            if (entityTag == null) {
                entityTag = TagKey.create(Registries.ENTITY_TYPE, target.id());
            }
            return type.is(entityTag);
        }
        return false;
    }

    /** Vrai si ce biome est la cible (biome exact ou membre du tag). */
    public boolean matchesBiome(Holder<Biome> biome) {
        if (target.id() == null) {
            return false;
        }
        if (target.type() == TargetType.BIOME) {
            return biome.is(target.id());
        }
        if (target.type() == TargetType.BIOME_TAG) {
            if (biomeTag == null) {
                biomeTag = TagKey.create(Registries.BIOME, target.id());
            }
            return biome.is(biomeTag);
        }
        return false;
    }

    // ------------------------------------------------------------------ NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("tpl", template.toString());
        tag.putString("kind", kind.id());
        tag.put("target", target.save());
        tag.putInt("minDist", target.minDistance());
        tag.putInt("amount", amount);
        tag.putInt("progress", progress);
        if (origin != null) {
            tag.put("origin", origin.save());
        }
        if (waypoint != null) {
            tag.put("waypoint", waypoint.save());
        }
        if (!visited.isEmpty()) {
            ListTag list = new ListTag();
            for (UUID u : visited) {
                list.add(NbtUtils.createUUID(u));
            }
            tag.put("visited", list);
        }
        tag.putLong("money", money);
        tag.putLong("points", points);
        tag.putIntArray("keys", keys.clone());
        ListTag itemList = new ListTag();
        for (ContractTemplate.ItemReward item : items) {
            CompoundTag i = new CompoundTag();
            i.putString("item", BuiltInRegistries.ITEM.getKey(item.item()).toString());
            i.putInt("count", item.count());
            itemList.add(i);
        }
        tag.put("items", itemList);
        tag.putInt("dur", durationHours);
        tag.putLong("offeredAt", offeredAt);
        tag.putLong("accepted", acceptedAt);
        tag.putLong("deadline", deadline);
        if (acceptedBy != null) {
            tag.putUUID("by", acceptedBy);
        }
        ListTag contrib = new ListTag();
        for (Map.Entry<UUID, Integer> e : contributions.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("u", e.getKey());
            c.putInt("n", e.getValue());
            contrib.add(c);
        }
        tag.put("contrib", contrib);
        return tag;
    }

    /** Relit un contrat ; {@code null} si le NBT est inutilisable (type ou cible inconnus). */
    @Nullable
    public static ContractInstance load(CompoundTag tag) {
        ContractKind kind = ContractKind.byId(tag.getString("kind"));
        ResourceLocation template = ResourceLocation.tryParse(tag.getString("tpl"));
        Target target = Target.load(tag.getCompound("target"), tag.getInt("minDist"));
        if (kind == null || template == null || target == null) {
            return null;
        }
        List<ContractTemplate.ItemReward> items = new ArrayList<>();
        ListTag itemList = tag.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < itemList.size(); i++) {
            CompoundTag it = itemList.getCompound(i);
            ResourceLocation itemId = ResourceLocation.tryParse(it.getString("item"));
            Item item = itemId == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(itemId).orElse(Items.AIR);
            if (item != Items.AIR && it.getInt("count") > 0) {
                items.add(new ContractTemplate.ItemReward(item, it.getInt("count")));
            }
        }
        ContractInstance c = new ContractInstance(tag.getInt("id"), template, kind, target, tag.getInt("amount"),
                tag.getLong("money"), tag.getLong("points"), tag.getIntArray("keys"), items, tag.getInt("dur"),
                tag.getLong("offeredAt"));
        c.progress = Math.max(0, Math.min(c.amount, tag.getInt("progress")));
        c.acceptedAt = tag.getLong("accepted");
        c.deadline = tag.getLong("deadline");
        c.acceptedBy = tag.hasUUID("by") ? tag.getUUID("by") : null;
        c.origin = tag.contains("origin", Tag.TAG_COMPOUND) ? Spot.load(tag.getCompound("origin")) : null;
        c.waypoint = tag.contains("waypoint", Tag.TAG_COMPOUND) ? Spot.load(tag.getCompound("waypoint")) : null;
        ListTag visited = tag.getList("visited", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < visited.size() && c.visited.size() < MAX_VISITED; i++) {
            UUID u = NbtUtils.loadUUID(visited.get(i));
            if (!c.visited.contains(u)) {
                c.visited.add(u);
            }
        }
        ListTag contrib = tag.getList("contrib", Tag.TAG_COMPOUND);
        for (int i = 0; i < contrib.size(); i++) {
            CompoundTag e = contrib.getCompound(i);
            if (e.hasUUID("u")) {
                c.contribute(e.getUUID("u"), e.getInt("n"));
            }
        }
        return c;
    }

    @Override
    public String toString() {
        return "Contrat n°" + id + " (" + template + ", " + progress + "/" + amount + ")";
    }
}
