package io.github.theoblaise1.sovereignfrontier.gametest;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.dungeon.DungeonBossEntity;
import io.github.theoblaise1.sovereignfrontier.dungeon.DungeonData;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModEntityTypes;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Autel, gardien et récompense du donjon (J6, docs/conception/J6.md §6, WP1). Zone : gabarit {@link J6TestUtil#AREA},
 * sol dégagé par {@link #floor} (bornes intérieures, le mur de barrières peut tomber sur les bords). Chaque test qui
 * fonde une nation a son propre lot {@code sf_j6_<test>} et dissout la nation dans un {@code finally}. Un test qui pose
 * un autel le retire lui-même ({@link #cleanup}).
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class DonjonJ6GameTests {
    private static final String AREA = J6TestUtil.AREA;
    private static final String BATCH = "sf_j6_donjon";
    /** Autel en (4, 1, 4) relatif : le gardien apparaît en (4, 2, 4). */
    private static final BlockPos ALTAR_REL = new BlockPos(4, 1, 4);
    /** Joueur de test : pieds en (2, 1, 2) relatif, œil dans l'air. */
    private static final BlockPos STAND_REL = new BlockPos(2, 1, 2);
    private static final long HOUR_MS = 3_600_000L;

    private DonjonJ6GameTests() {
    }

    /** Sol dégagé : pierre à y = 0 relatif, air sur 7 blocs au-dessus, pour x et z de 1 à 7 (comme {@code floor()}). */
    private static void floor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 7; x++) {
            for (int z = 1; z <= 7; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 7; dy++) {
                    level.setBlock(helper.absolutePos(new BlockPos(x, dy, z)), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /** Pose un autel sur la zone dégagée ; renvoie sa position absolue. */
    private static BlockPos placeAltar(GameTestHelper helper) {
        floor(helper);
        BlockPos altar = helper.absolutePos(ALTAR_REL);
        helper.getLevel().setBlock(altar, ModBlocks.DUNGEON_ALTAR.get().defaultBlockState(), 3);
        return altar;
    }

    /** Faux joueur sans nation, inventaire vide, debout sur la zone dégagée. */
    private static ServerPlayer newPlayer(GameTestHelper helper, String name) {
        ServerPlayer player = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, name));
        player.getInventory().clearContent();
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(STAND_REL));
        player.moveTo(at.x, at.y, at.z);
        return player;
    }

    private static void giveKeys(ServerPlayer player, int count) {
        player.getInventory().add(new ItemStack(ModItems.DUNGEON_KEY.get(), count));
    }

    private static int keys(ServerPlayer player) {
        return player.getInventory().countItem(ModItems.DUNGEON_KEY.get());
    }

    private static List<DungeonBossEntity> bosses(ServerLevel level, BlockPos altar) {
        return level.getEntitiesOfClass(DungeonBossEntity.class, new AABB(altar).inflate(8.0D));
    }

    private static DungeonData.AltarState altarState(GameTestHelper helper, BlockPos altar) {
        return DungeonData.get(helper.getLevel().getServer()).get(altar);
    }

    /** Clic droit sur l'autel comme le serveur le traite : {@code useItemOn}, puis {@code useWithoutItem} si passé. */
    private static void click(ServerLevel level, BlockPos altar, ServerPlayer player) {
        BlockState blockState = level.getBlockState(altar);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(altar), Direction.UP, altar, false);
        ItemInteractionResult result = blockState.useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, hit);
        if (result == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            blockState.useWithoutItem(level, player, hit);
        }
    }

    /** Retire les gardiens restants (sans récompense), l'entrée de l'autel et l'autel lui-même. */
    private static void cleanup(ServerLevel level, BlockPos altar) {
        for (DungeonBossEntity boss : bosses(level, altar)) {
            boss.discard();
        }
        DungeonData.get(level.getServer()).remove(altar);
        level.setBlock(altar, Blocks.AIR.defaultBlockState(), 3);
    }

    // ------------------------------------------------------------------ activation

    @GameTest(template = AREA, batch = BATCH)
    public static void altarWithoutKeyRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos altar = placeAltar(helper);
        ServerPlayer player = newPlayer(helper, "DonjA");
        try {
            click(level, altar, player);
            helper.assertTrue(bosses(level, altar).isEmpty(), "sans clé : aucun gardien");
            helper.assertTrue(altarState(helper, altar) == null, "sans clé : autel toujours neuf");
        } finally {
            cleanup(level, altar);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = BATCH)
    public static void keyIsConsumedByActivation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos altar = placeAltar(helper);
        ServerPlayer player = newPlayer(helper, "DonjB");
        giveKeys(player, 1);
        try {
            click(level, altar, player);
            helper.assertTrue(keys(player) == 0, "clé consommée : " + keys(player) + " restante(s)");
        } finally {
            cleanup(level, altar);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = BATCH)
    public static void bossAppearsAboveAltar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos altar = placeAltar(helper);
        ServerPlayer player = newPlayer(helper, "DonjC");
        giveKeys(player, 1);
        try {
            click(level, altar, player);
            List<DungeonBossEntity> found = bosses(level, altar);
            helper.assertTrue(found.size() == 1, "gardien présent après activation : " + found.size());
            DungeonBossEntity boss = found.get(0);
            helper.assertTrue(boss.isAlive(), "gardien vivant");
            helper.assertTrue(boss.blockPosition().equals(altar.above()), "gardien à 1 bloc au-dessus de l'autel");
            DungeonData.AltarState state = altarState(helper, altar);
            helper.assertTrue(state != null && boss.getUUID().equals(state.boss()), "UUID du gardien enregistré");
        } finally {
            cleanup(level, altar);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = BATCH)
    public static void secondActivationRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos altar = placeAltar(helper);
        ServerPlayer player = newPlayer(helper, "DonjD");
        giveKeys(player, 2);
        try {
            click(level, altar, player);
            List<DungeonBossEntity> first = bosses(level, altar);
            helper.assertTrue(first.size() == 1, "premier gardien présent");
            UUID id = first.get(0).getUUID();
            click(level, altar, player);
            List<DungeonBossEntity> again = bosses(level, altar);
            helper.assertTrue(again.size() == 1 && again.get(0).getUUID().equals(id), "deuxième activation refusée : un seul gardien");
            helper.assertTrue(keys(player) == 1, "deuxième activation refusée : clé non consommée");
        } finally {
            cleanup(level, altar);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ mort, recharge, récompense

    /** Nation de test : le gardien est tué sans joueur (aucun tueur), la clé militaire va au coffre national. */
    @GameTest(template = AREA, batch = "sf_j6_donjon_mort")
    public static void deathSetsRechargeAndRewardsNation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos altar = placeAltar(helper);
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "DonjChef"));
        Nation nation = J6TestUtil.foundNation(helper, chef, "donjon");
        giveKeys(chef, 1);
        try {
            click(level, altar, chef);
            List<DungeonBossEntity> found = bosses(level, altar);
            helper.assertTrue(found.size() == 1, "gardien présent avant la mort");
            long before = TimeUtil.now();
            found.get(0).kill();
            DungeonData.AltarState state = altarState(helper, altar);
            helper.assertTrue(state != null && state.boss() == null, "mort : autel sans gardien");
            long cooldown = SFConfig.ALTAR_COOLDOWN_HOURS.get() * HOUR_MS;
            long delay = state.rechargeUntilEpochMs() - before;
            helper.assertTrue(delay >= cooldown && delay <= cooldown + 60_000L,
                    "mort : recharge = altarCooldownHours (" + delay + " ms)");
            helper.assertTrue(nation.chest().countItem(CrateKind.MILITAIRE.keyItem()) == 1,
                    "mort : une clé militaire dans le coffre national");
        } finally {
            cleanup(level, altar);
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = BATCH)
    public static void altarInRechargeRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos altar = placeAltar(helper);
        ServerPlayer player = newPlayer(helper, "DonjE");
        giveKeys(player, 2);
        try {
            click(level, altar, player);
            List<DungeonBossEntity> found = bosses(level, altar);
            helper.assertTrue(found.size() == 1, "gardien présent avant la mort");
            found.get(0).kill();
            click(level, altar, player);
            helper.assertTrue(bosses(level, altar).isEmpty(), "recharge : aucun gardien");
            helper.assertTrue(keys(player) == 1, "recharge : clé non consommée");
            DungeonData.AltarState state = altarState(helper, altar);
            helper.assertTrue(state != null && state.rechargeUntilEpochMs() > TimeUtil.now(), "recharge en cours");
        } finally {
            cleanup(level, altar);
        }
        helper.succeed();
    }

    /** Gardien disparu sans mort (discard) : l'autel se réarme, sans clé militaire pour la nation. */
    @GameTest(template = AREA, batch = "sf_j6_donjon_perdu")
    public static void lostBossRearmsWithoutReward(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos altar = placeAltar(helper);
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, "DonjPerdu"));
        Nation nation = J6TestUtil.foundNation(helper, chef, "donjon");
        giveKeys(chef, 2);
        try {
            click(level, altar, chef);
            List<DungeonBossEntity> found = bosses(level, altar);
            helper.assertTrue(found.size() == 1, "gardien présent avant sa disparition");
            DungeonBossEntity lost = found.get(0);
            lost.discard();
            helper.assertTrue(bosses(level, altar).isEmpty(), "gardien perdu : plus aucun gardien");
            click(level, altar, chef);
            List<DungeonBossEntity> rearmed = bosses(level, altar);
            helper.assertTrue(rearmed.size() == 1 && !rearmed.get(0).getUUID().equals(lost.getUUID()),
                    "réarmement : nouveau gardien");
            helper.assertTrue(keys(chef) == 0, "réarmement : clé consommée");
            helper.assertTrue(nation.chest().countItem(CrateKind.MILITAIRE.keyItem()) == 0,
                    "gardien perdu : aucune récompense");
        } finally {
            cleanup(level, altar);
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ gardien non suivi

    /** Un gardien dont l'UUID n'est pas celui d'un autel ne rejoint pas le monde (événement d'entrée annulé). */
    @GameTest(template = AREA, batch = BATCH)
    public static void untrackedBossIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos altar = placeAltar(helper);
        DungeonBossEntity intruder = null;
        try {
            intruder = ModEntityTypes.DUNGEON_BOSS.get().create(level);
            helper.assertTrue(intruder != null, "gardien créé");
            intruder.moveTo(altar.getX() + 0.5D, altar.getY() + 1, altar.getZ() + 0.5D, 0.0F, 0.0F);
            level.addFreshEntity(intruder);
            helper.assertTrue(bosses(level, altar).isEmpty(), "gardien non suivi refusé au chargement");
        } finally {
            if (intruder != null) {
                intruder.discard();
            }
            cleanup(level, altar);
        }
        helper.succeed();
    }
}
