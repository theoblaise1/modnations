package io.github.theoblaise1.sovereignfrontier.energy;

import net.minecraft.nbt.CompoundTag;

/** Réserve d'énergie (unité du mod : l'énergon, « En ») avec débits maximaux d'entrée et de sortie par tick. */
public final class EnergyBuffer {
    private final long capacity;
    private final long maxIn;
    private final long maxOut;
    private long stored;

    public EnergyBuffer(long capacity, long maxIn, long maxOut) {
        this.capacity = capacity;
        this.maxIn = maxIn;
        this.maxOut = maxOut;
    }

    public long stored() {
        return stored;
    }

    public long capacity() {
        return capacity;
    }

    public long maxOut() {
        return maxOut;
    }

    public long space() {
        return capacity - stored;
    }

    /** Production interne (générateur) : sans limite de débit d'entrée. */
    public long generate(long amount) {
        long added = Math.min(Math.max(0, amount), space());
        stored += added;
        return added;
    }

    /** Réception depuis le réseau : limitée au débit d'entrée. */
    public long receive(long amount) {
        long added = Math.min(Math.min(Math.max(0, amount), maxIn), space());
        stored += added;
        return added;
    }

    /** Consommation interne (machine) : tout ou rien. */
    public boolean consume(long amount) {
        if (stored < amount) {
            return false;
        }
        stored -= amount;
        return true;
    }

    public void extract(long amount) {
        stored -= Math.min(stored, Math.max(0, amount));
    }

    public void setStored(long value) {
        stored = Math.max(0, Math.min(capacity, value));
    }

    public void save(CompoundTag tag) {
        tag.putLong("energy", stored);
    }

    public void load(CompoundTag tag) {
        setStored(tag.getLong("energy"));
    }
}
