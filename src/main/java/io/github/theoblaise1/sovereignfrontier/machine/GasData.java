package io.github.theoblaise1.sovereignfrontier.machine;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Réserve de gaz FINIE d'une dimension (SavedData propre à chaque niveau). Décrémentée par chaque
 * foreuse ; une fois épuisée, il faut aller chercher le gaz ailleurs (moteur vers l'espace).
 * Seul le gaz extrait est sauvegardé : la réserve restante se déduit à chaque lecture de la réserve totale
 * ({@link #reserveFor}), si bien qu'un changement de {@code gasPerDimension} ou du {@code gas_reserve} d'une planète
 * s'applique aussitôt.
 */
public final class GasData extends SavedData {
    private static final String FILE = SovereignFrontier.MODID + "_gas";
    private long extracted;
    /** Dimension du niveau qui porte ces données (fixée à chaque {@link #get}, non persistée). */
    private ResourceKey<Level> dimension = Level.OVERWORLD;

    public static GasData get(ServerLevel level) {
        GasData data = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(GasData::new, GasData::load, null), FILE);
        data.dimension = level.dimension();
        return data;
    }

    /**
     * Réserve totale d'une dimension (litres) : le {@code gas_reserve} de la planète publiée qui la déclare, sinon
     * {@code gasPerDimension} (Overworld, Nether, End, planète sans réserve propre).
     */
    public static long reserveFor(ResourceKey<Level> dimension) {
        PlanetDefinition planet = PlanetRegistry.get().byDimension(dimension);
        if (planet != null && planet.gasReserve().isPresent()) {
            return planet.gasReserve().getAsLong();
        }
        return SFConfig.GAS_PER_DIMENSION.get();
    }

    /** Réserve totale de la dimension de ces données. */
    public long reserve() {
        return reserveFor(dimension);
    }

    /** Litres restants : réserve totale moins le gaz déjà extrait, jamais négatif. */
    public long remaining() {
        return Math.max(0, reserve() - extracted);
    }

    /** Prélève jusqu'à {@code liters} ; renvoie la quantité réellement extraite. */
    public long extract(long liters) {
        long got = Math.min(Math.max(0, liters), remaining());
        if (got > 0) {
            extracted += got;
            setDirty();
        }
        return got;
    }

    /** Fixe la réserve restante (bornée par la réserve totale) ; réservé aux tests. */
    public void setRemaining(long liters) {
        extracted = Math.max(0, reserve() - Math.max(0, liters));
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("extracted", extracted);
        return tag;
    }

    private static GasData load(CompoundTag tag, HolderLookup.Provider registries) {
        GasData data = new GasData();
        if (tag.contains("extracted")) {
            data.extracted = tag.getLong("extracted");
        } else if (tag.contains("remaining")) {
            // Ancien format (antérieur aux planètes) : réserve restante absolue (-1 = jamais initialisée), convertie
            // en gaz extrait.
            long remaining = tag.getLong("remaining");
            data.extracted = remaining < 0 ? 0 : Math.max(0, SFConfig.GAS_PER_DIMENSION.get() - remaining);
        }
        return data;
    }
}
