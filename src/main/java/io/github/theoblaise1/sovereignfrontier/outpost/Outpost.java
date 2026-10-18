package io.github.theoblaise1.sovereignfrontier.outpost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Un avant-poste marchand enregistré (ancre : le comptoir). Son étal est un <b>instantané</b> tiré à chaque rotation
 * depuis son pool : prix et rachats de base, stock et demande restants. La pression ({@code b} = lots achetés par les
 * joueurs, {@code s} = lots vendus) est propre à l'avant-poste et à l'objet, amortie à chaque rotation.
 * Persisté dans {@link OutpostData}.
 */
public final class Outpost {
    /** Faction des avant-postes du J4 (crochet J7 : nations PNJ). */
    public static final String NEUTRAL_FACTION = "sovereignfrontier:neutre";

    private final UUID id;
    private final ResourceKey<Level> dimension;
    private final BlockPos pos;
    private Direction facing;
    private ResourceLocation pool;
    private String variant;
    private String faction = NEUTRAL_FACTION;
    @Nullable
    private UUID merchant;
    private final long created;
    /** Rotation à laquelle l'étal a été tiré ({@code -1} : jamais). */
    private long rotation = -1L;
    /** Réassorts forcés par un administrateur pendant la rotation courante (modifie la graine du tirage). */
    private int forced;
    private final List<Slot> offers = new ArrayList<>();
    private final Map<Item, Pressure> pressure = new LinkedHashMap<>();
    private final Map<UUID, Grievance> grievances = new LinkedHashMap<>();

    public Outpost(UUID id, ResourceKey<Level> dimension, BlockPos pos, Direction facing, ResourceLocation pool,
                   String variant, long created) {
        this.id = id;
        this.dimension = dimension;
        this.pos = pos.immutable();
        this.facing = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        this.pool = pool;
        this.variant = variant;
        this.created = created;
    }

    /** Une offre de l'étal courant. */
    public static final class Slot {
        private final Item item;
        private final int count;
        private final long price;
        private final long payout;
        private int stockLeft;
        private int demandLeft;

        public Slot(Item item, int count, long price, long payout, int stockLeft, int demandLeft) {
            this.item = item;
            this.count = count;
            this.price = price;
            this.payout = payout;
            this.stockLeft = Math.max(0, stockLeft);
            this.demandLeft = Math.max(0, demandLeft);
        }

        public Item item() {
            return item;
        }

        public int count() {
            return count;
        }

        /** Prix de base d'un lot (0 = non vendu). */
        public long price() {
            return price;
        }

        /** Rachat de base d'un lot (0 = non racheté). */
        public long payout() {
            return payout;
        }

        public int stockLeft() {
            return stockLeft;
        }

        public int demandLeft() {
            return demandLeft;
        }

        public void setStockLeft(int value) {
            stockLeft = Math.max(0, value);
        }

        public void setDemandLeft(int value) {
            demandLeft = Math.max(0, value);
        }
    }

    /** Pression d'achat ({@code b}) et de vente ({@code s}) d'un objet. */
    public static final class Pressure {
        double b;
        double s;

        public double b() {
            return b;
        }

        public double s() {
            return s;
        }
    }

    /** Griefs d'une nation envers cet avant-poste (crochet J7) : nombre de pillages et date du dernier. */
    public record Grievance(int count, long last) {
    }

    // ------------------------------------------------------------------ accès

