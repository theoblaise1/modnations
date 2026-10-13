package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Données persistantes de toutes les nations, attachées à l'Overworld
 * ({@code data/sovereignfrontier_nations.dat}). Maintient deux index dérivés :
 * joueur → nation et le {@link TerritoryIndex} (cache par chunk).
 */
public final class NationData extends SavedData {
    private static final String FILE = SovereignFrontier.MODID + "_nations";

    private final Map<UUID, Nation> nations = new LinkedHashMap<>();
    private final Map<UUID, UUID> memberIndex = new HashMap<>();
    private final Map<String, UUID> nameIndex = new HashMap<>();
    private final TerritoryIndex territory = new TerritoryIndex();

    /** Compteur de ticks persistant pour le revenu de population (pas de scan de chunks). */
    private long incomeTickCounter;
    private int buildingCursor;

    public static NationData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(NationData::new, NationData::load, null), FILE);
    }

    // ------------------------------------------------------------------ requêtes

    public Collection<Nation> nations() {
        return Collections.unmodifiableCollection(nations.values());
    }

    @Nullable
    public Nation byId(UUID id) {
        return nations.get(id);
    }

    @Nullable
    public Nation byName(String name) {
        UUID id = nameIndex.get(name.toLowerCase(Locale.ROOT));
        return id == null ? null : nations.get(id);
    }

    @Nullable
    public Nation nationOf(UUID player) {
        UUID id = memberIndex.get(player);
        return id == null ? null : nations.get(id);
    }

    @Nullable
    public Nation ownerAt(ResourceKey<Level> dimension, long chunk) {
        return territory.ownerAt(dimension, chunk);
    }

    @Nullable
    public Nation ownerAt(ResourceKey<Level> dimension, BlockPos pos) {
        return territory.ownerAt(dimension, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    public TerritoryIndex territory() {
        return territory;
    }

    // ------------------------------------------------------------------ mutations

    Nation create(String name, UUID leader, String leaderName, long nowMs) {
        Nation nation = new Nation(UUID.randomUUID(), name, nowMs, leader);
        nation.setDirtyCallback(this::setDirty);
        nation.addMember(leader, leaderName, NationRole.CHEF);
        nations.put(nation.id(), nation);
        memberIndex.put(leader, nation.id());
        nameIndex.put(name.toLowerCase(Locale.ROOT), nation.id());
        setDirty();
        return nation;
    }

    void remove(Nation nation) {
        for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> e : nation.claims().entrySet()) {
            for (long chunk : e.getValue()) {
                territory.invalidate(e.getKey(), chunk);
            }
        }
        for (UUID member : nation.memberIds()) {
            memberIndex.remove(member);
        }
        nameIndex.remove(nation.name().toLowerCase(Locale.ROOT));
        nations.remove(nation.id());
        setDirty();
    }

    void addMember(Nation nation, UUID player, String playerName, NationRole role) {
        nation.addMember(player, playerName, role);
        memberIndex.put(player, nation.id());
    }

    void removeMember(Nation nation, UUID player) {
        nation.removeMember(player);
        memberIndex.remove(player);
    }

    void claim(Nation nation, ResourceKey<Level> dimension, long chunk) {
        nation.addClaim(dimension, chunk);
        territory.put(dimension, chunk, nation);
    }

    void unclaim(Nation nation, ResourceKey<Level> dimension, long chunk) {
        nation.removeClaim(dimension, chunk);
        territory.invalidate(dimension, chunk);
        nation.removeBuildingsInChunk(dimension, chunk);
    }

    /** Avance le compteur persistant ; vrai quand un cycle de revenu est atteint. */
    boolean advanceIncomeCounter(int interval) {
        incomeTickCounter++;
        if (incomeTickCounter >= interval) {
            incomeTickCounter = 0;
            setDirty();
            return true;
        }
        if ((incomeTickCounter & 1023) == 0) {
            setDirty();
        }
        return false;
    }

    long incomeTickCounter() {
        return incomeTickCounter;
    }

    int nextBuildingCursor() {
        return buildingCursor++;
    }

    List<Nation> nationsSnapshot() {
        return new ArrayList<>(nations.values());
    }

    // ------------------------------------------------------------------ persistance

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Nation nation : nations.values()) {
            list.add(nation.save(registries));
        }
        tag.put("nations", list);
        tag.putLong("incomeTicks", incomeTickCounter);
        return tag;
    }

    private static NationData load(CompoundTag tag, HolderLookup.Provider registries) {
        NationData data = new NationData();
        ListTag list = tag.getList("nations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Nation nation = Nation.load(list.getCompound(i), registries);
            nation.setDirtyCallback(data::setDirty);
            data.nations.put(nation.id(), nation);
            data.nameIndex.put(nation.name().toLowerCase(Locale.ROOT), nation.id());
            for (UUID member : nation.memberIds()) {
                data.memberIndex.put(member, nation.id());
            }
        }
        data.territory.rebuild(data.nations.values());
        data.incomeTickCounter = tag.getLong("incomeTicks");
        return data;
    }
}
