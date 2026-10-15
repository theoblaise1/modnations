package io.github.theoblaise1.sovereignfrontier.economy;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Portefeuilles personnels des joueurs. Stockés dans un SavedData (et non sur le joueur) pour
 * rester consultables hors ligne : {@code /pay} vers un absent, {@code /baltop}.
 */
public final class EconomyData extends SavedData {
    private static final String FILE = SovereignFrontier.MODID + "_economy";

    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public static EconomyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(EconomyData::new, EconomyData::load, null), FILE);
    }

    public boolean hasAccount(UUID player) {
        return balances.containsKey(player);
    }

    /** Crée le compte s'il n'existe pas ; renvoie vrai si c'est une création. */
    public boolean ensureAccount(UUID player, String name, long startingBalance) {
        boolean created = false;
        if (!balances.containsKey(player)) {
            balances.put(player, startingBalance);
            created = true;
        }
        if (!name.equals(names.put(player, name)) || created) {
            setDirty();
        }
        return created;
    }

    public long balance(UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    public void add(UUID player, long amount) {
        long current = balance(player);
        long next = current + amount;
        if (amount > 0 && next < current) {
            next = Long.MAX_VALUE;
        }
        balances.put(player, Math.max(0L, next));
        setDirty();
    }

    public void set(UUID player, long amount) {
        balances.put(player, Math.max(0L, amount));
        setDirty();
    }

    public boolean trySpend(UUID player, long amount) {
        long current = balance(player);
        if (amount < 0 || current < amount) {
            return false;
        }
        balances.put(player, current - amount);
        setDirty();
        return true;
    }

    /**
     * Comptes portant ce nom : la correspondance exacte prime, sinon la casse est ignorée. Plusieurs
     * résultats = nom ambigu (hors ligne, « Alice » et « alice » sont deux joueurs distincts).
     */
    public List<UUID> findByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        List<UUID> exact = new ArrayList<>();
        List<UUID> loose = new ArrayList<>();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equals(name)) {
                exact.add(e.getKey());
            } else if (e.getValue().toLowerCase(Locale.ROOT).equals(lower)) {
                loose.add(e.getKey());
            }
        }
        return exact.isEmpty() ? loose : exact;
    }

    public String nameOf(UUID player) {
        return names.getOrDefault(player, player.toString().substring(0, 8));
    }

    public List<String> knownNames() {
        return names.values().stream().distinct().toList();
    }

    public List<Map.Entry<UUID, Long>> top(int limit) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Long> e : balances.entrySet()) {
            CompoundTag acc = new CompoundTag();
            acc.putUUID("uuid", e.getKey());
            acc.putLong("balance", e.getValue());
            acc.putString("name", nameOf(e.getKey()));
            list.add(acc);
        }
        tag.put("accounts", list);
        return tag;
    }

    private static EconomyData load(CompoundTag tag, HolderLookup.Provider registries) {
        EconomyData data = new EconomyData();
        ListTag list = tag.getList("accounts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag acc = list.getCompound(i);
            UUID uuid = acc.getUUID("uuid");
            data.balances.put(uuid, acc.getLong("balance"));
            data.names.put(uuid, acc.getString("name"));
        }
        return data;
    }
}
