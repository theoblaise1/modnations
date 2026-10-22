package io.github.theoblaise1.sovereignfrontier.outpost;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Avant-postes enregistrés et compteur de ticks des rotations, attachés à l'Overworld
 * ({@code data/sovereignfrontier_outposts.dat}). L'{@link OutpostIndex} est dérivé et reconstruit au chargement.
 * <p>
 * Le compteur {@code ticks} avance d'une unité par tick serveur (marqué modifié toutes les 1024) ; la rotation
 * courante vaut {@code ticks / merchantRotationTicks}. Les étals sont renouvelés paresseusement, à l'accès.
 */
public final class OutpostData extends SavedData {
    private static final String FILE = SovereignFrontier.MODID + "_outposts";
    private static final int DIRTY_INTERVAL = 1024;

    private final Map<UUID, Outpost> outposts = new LinkedHashMap<>();
    private final OutpostIndex index = new OutpostIndex();
    private long ticks;

    public OutpostData() {
    }

    public static OutpostData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OutpostData::new, OutpostData::load, null), FILE);
    }

    // ------------------------------------------------------------------ compteur

    /** Un tick serveur : O(1). */
    public void tick() {
        ticks++;
        if (ticks % DIRTY_INTERVAL == 0L) {
            setDirty();
        }
    }

    public long ticks() {
        return ticks;
    }

    /** Avance le compteur (GameTests, administration). */
    public void advance(long n) {
        ticks = Math.max(0L, ticks + n);
        setDirty();
    }

    public long rotationIndex(int rotationTicks) {
        return ticks / Math.max(1, rotationTicks);
    }

    public long ticksToRotation(int rotationTicks) {
        int period = Math.max(1, rotationTicks);
        return period - ticks % period;
    }

    // ------------------------------------------------------------------ requêtes

    @Nullable
    public Outpost get(UUID id) {
        return outposts.get(id);
    }

    public Collection<Outpost> all() {
        return Collections.unmodifiableCollection(outposts.values());
    }

    public int size() {
        return outposts.size();
    }

    public OutpostIndex index() {
        return index;
    }

    @Nullable
    public Outpost atAnchor(ResourceKey<Level> dimension, BlockPos pos) {
        UUID id = index.anchorAt(GlobalPos.of(dimension, pos));
        return id == null ? null : outposts.get(id);
    }

    /** Vrai si ce chunk est dans la zone non revendicable (3 × 3 chunks) d'un avant-poste. */
    public boolean claimBlocked(ResourceKey<Level> dimension, long chunk) {
        return index.covers(dimension, chunk);
    }

    /** Avant-poste dont la boîte protégée contient {@code pos}, ou {@code null} (une recherche dans l'index). */
    @Nullable
    public Outpost boxAt(ResourceKey<Level> dimension, BlockPos pos) {
        for (UUID id : index.at(dimension, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4))) {
            Outpost o = outposts.get(id);
            if (o != null && o.boxContains(pos)) {
                return o;
            }
        }
        return null;
    }

    /**
     * Avant-poste dont le comptoir est le plus proche de {@code pos}, à {@code radius} blocs au plus (distance entre
     * centres de blocs). Seuls les chunks du carré de rayon {@code radius} sont consultés dans l'index.
     */
    @Nullable
    public Outpost nearest(ResourceKey<Level> dimension, BlockPos pos, double radius) {
        Outpost best = null;
        double bestSq = radius * radius;
        int r = (int) Math.ceil(radius);
        for (int cx = (pos.getX() - r) >> 4; cx <= (pos.getX() + r) >> 4; cx++) {
            for (int cz = (pos.getZ() - r) >> 4; cz <= (pos.getZ() + r) >> 4; cz++) {
                for (UUID id : index.at(dimension, ChunkPos.asLong(cx, cz))) {
                    Outpost o = outposts.get(id);
                    if (o == null) {
                        continue;
                    }
                    double d = o.pos().distSqr(pos);
                    if (d <= bestSq && (best == null || d < best.pos().distSqr(pos))) {
                        best = o;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ mutations

    void add(Outpost outpost) {
        Outpost previous = outposts.put(outpost.id(), outpost);
        if (previous != null) {
            index.remove(previous);
        }
        index.add(outpost);
        setDirty();
    }

    void remove(Outpost outpost) {
        if (outposts.remove(outpost.id()) != null) {
            index.remove(outpost);
            setDirty();
        }
    }

    // ------------------------------------------------------------------ persistance

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("ticks", ticks);
        ListTag list = new ListTag();
        for (Outpost o : outposts.values()) {
            list.add(o.save());
        }
        tag.put("outposts", list);
        return tag;
    }

    /** Relit les données (public pour les GameTests) ; l'index est reconstruit. */
    public static OutpostData load(CompoundTag tag, HolderLookup.Provider registries) {
        OutpostData data = new OutpostData();
        data.ticks = Math.max(0L, tag.getLong("ticks"));
        ListTag list = tag.getList("outposts", Tag.TAG_COMPOUND);
        List<Outpost> loaded = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Outpost o = Outpost.load(list.getCompound(i));
            if (o != null) {
                loaded.add(o);
            }
        }
        for (Outpost o : loaded) {
            data.outposts.put(o.id(), o);
            data.index.add(o);
        }
        return data;
    }
}
