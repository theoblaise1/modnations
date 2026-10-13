package io.github.theoblaise1.sovereignfrontier.machine;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Réserve de gaz FINIE d'une dimension (SavedData propre à chaque niveau). Décrémentée par chaque
 * foreuse ; une fois épuisée, il faut aller chercher le gaz ailleurs (moteur vers l'espace).
 */
public final class GasData extends SavedData {
    private static final String FILE = SovereignFrontier.MODID + "_gas";
    private long remaining = -1L;

    public static GasData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(GasData::new, GasData::load, null), FILE);
    }

    public long remaining() {
        if (remaining < 0) {
            remaining = SFConfig.GAS_PER_DIMENSION.get();
            setDirty();
        }
        return remaining;
    }

    /** Prélève jusqu'à {@code liters} ; renvoie la quantité réellement extraite. */
    public long extract(long liters) {
        long got = Math.min(Math.max(0, liters), remaining());
        if (got > 0) {
            remaining -= got;
            setDirty();
        }
        return got;
    }

    public void setRemaining(long liters) {
        remaining = Math.max(0, liters);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("remaining", remaining);
        return tag;
    }

    private static GasData load(CompoundTag tag, HolderLookup.Provider registries) {
        GasData data = new GasData();
        data.remaining = tag.contains("remaining") ? tag.getLong("remaining") : -1L;
        return data;
    }
}
