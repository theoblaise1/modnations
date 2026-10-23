// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.war.missile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Silo à missiles de chaque nation sur l'Overworld (J7, D12 : un silo par nation). Une entrée {@code nation -> position}
 * sert à refuser une seconde pose, et à retrouver le silo d'un tireur sans scanner de chunks. Une entrée disparaît quand
 * le silo est cassé ({@link #removeAt}), ou à la lecture si le bloc n'est plus un silo dans un chunk chargé.
 */
public final class SiloRegistry extends SavedData {
    private static final String FILE = SovereignFrontier.MODID + "_silos";
    private static final String SILOS = "silos";

    private final Map<UUID, BlockPos> silos = new HashMap<>();

    SiloRegistry() {
    }

    public static SiloRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SiloRegistry::new, SiloRegistry::load, null), FILE);
    }

    /**
     * Silo vivant de la nation, ou {@code null}. Une entrée dont le bloc a disparu (chunk chargé, bloc qui n'est plus un
     * silo) est retirée au passage.
     */
    @Nullable
    public BlockPos liveSiloOf(ServerLevel level, UUID nation) {
        BlockPos pos = silos.get(nation);
        if (pos == null) {
            return null;
        }
        if (level.isLoaded(pos) && !level.getBlockState(pos).is(ModBlocks.SILO_MISSILES.get())) {
            silos.remove(nation);
            setDirty();
            return null;
        }
        return pos;
    }

    /** Enregistre {@code pos} pour la nation ; {@code false} si elle a déjà un autre silo vivant. */
    public boolean register(ServerLevel level, UUID nation, BlockPos pos) {
        BlockPos current = liveSiloOf(level, nation);
        if (current != null && !current.equals(pos)) {
            return false;
        }
        silos.put(nation, pos.immutable());
        setDirty();
        return true;
    }

    /** Retire le silo situé en {@code pos}, quelle que soit la nation (le bloc vient de disparaître). */
    public void removeAt(BlockPos pos) {
        if (silos.values().removeIf(pos::equals)) {
            setDirty();
        }
    }

    // ------------------------------------------------------------------ persistance

    private record Entry(UUID nation, BlockPos pos) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("nation").forGetter(Entry::nation),
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    public static SiloRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        SiloRegistry registry = new SiloRegistry();
        ListTag list = tag.getList(SILOS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Entry.CODEC.parse(NbtOps.INSTANCE, list.getCompound(i)).result()
                    .ifPresent(e -> registry.silos.put(e.nation(), e.pos()));
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, BlockPos> e : silos.entrySet()) {
            Entry.CODEC.encodeStart(NbtOps.INSTANCE, new Entry(e.getKey(), e.getValue())).result().ifPresent(list::add);
        }
        tag.put(SILOS, list);
        return tag;
    }
}
