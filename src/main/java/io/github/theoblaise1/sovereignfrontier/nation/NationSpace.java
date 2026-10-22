package io.github.theoblaise1.sovereignfrontier.nation;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Programme spatial d'une nation (J5), persisté sous {@code "space"} dans le NBT de la nation : date du premier
 * atterrissage sur chaque planète (epoch ms) et nombre de vols. Structure du socle (WP0) ; les vols (WP2) l'écrivent.
 * Les mutations passent par cette classe, puis l'appelant marque la nation ({@link Nation#markDirty()}).
 */
public final class NationSpace {
    private final Map<ResourceKey<Level>, Long> landed = new LinkedHashMap<>();
    private int flights;

    /** Premiers atterrissages par dimension (lecture seule). */
    public Map<ResourceKey<Level>, Long> landed() {
        return Collections.unmodifiableMap(landed);
    }

    public boolean hasLanded(ResourceKey<Level> dimension) {
        return landed.containsKey(dimension);
    }

    /** Date du premier atterrissage (epoch ms), 0 si la nation ne s'y est jamais posée. */
    public long firstLanding(ResourceKey<Level> dimension) {
        return landed.getOrDefault(dimension, 0L);
    }

    /** Enregistre un atterrissage ; vrai s'il est le premier de la nation dans cette dimension (annonce, journal). */
    public boolean recordLanding(ResourceKey<Level> dimension, long nowMs) {
        return landed.putIfAbsent(dimension, nowMs) == null;
    }

    public int flights() {
        return flights;
    }

    public void addFlight() {
        if (flights < Integer.MAX_VALUE) {
            flights++;
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag landedTag = new CompoundTag();
        for (Map.Entry<ResourceKey<Level>, Long> e : landed.entrySet()) {
            landedTag.putLong(e.getKey().location().toString(), e.getValue());
        }
        tag.put("landed", landedTag);
        tag.putInt("flights", flights);
        return tag;
    }

    public static NationSpace load(CompoundTag tag) {
        NationSpace space = new NationSpace();
        CompoundTag landedTag = tag.getCompound("landed");
        for (String key : landedTag.getAllKeys()) {
            ResourceLocation loc = ResourceLocation.tryParse(key);
            if (loc != null) {
                space.landed.put(ResourceKey.create(Registries.DIMENSION, loc), landedTag.getLong(key));
            }
        }
        space.flights = Math.max(0, tag.getInt("flights"));
        return space;
    }
}
