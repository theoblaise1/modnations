package io.github.theoblaise1.sovereignfrontier.gametest;

import com.mojang.authlib.GameProfile;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringManager;
import io.github.theoblaise1.sovereignfrontier.machine.GasData;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlock;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.machine.ReactorStructure;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** GameTests du jalon J3 : réseau d'énergie, gaz fini, réacteur multibloc, Station d'ingénierie. */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class IndustryGameTests {
    private static final String AREA = "gametest/empty9";

    private IndustryGameTests() {
    }

    private static MachineBlockEntity machine(GameTestHelper helper, BlockPos rel, MachineBlock block) {
        helper.setBlock(rel, block.defaultBlockState());
        return (MachineBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    private static void tick(GameTestHelper helper, MachineBlockEntity be) {
        ServerLevel level = helper.getLevel();
        be.tick(level, be.getBlockPos(), level.getBlockState(be.getBlockPos()));
    }

    @GameTest(template = AREA)
    public static void energyFlowsThroughCables(GameTestHelper helper) {
        MachineBlockEntity generator = machine(helper, new BlockPos(1, 2, 4), ModBlocks.THERMAL_GENERATOR.get());
        for (int x = 2; x <= 5; x++) {
            helper.setBlock(new BlockPos(x, 2, 4), ModBlocks.CABLE.get());
        }
        MachineBlockEntity drill = machine(helper, new BlockPos(6, 2, 4), ModBlocks.GAS_DRILL.get());
        MachineBlockEntity battery = machine(helper, new BlockPos(4, 3, 4), ModBlocks.ENERGY_CELL.get());
        generator.energyBuffer().setStored(10_000);
        // Foreuse presque pleine : elle prend 50 En, le reste du débit (200 En/tick) va au stockage.
        drill.energyBuffer().setStored(drill.energyBuffer().capacity() - 50);
        tick(helper, generator);
        helper.assertTrue(drill.energyBuffer().space() == 0, "la machine est servie en premier par le câble");
        helper.assertTrue(battery.energyBuffer().stored() == 150, "le surplus va au stockage : " + battery.energyBuffer().stored());
        long before = battery.energyBuffer().stored();
        tick(helper, battery);
        helper.assertTrue(battery.energyBuffer().stored() <= before, "le stockage alimente les machines, jamais l'inverse");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_gas")
    public static void gasReserveIsFinite(GameTestHelper helper) {
        GasData gas = GasData.get(helper.getLevel());
        long saved = gas.remaining();
        gas.setRemaining(15);
        try {
            MachineBlockEntity drill = machine(helper, new BlockPos(4, 2, 4), ModBlocks.GAS_DRILL.get());
            drill.energyBuffer().setStored(20_000);
            for (int i = 0; i < 100; i++) {
                tick(helper, drill);
            }
            helper.assertTrue(gas.remaining() == 0, "réserve épuisée, reste " + gas.remaining());
            helper.assertTrue(drill.gasTank() == 15, "15 L extraits au total, obtenu " + drill.gasTank());
        } finally {
            gas.setRemaining(saved);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void reactorStructureValidation(GameTestHelper helper) {
        // Coque 5×5×5 en x,z = 2..6, y = 2..6 ; contrôleur au centre de la face nord, façade vers le nord.
        for (int x = 2; x <= 6; x++) {
            for (int y = 2; y <= 6; y++) {
                for (int z = 2; z <= 6; z++) {
                    boolean shell = x == 2 || x == 6 || y == 2 || y == 6 || z == 2 || z == 6;
                    helper.setBlock(new BlockPos(x, y, z), shell ? ModBlocks.REACTOR_CASING.get() : Blocks.AIR);
                }
            }
        }
        helper.setBlock(new BlockPos(4, 4, 4), ModBlocks.FUEL_CELL.get());
        helper.setBlock(new BlockPos(3, 4, 4), ModBlocks.HEAT_SINK.get());
        helper.setBlock(new BlockPos(5, 4, 4), ModBlocks.HEAT_SINK.get());
        BlockPos controller = helper.absolutePos(new BlockPos(4, 4, 2));
        // La façade doit regarder hors du réacteur : l'intérieur est du côté opposé.
        Direction outward = Direction.NORTH;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (helper.getLevel().getBlockState(controller.relative(d.getOpposite(), 2)).is(ModBlocks.FUEL_CELL.get())) {
                outward = d;
            }
        }
        BlockState state = ModBlocks.REACTOR_CONTROLLER.get().defaultBlockState().setValue(MachineBlock.FACING, outward);
        helper.getLevel().setBlock(controller, state, 3);
        ReactorStructure.Result ok = ReactorStructure.validate(helper.getLevel(), controller, outward);
        helper.assertTrue(ok.valid() && ok.cells() == 1 && ok.sinks() == 2,
                "structure valide attendue : " + ok + " (façade " + outward + ")");
        helper.setBlock(new BlockPos(6, 6, 6), Blocks.STONE);
        helper.assertFalse(ReactorStructure.validate(helper.getLevel(), controller, outward).valid(), "coque percée : invalide");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_engineering")
    public static void engineeringRecipesNeedResearch(GameTestHelper helper) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "sf_ingenieur"));
        BlockPos mid = new ChunkPos(helper.absolutePos(BlockPos.ZERO)).getMiddleBlockPosition(helper.absolutePos(BlockPos.ZERO).getY());
        player.moveTo(mid.getX() + 0.5D, mid.getY(), mid.getZ() + 0.5D);
        String name = "TestIngenierie" + Math.floorMod(mid.getX() ^ mid.getZ(), 100000);
        helper.assertTrue(NationManager.create(player, name).success(), "fondation");
        Nation nation = NationData.get(helper.getLevel().getServer()).nationOf(player.getUUID());
        ResourceLocation cable = ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "cable_energie");
        try {
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(Items.COPPER_INGOT, 4));
            player.getInventory().add(new ItemStack(Items.REDSTONE, 2));
            helper.assertFalse(EngineeringManager.craftUnchecked(player, cable).success(), "recette verrouillée sans recherche");
            ResearchRegistry registry = ResearchRegistry.server();
            for (String id : new String[]{"ressources_1", "industrie_1", "industrie_2"}) {
                ResearchManager.adminComplete(helper.getLevel().getServer(), nation,
                        registry.get(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, id)));
            }
            helper.assertTrue(EngineeringManager.craftUnchecked(player, cable).success(), "fabrication après recherche");
            helper.assertTrue(EngineeringManager.count(player.getInventory(), ModItems.CABLE.get()) == 8, "8 câbles fabriqués");
            helper.assertTrue(EngineeringManager.count(player.getInventory(), Items.COPPER_INGOT) == 0, "cuivre consommé");
        } finally {
            NationManager.disband(player);
        }
        helper.succeed();
    }
}
