// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.enterprise;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entreprises d'une nation, persistées dans le NBT de {@code Nation} sous {@code "enterprises"} :
 * habitants affectés par secteur ({@code workers}, indexé par ordinal de {@link Sector}), fractions de
 * production en attente par objet ({@code frac}) et bilan du dernier cycle ({@code last}).
 * Appeler {@code Nation#markDirty()} après toute mutation.
 */
public final class NationEnterprises {
    private final int[] workers = new int[Sector.values().length];
    private final Map<String, Double> fractions = new LinkedHashMap<>();
    private long lastMoney;
    private int lastItems;
    private int lastLost;

    public int workers(Sector sector) {
        return workers[sector.ordinal()];
    }

    /** Affectation absolue (bornée à 0) ; la validation métier (déblocage, population) est faite par l'appelant. */
    public void setWorkers(Sector sector, int count) {
        workers[sector.ordinal()] = Math.max(0, count);
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

    public void setFraction(String item, double value) {
        if (value <= 0.0D || !Double.isFinite(value)) {
            fractions.remove(item);
        } else {
            fractions.put(item, value);
        }
    }

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
        tag.put("last", last);
        return tag;
    }

    public static NationEnterprises load(CompoundTag tag) {
        NationEnterprises e = new NationEnterprises();
        int[] saved = tag.getIntArray("workers");
        for (int i = 0; i < Math.min(saved.length, e.workers.length); i++) {
            e.workers[i] = Math.max(0, saved[i]);
        }
        CompoundTag frac = tag.getCompound("frac");
        for (String key : frac.getAllKeys()) {
            if (frac.contains(key, Tag.TAG_DOUBLE)) {
                e.setFraction(key, frac.getDouble(key));
            }
        }
        CompoundTag last = tag.getCompound("last");
        e.setLast(last.getLong("money"), last.getInt("items"), last.getInt("lost"));
        return e;
    }

    @Override
    public String toString() {
        return "NationEnterprises" + Arrays.toString(workers);
    }
}
