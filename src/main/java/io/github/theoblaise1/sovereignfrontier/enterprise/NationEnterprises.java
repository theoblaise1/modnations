// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.enterprise;

import io.github.theoblaise1.sovereignfrontier.network.EnterpriseAssignC2S;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entreprises d'une nation, persistées dans le NBT de {@code Nation} sous {@code "enterprises"} :
 * habitants affectés par secteur ({@code workers}, indexé par ordinal de {@link Sector}), fractions de
 * production en attente par objet ({@code frac}) et bilan du dernier cycle ({@code last} : totaux, plus le détail
 * par secteur sous {@code last.sectors}).
 * Appeler {@code Nation#markDirty()} après toute mutation.
 */
public final class NationEnterprises {
    /** Borne d'une affectation (paquet, commande, NBT relu) : garde les boucles d'ajustement bornées. */
    public static final int MAX_WORKERS = EnterpriseAssignC2S.MAX_WORKERS;

    private static final int SECTORS = Sector.values().length;

    private final int[] workers = new int[SECTORS];
    private final Map<String, Double> fractions = new LinkedHashMap<>();
    private long lastMoney;
    private int lastItems;
    private int lastLost;
    private final long[] sectorMoney = new long[SECTORS];
    private final int[] sectorItems = new int[SECTORS];
    private final int[] sectorLost = new int[SECTORS];

    public int workers(Sector sector) {
        return workers[sector.ordinal()];
    }

    /** Affectation absolue (bornée à 0..{@link #MAX_WORKERS}) ; la validation métier (déblocage, population) est faite par l'appelant. */
    public void setWorkers(Sector sector, int count) {
        workers[sector.ordinal()] = Math.max(0, Math.min(MAX_WORKERS, count));
    }

    /** Habitants affectés à l'ensemble des secteurs (ils ne touchent plus le revenu passif). */
    public int assignedTotal() {
        long total = 0L;
        for (int w : workers) {
            total += w;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Fractions de production en attente, par identifiant d'objet. */
    public Map<String, Double> fractions() {
        return Collections.unmodifiableMap(fractions);
    }

    /** Fraction en attente pour cet objet (0 si aucune). */
    public double fraction(String item) {
        return fractions.getOrDefault(item, 0.0D);
    }

    public void setFraction(String item, double value) {
        if (value <= 0.0D || !Double.isFinite(value)) {
            fractions.remove(item);
        } else {
            fractions.put(item, value);
        }
    }

    // ------------------------------------------------------------------ bilan du dernier cycle

    public long lastMoney() {
        return lastMoney;
    }

    public int lastItems() {
        return lastItems;
    }

    public int lastLost() {
        return lastLost;
    }

    public void setLast(long money, int items, int lost) {
        this.lastMoney = money;
        this.lastItems = items;
        this.lastLost = lost;
    }

    public long lastMoney(Sector sector) {
        return sectorMoney[sector.ordinal()];
    }

    public int lastItems(Sector sector) {
        return sectorItems[sector.ordinal()];
    }

    public int lastLost(Sector sector) {
        return sectorLost[sector.ordinal()];
    }

    /** Bilan d'un secteur au dernier cycle ; les totaux ({@link #setLast(long, int, int)}) sont fixés à part. */
    public void setLast(Sector sector, long money, int items, int lost) {
        sectorMoney[sector.ordinal()] = money;
        sectorItems[sector.ordinal()] = items;
        sectorLost[sector.ordinal()] = lost;
    }

    /** Aucun habitant affecté et un dernier bilan nul : un cycle n'a rien à faire ni à effacer. */
    public boolean isIdle() {
        if (assignedTotal() > 0 || lastMoney != 0L || lastItems != 0 || lastLost != 0) {
            return false;
        }
        for (int i = 0; i < SECTORS; i++) {
            if (sectorMoney[i] != 0L || sectorItems[i] != 0 || sectorLost[i] != 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("workers", workers.clone());
        CompoundTag frac = new CompoundTag();
        for (Map.Entry<String, Double> e : fractions.entrySet()) {
            frac.putDouble(e.getKey(), e.getValue());
        }
        tag.put("frac", frac);
        CompoundTag last = new CompoundTag();
        last.putLong("money", lastMoney);
        last.putInt("items", lastItems);
        last.putInt("lost", lastLost);
        CompoundTag bySector = new CompoundTag();
        bySector.putLongArray("money", sectorMoney.clone());
        bySector.putIntArray("items", sectorItems.clone());
        bySector.putIntArray("lost", sectorLost.clone());
        last.put("sectors", bySector);
        tag.put("last", last);
        return tag;
    }

    public static NationEnterprises load(CompoundTag tag) {
        NationEnterprises e = new NationEnterprises();
        int[] saved = tag.getIntArray("workers");
        for (int i = 0; i < Math.min(saved.length, e.workers.length); i++) {
            e.workers[i] = Math.max(0, Math.min(MAX_WORKERS, saved[i]));
        }
        CompoundTag frac = tag.getCompound("frac");
        for (String key : frac.getAllKeys()) {
            if (frac.contains(key, Tag.TAG_DOUBLE)) {
                e.setFraction(key, frac.getDouble(key));
            }
        }
        CompoundTag last = tag.getCompound("last");
        e.setLast(last.getLong("money"), last.getInt("items"), last.getInt("lost"));
        CompoundTag bySector = last.getCompound("sectors");
        long[] money = bySector.getLongArray("money");
        int[] items = bySector.getIntArray("items");
        int[] lost = bySector.getIntArray("lost");
        for (int i = 0; i < SECTORS; i++) {
            e.sectorMoney[i] = i < money.length ? money[i] : 0L;
            e.sectorItems[i] = i < items.length ? items[i] : 0;
            e.sectorLost[i] = i < lost.length ? lost[i] : 0;
        }
        return e;
    }

    @Override
    public String toString() {
        return "NationEnterprises" + Arrays.toString(workers);
    }
}
