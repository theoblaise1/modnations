// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.gametest;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.LogEntry;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationAdmin;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import io.github.theoblaise1.sovereignfrontier.war.WarData;
import io.github.theoblaise1.sovereignfrontier.war.WarProtections;
import io.github.theoblaise1.sovereignfrontier.war.WarScore;
import io.github.theoblaise1.sovereignfrontier.war.WarState;
import io.github.theoblaise1.sovereignfrontier.war.WarVictory;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Score, victoire et capitale de guerre (J7, docs/conception/J7.md §6, WP2). Zone : gabarit {@link J6TestUtil#AREA}.
 * Chaque test fonde ses nations dans son propre lot {@code sf_j7_score_<test>} et les dissout dans un {@code finally},
 * avec la relation de guerre retirée. La relation est posée directement dans {@link WarData} : ces tests ne dépendent
 * pas des transitions de WP1 (sauf l'état final {@code TREVE} après une fin de guerre).
 * <p>
 * La première nation a pour capitale le chunk de la zone ; la seconde est fondée dans le chunk voisin à l'est, comme
 * dans {@code ProtectionGameTests}. Les chefs de test ne font aucune interaction avec les blocs.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class WarScoreJ7GameTests {
    private static final String AREA = J6TestUtil.AREA;
    private static final long HOUR_MS = 3_600_000L;

    private WarScoreJ7GameTests() {
    }

    /** Seconde nation : chef placé dans le chunk voisin à l'est du chunk de la zone (capitale distincte). */
    private static Nation foundRival(GameTestHelper helper, ServerPlayer chef, String base) {
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        J4TestUtil.moveTo(helper, chef, new ChunkPos(origin.x + 1, origin.z));
        J4TestUtil.expect(helper, NationManager.create(chef, J4TestUtil.unique(helper, base)), true,
                "fondation de la nation adverse");
        Nation nation = NationData.get(helper.getLevel().getServer()).nationOf(chef.getUUID());
        helper.assertTrue(nation != null, "nation adverse introuvable après sa fondation");
        return nation;
    }

    /** Pose une relation entre les deux nations, dans l'état donné, sans passer par les transitions. */
    private static void setWar(MinecraftServer server, UUID attacker, UUID defender, WarState state) {
        long now = TimeUtil.now();
        long until = now + SFConfig.WAR_HOURS.get() * HOUR_MS;
        WarData.get(server).put(attacker, defender, new WarData.Relation(state, now, until, attacker, 0, 0, null));
    }

    private static int scoreOf(MinecraftServer server, UUID a, UUID b, UUID nation) {
        WarData.Relation relation = WarData.get(server).relation(a, b);
        return relation == null ? 0 : relation.scoreOf(WarData.RelationKey.of(a, b), nation);
    }

    private static WarState stateOf(MinecraftServer server, UUID a, UUID b) {
        return WarData.get(server).state(a, b);
    }

    private static int keys(Nation nation) {
        return nation.chest().countItem(CrateKind.MILITAIRE.keyItem());
    }

    private static boolean hasLog(Nation nation, String key) {
        for (LogEntry entry : nation.logEntries()) {
            if (entry.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Vrai si la sauvegarde de la nation {@code id} contient la capitale {@code chunk} de la dimension {@code dim}. */
    private static boolean savedCapital(CompoundTag saved, UUID id, String dim, ChunkPos chunk) {
        ListTag nations = saved.getList("nations", Tag.TAG_COMPOUND);
        for (int i = 0; i < nations.size(); i++) {
            CompoundTag entry = nations.getCompound(i);
            if (id.equals(entry.getUUID("id"))) {
                CompoundTag capital = entry.getCompound("capital");
                return dim.equals(capital.getString("dim")) && capital.getInt("x") == chunk.x && capital.getInt("z") == chunk.z;
            }
        }
        return false;
    }

    /** Retire la relation entre les nations des deux chefs (si elles existent), puis dissout les deux nations. */
    private static void cleanup(MinecraftServer server, ServerPlayer chefA, ServerPlayer chefB) {
        NationData nations = NationData.get(server);
        Nation a = nations.nationOf(chefA.getUUID());
        Nation b = nations.nationOf(chefB.getUUID());
        if (a != null && b != null) {
            WarData.get(server).remove(a.id(), b.id());
        }
        J6TestUtil.disband(chefA);
        J6TestUtil.disband(chefB);
    }

    // ------------------------------------------------------------------ points

    /** Un meurtre en guerre donne un point au tueur, et à lui seul ; la réciproque donne un point à l'autre. */
    @GameTest(template = AREA, batch = "sf_j7_score_point")
    public static void killInWarGivesOnePoint(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefA"));
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefB"));
        try {
            UUID a = J6TestUtil.foundNation(helper, chefA, "SfScoreA").id();
            UUID b = foundRival(helper, chefB, "SfScoreB").id();
            setWar(server, a, b, WarState.GUERRE);
            WarScore.recordKill(server, a, b);
            helper.assertTrue(scoreOf(server, a, b, a) == 1, "meurtre en guerre : 1 point pour le tueur");
            helper.assertTrue(scoreOf(server, a, b, b) == 0, "meurtre en guerre : aucun point pour la victime");
            WarScore.recordKill(server, b, a);
            helper.assertTrue(scoreOf(server, a, b, b) == 1 && scoreOf(server, a, b, a) == 1,
                    "meurtre réciproque : 1 point chacun");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /** Hors guerre (aucune relation, puis préparation) : aucun point et aucun changement d'état. */
    @GameTest(template = AREA, batch = "sf_j7_score_hors")
    public static void killOutsideWarGivesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefA"));
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefB"));
        try {
            UUID a = J6TestUtil.foundNation(helper, chefA, "SfScoreA").id();
            UUID b = foundRival(helper, chefB, "SfScoreB").id();
            WarScore.recordKill(server, a, b);
            helper.assertTrue(WarData.get(server).relation(a, b) == null, "en paix : aucune relation créée");
            setWar(server, a, b, WarState.PREPARATION);
            WarScore.recordKill(server, a, b);
            helper.assertTrue(scoreOf(server, a, b, a) == 0, "en préparation : aucun point");
            helper.assertTrue(stateOf(server, a, b) == WarState.PREPARATION, "en préparation : état inchangé");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /**
     * L'événement de mort compte pour le score : un joueur ennemi tué par un joueur de l'autre nation donne un point ;
     * un joueur sans nation, dans un sens ou dans l'autre, n'en donne aucun.
     */
    @GameTest(template = AREA, batch = "sf_j7_score_mort")
    public static void deathEventCountsEnemyPlayersOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefA"));
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefB"));
        ServerPlayer lone = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScSeul"));
        try {
            UUID a = J6TestUtil.foundNation(helper, chefA, "SfScoreA").id();
            UUID b = foundRival(helper, chefB, "SfScoreB").id();
            setWar(server, a, b, WarState.GUERRE);
            NeoForge.EVENT_BUS.post(new LivingDeathEvent(chefB, helper.getLevel().damageSources().playerAttack(chefA)));
            helper.assertTrue(scoreOf(server, a, b, a) == 1, "mort d'un joueur ennemi : 1 point pour le tueur");
            NeoForge.EVENT_BUS.post(new LivingDeathEvent(lone, helper.getLevel().damageSources().playerAttack(chefA)));
            helper.assertTrue(scoreOf(server, a, b, a) == 1, "joueur sans nation tué : aucun point");
            NeoForge.EVENT_BUS.post(new LivingDeathEvent(chefB, helper.getLevel().damageSources().playerAttack(lone)));
            helper.assertTrue(scoreOf(server, a, b, a) == 1 && scoreOf(server, a, b, b) == 0,
                    "tué par un joueur sans nation : aucun point");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ victoires

    /**
     * À {@code killsToWin} points, la nation tueuse reçoit une clé militaire dans son coffre, le journal enregistre la
     * victoire, la guerre se termine ; un meurtre suivant ne donne plus rien.
     */
    @GameTest(template = AREA, batch = "sf_j7_score_victoire")
    public static void victoryAtKillsToWinGivesKeyAndEndsWar(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefA"));
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefB"));
        try {
            Nation a = J6TestUtil.foundNation(helper, chefA, "SfScoreA");
            Nation b = foundRival(helper, chefB, "SfScoreB");
            setWar(server, a.id(), b.id(), WarState.GUERRE);
            int kills = SFConfig.KILLS_TO_WIN.get();
            for (int i = 1; i < kills; i++) {
                WarScore.recordKill(server, a.id(), b.id());
            }
            helper.assertTrue(keys(a) == 0, "avant le dernier point : aucune clé");
            WarScore.recordKill(server, a.id(), b.id());
            helper.assertTrue(scoreOf(server, a.id(), b.id(), a.id()) == 0, "victoire : scores remis à zéro");
            helper.assertTrue(keys(a) == 1, "victoire : une clé militaire dans le coffre du vainqueur");
            helper.assertTrue(keys(b) == 0, "victoire : aucune clé pour le perdant");
            helper.assertTrue(hasLog(a, "war_won"), "victoire : entrée war_won au journal du vainqueur");
            helper.assertTrue(stateOf(server, a.id(), b.id()) == WarState.TREVE, "victoire : guerre terminée, trêve");
            WarScore.recordKill(server, a.id(), b.id());
            helper.assertTrue(keys(a) == 1, "après la victoire : aucune nouvelle clé");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /** Délai écoulé : la nation au plus grand score reçoit la clé ; l'autre n'en reçoit pas. */
    @GameTest(template = AREA, batch = "sf_j7_score_delai")
    public static void timeoutGivesKeyToHigherScore(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefA"));
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefB"));
        try {
            Nation a = J6TestUtil.foundNation(helper, chefA, "SfScoreA");
            Nation b = foundRival(helper, chefB, "SfScoreB");
            WarData.RelationKey key = WarData.RelationKey.of(a.id(), b.id());
            long now = TimeUtil.now();
            WarData.Relation war = new WarData.Relation(WarState.GUERRE, now, now + HOUR_MS, a.id(), 0, 0, null)
                    .withScoreOf(key, a.id(), 3)
                    .withScoreOf(key, b.id(), 1);
            WarData.get(server).put(a.id(), b.id(), war);
            WarVictory.onTimeout(server, key);
            helper.assertTrue(keys(a) == 1, "délai : la nation au plus grand score reçoit la clé");
            helper.assertTrue(keys(b) == 0, "délai : pas de clé pour la nation au plus petit score");
            helper.assertTrue(hasLog(a, "war_timeout") && hasLog(b, "war_timeout"), "délai : war_timeout dans les deux journaux");
            helper.assertTrue(stateOf(server, a.id(), b.id()) == WarState.TREVE, "délai : guerre terminée, trêve");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    /** Délai écoulé à égalité : nul, aucune clé, journal de délai dans les deux nations. */
    @GameTest(template = AREA, batch = "sf_j7_score_nul")
    public static void timeoutDrawGivesNoKey(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chefA = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefA"));
        ServerPlayer chefB = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefB"));
        try {
            Nation a = J6TestUtil.foundNation(helper, chefA, "SfScoreA");
            Nation b = foundRival(helper, chefB, "SfScoreB");
            WarData.RelationKey key = WarData.RelationKey.of(a.id(), b.id());
            long now = TimeUtil.now();
            WarData.Relation war = new WarData.Relation(WarState.GUERRE, now, now + HOUR_MS, a.id(), 0, 0, null)
                    .withScoreOf(key, a.id(), 2)
                    .withScoreOf(key, b.id(), 2);
            WarData.get(server).put(a.id(), b.id(), war);
            WarVictory.onTimeout(server, key);
            helper.assertTrue(keys(a) == 0 && keys(b) == 0, "égalité : aucune clé");
            helper.assertTrue(!hasLog(a, "war_won") && !hasLog(b, "war_won"), "égalité : aucun war_won");
            helper.assertTrue(hasLog(a, "war_timeout") && hasLog(b, "war_timeout"), "égalité : war_timeout dans les deux journaux");
            helper.assertTrue(stateOf(server, a.id(), b.id()) == WarState.TREVE, "égalité : guerre terminée, trêve");
        } finally {
            cleanup(server, chefA, chefB);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ capitale

    /**
     * La capitale est le chunk de fondation, et seulement lui : un chunk non possédé, ou possédé mais non fondateur, n'est
     * pas une capitale (règle des missiles, WP3).
     */
    @GameTest(template = AREA, batch = "sf_j7_score_capitale")
    public static void capitalIsOnlyTheFoundingChunk(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var dimension = helper.getLevel().dimension();
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "ScChefC"));
        try {
            Nation nation = J6TestUtil.foundNation(helper, chef, "SfScoreC");
            ChunkPos capital = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
            ChunkPos east = new ChunkPos(capital.x + 1, capital.z);
            ChunkPos far = new ChunkPos(capital.x + 3, capital.z);
            helper.assertTrue(WarProtections.isCapitalChunk(server, dimension, capital.x, capital.z),
                    "la fondation est la capitale");
            CompoundTag saved = NationData.get(server).save(new CompoundTag(), helper.getLevel().registryAccess());
            helper.assertTrue(savedCapital(saved, nation.id(), dimension.location().toString(), capital),
                    "la capitale est écrite dans la sauvegarde de la nation");
            helper.assertFalse(WarProtections.isCapitalChunk(server, dimension, far.x, far.z),
                    "chunk non possédé : pas une capitale");
            NationAdmin.adjustTreasury(server, nation, 10_000L);
            J4TestUtil.expect(helper, NationManager.claim(chef, dimension, east), true, "revendication du chunk voisin");
            helper.assertFalse(WarProtections.isCapitalChunk(server, dimension, east.x, east.z),
                    "chunk possédé non fondateur : pas une capitale");
            helper.assertTrue(WarProtections.isCapitalChunk(server, dimension, capital.x, capital.z),
                    "la capitale reste reconnue après une revendication");
        } finally {
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }
}
