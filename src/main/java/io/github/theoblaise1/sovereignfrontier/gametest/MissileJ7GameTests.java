package io.github.theoblaise1.sovereignfrontier.gametest;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationAdmin;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import io.github.theoblaise1.sovereignfrontier.war.SiloBlockEntity;
import io.github.theoblaise1.sovereignfrontier.war.WarData;
import io.github.theoblaise1.sovereignfrontier.war.WarState;
import io.github.theoblaise1.sovereignfrontier.war.missile.MissileCommands;
import io.github.theoblaise1.sovereignfrontier.war.missile.MissileImpact;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * GameTests du silo et des missiles du J7 (docs/conception/J7.md §6, WP3). Chaque test fonde ses nations et les dissout
 * dans un {@code finally} ; son lot est {@code sf_j7_missile_<test>}.
 * <p>
 * Terrain : trois chunks côte à côte, sans supposer leur alignement sur le gabarit. Le chunk {@code home} (capitale de
 * la nation tireuse) contient le silo ; le chunk {@code target} (revendiqué par la nation visée, non capitale) reçoit
 * l'impact ; le chunk {@code capital} est la capitale de la nation visée. {@link #prepare} dégage un sol de pierre et
 * l'air au-dessus sur toute l'étendue (y compris autour des centres de chunk) : tous les blocs et les joueurs sont dans
 * cette zone. Le tireur se tient à 4 blocs du silo, son œil reste dans l'air.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class MissileJ7GameTests {
    private static final String AREA = "gametest/empty9";
    /** Fonds de la nation visée (coût de revendication, voir {@link NationManager#claim}). */
    private static final long FUNDS = 1_000_000L;
    private static final long WAR_MS = 168L * 3_600_000L;

    private MissileJ7GameTests() {
    }

    /** Terrain d'un test : {@code origin} (absolu), chunks {@code home}, {@code target}, {@code capital}, silo et pied du tireur. */
    private record Theatre(BlockPos origin, ChunkPos home, ChunkPos target, ChunkPos capital, BlockPos silo, BlockPos stand) {
    }

    /** Nations du test : tireuse (silo) et visée (chunk cible et capitale). */
    private record Duel(Nation attacker, Nation defender) {
    }

    /** Sans guerre, le tir est refusé : aucune munition consommée, aucun missile en vol. */
    @GameTest(template = AREA, batch = "sf_j7_missile_hors_guerre")
    public static void launchRefusedOutsideWar(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Theatre t = prepare(helper);
        ServerPlayer attacker = J6TestUtil.fakePlayer(helper, "sf_missile_tireur");
        ServerPlayer defender = J6TestUtil.fakePlayer(helper, "sf_missile_cible");
        try {
            found(helper, t, attacker, defender);
            SiloBlockEntity silo = placeSilo(helper, t, attacker);
            stock(silo, 1, 1);
            stand(attacker, t);
            ActionResult result = MissileCommands.launch(attacker, aimAt(t.target(), t));
            helper.assertTrue(!result.success(), "hors guerre : tir refusé");
            helper.assertTrue(silo.missiles() == 1 && silo.warheads() == 1, "hors guerre : munitions conservées");
            helper.assertTrue(WarData.get(server).missiles().isEmpty(), "hors guerre : aucun missile en vol");
        } finally {
            cleanup(helper, t, attacker, defender);
        }
        helper.succeed();
    }

    /** En guerre, la capitale de la nation visée reste hors cible : tir refusé, munitions conservées. */
    @GameTest(template = AREA, batch = "sf_j7_missile_capitale")
    public static void launchRefusedOnCapital(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Theatre t = prepare(helper);
        ServerPlayer attacker = J6TestUtil.fakePlayer(helper, "sf_missile_tireur");
        ServerPlayer defender = J6TestUtil.fakePlayer(helper, "sf_missile_cible");
        try {
            found(helper, t, attacker, defender);
            declareWar(helper, attacker, defender);
            SiloBlockEntity silo = placeSilo(helper, t, attacker);
            stock(silo, 1, 1);
            stand(attacker, t);
            ActionResult result = MissileCommands.launch(attacker, aimAt(t.capital(), t));
            helper.assertTrue(!result.success(), "capitale visée : tir refusé");
            helper.assertTrue(silo.missiles() == 1 && silo.warheads() == 1, "capitale : munitions conservées");
            helper.assertTrue(WarData.get(server).missiles().isEmpty(), "capitale : aucun missile en vol");
        } finally {
            cleanup(helper, t, attacker, defender);
        }
        helper.succeed();
    }

    /** Un impact détruit le bloc placé au centre du chunk visé, et lui seul : le bloc du chunk tireur reste. */
    @GameTest(template = AREA, batch = "sf_j7_missile_impact")
    public static void impactDestroysBlockOfTargetChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Theatre t = prepare(helper);
        ServerPlayer attacker = J6TestUtil.fakePlayer(helper, "sf_missile_tireur");
        ServerPlayer defender = J6TestUtil.fakePlayer(helper, "sf_missile_cible");
        try {
            Duel duel = found(helper, t, attacker, defender);
            declareWar(helper, attacker, defender);
            BlockPos centre = MissileImpact.epicenter(level, t.target());
            BlockPos kept = new BlockPos(t.home().getMiddleBlockX(), centre.getY(), t.home().getMiddleBlockZ());
            helper.assertTrue(level.getBlockState(centre).is(Blocks.STONE), "témoin : bloc du chunk visé présent");
            helper.assertTrue(level.getBlockState(kept).is(Blocks.STONE), "témoin : bloc du chunk tireur présent");
            MissileImpact.impact(server, pending(t, duel));
            helper.assertTrue(level.getBlockState(centre).isAir(), "impact : bloc du chunk visé détruit");
            helper.assertTrue(level.getBlockState(kept).is(Blocks.STONE), "impact : bloc du chunk tireur épargné");
        } finally {
            cleanup(helper, t, attacker, defender);
        }
        helper.succeed();
    }

    /** Le Trésor et le coffre national, dans le rayon de l'impact, restent en place ; le bloc voisin est détruit. */
    @GameTest(template = AREA, batch = "sf_j7_missile_epargne")
    public static void impactSparesTreasuryAndNationalChest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Theatre t = prepare(helper);
        ServerPlayer attacker = J6TestUtil.fakePlayer(helper, "sf_missile_tireur");
        ServerPlayer defender = J6TestUtil.fakePlayer(helper, "sf_missile_cible");
        try {
            Duel duel = found(helper, t, attacker, defender);
            declareWar(helper, attacker, defender);
            BlockPos centre = MissileImpact.epicenter(level, t.target());
            BlockPos treasury = centre.above();
            BlockPos chest = centre.offset(-1, 1, 0);
            level.setBlock(treasury, ModBlocks.TREASURY.get().defaultBlockState(), 3);
            level.setBlock(chest, ModBlocks.NATIONAL_CHEST.get().defaultBlockState(), 3);
            MissileImpact.impact(server, pending(t, duel));
            helper.assertTrue(level.getBlockState(centre).isAir(), "impact : bloc ordinaire détruit");
            helper.assertTrue(level.getBlockState(treasury).is(ModBlocks.TREASURY.get()), "impact : Trésor national épargné");
            helper.assertTrue(level.getBlockState(chest).is(ModBlocks.NATIONAL_CHEST.get()), "impact : coffre national épargné");
        } finally {
            cleanup(helper, t, attacker, defender);
        }
        helper.succeed();
    }

    /** Après un tir accepté, le silo est en recharge : un second tir est refusé et ne consomme rien. */
    @GameTest(template = AREA, batch = "sf_j7_missile_cooldown")
    public static void cooldownRefusesSecondShot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Theatre t = prepare(helper);
        ServerPlayer attacker = J6TestUtil.fakePlayer(helper, "sf_missile_tireur");
        ServerPlayer defender = J6TestUtil.fakePlayer(helper, "sf_missile_cible");
        try {
            found(helper, t, attacker, defender);
            declareWar(helper, attacker, defender);
            SiloBlockEntity silo = placeSilo(helper, t, attacker);
            stock(silo, 2, 2);
            stand(attacker, t);
            ActionResult first = MissileCommands.launch(attacker, aimAt(t.target(), t));
            helper.assertTrue(first.success(), "premier tir accepté (témoin)");
            helper.assertTrue(silo.missiles() == 1 && silo.warheads() == 1, "premier tir : une munition de chaque consommée");
            helper.assertTrue(WarData.get(server).siloCooldownUntil(t.silo()) > TimeUtil.now(), "premier tir : silo en recharge");
            ActionResult second = MissileCommands.launch(attacker, aimAt(t.target(), t));
            helper.assertTrue(!second.success(), "second tir refusé pendant la recharge");
            helper.assertTrue(silo.missiles() == 1 && silo.warheads() == 1, "recharge : munitions conservées");
            helper.assertTrue(WarData.get(server).missiles().size() == 1, "recharge : un seul missile en vol");
        } finally {
            cleanup(helper, t, attacker, defender);
        }
        helper.succeed();
    }

    /** Un membre sans {@code tirer_missile} est refusé au même silo ; le chef, lui, tire (témoin). */
    @GameTest(template = AREA, batch = "sf_j7_missile_permission")
    public static void siloWithoutPermissionRefused(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Theatre t = prepare(helper);
        ServerPlayer attacker = J6TestUtil.fakePlayer(helper, "sf_missile_tireur");
        ServerPlayer defender = J6TestUtil.fakePlayer(helper, "sf_missile_cible");
        ServerPlayer recruit = J6TestUtil.fakePlayer(helper, "sf_missile_recrue");
        try {
            Duel duel = found(helper, t, attacker, defender);
            declareWar(helper, attacker, defender);
            SiloBlockEntity silo = placeSilo(helper, t, attacker);
            stock(silo, 1, 1);
            J4TestUtil.expect(helper, NationManager.invite(attacker, recruit), true, "invitation de la recrue");
            J4TestUtil.expect(helper, NationManager.join(recruit, duel.attacker().name()), true, "adhésion de la recrue");
            stand(recruit, t);
            ActionResult refused = MissileCommands.launch(recruit, aimAt(t.target(), t));
            helper.assertTrue(!refused.success(), "sans tirer_missile : tir refusé");
            helper.assertTrue(silo.missiles() == 1 && silo.warheads() == 1, "sans permission : munitions conservées");
            helper.assertTrue(WarData.get(server).missiles().isEmpty(), "sans permission : aucun missile en vol");
            stand(attacker, t);
            ActionResult allowed = MissileCommands.launch(attacker, aimAt(t.target(), t));
            helper.assertTrue(allowed.success(), "témoin : le chef tire depuis le même silo");
        } finally {
            cleanup(helper, t, attacker, defender);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ terrain et nations

    /** Dégage le sol (pierre à y+1, air jusqu'à y+12) sur les trois chunks, et renvoie le terrain du test. */
    private static Theatre prepare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos home = new ChunkPos(origin);
        ChunkPos target = new ChunkPos(home.x + 1, home.z);
        ChunkPos capital = new ChunkPos(home.x + 2, home.z);
        // Chargement explicite : sans lui, setBlock échoue sans bruit dans un chunk non chargé.
        for (int cx = home.x; cx <= capital.x; cx++) {
            level.getChunk(cx, home.z);
        }
        int floorY = origin.getY() + 1;
        for (int x = home.getMinBlockX(); x <= capital.getMaxBlockX(); x++) {
            for (int z = home.getMinBlockZ() + 2; z <= home.getMinBlockZ() + 14; z++) {
                level.setBlock(new BlockPos(x, floorY, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = floorY + 1; y <= floorY + 12; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        int feetY = origin.getY() + 2;
        BlockPos silo = new BlockPos(home.getMiddleBlockX(), feetY, home.getMiddleBlockZ());
        BlockPos stand = new BlockPos(home.getMinBlockX() + 4, feetY, home.getMiddleBlockZ());
        return new Theatre(origin, home, target, capital, silo, stand);
    }

    /** Fonde la nation tireuse sur le chunk de l'origine, puis la nation visée sur sa capitale, et lui fait revendiquer la cible. */
    private static Duel found(GameTestHelper helper, Theatre t, ServerPlayer attacker, ServerPlayer defender) {
        MinecraftServer server = helper.getLevel().getServer();
        J4TestUtil.foundNation(helper, attacker, "MissileA");
        J4TestUtil.moveTo(helper, defender, t.capital());
        J4TestUtil.expect(helper, NationManager.create(defender, J4TestUtil.unique(helper, "MissileB")), true,
                "fondation de la nation visée");
        Nation a = NationData.get(server).nationOf(attacker.getUUID());
        Nation b = NationData.get(server).nationOf(defender.getUUID());
        helper.assertTrue(a != null && b != null, "nations de test introuvables après leur fondation");
        NationAdmin.adjustTreasury(server, b, FUNDS);
        J4TestUtil.expect(helper, NationManager.claim(defender, helper.getLevel().dimension(), t.target()), true,
                "revendication du chunk visé");
        return new Duel(a, b);
    }

    /** Guerre ouverte entre les deux nations (écrite directement : la boucle de guerre n'est pas exécutée ici). */
    private static void declareWar(GameTestHelper helper, ServerPlayer attacker, ServerPlayer defender) {
        MinecraftServer server = helper.getLevel().getServer();
        NationData data = NationData.get(server);
        Nation a = data.nationOf(attacker.getUUID());
        Nation b = data.nationOf(defender.getUUID());
        helper.assertTrue(a != null && b != null, "nations de test introuvables pour la guerre");
        long now = TimeUtil.now();
        WarData.get(server).put(a.id(), b.id(), new WarData.Relation(WarState.GUERRE, now, now + WAR_MS, a.id(), 0, 0, null));
    }

    /** Pose le silo sur le territoire de la nation tireuse, comme le ferait le placement d'un joueur. */
    private static SiloBlockEntity placeSilo(GameTestHelper helper, Theatre t, ServerPlayer owner) {
        ServerLevel level = helper.getLevel();
        level.setBlock(t.silo(), ModBlocks.SILO_MISSILES.get().defaultBlockState(), 3);
        ModBlocks.SILO_MISSILES.get().setPlacedBy(level, t.silo(), level.getBlockState(t.silo()), owner, ItemStack.EMPTY);
        helper.assertTrue(level.getBlockEntity(t.silo()) instanceof SiloBlockEntity, "silo : bloc-entité présent");
        SiloBlockEntity silo = (SiloBlockEntity) level.getBlockEntity(t.silo());
        helper.assertTrue(silo.owner() != null, "silo : posé sur le territoire, il appartient à la nation");
        return silo;
    }

    private static void stock(SiloBlockEntity silo, int missiles, int warheads) {
        silo.loadMissiles(missiles);
        silo.loadWarheads(warheads);
    }

    /** Pied du tireur sur le sol de la zone, à 4 blocs du silo, face à l'est. */
    private static void stand(ServerPlayer player, Theatre t) {
        player.moveTo(t.stand().getX() + 0.5D, t.stand().getY(), t.stand().getZ() + 0.5D, -90.0F, 0.0F);
    }

    /** Un bloc au milieu du chunk, à la hauteur de la zone : le tir vise ce chunk. */
    private static BlockPos aimAt(ChunkPos chunk, Theatre t) {
        return chunk.getMiddleBlockPosition(t.origin().getY() + 2);
    }

    /** Missile en vol vers le chunk cible, impact immédiat (échéance passée). */
    private static WarData.PendingMissile pending(Theatre t, Duel duel) {
        return new WarData.PendingMissile(TimeUtil.now(), t.target().x, t.target().z, duel.attacker().id(), t.silo());
    }

    /** Nettoyage : relation, missiles en vol et recharge de la nation tireuse, bloc du silo, puis dissolution des deux nations. */
    private static void cleanup(GameTestHelper helper, Theatre t, ServerPlayer attacker, ServerPlayer defender) {
        ServerLevel level = helper.getLevel();
        NationData data = NationData.get(level.getServer());
        Nation a = data.nationOf(attacker.getUUID());
        Nation b = data.nationOf(defender.getUUID());
        WarData war = WarData.get(level.getServer());
        if (a != null && b != null) {
            war.remove(a.id(), b.id());
        }
        if (a != null) {
            for (WarData.PendingMissile missile : List.copyOf(war.missiles())) {
                if (missile.launcherNationId().equals(a.id())) {
                    war.removeMissile(missile);
                }
            }
        }
        war.setSiloCooldown(t.silo(), 0L);
        level.removeBlock(t.silo(), false);
        J6TestUtil.disband(attacker);
        J6TestUtil.disband(defender);
    }
}
