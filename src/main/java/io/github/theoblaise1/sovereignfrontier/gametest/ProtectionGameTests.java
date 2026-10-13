package io.github.theoblaise1.sovereignfrontier.gametest;

import com.mojang.authlib.GameProfile;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
                for (int dy = 1; dy <= 3; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z + dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    @GameTest(template = AREA, batch = "sf_protection_eau")
    public static void waterDoesNotFlowIntoTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int border = claimArea(helper, "TestEau");
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 3;
        int z = helper.absolutePos(new BlockPos(4, 0, 4)).getZ();
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
        int z = helper.absolutePos(new BlockPos(4, 0, 4)).getZ();
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
        int z = helper.absolutePos(new BlockPos(4, 0, 4)).getZ();
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
}
