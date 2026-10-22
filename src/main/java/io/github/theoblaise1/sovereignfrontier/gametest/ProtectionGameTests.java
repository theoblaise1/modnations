package io.github.theoblaise1.sovereignfrontier.gametest;

import com.mojang.authlib.GameProfile;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Protection de frontière en conditions réelles (prouve aussi que les mixins s'appliquent) :
 * eau qui coule, distributeur et piston situés hors d'un territoire n'y modifient rien.
 * Chaque test revendique le chunk à l'est de sa zone et agit depuis le chunk de la zone.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class ProtectionGameTests {
    private static final String AREA = "gametest/empty9";

    private ProtectionGameTests() {
    }

    /** Fonde une nation dont la capitale est le chunk de la zone ; renvoie le X du premier bloc du territoire. */
    private static ServerPlayer lastChef;
    /** z au milieu du chunk revendiqué : la zone de test peut chevaucher une limite de chunk. */
    private static int lastZ;

    private static int claimArea(GameTestHelper helper, String name) {
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        // Territoire = chunk à l'est de la zone ; l'action part du chunk de la zone, qui « tick » toujours.
        ChunkPos chunk = new ChunkPos(new ChunkPos(origin).x + 1, new ChunkPos(origin).z);
        ServerPlayer chef = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "sf_" + name));
        BlockPos mid = chunk.getMiddleBlockPosition(origin.getY());
        chef.moveTo(mid.getX() + 0.5D, mid.getY(), mid.getZ() + 0.5D);
        // Le monde de test persiste entre deux exécutions : nom unique dérivé de la position.
        var created = NationManager.create(chef, name + Math.floorMod(origin.getX() ^ origin.getZ(), 100000));
        helper.assertTrue(created.success(), "fondation de " + name + " : " + created.message().getString());
        lastChef = chef;
        lastZ = chunk.getMiddleBlockZ();
        return chunk.getMinBlockX();
    }

    /** Exécute les vérifications puis dissout toujours la nation de test. */
    private static void finish(GameTestHelper helper, Runnable checks) {
        ServerPlayer chef = lastChef;
        try {
            checks.run();
        } finally {
            NationManager.disband(chef);
        }
        helper.succeed();
    }

    private static void floor(ServerLevel level, int fromX, int toX, int y, int z) {
        for (int x = fromX; x <= toX; x++) {
            for (int dz = -5; dz <= 5; dz++) {
                level.setBlock(new BlockPos(x, y, z + dz), Blocks.STONE.defaultBlockState(), 3);
                // 8 blocs d'air : la bande déborde parfois sur le mur de barrières que le serveur de GameTests dresse
                // autour de la zone (de la hauteur de la zone + 10), selon l'alignement des chunks ; un bloc de barrière
                // à la hauteur de la tête d'un joueur de test fausse ses tirs (nénuphar, par exemple).
                for (int dy = 1; dy <= 8; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z + dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /** Joueur sans nation (donc étranger au territoire de test), tourné vers le sud et regardant vers le bas. */
    private static ServerPlayer outsider(GameTestHelper helper, String name) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.moveTo(0.0D, 0.0D, 0.0D, 0.0F, 90.0F);
        return player;
    }

    @GameTest(template = AREA, batch = "sf_protection_eau")
    public static void waterDoesNotFlowIntoTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestEau");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 6, border + 4, y - 1, z);
        BlockPos source = new BlockPos(border - 1, y, z);
        finish(helper, () -> {
            level.setBlock(source, Blocks.WATER.defaultBlockState(), 3);
            // Tick du fluide déclenché directement (les ticks programmés dépendent des joueurs proches).
            level.getFluidState(source).tick(level, source);
            helper.assertTrue(level.getFluidState(source.west()).is(Fluids.FLOWING_WATER), "témoin : l'eau coule vers l'ouest");
            helper.assertTrue(level.getFluidState(source.east()).isEmpty(), "eau entrée dans le territoire");
        });
    }

    @GameTest(template = AREA, batch = "sf_protection_distributeur")
    public static void dispenserCannotEmptyIntoTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestDistributeur");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 4, border + 2, y - 1, z);
        finish(helper, () -> {
            BlockPos toward = dispenser(level, new BlockPos(border - 1, y, z), Direction.EAST);
            BlockPos away = dispenser(level, new BlockPos(border - 3, y, z), Direction.WEST);
            helper.assertTrue(level.getFluidState(away).is(Fluids.LAVA), "témoin : le distributeur vide la lave hors territoire");
            helper.assertTrue(level.getFluidState(toward).isEmpty(), "lave versée dans le territoire ; frontière franchie ? "
                    + io.github.theoblaise1.sovereignfrontier.nation.TerritoryRules.crossesBorder(level, toward.west(), toward)
                    + " ; propriétaire : " + io.github.theoblaise1.sovereignfrontier.nation.NationData.get(level.getServer())
                    .ownerAt(level.dimension(), toward));
        });
    }

    /** Pose un distributeur chargé d'un seau de lave, le déclenche et renvoie la case visée. */
    private static BlockPos dispenser(ServerLevel level, BlockPos pos, Direction facing) {
        level.setBlock(pos, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing), 3);
        if (level.getBlockEntity(pos) instanceof DispenserBlockEntity be) {
            be.setItem(0, new ItemStack(Items.LAVA_BUCKET));
        }
        level.getBlockState(pos).tick(level, pos, level.getRandom());
        return pos.relative(facing);
    }

    @GameTest(template = AREA, batch = "sf_protection_piston")
    public static void pistonCannotPushIntoTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestPiston");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 6, border + 2, y - 1, z);
        finish(helper, () -> {
            BlockPos blocked = new BlockPos(border - 1, y, z);
            BlockPos free = new BlockPos(border - 5, y, z);
            push(level, blocked.west(), blocked);
            push(level, free.east(), free);
            // Le bloc poussé devient un « bloc en mouvement » pendant l'animation : il a quitté sa case.
            helper.assertFalse(level.getBlockState(free).is(Blocks.COBBLESTONE), "témoin : le piston pousse hors territoire");
            helper.assertTrue(level.getBlockState(blocked).is(Blocks.COBBLESTONE), "le bloc a été poussé dans le territoire");
            helper.assertTrue(level.getBlockState(blocked.east()).isAir(), "bloc apparu dans le territoire");
        });
    }

    /** Piston en {@code piston} poussant le bloc {@code block} (voisin dans la direction piston → bloc). */
    private static void push(ServerLevel level, BlockPos piston, BlockPos block) {
        Direction facing = Direction.getNearest(block.getX() - piston.getX(), 0, block.getZ() - piston.getZ());
        level.setBlock(block, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, facing), 3);
        level.setBlock(piston.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        // Événement d'extension déclenché directement (0 = extension), comme après un signal redstone.
        level.getBlockState(piston).triggerEvent(level, piston, 0, facing.get3DDataValue());
    }

    /** Un piston simple ne tire rien : à la frontière, il se rétracte quand même (le collant reste bloqué). */
    @GameTest(template = AREA, batch = "sf_protection_piston_simple")
    public static void plainPistonRetractsAtBorder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestPistonSimple");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 4, border + 2, y - 1, z);
        finish(helper, () -> {
            BlockPos plain = new BlockPos(border - 2, y, z);
            BlockPos sticky = new BlockPos(border - 2, y, z + 2);
            extendedPiston(level, plain, Blocks.PISTON, PistonType.DEFAULT);
            extendedPiston(level, sticky, Blocks.STICKY_PISTON, PistonType.STICKY);
            // Événements de rétraction déclenchés directement (1 = rétraction), comme à la coupure du signal.
            level.getBlockState(plain).triggerEvent(level, plain, 1, Direction.EAST.get3DDataValue());
            level.getBlockState(sticky).triggerEvent(level, sticky, 1, Direction.EAST.get3DDataValue());
            helper.assertFalse(level.getBlockState(plain.east()).is(Blocks.PISTON_HEAD), "piston simple bloqué en extension");
            helper.assertTrue(level.getBlockState(plain.east(2)).is(Blocks.COBBLESTONE), "bloc du territoire déplacé");
            helper.assertTrue(level.getBlockState(sticky).is(Blocks.STICKY_PISTON)
                    && level.getBlockState(sticky.east(2)).is(Blocks.COBBLESTONE), "piston collant : bloc tiré hors du territoire");
        });
    }

    /** Piston déployé vers l'est en {@code pos}, avec un bloc du territoire juste devant sa tête. */
    private static void extendedPiston(ServerLevel level, BlockPos pos, Block piston, PistonType type) {
        level.setBlock(pos.east(2), Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(pos, piston.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, true), 3);
        level.setBlock(pos.east(), Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.EAST)
                .setValue(PistonHeadBlock.TYPE, type), 3);
    }

    /** La lave hors territoire n'allume aucun feu de l'autre côté de la frontière. */
    @GameTest(template = AREA, batch = "sf_protection_lave")
    public static void lavaDoesNotIgniteTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestLave");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 4, border + 3, y - 1, z);
        BlockPos lava = new BlockPos(border - 1, y, z);
        finish(helper, () -> {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(new BlockPos(border, y, z + dz), Blocks.OAK_PLANKS.defaultBlockState(), 3);
                level.setBlock(new BlockPos(border - 2, y, z + dz), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
            level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 3);
            // Ticks aléatoires de la lave déclenchés directement ; le serveur de test coupe doFireTick.
            GameRules.BooleanValue fireTick = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
            boolean previous = fireTick.get();
            fireTick.set(true, level.getServer());
            try {
                for (int i = 0; i < 300; i++) {
                    level.getFluidState(lava).randomTick(level, lava, level.getRandom());
                }
            } finally {
                fireTick.set(previous, level.getServer());
            }
            boolean witness = false;
            BlockPos burning = null;
            for (BlockPos cursor : BlockPos.betweenClosed(border - 2, y, z - 2, border + 2, y + 2, z + 2)) {
                BlockPos pos = cursor.immutable();
                if (level.getBlockState(pos).is(Blocks.FIRE)) {
                    if (pos.getX() < border) {
                        witness = true;
                    } else if (burning == null) {
                        burning = pos;
                    }
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            level.setBlock(lava, Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(witness, "témoin : la lave enflamme le bois hors territoire");
            helper.assertTrue(burning == null, "feu allumé dans le territoire en " + burning);
        });
    }

    /** Un coffre posé hors territoire ne s'assemble pas avec un coffre du territoire. */
    @GameTest(template = AREA, batch = "sf_protection_coffre")
    public static void chestDoesNotPairAcrossBorder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestCoffre");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 6, border + 2, y - 1, z);
        finish(helper, () -> {
            ServerPlayer intruder = outsider(helper, "sf_intrus_coffre");
            BlockState single = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
            // Coffre de la nation au bord ouest du territoire ; l'intrus (tourné vers le sud) pose le sien à côté.
            BlockPos national = new BlockPos(border, y, z);
            level.setBlock(national, single, 3);
            placeOn(intruder, national.west().below(), Items.CHEST);
            BlockPos wild = new BlockPos(border - 4, y, z);
            level.setBlock(wild, single, 3);
            placeOn(intruder, wild.west().below(), Items.CHEST);
            helper.assertTrue(level.getBlockState(wild.west()).is(Blocks.CHEST)
                    && level.getBlockState(wild.west()).getValue(ChestBlock.TYPE) != ChestType.SINGLE, "témoin : double coffre hors territoire");
            helper.assertTrue(level.getBlockState(national.west()).is(Blocks.CHEST), "coffre de l'intrus non posé");
            helper.assertTrue(level.getBlockState(national.west()).getValue(ChestBlock.TYPE) == ChestType.SINGLE
                    && level.getBlockState(national).getValue(ChestBlock.TYPE) == ChestType.SINGLE, "double coffre à cheval sur la frontière");
            // Double coffre déjà à cheval (chunk revendiqué après coup) : sa moitié hors territoire reste fermée.
            BlockPos left = new BlockPos(border - 1, y, z + 3);
            level.setBlock(left, single.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
            level.setBlock(left.east(), single.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
            helper.assertTrue(rightClick(intruder, left).isCanceled(), "moitié hors territoire d'un double coffre ouverte");
            helper.assertFalse(rightClick(intruder, wild).isCanceled(), "témoin : double coffre hors territoire ouvert");
        });
    }

    /** Un entonnoir sous la moitié hors territoire d'un double coffre ne voit que cette moitié. */
    @GameTest(template = AREA, batch = "sf_protection_entonnoir")
    public static void hopperSeesOnlyItsOwnChestHalf(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestEntonnoir");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 6, border + 2, y - 1, z);
        finish(helper, () -> {
            BlockState single = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
            // Double coffre déjà à cheval : moitié gauche hors territoire, droite dans le territoire.
            BlockPos wildLeft = new BlockPos(border - 1, y, z + 3);
            level.setBlock(wildLeft, single.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
            level.setBlock(wildLeft.east(), single.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
            helper.assertTrue(level.getBlockEntity(wildLeft) instanceof ChestBlockEntity, "témoin : coffre sans entité");
            BlockState wildState = level.getBlockState(wildLeft);
            Container seenFromWild = ChestBlock.getContainer((ChestBlock) wildState.getBlock(), wildState, level, wildLeft, false);
            helper.assertTrue(seenFromWild == level.getBlockEntity(wildLeft),
                    "entonnoir hors territoire : voit l'autre moitié du coffre");

            // Témoin : double coffre entièrement dans le territoire, il garde son double inventaire.
            BlockPos inLeft = new BlockPos(border + 2, y, z + 3);
            level.setBlock(inLeft, single.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
            level.setBlock(inLeft.east(), single.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
            BlockState inState = level.getBlockState(inLeft);
            Container seenInside = ChestBlock.getContainer((ChestBlock) inState.getBlock(), inState, level, inLeft, false);
            helper.assertTrue(seenInside != null && seenInside != level.getBlockEntity(inLeft),
                    "témoin : double coffre du territoire non réduit à une moitié");
        });
    }

    /** Un poisson d'argent à 4 blocs du territoire ne réveille pas ses congénères (zone de 10 blocs). */
    @GameTest(template = AREA, batch = "sf_protection_poisson")
    public static void silverfishDoesNotWakeNearTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestPoisson");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 8, border + 2, y - 1, z);
        finish(helper, () -> {
            Silverfish near = EntityType.SILVERFISH.create(level);
            helper.assertTrue(near != null, "poisson d'argent non créé");
            near.moveTo(border - 4.5D, y, z + 0.5D);
            Goal nearWake = wakeUpFriendsGoal(near);
            notifyHurt(nearWake);
            helper.assertFalse(nearWake.canUse(), "poisson d'argent à 4 blocs du territoire : congénères réveillés");

            // Témoin : le même réveil loin de tout territoire fonctionne.
            Silverfish far = EntityType.SILVERFISH.create(level);
            far.moveTo(border - 40.5D, y, z + 0.5D);
            Goal farWake = wakeUpFriendsGoal(far);
            notifyHurt(farWake);
            helper.assertTrue(farWake.canUse(), "témoin : poisson d'argent loin du territoire");
        });
    }

    /** Déclenche le réveil du goal comme le ferait un coup reçu (méthode {@code notifyHurt}, classe package-private). */
    private static void notifyHurt(Goal goal) {
        try {
            java.lang.reflect.Method method = goal.getClass().getDeclaredMethod("notifyHurt");
            method.setAccessible(true);
            method.invoke(goal);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("réveil du goal impossible", e);
        }
    }

    /** Le goal {@code SilverfishWakeUpFriendsGoal} est package-private et son sélecteur protégé : accès par réflexion. */
    private static Goal wakeUpFriendsGoal(Mob mob) {
        try {
            Field field = Mob.class.getDeclaredField("goalSelector");
            field.setAccessible(true);
            GoalSelector selector = (GoalSelector) field.get(mob);
            for (WrappedGoal wrapped : selector.getAvailableGoals()) {
                if (wrapped.getGoal().getClass().getSimpleName().equals("SilverfishWakeUpFriendsGoal")) {
                    return wrapped.getGoal();
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("accès au sélecteur de buts impossible", e);
        }
        throw new IllegalStateException("goal SilverfishWakeUpFriendsGoal absent");
    }

    /** Clic droit du joueur sur la face supérieure de {@code below}, objet {@code item} en main (pose d'un bloc). */
    private static void placeOn(ServerPlayer player, BlockPos below, Item item) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(below).add(0.0D, 0.5D, 0.0D), Direction.UP, below, false);
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private static PlayerInteractEvent.RightClickBlock rightClick(ServerPlayer player, BlockPos pos) {
        return CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    /** Projectiles venus d'ailleurs : arrêtés au mur de la nation, sans le traverser ni y mettre le feu. */
    @GameTest(template = AREA, batch = "sf_protection_projectile")
    public static void projectilesStopAtBorder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestProjectile");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 6, border + 4, y - 1, z);
        finish(helper, () -> {
            BlockPos wall = new BlockPos(border + 1, y, z);
            BlockPos wildWall = new BlockPos(border - 4, y, z);
            level.setBlock(wall, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            level.setBlock(wildWall, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            // Boule de feu sans tireur (distributeur) partie hors territoire.
            SmallFireball foreign = fireball(level, border - 2.5D, y + 0.5D, z + 0.5D);
            helper.assertTrue(impact(foreign, wall, Direction.WEST) && foreign.isRemoved(), "boule de feu étrangère non arrêtée");
            // Témoins : impact hors territoire, et boule de feu d'un distributeur de la nation elle-même.
            SmallFireball wild = fireball(level, border - 2.5D, y + 0.5D, z + 0.5D);
            SmallFireball home = fireball(level, border + 3.5D, y + 0.5D, z + 0.5D);
            boolean wildStopped = impact(wild, wildWall, Direction.EAST);
            boolean homeStopped = impact(home, wall, Direction.EAST);
            wild.discard();
            home.discard();
            helper.assertFalse(wildStopped, "témoin : impact hors territoire annulé");
            helper.assertFalse(homeStopped, "témoin : impact du distributeur de la nation annulé");
            // Flèche d'un étranger : arrêtée au mur (et non plus traversante).
            ServerPlayer archer = outsider(helper, "sf_archer");
            archer.moveTo(border - 3.5D, y, z + 0.5D);
            Arrow arrow = new Arrow(level, archer, new ItemStack(Items.ARROW), null);
            level.addFreshEntity(arrow);
            helper.assertTrue(impact(arrow, wall, Direction.WEST) && arrow.isRemoved(), "flèche étrangère non arrêtée");
        });
    }

    /** Boule de feu sans tireur, ajoutée au monde (sa nation d'origine est alors enregistrée). */
    private static SmallFireball fireball(ServerLevel level, double x, double y, double z) {
        SmallFireball fireball = new SmallFireball(level, x, y, z, new Vec3(1.0D, 0.0D, 0.0D));
        level.addFreshEntity(fireball);
        return fireball;
    }

    /** Impact du projectile sur la face {@code face} de {@code block} ; vrai s'il est annulé. */
    private static boolean impact(Projectile projectile, BlockPos block, Direction face) {
        return EventHooks.onProjectileImpact(projectile, new BlockHitResult(Vec3.atCenterOf(block), face, block, false));
    }

    /** La fusion d'un réacteur épargne les autres territoires, mais pas celui de sa propre nation. */
    @GameTest(template = AREA, batch = "sf_protection_fusion")
    public static void meltdownSparesOtherTerritories(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestFusion");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 6, border + 6, y - 1, z);
        finish(helper, () -> {
            // Réacteur hors territoire, à deux blocs de la frontière.
            BlockPos wild = new BlockPos(border - 1, y, z);
            BlockPos claimed = new BlockPos(border, y, z);
            level.setBlock(wild, Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(claimed, Blocks.DIRT.defaultBlockState(), 3);
            Vec3 wildCore = new BlockPos(border - 2, y, z).getCenter();
            explode(level, wildCore, wildCore);
            helper.assertTrue(level.getBlockState(wild).isAir(), "témoin : l'explosion détruit hors territoire");
            helper.assertTrue(level.getBlockState(claimed).is(Blocks.DIRT), "réacteur hors territoire : territoire voisin détruit");
            // Autre explosion pendant la fusion (centre différent) : protection normale du territoire.
            BlockPos own = new BlockPos(border + 4, y, z);
            Vec3 ownCore = new BlockPos(border + 3, y, z).getCenter();
            level.setBlock(own, Blocks.DIRT.defaultBlockState(), 3);
            explode(level, ownCore, wildCore);
            helper.assertTrue(level.getBlockState(own).is(Blocks.DIRT), "explosion ordinaire pendant une fusion : territoire détruit");
            // Réacteur dans le territoire : sa propre nation n'est pas protégée.
            explode(level, ownCore, ownCore);
            helper.assertTrue(level.getBlockState(own).isAir(), "réacteur en territoire : sa nation reste protégée");
        });
    }

    /** Explosion en {@code center} pendant la fusion du réacteur de centre {@code core} (cf. MachineBlockEntity#meltdown). */
    private static void explode(ServerLevel level, Vec3 center, Vec3 core) {
        MachineBlockEntity.meltdownInProgress = core;
        try {
            level.explode(null, center.x, center.y, center.z, 4.0F, Level.ExplosionInteraction.BLOCK);
        } finally {
            MachineBlockEntity.meltdownInProgress = null;
        }
    }

    /** Zombies et endermen ne touchent à aucun bloc du territoire, même depuis l'extérieur. */
    @GameTest(template = AREA, batch = "sf_protection_mobs")
    public static void mobsCannotGriefTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestMobs");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 8, border + 2, y - 1, z);
        finish(helper, () -> {
            BlockPos door = new BlockPos(border, y, z);
            BlockPos wildDoor = new BlockPos(border - 3, y, z);
            level.setBlock(door, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            level.setBlock(wildDoor, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            // Zombie qui défonce une porte (BreakDoorGoal), Wither : LivingDestroyBlockEvent.
            Zombie zombie = EntityType.ZOMBIE.create(level);
            helper.assertTrue(zombie != null, "zombie non créé");
            zombie.moveTo(border - 0.5D, y, z + 0.5D);
            helper.assertFalse(CommonHooks.canEntityDestroy(level, door, zombie), "zombie autorisé à détruire en territoire");
            helper.assertTrue(CommonHooks.canEntityDestroy(level, wildDoor, zombie), "témoin : zombie autorisé hors territoire");
            // Enderman hors territoire mais à portée de prise (2 blocs) de la frontière.
            EnderMan enderman = EntityType.ENDERMAN.create(level);
            helper.assertTrue(enderman != null, "enderman non créé");
            enderman.moveTo(border - 1.5D, y, z + 0.5D);
            helper.assertFalse(EventHooks.canEntityGrief(level, enderman), "enderman au bord du territoire autorisé à prendre des blocs");
            enderman.moveTo(border - 5.5D, y, z + 0.5D);
            helper.assertTrue(EventHooks.canEntityGrief(level, enderman), "témoin : enderman loin du territoire");
        });
    }

    /** Un levier posé hors territoire, à deux blocs de la frontière, ne peut pas l'atteindre par la redstone. */
    @GameTest(template = AREA, batch = "sf_protection_levier")
    public static void redstoneCannotBePlacedAtBorder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestLevier");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 8, border + 2, y - 1, z);
        finish(helper, () -> {
            ServerPlayer intruder = outsider(helper, "sf_intrus_levier");
            BlockPos near = new BlockPos(border - 2, y, z);
            BlockPos far = new BlockPos(border - 6, y, z);
            placeOn(intruder, near.below(), Items.LEVER);
            placeOn(intruder, far.below(), Items.LEVER);
            helper.assertTrue(level.getBlockState(far).is(Blocks.LEVER), "témoin : levier posé loin de la frontière");
            helper.assertTrue(level.getBlockState(near).isAir(), "levier posé à deux blocs du territoire");
        });
    }

    /** Une éponge posée hors territoire n'absorbe pas l'eau du territoire voisin. */
    @GameTest(template = AREA, batch = "sf_protection_eponge")
    public static void spongeDoesNotDrainTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestEponge");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 4, border + 2, y - 1, z);
        finish(helper, () -> {
            BlockPos sponge = new BlockPos(border - 1, y, z);
            BlockPos claimedWater = sponge.east();
            BlockPos wildWater = sponge.west();
            level.setBlock(claimedWater, Blocks.WATER.defaultBlockState(), 3);
            level.setBlock(wildWater, Blocks.WATER.defaultBlockState(), 3);
            // SpongeBlock#onPlace absorbe aussitôt (pose directe, sans capture de la pose).
            level.setBlock(sponge, Blocks.SPONGE.defaultBlockState(), 3);
            boolean wildDrained = level.getFluidState(wildWater).isEmpty();
            boolean claimedKept = level.getFluidState(claimedWater).isSource();
            level.setBlock(claimedWater, Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(wildDrained, "témoin : l'éponge absorbe l'eau hors territoire");
            helper.assertTrue(claimedKept, "eau du territoire absorbée");
        });
    }

    /** Un nénuphar (posé par Item#use, sans événement de pose) ne se pose pas sur l'eau d'un territoire. */
    @GameTest(template = AREA, batch = "sf_protection_nenuphar")
    public static void lilyPadCannotBePlacedInTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestNenuphar");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = lastZ;
        floor(level, border - 5, border + 3, y - 1, z);
        finish(helper, () -> {
            BlockPos claimedWater = new BlockPos(border + 1, y, z);
            BlockPos wildWater = new BlockPos(border - 3, y, z);
            level.setBlock(claimedWater, Blocks.WATER.defaultBlockState(), 3);
            level.setBlock(wildWater, Blocks.WATER.defaultBlockState(), 3);
            ServerPlayer intruder = outsider(helper, "sf_intrus_nenuphar");
            useLilyPadAbove(intruder, level, wildWater);
            useLilyPadAbove(intruder, level, claimedWater);
            boolean wildPlaced = level.getBlockState(wildWater.above()).is(Blocks.LILY_PAD);
            boolean claimedEmpty = level.getBlockState(claimedWater.above()).isAir();
            level.setBlock(claimedWater, Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(wildPlaced, "témoin : nénuphar posé hors territoire");
            helper.assertTrue(claimedEmpty, "nénuphar posé dans le territoire");
        });
    }

    /**
     * Clic droit avec un nénuphar, le joueur un bloc au-dessus de la source {@code water} et regardant vers le bas.
     * L'œil (+ 1,62) doit rester sous la couche de barrières que le GameTest pose à {@code water.y + 3} : à une
     * hauteur de 2 blocs, il y entrait selon la position (aléatoire) du test et le rayon touchait la barrière.
     */
    private static void useLilyPadAbove(ServerPlayer player, ServerLevel level, BlockPos water) {
        player.moveTo(water.getX() + 0.5D, water.getY() + 1, water.getZ() + 0.5D, 0.0F, 90.0F);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LILY_PAD));
        player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
    }
}
