package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transitions de l'état de guerre entre deux nations (J7, docs/conception/J7.md §1.1, §1.3, §1.4) : déclaration,
 * préparation, ouverture des hostilités, fin de guerre (traité, victoire, délai, administrateur), trêve, traités.
 * <p>
 * Chaque transition persiste ses échéances en epoch ms, écrit le journal des nations concernées et prévient leurs
 * membres connectés. Une transition appelée hors de son état de départ ne fait rien. Les échéances sont posées par
 * {@link TimeUtil#now()} : la boucle {@link WarTicker} ne fait que les déclencher.
 */
public final class WarTransitions {
    private static final long MS_PER_HOUR = 3_600_000L;

    /** Raison d'une fin de guerre (message et journal). */
    public enum WarEnd {
        /** Traité accepté : pas de vainqueur, pas de score. */
        TREATY,
        /** Score {@code killsToWin} atteint : le vainqueur est passé à {@code endWar}. */
        VICTORY,
        /** Délai {@code warHours} écoulé : le plus grand score gagne, égalité = nul. */
        TIMEOUT,
        /** Fin ordonnée par un administrateur, sans score. */
        ADMIN
    }

    /** Proposition de traité reçue par une nation : nation qui la fait et proposition. */
    public record Incoming(Nation proposer, WarData.Proposal proposal) {
    }

    /** Les deux nations d'une relation, toutes deux existantes. */
    private record Pair(Nation low, Nation high) {
    }

    private WarTransitions() {
    }

    // ------------------------------------------------------------------ PAIX -> PREPARATION

    /**
     * Déclare la guerre de {@code attacker} à {@code defender} : préparation de {@code preparationHours}. Le verdict de
     * {@link WarQueries#canDeclare} est renvoyé ; seul {@link WarQueries.DeclareCheck#OK} modifie l'état.
     */
    public static WarQueries.DeclareCheck declare(MinecraftServer server, Nation attacker, Nation defender) {
        WarData data = WarData.get(server);
        long now = TimeUtil.now();
        WarQueries.DeclareCheck check = WarQueries.canDeclare(data, attacker.id(), defender.id(),
                attacker.memberCount(), defender.memberCount(), now);
        if (check != WarQueries.DeclareCheck.OK) {
            return check;
        }
        long preparationMs = SFConfig.PREPARATION_HOURS.get() * MS_PER_HOUR;
        data.put(attacker.id(), defender.id(), new WarData.Relation(WarState.PREPARATION, now, now + preparationMs,
                attacker.id(), 0, 0, null));
        String delay = TimeUtil.duration(preparationMs);
        both(server, attacker, defender, Msg.warn("war.declared", attacker.name(), defender.name(), delay));
        attacker.log("war_declared", defender.name(), delay);
        defender.log("war_prepared", attacker.name(), delay);
        return check;
    }

    /** Annule une préparation (aucune guerre n'a commencé : retour en paix, pas de trêve). Sans effet hors préparation. */
    public static void cancelPreparation(MinecraftServer server, WarData.RelationKey key) {
        WarData data = WarData.get(server);
        WarData.Relation relation = data.relations().get(key);
        if (relation == null || relation.state() != WarState.PREPARATION) {
            return;
        }
        Pair pair = pairOf(server, data, key);
        if (pair == null) {
            return;
        }
        data.remove(key.low(), key.high());
        both(server, pair.low(), pair.high(), Msg.info("war.cancelled", pair.low().name(), pair.high().name()));
        pair.low().log("war_cancelled", pair.high().name());
        pair.high().log("war_cancelled", pair.low().name());
    }

    // ------------------------------------------------------------------ PREPARATION -> GUERRE

    /** Ouvre les hostilités : {@code untilEpochMs} = maintenant + {@code warHours}. Sans effet hors préparation. */
    public static void startWar(MinecraftServer server, WarData.RelationKey key) {
        WarData data = WarData.get(server);
        WarData.Relation relation = data.relations().get(key);
        if (relation == null || relation.state() != WarState.PREPARATION) {
            return;
        }
        Pair pair = pairOf(server, data, key);
        if (pair == null) {
            return;
        }
        long now = TimeUtil.now();
        long warMs = SFConfig.WAR_HOURS.get() * MS_PER_HOUR;
        data.put(key.low(), key.high(), new WarData.Relation(WarState.GUERRE, now, now + warMs,
                relation.declarerNationId(), 0, 0, null));
        String duration = TimeUtil.duration(warMs);
        both(server, pair.low(), pair.high(), Msg.warn("war.started", pair.low().name(), pair.high().name(), duration));
        pair.low().log("war_started", pair.high().name());
        pair.high().log("war_started", pair.low().name());
    }

    // ------------------------------------------------------------------ GUERRE -> TREVE

    /**
     * Termine une guerre : la relation passe en {@code TREVE} de {@code truceHours} (ou en paix si ce délai est nul), les
     * scores repartent de zéro, la proposition en attente est retirée. Sans effet hors guerre.
     *
     * @param winner nation gagnante pour {@link WarEnd#VICTORY} et {@link WarEnd#TIMEOUT} ; {@code null} : nul
     */
    public static void endWar(MinecraftServer server, WarData.RelationKey key, @Nullable UUID winner, WarEnd reason) {
        WarData data = WarData.get(server);
        WarData.Relation relation = data.relations().get(key);
        if (relation == null || relation.state() != WarState.GUERRE) {
            return;
        }
        Pair pair = pairOf(server, data, key);
        if (pair == null) {
            return;
        }
        Nation low = pair.low();
        Nation high = pair.high();
        UUID won = winner != null && (winner.equals(key.low()) || winner.equals(key.high())) ? winner : null;
        int lowScore = relation.scoreOf(key, key.low());
        int highScore = relation.scoreOf(key, key.high());
        long truceMs = SFConfig.TRUCE_HOURS.get() * MS_PER_HOUR;
        switch (reason) {
            case TREATY -> {
                String truce = TimeUtil.duration(truceMs);
                both(server, low, high, Msg.ok("treaty.signed", low.name(), high.name(), truce));
                low.log("treaty_signed", high.name(), truce);
                high.log("treaty_signed", low.name(), truce);
            }
            case VICTORY, TIMEOUT -> scored(server, reason, low, high, lowScore, highScore, won);
            case ADMIN -> both(server, low, high, Msg.info("war.admin.ended", low.name(), high.name()));
        }
        if (truceMs > 0) {
            long now = TimeUtil.now();
            data.put(key.low(), key.high(), new WarData.Relation(WarState.TREVE, now, now + truceMs,
                    relation.declarerNationId(), 0, 0, null));
        } else {
            data.remove(key.low(), key.high());
        }
    }

    /** Message et journal d'une fin au score : victoire (clé militaire attribuée par WP2) ou délai. */
    private static void scored(MinecraftServer server, WarEnd reason, Nation low, Nation high, int lowScore, int highScore,
                               @Nullable UUID winner) {
        if (winner == null) {
            both(server, low, high, Msg.info("war.timeout.draw", low.name(), high.name(), lowScore, highScore));
            low.log("war_timeout", high.name(), String.valueOf(lowScore), String.valueOf(highScore));
            high.log("war_timeout", low.name(), String.valueOf(highScore), String.valueOf(lowScore));
            return;
        }
        boolean lowWins = winner.equals(low.id());
        Nation won = lowWins ? low : high;
        Nation lost = lowWins ? high : low;
        int wonScore = lowWins ? lowScore : highScore;
        int lostScore = lowWins ? highScore : lowScore;
        if (reason == WarEnd.VICTORY) {
            both(server, low, high, Msg.ok("war.won", won.name(), lost.name()));
            won.log("war_won", lost.name(), String.valueOf(wonScore), String.valueOf(lostScore));
            return;
        }
        both(server, low, high, Msg.info("war.timeout.winner", won.name(), lost.name(), wonScore, lostScore));
        low.log("war_timeout", high.name(), String.valueOf(lowScore), String.valueOf(highScore));
        high.log("war_timeout", low.name(), String.valueOf(highScore), String.valueOf(lowScore));
        won.log("war_won", lost.name(), String.valueOf(wonScore), String.valueOf(lostScore));
    }

    // ------------------------------------------------------------------ traités

    /**
     * Propose un traité de paix : seulement en guerre, ambassade requise pour le proposant, une seule proposition en
     * attente par relation. Renvoie l'erreur, ou {@code null} si la proposition est enregistrée.
     */
    @Nullable
    public static Component propose(MinecraftServer server, Nation proposer, Nation receiver) {
        if (!proposer.research().hasUnlock(UnlockKeys.DIPLOMACY_EMBASSY)) {
            return Msg.err("treaty.error.embassy");
        }
        WarData data = WarData.get(server);
        WarData.Relation relation = data.relation(proposer.id(), receiver.id());
        if (relation == null || relation.state() != WarState.GUERRE) {
            return Msg.err("treaty.error.not_at_war", receiver.name());
        }
        long now = TimeUtil.now();
        WarData.Proposal current = relation.proposal();
        if (current != null && now < current.expiresEpochMs()) {
            return Msg.err("treaty.error.pending", receiver.name());
        }
        long validMs = SFConfig.TREATY_HOURS.get() * MS_PER_HOUR;
        data.put(proposer.id(), receiver.id(), withProposal(relation, new WarData.Proposal(proposer.id(), now + validMs)));
        String valid = TimeUtil.duration(validMs);
        NationManager.broadcast(server, proposer, Msg.info("treaty.sent", receiver.name(), valid));
        NationManager.broadcast(server, receiver, Msg.warn("treaty.proposed", proposer.name(), receiver.name(), valid));
        proposer.log("treaty_proposed", receiver.name());
        receiver.log("treaty_proposed", proposer.name());
        return null;
    }

    /**
     * Accepte la proposition de {@code proposer} : la guerre se termine par un traité ({@link WarEnd#TREATY}).
     * Renvoie l'erreur, ou {@code null} si la guerre est terminée.
     */
    @Nullable
    public static Component accept(MinecraftServer server, Nation receiver, Nation proposer) {
        WarData data = WarData.get(server);
        WarData.RelationKey key = WarData.RelationKey.of(receiver.id(), proposer.id());
        WarData.Relation relation = data.relations().get(key);
        WarData.Proposal proposal = pendingFrom(key, relation, receiver.id());
        if (proposal == null) {
            return Msg.err("treaty.error.none");
        }
        if (TimeUtil.now() >= proposal.expiresEpochMs()) {
            data.put(receiver.id(), proposer.id(), withProposal(relation, null));
            return Msg.err("treaty.error.expired");
        }
        endWar(server, key, null, WarEnd.TREATY);
        return null;
    }

    /**
     * Refuse la proposition de {@code proposer} : elle est retirée, les deux nations sont prévenues. Renvoie l'erreur, ou
     * {@code null} si elle a été refusée.
     */
    @Nullable
    public static Component refuse(MinecraftServer server, Nation receiver, Nation proposer) {
        WarData data = WarData.get(server);
        WarData.RelationKey key = WarData.RelationKey.of(receiver.id(), proposer.id());
        WarData.Relation relation = data.relations().get(key);
        WarData.Proposal proposal = pendingFrom(key, relation, receiver.id());
        if (proposal == null) {
            return Msg.err("treaty.error.none");
        }
        data.put(receiver.id(), proposer.id(), withProposal(relation, null));
        if (TimeUtil.now() >= proposal.expiresEpochMs()) {
            return Msg.err("treaty.error.expired");
        }
        both(server, receiver, proposer, Msg.info("treaty.refused", receiver.name()));
        receiver.log("treaty_refused", proposer.name());
        proposer.log("treaty_refused", receiver.name());
        return null;
    }

    /** Propositions non expirées reçues par {@code receiver}, dans les guerres en cours. */
    public static List<Incoming> incoming(MinecraftServer server, Nation receiver) {
        WarData data = WarData.get(server);
        NationData nations = NationData.get(server);
        long now = TimeUtil.now();
        List<Incoming> out = new ArrayList<>();
        for (Map.Entry<WarData.RelationKey, WarData.Relation> entry : data.relations().entrySet()) {
            WarData.Proposal proposal = pendingFrom(entry.getKey(), entry.getValue(), receiver.id());
            if (proposal == null || now >= proposal.expiresEpochMs()) {
                continue;
            }
            Nation proposer = nations.byId(proposal.proposerNationId());
            if (proposer != null) {
                out.add(new Incoming(proposer, proposal));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ aides

    /** Proposition en attente, émise par l'autre nation de la relation, à destination de {@code receiver}. */
    @Nullable
    private static WarData.Proposal pendingFrom(WarData.RelationKey key, @Nullable WarData.Relation relation, UUID receiver) {
        if (relation == null || relation.state() != WarState.GUERRE || relation.proposal() == null) {
            return null;
        }
        if (!key.low().equals(receiver) && !key.high().equals(receiver)) {
            return null;
        }
        return relation.proposal().proposerNationId().equals(receiver) ? null : relation.proposal();
    }

    private static WarData.Relation withProposal(WarData.Relation relation, @Nullable WarData.Proposal proposal) {
        return new WarData.Relation(relation.state(), relation.startedEpochMs(), relation.untilEpochMs(),
                relation.declarerNationId(), relation.scoreA(), relation.scoreB(), proposal);
    }

    /** Les deux nations de la relation, ou {@code null} si l'une n'existe plus (la relation est alors retirée). */
    @Nullable
    private static Pair pairOf(MinecraftServer server, WarData data, WarData.RelationKey key) {
        NationData nations = NationData.get(server);
        Nation low = nations.byId(key.low());
        Nation high = nations.byId(key.high());
        if (low == null || high == null) {
            data.remove(key.low(), key.high());
            return null;
        }
        return new Pair(low, high);
    }

    private static void both(MinecraftServer server, Nation a, Nation b, Component message) {
        NationManager.broadcast(server, a, message);
        NationManager.broadcast(server, b, message);
    }
}
