package io.github.theoblaise1.sovereignfrontier.war;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Relations de guerre entre nations, missiles en vol et cooldowns des silos (J7, docs/conception/J7.md §2.1), attachés à
 * l'Overworld. Les données ne vont pas dans {@code Nation} (format des nations inchangé). Les échéances sont des
 * timestamps epoch (ms) ; aucune tâche par tick.
 * <p>
 * Ce SavedData stocke et restitue, sans règle de transition : les règles sont dans {@link WarQueries} (fonctions pures)
 * et dans les lots de boucle et de commande.
 */
public final class WarData extends SavedData {
    private static final String FILE = SovereignFrontier.MODID + "_wars";
    private static final String RELATIONS = "relations";
    private static final String MISSILES = "missiles";
    private static final String COOLDOWNS = "cooldowns";

    private final Map<RelationKey, Relation> relations = new HashMap<>();
    private final List<PendingMissile> missiles = new ArrayList<>();
    private final Map<BlockPos, Long> siloCooldowns = new HashMap<>();

    public WarData() {
    }

    public static WarData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WarData::new, WarData::load, null), FILE);
    }

    // ------------------------------------------------------------------ types

    /** Paire non ordonnée de nations : {@code low} est le plus petit UUID, {@code high} le plus grand. */
    public record RelationKey(UUID low, UUID high) {
        public RelationKey {
            if (low.compareTo(high) > 0) {
                UUID swap = low;
                low = high;
                high = swap;
            }
        }

        public static RelationKey of(UUID a, UUID b) {
            return new RelationKey(a, b);
        }
    }

    /** Proposition de traité en attente : nation qui l'a faite et échéance en epoch ms. */
    public record Proposal(UUID proposerNationId, long expiresEpochMs) {
        public static final Codec<Proposal> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("proposer").forGetter(Proposal::proposerNationId),
                Codec.LONG.fieldOf("expires").forGetter(Proposal::expiresEpochMs)
        ).apply(i, Proposal::new));
    }

    /**
     * Relation entre deux nations. {@code startedEpochMs} et {@code untilEpochMs} bornent l'état courant (0 si sans objet).
     * {@code declarerNationId} est la nation qui a déclaré la guerre. {@code scoreA} est le score de
     * {@link RelationKey#low()}, {@code scoreB} celui de {@link RelationKey#high()} : utiliser {@link #scoreOf} et
     * {@link #withScoreOf} pour ne pas confondre les deux. {@code proposal} est le traité en attente, ou {@code null}.
     */
    public record Relation(WarState state, long startedEpochMs, long untilEpochMs, @Nullable UUID declarerNationId,
                           int scoreA, int scoreB, @Nullable Proposal proposal) {
        public static final Codec<Relation> CODEC = RecordCodecBuilder.create(i -> i.group(
                WarState.CODEC.fieldOf("state").forGetter(Relation::state),
                Codec.LONG.optionalFieldOf("started", 0L).forGetter(Relation::startedEpochMs),
                Codec.LONG.optionalFieldOf("until", 0L).forGetter(Relation::untilEpochMs),
                UUIDUtil.CODEC.optionalFieldOf("declarer").forGetter(r -> Optional.ofNullable(r.declarerNationId())),
                Codec.INT.optionalFieldOf("scoreA", 0).forGetter(Relation::scoreA),
                Codec.INT.optionalFieldOf("scoreB", 0).forGetter(Relation::scoreB),
                Proposal.CODEC.optionalFieldOf("proposal").forGetter(r -> Optional.ofNullable(r.proposal()))
        ).apply(i, (state, started, until, declarer, scoreA, scoreB, proposal) -> new Relation(
                state, started, until, declarer.orElse(null), scoreA, scoreB, proposal.orElse(null))));

        /** Score de {@code nation} dans cette relation, ou 0 si elle n'en fait pas partie. */
        public int scoreOf(RelationKey key, UUID nation) {
            if (nation.equals(key.low())) {
                return scoreA;
            }
            return nation.equals(key.high()) ? scoreB : 0;
        }

        /** Copie de la relation avec le score de {@code nation} remplacé ; inchangée si elle n'en fait pas partie. */
        public Relation withScoreOf(RelationKey key, UUID nation, int score) {
            if (nation.equals(key.low())) {
                return new Relation(state, startedEpochMs, untilEpochMs, declarerNationId, score, scoreB, proposal);
            }
            if (nation.equals(key.high())) {
                return new Relation(state, startedEpochMs, untilEpochMs, declarerNationId, scoreA, score, proposal);
            }
            return this;
        }
    }

    /**
     * Missile en vol : impact à {@code atEpochMs} sur le chunk ({@code chunkX}, {@code chunkZ}), tiré par
     * {@code launcherNationId} depuis le silo {@code siloPos}.
     */
    public record PendingMissile(long atEpochMs, int chunkX, int chunkZ, UUID launcherNationId, BlockPos siloPos) {
        public static final Codec<PendingMissile> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("at").forGetter(PendingMissile::atEpochMs),
                Codec.INT.fieldOf("cx").forGetter(PendingMissile::chunkX),
                Codec.INT.fieldOf("cz").forGetter(PendingMissile::chunkZ),
                UUIDUtil.CODEC.fieldOf("launcher").forGetter(PendingMissile::launcherNationId),
                BlockPos.CODEC.fieldOf("silo").forGetter(PendingMissile::siloPos)
        ).apply(i, PendingMissile::new));
    }

    /** Entrée sérialisée d'une relation : paire de nations et relation. */
    private record RelationEntry(RelationKey key, Relation relation) {
        private static final Codec<RelationEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("low").forGetter(e -> e.key().low()),
                UUIDUtil.CODEC.fieldOf("high").forGetter(e -> e.key().high()),
                Relation.CODEC.fieldOf("relation").forGetter(RelationEntry::relation)
        ).apply(i, (low, high, relation) -> new RelationEntry(new RelationKey(low, high), relation)));
    }

    /** Entrée sérialisée d'un cooldown de silo : position du silo et fin en epoch ms. */
    private record SiloCooldown(BlockPos pos, long untilEpochMs) {
        private static final Codec<SiloCooldown> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(SiloCooldown::pos),
                Codec.LONG.fieldOf("until").forGetter(SiloCooldown::untilEpochMs)
        ).apply(i, SiloCooldown::new));
    }

    // ------------------------------------------------------------------ relations

    /** Relation entre {@code a} et {@code b}, ou {@code null} si la paire n'en a pas (paix implicite). */
    @Nullable
    public Relation relation(UUID a, UUID b) {
        return relations.get(RelationKey.of(a, b));
    }

    /** État entre {@code a} et {@code b} ; {@link WarState#PAIX} si la paire n'a pas de relation. */
    public WarState state(UUID a, UUID b) {
        Relation relation = relation(a, b);
        return relation == null ? WarState.PAIX : relation.state();
    }

    /** Vue en lecture seule de toutes les relations. Copier la vue avant de parcourir si la boucle la modifie. */
    public Map<RelationKey, Relation> relations() {
        return Collections.unmodifiableMap(relations);
    }

    /** Pose ou remplace la relation entre {@code a} et {@code b}. */
    public void put(UUID a, UUID b, Relation relation) {
        relations.put(RelationKey.of(a, b), relation);
        setDirty();
    }

    /** Supprime la relation entre {@code a} et {@code b} (retour à la paix implicite). */
    public void remove(UUID a, UUID b) {
        if (relations.remove(RelationKey.of(a, b)) != null) {
            setDirty();
        }
    }

    // ------------------------------------------------------------------ missiles en vol

    /** Vue en lecture seule des missiles en vol. Copier la vue avant de parcourir si la boucle en retire. */
    public List<PendingMissile> missiles() {
        return Collections.unmodifiableList(missiles);
    }

    public void addMissile(PendingMissile missile) {
        missiles.add(missile);
        setDirty();
    }

    /** Retire un missile (égalité de valeur) ; {@code false} s'il n'était pas en vol. */
    public boolean removeMissile(PendingMissile missile) {
        boolean removed = missiles.remove(missile);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    // ------------------------------------------------------------------ cooldown des silos

    /** Fin du cooldown du silo en epoch ms, ou 0 si le silo n'en a pas. */
    public long siloCooldownUntil(BlockPos silo) {
        return siloCooldowns.getOrDefault(silo, 0L);
    }

    public void setSiloCooldown(BlockPos silo, long untilEpochMs) {
        siloCooldowns.put(silo.immutable(), untilEpochMs);
        setDirty();
    }

    // ------------------------------------------------------------------ persistance

    public static WarData load(CompoundTag tag, HolderLookup.Provider registries) {
        WarData data = new WarData();
        ListTag relationList = tag.getList(RELATIONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < relationList.size(); i++) {
            RelationEntry.CODEC.parse(NbtOps.INSTANCE, relationList.getCompound(i)).result()
                    .ifPresent(e -> data.relations.put(e.key(), e.relation()));
        }
        ListTag missileList = tag.getList(MISSILES, Tag.TAG_COMPOUND);
        for (int i = 0; i < missileList.size(); i++) {
            PendingMissile.CODEC.parse(NbtOps.INSTANCE, missileList.getCompound(i)).result()
                    .ifPresent(data.missiles::add);
        }
        ListTag cooldownList = tag.getList(COOLDOWNS, Tag.TAG_COMPOUND);
        for (int i = 0; i < cooldownList.size(); i++) {
            SiloCooldown.CODEC.parse(NbtOps.INSTANCE, cooldownList.getCompound(i)).result()
                    .ifPresent(c -> data.siloCooldowns.put(c.pos(), c.untilEpochMs()));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag relationList = new ListTag();
        for (Map.Entry<RelationKey, Relation> e : relations.entrySet()) {
            RelationEntry.CODEC.encodeStart(NbtOps.INSTANCE, new RelationEntry(e.getKey(), e.getValue()))
                    .result().ifPresent(relationList::add);
        }
        ListTag missileList = new ListTag();
        for (PendingMissile missile : missiles) {
            PendingMissile.CODEC.encodeStart(NbtOps.INSTANCE, missile).result().ifPresent(missileList::add);
        }
        ListTag cooldownList = new ListTag();
        for (Map.Entry<BlockPos, Long> e : siloCooldowns.entrySet()) {
            SiloCooldown.CODEC.encodeStart(NbtOps.INSTANCE, new SiloCooldown(e.getKey(), e.getValue()))
                    .result().ifPresent(cooldownList::add);
        }
        tag.put(RELATIONS, relationList);
        tag.put(MISSILES, missileList);
        tag.put(COOLDOWNS, cooldownList);
        return tag;
    }
}
