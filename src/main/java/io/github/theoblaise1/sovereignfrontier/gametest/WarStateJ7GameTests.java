package io.github.theoblaise1.sovereignfrontier.gametest;

import io.github.theoblaise1.sovereignfrontier.event.TerritoryEvents;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import io.github.theoblaise1.sovereignfrontier.war.WarData;
import io.github.theoblaise1.sovereignfrontier.war.WarQueries;
import io.github.theoblaise1.sovereignfrontier.war.WarRules;
import io.github.theoblaise1.sovereignfrontier.war.WarState;
import io.github.theoblaise1.sovereignfrontier.war.WarTicker;
import io.github.theoblaise1.sovereignfrontier.war.WarTransitions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests de la diplomatie et de la guerre, lot WP1 du J7 (docs/conception/J7.md §6) : déclaration refusée sous le
 * minimum de membres, préparation et annulation, passage en guerre à l'échéance, PvP selon l'état, trêve après guerre,
 * traité proposé puis accepté ou refusé, ambassade requise.
 * <p>
 * Chaque test a son lot {@code sf_j7_<test>}. Les tests qui fondent des nations les dissolvent dans un {@code finally}.
 * La seconde nation d'un test est fondée sur le chunk voisin de l'origine : une nation ne pose aucun bloc, seule sa
 * revendication compte. Les échéances sont ramenées dans le passé par {@link #expire} au lieu d'attendre l'horloge.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class WarStateJ7GameTests {
    private static final String AREA = "gametest/empty9";
    private static final long MS_PER_HOUR = 3_600_000L;

    private WarStateJ7GameTests() {
    }

    /** Déclaration refusée sous le minimum de membres (attaquant puis défenseur), puis permise à effectifs suffisants. */
    @GameTest(template = AREA, batch = "sf_j7_decl_min")
    public static void declarationRefusedBelowMinimumMembers(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, "sf_guerre_a");
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, "sf_guerre_b");
        try {
            Nation a = J6TestUtil.foundNation(helper, chefA, "WarSolo");
            Nation b = foundNeighbour(helper, chefB, "WarPair");
            ServerPlayer b1 = J6TestUtil.fakePlayer(helper, "sf_guerre_b1");
            ServerPlayer b2 = J6TestUtil.fakePlayer(helper, "sf_guerre_b2");
            recruit(helper, b, chefB, b1, b2);
            // a n'a que son chef ; b compte trois membres.
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.ATTACKER_TOO_SMALL, "attaquant seul");
            check(helper, WarTransitions.declare(server, b, a), WarQueries.DeclareCheck.DEFENDER_TOO_SMALL, "défenseur seul");
            check(helper, WarTransitions.declare(server, b, b), WarQueries.DeclareCheck.SAME_NATION, "même nation");
            helper.assertTrue(stateOf(server, a, b) == WarState.PAIX, "refus : toujours en paix");

            ServerPlayer a1 = J6TestUtil.fakePlayer(helper, "sf_guerre_a1");
            ServerPlayer a2 = J6TestUtil.fakePlayer(helper, "sf_guerre_a2");
            recruit(helper, a, chefA, a1, a2);
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.OK, "déclaration à effectifs suffisants");
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.ENGAGED, "seconde déclaration en préparation");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /** Préparation après déclaration (durée {@code preparationHours}), annulation sans trêve, sans effet hors préparation. */
    @GameTest(template = AREA, batch = "sf_j7_prep")
    public static void preparationCanBeCancelledWithoutTruce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, "sf_guerre_a");
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, "sf_guerre_b");
        try {
            Nation a = J6TestUtil.foundNation(helper, chefA, "WarPrep");
            Nation b = foundNeighbour(helper, chefB, "WarPrepB");
            recruit(helper, a, chefA, J6TestUtil.fakePlayer(helper, "sf_guerre_a1"), J6TestUtil.fakePlayer(helper, "sf_guerre_a2"));
            recruit(helper, b, chefB, J6TestUtil.fakePlayer(helper, "sf_guerre_b1"), J6TestUtil.fakePlayer(helper, "sf_guerre_b2"));
            WarData.RelationKey key = WarData.RelationKey.of(a.id(), b.id());

            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.OK, "déclaration");
            WarData.Relation preparation = relationOf(helper, server, a, b);
            helper.assertTrue(preparation.state() == WarState.PREPARATION, "déclaration : préparation");
            helper.assertTrue(a.id().equals(preparation.declarerNationId()), "déclaration : l'attaquant est le déclarant");
            helper.assertTrue(preparation.untilEpochMs() - preparation.startedEpochMs()
                    == SFConfig.PREPARATION_HOURS.get() * MS_PER_HOUR, "préparation : durée preparationHours");

            WarTransitions.cancelPreparation(server, key);
            helper.assertTrue(stateOf(server, a, b) == WarState.PAIX, "annulation : retour en paix");
            helper.assertTrue(WarData.get(server).relation(a.id(), b.id()) == null, "annulation : aucune trêve, relation retirée");
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.OK, "déclaration après annulation");

            WarTransitions.startWar(server, key);
            helper.assertTrue(stateOf(server, a, b) == WarState.GUERRE, "hostilités ouvertes");
            WarTransitions.cancelPreparation(server, key);
            helper.assertTrue(stateOf(server, a, b) == WarState.GUERRE, "annulation hors préparation sans effet");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /** La guerre ne commence qu'à l'échéance de la préparation, et dure {@code warHours}. */
    @GameTest(template = AREA, batch = "sf_j7_guerre")
    public static void warStartsOnlyAtPreparationDeadline(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, "sf_guerre_a");
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, "sf_guerre_b");
        try {
            Nation a = J6TestUtil.foundNation(helper, chefA, "WarDeadA");
            Nation b = foundNeighbour(helper, chefB, "WarDeadB");
            recruit(helper, a, chefA, J6TestUtil.fakePlayer(helper, "sf_guerre_a1"), J6TestUtil.fakePlayer(helper, "sf_guerre_a2"));
            recruit(helper, b, chefB, J6TestUtil.fakePlayer(helper, "sf_guerre_b1"), J6TestUtil.fakePlayer(helper, "sf_guerre_b2"));
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.OK, "déclaration");

            WarTicker.pass(server);
            helper.assertTrue(stateOf(server, a, b) == WarState.PREPARATION, "avant l'échéance : toujours en préparation");

            expire(helper, server, a, b);
            WarTicker.pass(server);
            helper.assertTrue(stateOf(server, a, b) == WarState.GUERRE, "échéance passée : guerre ouverte");
            WarData.Relation war = relationOf(helper, server, a, b);
            helper.assertTrue(war.untilEpochMs() - war.startedEpochMs() == SFConfig.WAR_HOURS.get() * MS_PER_HOUR,
                    "guerre : durée warHours");
            helper.assertTrue(war.scoreA() == 0 && war.scoreB() == 0, "guerre : scores à zéro");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /** PvP : refusé en paix, en préparation et hors nation ; accepté seulement entre nations en guerre. */
    @GameTest(template = AREA, batch = "sf_j7_pvp")
    public static void pvpOnlyBetweenNationsAtWar(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, "sf_guerre_a");
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, "sf_guerre_b");
        ServerPlayer loner = J6TestUtil.fakePlayer(helper, "sf_sans_nation");
        try {
            Nation a = J6TestUtil.foundNation(helper, chefA, "WarPvpA");
            Nation b = foundNeighbour(helper, chefB, "WarPvpB");
            ServerPlayer a1 = J6TestUtil.fakePlayer(helper, "sf_guerre_a1");
            ServerPlayer a2 = J6TestUtil.fakePlayer(helper, "sf_guerre_a2");
            ServerPlayer b1 = J6TestUtil.fakePlayer(helper, "sf_guerre_b1");
            recruit(helper, a, chefA, a1, a2);
            recruit(helper, b, chefB, b1, J6TestUtil.fakePlayer(helper, "sf_guerre_b2"));

            helper.assertFalse(WarRules.mayHurt(server, a1, b1), "paix : pas de PvP entre nations");
            helper.assertFalse(WarRules.mayHurt(server, a1, a2), "tir ami : interdit");
            helper.assertFalse(WarRules.mayHurt(server, loner, b1), "sans nation : pas de PvP");
            helper.assertFalse(WarRules.mayHurt(server, a1, loner), "sans nation : protégé");
            helper.assertTrue(coupAnnule(a1, b1), "paix : coup annulé par TerritoryEvents");

            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.OK, "déclaration");
            helper.assertFalse(WarRules.mayHurt(server, a1, b1), "préparation : pas de combat");

            expire(helper, server, a, b);
            WarTicker.pass(server);
            helper.assertTrue(WarRules.mayHurt(server, a1, b1), "guerre : PvP entre nations en guerre");
            helper.assertFalse(WarRules.mayHurt(server, a1, a2), "guerre : tir ami toujours interdit");
            helper.assertFalse(WarRules.mayHurt(server, loner, b1), "guerre : sans nation toujours protégé");
            // Témoin : le coup passe par TerritoryEvents et n'est pas annulé.
            helper.assertFalse(coupAnnule(a1, b1), "guerre : coup accepté par TerritoryEvents (témoin)");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /** Après une fin de guerre : trêve de {@code truceHours} (déclaration refusée), puis paix à l'échéance. */
    @GameTest(template = AREA, batch = "sf_j7_trace")
    public static void truceFollowsWarAndBlocksDeclaration(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, "sf_guerre_a");
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, "sf_guerre_b");
        try {
            Nation a = J6TestUtil.foundNation(helper, chefA, "WarTruceA");
            Nation b = foundNeighbour(helper, chefB, "WarTruceB");
            ServerPlayer a1 = J6TestUtil.fakePlayer(helper, "sf_guerre_a1");
            ServerPlayer b1 = J6TestUtil.fakePlayer(helper, "sf_guerre_b1");
            recruit(helper, a, chefA, a1, J6TestUtil.fakePlayer(helper, "sf_guerre_a2"));
            recruit(helper, b, chefB, b1, J6TestUtil.fakePlayer(helper, "sf_guerre_b2"));
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.OK, "déclaration");
            expire(helper, server, a, b);
            WarTicker.pass(server);
            helper.assertTrue(stateOf(server, a, b) == WarState.GUERRE, "guerre ouverte");

            WarTransitions.endWar(server, WarData.RelationKey.of(a.id(), b.id()), null, WarTransitions.WarEnd.ADMIN);
            helper.assertTrue(stateOf(server, a, b) == WarState.TREVE, "fin de guerre : trêve");
            WarData.Relation truce = relationOf(helper, server, a, b);
            helper.assertTrue(truce.untilEpochMs() - truce.startedEpochMs() == SFConfig.TRUCE_HOURS.get() * MS_PER_HOUR,
                    "trêve : durée truceHours");
            helper.assertTrue(truce.scoreA() == 0 && truce.scoreB() == 0, "fin de guerre : scores à zéro");
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.TRUCE, "déclaration pendant la trêve");
            helper.assertFalse(WarRules.mayHurt(server, a1, b1), "trêve : pas de PvP");

            expire(helper, server, a, b);
            WarTicker.pass(server);
            helper.assertTrue(stateOf(server, a, b) == WarState.PAIX, "trêve échue : paix");
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.OK, "déclaration après la trêve");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /** Traité : proposé en guerre, refusé par le destinataire, reproposé, puis accepté : la guerre passe en trêve. */
    @GameTest(template = AREA, batch = "sf_j7_traite")
    public static void treatyProposedRefusedThenAccepted(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, "sf_guerre_a");
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, "sf_guerre_b");
        try {
            // Le proposant a l'ambassade (palier civil_12, qui accorde diplomacy.embassy).
            Nation a = J6TestUtil.foundNation(helper, chefA, "WarTraiteA", "civil_12");
            Nation b = foundNeighbour(helper, chefB, "WarTraiteB");
            recruit(helper, a, chefA, J6TestUtil.fakePlayer(helper, "sf_guerre_a1"), J6TestUtil.fakePlayer(helper, "sf_guerre_a2"));
            recruit(helper, b, chefB, J6TestUtil.fakePlayer(helper, "sf_guerre_b1"), J6TestUtil.fakePlayer(helper, "sf_guerre_b2"));
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.OK, "déclaration");
            expire(helper, server, a, b);
            WarTicker.pass(server);

            helper.assertTrue(WarTransitions.propose(server, a, b) == null, "traité proposé en guerre");
            WarData.Relation offer = relationOf(helper, server, a, b);
            helper.assertTrue(offer.proposal() != null && a.id().equals(offer.proposal().proposerNationId()),
                    "traité : proposition enregistrée au nom du proposant");
            helper.assertTrue(WarTransitions.propose(server, a, b) != null, "traité : une seule proposition en attente");
            helper.assertTrue(WarTransitions.accept(server, a, b) != null, "traité : le proposant ne peut pas accepter sa propre proposition");

            helper.assertTrue(WarTransitions.refuse(server, b, a) == null, "traité : refus par le destinataire");
            helper.assertTrue(relationOf(helper, server, a, b).proposal() == null, "traité refusé : proposition retirée");
            helper.assertTrue(stateOf(server, a, b) == WarState.GUERRE, "traité refusé : la guerre continue");

            helper.assertTrue(WarTransitions.propose(server, a, b) == null, "traité : nouvelle proposition après refus");
            helper.assertTrue(WarTransitions.accept(server, b, a) == null, "traité : acceptation par le destinataire");
            helper.assertTrue(stateOf(server, a, b) == WarState.TREVE, "traité signé : trêve");
            helper.assertTrue(relationOf(helper, server, a, b).proposal() == null, "traité signé : aucune proposition");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /** Ambassade requise pour proposer un traité : refus sans {@code diplomacy.embassy}, accepté après civil_12. */
    @GameTest(template = AREA, batch = "sf_j7_ambassade")
    public static void embassyRequiredToPropose(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, "sf_guerre_a");
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, "sf_guerre_b");
        try {
            Nation a = J6TestUtil.foundNation(helper, chefA, "WarAmbA");
            Nation b = foundNeighbour(helper, chefB, "WarAmbB");
            recruit(helper, a, chefA, J6TestUtil.fakePlayer(helper, "sf_guerre_a1"), J6TestUtil.fakePlayer(helper, "sf_guerre_a2"));
            recruit(helper, b, chefB, J6TestUtil.fakePlayer(helper, "sf_guerre_b1"), J6TestUtil.fakePlayer(helper, "sf_guerre_b2"));
            check(helper, WarTransitions.declare(server, a, b), WarQueries.DeclareCheck.OK, "déclaration");
            expire(helper, server, a, b);
            WarTicker.pass(server);

            helper.assertFalse(a.research().hasUnlock(UnlockKeys.DIPLOMACY_EMBASSY), "sans palier : pas d'ambassade");
            helper.assertTrue(WarTransitions.propose(server, a, b) != null, "ambassade requise : proposition refusée");
            helper.assertTrue(relationOf(helper, server, a, b).proposal() == null, "ambassade requise : aucune proposition");

            J5TestUtil.complete(helper, a, "civil_12");
            helper.assertTrue(a.research().hasUnlock(UnlockKeys.DIPLOMACY_EMBASSY), "palier civil_12 : ambassade accordée");
            helper.assertTrue(WarTransitions.propose(server, a, b) == null, "avec ambassade : proposition acceptée");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ aides

    /** Seconde nation, fondée sur le chunk voisin de l'origine (seule la revendication compte). */
    private static Nation foundNeighbour(GameTestHelper helper, ServerPlayer chef, String base) {
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        J4TestUtil.moveTo(helper, chef, new ChunkPos(origin.x + 1, origin.z));
        J4TestUtil.expect(helper, NationManager.create(chef, J4TestUtil.unique(helper, base)), true,
                "fondation de la seconde nation");
        Nation nation = NationData.get(helper.getLevel().getServer()).nationOf(chef.getUUID());
        helper.assertTrue(nation != null, "seconde nation introuvable après sa fondation");
        return nation;
    }

    /** Fait entrer {@code members} dans {@code nation} par invitation puis adhésion. */
    private static void recruit(GameTestHelper helper, Nation nation, ServerPlayer chef, ServerPlayer... members) {
        for (ServerPlayer member : members) {
            J4TestUtil.expect(helper, NationManager.invite(chef, member), true, "invitation");
            J4TestUtil.expect(helper, NationManager.join(member, nation.name()), true, "adhésion");
        }
    }

    /** Vérifie le verdict de {@link WarTransitions#declare} ou de {@link WarQueries#canDeclare}. */
    private static void check(GameTestHelper helper, WarQueries.DeclareCheck actual, WarQueries.DeclareCheck expected,
                              String step) {
        helper.assertTrue(actual == expected, step + " : attendu " + expected + ", obtenu " + actual);
    }

    private static WarState stateOf(MinecraftServer server, Nation a, Nation b) {
        return WarData.get(server).state(a.id(), b.id());
    }

    private static WarData.Relation relationOf(GameTestHelper helper, MinecraftServer server, Nation a, Nation b) {
        WarData.Relation relation = WarData.get(server).relation(a.id(), b.id());
        helper.assertTrue(relation != null, "relation attendue entre " + a.name() + " et " + b.name());
        return relation;
    }

    /** Ramène l'échéance de la relation dans le passé : la boucle la traitera à la passe suivante. */
    private static void expire(GameTestHelper helper, MinecraftServer server, Nation a, Nation b) {
        WarData.Relation relation = relationOf(helper, server, a, b);
        WarData.get(server).put(a.id(), b.id(), new WarData.Relation(relation.state(), relation.startedEpochMs(),
                TimeUtil.now() - 1L, relation.declarerNationId(), relation.scoreA(), relation.scoreB(), relation.proposal()));
    }

    /** Nettoyage : relation retirée, puis dissolution des deux nations (coffre vidé par J6TestUtil.disband). */
    private static void cleanup(MinecraftServer server, ServerPlayer chefA, ServerPlayer chefB) {
        Nation a = NationData.get(server).nationOf(chefA.getUUID());
        Nation b = NationData.get(server).nationOf(chefB.getUUID());
        if (a != null && b != null) {
            WarData.get(server).remove(a.id(), b.id());
        }
        J6TestUtil.disband(chefA);
        J6TestUtil.disband(chefB);
    }

    /**
     * Passe un coup de {@code attacker} sur {@code victim} par l'événement réel de TerritoryEvents : vrai s'il est annulé.
     * Un {@code FakePlayer} est invulnérable à {@code hurt} (NeoForge), donc l'événement est construit à la main.
     */
    private static boolean coupAnnule(ServerPlayer attacker, ServerPlayer victim) {
        LivingIncomingDamageEvent event = new LivingIncomingDamageEvent(victim,
                new DamageContainer(victim.level().damageSources().playerAttack(attacker), 4.0F));
        TerritoryEvents.onIncomingDamage(event);
        return event.isCanceled();
    }
}
