// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.dungeon;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Autels de donjon et gardiens (J6, docs/conception/J6.md §2.4), attachés à l'Overworld. Un autel sans entrée est un
 * autel neuf. Les délais sont des timestamps epoch (ms) ; aucune tâche par tick.
 * <p>
 * Un index inverse {@code gardien → autel} est maintenu avec la carte : la mort d'un gardien et son chargement se
 * résolvent en O(1), sans parcours dans un événement.
 */
public final class DungeonData extends SavedData {
    private static final String FILE = SovereignFrontier.MODID + "_dungeons";
    private static final String LIST = "altars";

    private final Map<BlockPos, AltarState> altars = new HashMap<>();
    private final Map<UUID, BlockPos> bosses = new HashMap<>();

    public DungeonData() {
    }

    public static DungeonData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DungeonData::new, DungeonData::load, null), FILE);
    }

    /**
     * État d'un autel : gardien courant ({@code null} si aucun), nation de l'activateur ({@code null} si aucune), fin de
     * recharge en epoch ms (0 si aucune).
     */
    public record AltarState(@Nullable UUID boss, @Nullable UUID nation, long rechargeUntilEpochMs) {
        public static final Codec<AltarState> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.optionalFieldOf("boss").forGetter(s -> Optional.ofNullable(s.boss())),
                UUIDUtil.CODEC.optionalFieldOf("nation").forGetter(s -> Optional.ofNullable(s.nation())),
                Codec.LONG.optionalFieldOf("recharge", 0L).forGetter(AltarState::rechargeUntilEpochMs)
        ).apply(i, (boss, nation, recharge) -> new AltarState(boss.orElse(null), nation.orElse(null), recharge)));

        public AltarState withBoss(@Nullable UUID newBoss) {
            return new AltarState(newBoss, nation, rechargeUntilEpochMs);
        }
    }

    /** Entrée sérialisée : position de l'autel et son état. */
    private record AltarEntry(BlockPos pos, AltarState state) {
        private static final Codec<AltarEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(AltarEntry::pos),
                AltarState.CODEC.fieldOf("state").forGetter(AltarEntry::state)
        ).apply(i, AltarEntry::new));
    }

    // ------------------------------------------------------------------ requêtes

    /** État de l'autel en {@code pos}, ou {@code null} pour un autel neuf. */
    @Nullable
    public AltarState get(BlockPos pos) {
        return altars.get(pos);
    }

    /** Autel dont le gardien courant a pour UUID {@code boss}, ou {@code null} (index O(1)). */
    @Nullable
    public BlockPos altarOf(UUID boss) {
        return bosses.get(boss);
    }

    // ------------------------------------------------------------------ mutations

    public void put(BlockPos pos, AltarState state) {
        BlockPos key = pos.immutable();
        AltarState previous = altars.put(key, state);
        if (previous != null && previous.boss() != null) {
            bosses.remove(previous.boss(), key);
        }
        if (state.boss() != null) {
            bosses.put(state.boss(), key);
        }
        setDirty();
    }

    public void remove(BlockPos pos) {
        AltarState previous = altars.remove(pos);
        if (previous != null) {
            if (previous.boss() != null) {
                bosses.remove(previous.boss());
            }
            setDirty();
        }
    }

    private void restore(BlockPos pos, AltarState state) {
        altars.put(pos, state);
        if (state.boss() != null) {
            bosses.put(state.boss(), pos);
        }
    }

    // ------------------------------------------------------------------ persistance

    public static DungeonData load(CompoundTag tag, HolderLookup.Provider registries) {
        DungeonData data = new DungeonData();
        ListTag list = tag.getList(LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Optional<AltarEntry> entry = AltarEntry.CODEC.parse(NbtOps.INSTANCE, list.getCompound(i)).result();
            entry.ifPresent(e -> data.restore(e.pos(), e.state()));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, AltarState> e : altars.entrySet()) {
            Optional<Tag> encoded = AltarEntry.CODEC.encodeStart(NbtOps.INSTANCE, new AltarEntry(e.getKey(), e.getValue())).result();
            encoded.ifPresent(list::add);
        }
        tag.put(LIST, list);
        return tag;
    }
}