    public UUID id() {
        return id;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** Position du comptoir (ancre). */
    public BlockPos pos() {
        return pos;
    }

    public GlobalPos anchor() {
        return GlobalPos.of(dimension, pos);
    }

    public Direction facing() {
        return facing;
    }

    void setFacing(Direction facing) {
        if (facing.getAxis().isHorizontal()) {
            this.facing = facing;
        }
    }

    /** Poste du marchand : derrière le comptoir, du côté opposé à sa face. */
    public BlockPos post() {
        return pos.relative(facing.getOpposite());
    }

    public ResourceLocation pool() {
        return pool;
    }

    void setPool(ResourceLocation pool, String variant) {
        this.pool = pool;
        this.variant = variant;
    }

    public String variant() {
        return variant;
    }

    public String faction() {
        return faction;
    }

    @Nullable
    public UUID merchant() {
        return merchant;
    }

    public void setMerchant(@Nullable UUID merchant) {
        this.merchant = merchant;
    }

    public long created() {
        return created;
    }

    public long rotation() {
        return rotation;
    }

    void setRotation(long rotation) {
        this.rotation = rotation;
    }

    public int forced() {
        return forced;
    }

    void setForced(int forced) {
        this.forced = Math.max(0, forced);
    }

    public List<Slot> offers() {
        return Collections.unmodifiableList(offers);
    }

    void replaceOffers(List<Slot> slots) {
        offers.clear();
        offers.addAll(slots);
    }

    @Nullable
    public Slot slot(Item item) {
        for (Slot slot : offers) {
            if (slot.item == item) {
                return slot;
            }
        }
        return null;
    }

    public double buyPressure(Item item) {
        Pressure p = pressure.get(item);
        return p == null ? 0.0D : p.b;
    }

    public double sellPressure(Item item) {
        Pressure p = pressure.get(item);
        return p == null ? 0.0D : p.s;
    }

    public Map<Item, Pressure> pressure() {
        return Collections.unmodifiableMap(pressure);
    }

    /** Ajoute des lots à la pression d'achat ({@code buy}) ou de vente. */
    public void addPressure(Item item, boolean buy, double lots) {
        Pressure p = pressure.computeIfAbsent(item, i -> new Pressure());
        if (buy) {
            p.b += lots;
        } else {
            p.s += lots;
        }
    }

    /** Multiplie toute la pression par {@code factor} ; les valeurs sous {@link MarketRules#MIN_PRESSURE} sont oubliées. */
    void decayPressure(double factor) {
        Iterator<Pressure> it = pressure.values().iterator();
        while (it.hasNext()) {
            Pressure p = it.next();
            p.b = p.b * factor < MarketRules.MIN_PRESSURE ? 0.0D : p.b * factor;
            p.s = p.s * factor < MarketRules.MIN_PRESSURE ? 0.0D : p.s * factor;
            if (p.b == 0.0D && p.s == 0.0D) {
                it.remove();
            }
        }
    }

    public Map<UUID, Grievance> grievances() {
        return Collections.unmodifiableMap(grievances);
    }

    /** Crochet J7 : un membre de {@code nation} a pillé un conteneur de l'avant-poste. */
    public void recordGrievance(UUID nation, long now) {
        Grievance g = grievances.get(nation);
        grievances.put(nation, new Grievance(g == null ? 1 : g.count() + 1, now));
    }

    /** Vrai si {@code target} est dans la boîte protégée (comptoir ±7 en X/Z, −2..+7 en Y). */
    public boolean boxContains(BlockPos target) {
        int dy = target.getY() - pos.getY();
        return Math.abs(target.getX() - pos.getX()) <= MarketRules.BOX_HORIZONTAL
                && Math.abs(target.getZ() - pos.getZ()) <= MarketRules.BOX_HORIZONTAL
                && dy >= -MarketRules.BOX_DOWN && dy <= MarketRules.BOX_UP;
    }

    // ------------------------------------------------------------------ persistance

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("dim", dimension.location().toString());
        tag.putLong("pos", pos.asLong());
        tag.putString("facing", facing.getSerializedName());
        tag.putString("pool", pool.toString());
        tag.putString("variant", variant);
        tag.putString("faction", faction);
        if (merchant != null) {
            tag.putUUID("merchant", merchant);
        }
        tag.putLong("created", created);
        tag.putLong("rotation", rotation);
        if (forced > 0) {
            tag.putInt("forced", forced);
        }
        ListTag list = new ListTag();
        for (Slot slot : offers) {
            CompoundTag o = new CompoundTag();
            o.putString("item", BuiltInRegistries.ITEM.getKey(slot.item).toString());
            o.putInt("count", slot.count);
            o.putLong("price", slot.price);
            o.putLong("payout", slot.payout);
            o.putInt("stockLeft", slot.stockLeft);
            o.putInt("demandLeft", slot.demandLeft);
            list.add(o);
        }
        tag.put("offers", list);
        ListTag pressures = new ListTag();
        for (Map.Entry<Item, Pressure> e : pressure.entrySet()) {
            CompoundTag p = new CompoundTag();
            p.putString("item", BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
            p.putDouble("b", e.getValue().b);
            p.putDouble("s", e.getValue().s);
            pressures.add(p);
        }
        tag.put("pressure", pressures);
        ListTag griefs = new ListTag();
        for (Map.Entry<UUID, Grievance> e : grievances.entrySet()) {
            CompoundTag g = new CompoundTag();
            g.putUUID("nation", e.getKey());
            g.putInt("n", e.getValue().count());
            g.putLong("last", e.getValue().last());
            griefs.add(g);
        }
        tag.put("grievances", griefs);
        return tag;
    }

    /** Relit un avant-poste ; {@code null} si l'entrée est inutilisable (identifiant ou dimension manquants). */
    @Nullable
    public static Outpost load(CompoundTag tag) {
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dim"));
        ResourceLocation pool = ResourceLocation.tryParse(tag.getString("pool"));
        if (!tag.hasUUID("id") || dim == null) {
            return null;
        }
        Direction facing = Direction.byName(tag.getString("facing"));
        Outpost o = new Outpost(tag.getUUID("id"), ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(tag.getLong("pos")),
                facing == null ? Direction.NORTH : facing, pool == null ? OutpostManager.DEFAULT_POOL : pool,
                tag.getString("variant"), tag.getLong("created"));
        if (tag.contains("faction", Tag.TAG_STRING)) {
            o.faction = tag.getString("faction");
        }
        o.merchant = tag.hasUUID("merchant") ? tag.getUUID("merchant") : null;
        o.rotation = tag.contains("rotation", Tag.TAG_LONG) ? tag.getLong("rotation") : -1L;
        o.forced = Math.max(0, tag.getInt("forced"));
        ListTag list = tag.getList("offers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && o.offers.size() < MarketRules.MAX_OFFERS; i++) {
            CompoundTag s = list.getCompound(i);
            Item item = item(s.getString("item"));
            if (item != null && o.slot(item) == null) {
                o.offers.add(new Slot(item, Math.max(1, s.getInt("count")), Math.max(0L, s.getLong("price")),
                        Math.max(0L, s.getLong("payout")), s.getInt("stockLeft"), s.getInt("demandLeft")));
            }
        }
        ListTag pressures = tag.getList("pressure", Tag.TAG_COMPOUND);
        for (int i = 0; i < pressures.size(); i++) {
            CompoundTag p = pressures.getCompound(i);
            Item item = item(p.getString("item"));
            double b = p.getDouble("b");
            double s = p.getDouble("s");
            if (item != null && Double.isFinite(b) && Double.isFinite(s) && (b > 0.0D || s > 0.0D)) {
                Pressure pr = o.pressure.computeIfAbsent(item, k -> new Pressure());
                pr.b = Math.max(0.0D, b);
                pr.s = Math.max(0.0D, s);
            }
        }
        ListTag griefs = tag.getList("grievances", Tag.TAG_COMPOUND);
        for (int i = 0; i < griefs.size(); i++) {
            CompoundTag g = griefs.getCompound(i);
            if (g.hasUUID("nation")) {
                o.grievances.put(g.getUUID("nation"), new Grievance(Math.max(0, g.getInt("n")), g.getLong("last")));
            }
        }
        return o;
    }

    /** Objet d'un identifiant enregistré, ou {@code null} (objet d'un mod retiré). */
    @Nullable
    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(key);
        return item == Items.AIR ? null : item;
    }
}
