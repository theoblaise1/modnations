package io.github.theoblaise1.sovereignfrontier.reward;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Récompenses personnelles des joueurs (SavedData {@code sovereignfrontier_rewards}) : épargne du Terminal bancaire,
 * série {@code /daily} et compteurs de pitié des caisses. Indépendant de toute nation ; écrit dans le même
 * {@code saveEverything} que les portefeuilles. Tous les horodatages sont en epoch (ms).
 * <p>
 * Format : {@code players:[{uuid, terminal:{window, activeSec, units, lifetime}, daily:{last, streak},
 * pity:{<caisse>:{<idEntrée>:int}}}]}. Les appelants qui modifient un {@link PlayerRewards} appellent
 * {@link #setDirty()}.
 */
public final class RewardData extends SavedData {
    private static final String FILE = SovereignFrontier.MODID + "_rewards";

    private final Map<UUID, PlayerRewards> players = new HashMap<>();

    public static RewardData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RewardData::new, RewardData::load, null), FILE);
    }

    /** Fiche du joueur, ou {@code null} s'il n'en a pas encore. */
    @Nullable
    public PlayerRewards find(UUID player) {
        return players.get(player);
    }

    /** Fiche du joueur, créée vide au besoin (la création seule ne marque rien à sauvegarder). */
    public PlayerRewards of(UUID player) {
        return players.computeIfAbsent(player, id -> new PlayerRewards());
    }

    /** Supprime la fiche (nettoyage des GameTests). */
    public void remove(UUID player) {
        if (players.remove(player) != null) {
            setDirty();
        }
    }

    public int size() {
        return players.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PlayerRewards> e : players.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            CompoundTag p = e.getValue().save();
            p.putUUID("uuid", e.getKey());
            list.add(p);
        }
        tag.put("players", list);
        return tag;
    }

    /** Relecture (publique pour le test d'aller-retour NBT). */
    public static RewardData load(CompoundTag tag, HolderLookup.Provider registries) {
        RewardData data = new RewardData();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag p = list.getCompound(i);
            if (p.hasUUID("uuid")) {
                data.players.put(p.getUUID("uuid"), PlayerRewards.load(p));
            }
        }
        return data;
    }

    /** État d'un joueur. Champs mutables, modifiés par {@code TerminalManager}, {@code DailyManager} et {@code CrateManager}. */
    public static final class PlayerRewards {
        /** Début de la fenêtre du barème du terminal (epoch ms, 0 = aucune). */
        public long window;
        /** Secondes actives dans la fenêtre courante. */
        public int activeSec;
        /** Épargne non encaissée, en 1/3600 cr. */
        public long units;
        /** Secondes actives cumulées depuis toujours (statistique). */
        public long lifetime;
        /** Dernier {@code /daily} (epoch ms, 0 = jamais). */
        public long dailyLast;
        /** Série de connexions courante (0 = aucune). */
        public int streak;
        private final EnumMap<CrateKind, Map<String, Integer>> pity = new EnumMap<>(CrateKind.class);

        /** Compteurs de pitié du type (identifiant d'entrée rare → ouvertures sans elle), en lecture seule. */
        public Map<String, Integer> pity(CrateKind kind) {
            Map<String, Integer> m = pity.get(kind);
            return m == null ? Map.of() : Collections.unmodifiableMap(m);
        }

        /** Remplace les compteurs du type (les identifiants absents de {@code counters} sont purgés). */
        public void setPity(CrateKind kind, Map<String, Integer> counters) {
            Map<String, Integer> copy = new LinkedHashMap<>();
            counters.forEach((id, n) -> copy.put(id, Math.max(0, n)));
            if (copy.isEmpty()) {
                pity.remove(kind);
            } else {
                pity.put(kind, copy);
            }
        }

        public boolean isEmpty() {
            return window == 0L && activeSec == 0 && units == 0L && lifetime == 0L && dailyLast == 0L && streak == 0
                    && pity.isEmpty();
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            CompoundTag terminal = new CompoundTag();
            terminal.putLong("window", window);
            terminal.putInt("activeSec", activeSec);
            terminal.putLong("units", units);
            terminal.putLong("lifetime", lifetime);
            tag.put("terminal", terminal);
            CompoundTag daily = new CompoundTag();
            daily.putLong("last", dailyLast);
            daily.putInt("streak", streak);
            tag.put("daily", daily);
            CompoundTag pityTag = new CompoundTag();
            for (Map.Entry<CrateKind, Map<String, Integer>> e : pity.entrySet()) {
                CompoundTag counters = new CompoundTag();
                e.getValue().forEach(counters::putInt);
                pityTag.put(e.getKey().id(), counters);
            }
            tag.put("pity", pityTag);
            return tag;
        }

        static PlayerRewards load(CompoundTag tag) {
            PlayerRewards r = new PlayerRewards();
            CompoundTag terminal = tag.getCompound("terminal");
            r.window = Math.max(0L, terminal.getLong("window"));
            r.activeSec = Math.max(0, terminal.getInt("activeSec"));
            r.units = Math.max(0L, terminal.getLong("units"));
            r.lifetime = Math.max(0L, terminal.getLong("lifetime"));
            CompoundTag daily = tag.getCompound("daily");
            r.dailyLast = Math.max(0L, daily.getLong("last"));
            r.streak = Math.max(0, daily.getInt("streak"));
            CompoundTag pityTag = tag.getCompound("pity");
            for (String key : pityTag.getAllKeys()) {
                CrateKind kind = CrateKind.byId(key);
                if (kind == null) {
                    continue;
                }
                CompoundTag counters = pityTag.getCompound(key);
                Map<String, Integer> map = new LinkedHashMap<>();
                for (String id : counters.getAllKeys()) {
                    map.put(id, counters.getInt(id));
                }
                r.setPity(kind, map);
            }
            return r;
        }
    }
}
