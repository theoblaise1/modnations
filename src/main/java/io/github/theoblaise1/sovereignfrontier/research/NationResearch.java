package io.github.theoblaise1.sovereignfrontier.research;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * État de recherche d'une nation : paliers terminés, recherche active (horodatages epoch, donc
 * elle progresse serveur éteint) et points de recherche. Persisté dans le NBT de la nation.
 * Les mutations passent par {@code Nation}, qui marque les données à sauvegarder.
 */
public final class NationResearch {
    /**
     * Recherche en cours ; {@code endEpochMs} est fixé au lancement (bonus de vitesse inclus).
     * {@code paidTreasury} est la trésorerie réellement prélevée au lancement, base du remboursement
     * en cas d'annulation (la définition a pu changer ou disparaître depuis, après un {@code /reload}) ;
     * {@link #PAID_UNKNOWN} pour une recherche lancée avant que ce montant soit enregistré.
     */
    public record Active(ResourceLocation id, long startEpochMs, long endEpochMs, long paidTreasury) {
        /** Montant payé inconnu (sauvegarde antérieure) : le remboursement retombe sur la définition courante. */
        public static final long PAID_UNKNOWN = -1L;

        public long totalMs() {
            return Math.max(1L, endEpochMs - startEpochMs);
        }
    }

    private final Set<ResourceLocation> completed = new LinkedHashSet<>();
    @Nullable
    private Active active;
    private long points;

    /** Cache des bonus, recalculé quand la génération du registre ou les paliers terminés changent. */
    private int bonusGeneration = -1;
    private double[] bonusCache = new double[ResearchStat.values().length];

    public Set<ResourceLocation> completed() {
        return Collections.unmodifiableSet(completed);
    }

    public boolean isCompleted(ResourceLocation id) {
        return completed.contains(id);
    }

    @Nullable
    public Active active() {
        return active;
    }

    public long points() {
        return points;
    }

    public void setActive(@Nullable Active active) {
        this.active = active;
    }

    public void complete(ResourceLocation id) {
        completed.add(id);
        bonusGeneration = -1;
    }

    public boolean revoke(ResourceLocation id) {
        boolean removed = completed.remove(id);
        if (removed) {
            bonusGeneration = -1;
        }
        return removed;
    }

    public void setPoints(long points) {
        this.points = Math.max(0L, points);
    }

    /** Somme des bonus {@code stat} des paliers terminés connus du registre courant. */
    public double bonus(ResearchStat stat) {
        ResearchRegistry registry = ResearchRegistry.server();
        if (bonusGeneration != registry.generation()) {
            double[] sums = new double[ResearchStat.values().length];
            for (ResourceLocation id : completed) {
                ResearchTier tier = registry.get(id);
                if (tier == null) {
                    continue;
                }
                for (ResearchReward reward : tier.definition().rewards()) {
                    reward.stat().ifPresent(s -> sums[s.ordinal()] += reward.amount());
                }
            }
            bonusCache = sums;
            bonusGeneration = registry.generation();
        }
        return bonusCache[stat.ordinal()];
    }

    /** Vrai si un palier terminé accorde le déblocage {@code key}. */
    public boolean hasUnlock(String key) {
        ResearchRegistry registry = ResearchRegistry.server();
        for (ResourceLocation id : completed) {
            ResearchTier tier = registry.get(id);
            if (tier == null) {
                continue;
            }
            for (ResearchReward reward : tier.definition().rewards()) {
                if (reward.type() == ResearchReward.Type.UNLOCK && reward.key().equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (ResourceLocation id : completed) {
            list.add(StringTag.valueOf(id.toString()));
        }
        tag.put("completed", list);
        if (active != null) {
            CompoundTag a = new CompoundTag();
            a.putString("id", active.id().toString());
            a.putLong("start", active.startEpochMs());
            a.putLong("end", active.endEpochMs());
            a.putLong("paid", active.paidTreasury());
            tag.put("active", a);
        }
        tag.putLong("points", points);
        return tag;
    }

    public static NationResearch load(CompoundTag tag) {
        NationResearch r = new NationResearch();
        ListTag list = tag.getList("completed", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (id != null) {
                r.completed.add(id);
            }
        }
        if (tag.contains("active", Tag.TAG_COMPOUND)) {
            CompoundTag a = tag.getCompound("active");
            ResourceLocation id = ResourceLocation.tryParse(a.getString("id"));
            if (id != null) {
                long paid = a.contains("paid", Tag.TAG_LONG) ? a.getLong("paid") : Active.PAID_UNKNOWN;
                r.active = new Active(id, a.getLong("start"), a.getLong("end"), paid);
            }
        }
        r.points = Math.max(0L, tag.getLong("points"));
        return r;
    }
}
