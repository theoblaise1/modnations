package io.github.theoblaise1.sovereignfrontier.gametest;

import com.mojang.authlib.GameProfile;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringManager;
import io.github.theoblaise1.sovereignfrontier.machine.GasData;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlock;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.machine.MachineMenu;
import io.github.theoblaise1.sovereignfrontier.machine.ReactorStructure;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
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

    /** Pose un bloc à une position absolue, y compris hors de la zone de test (la zone est remise à zéro par le test). */
    private static MachineBlockEntity placeMachine(ServerLevel level, BlockPos abs, Block block) {
        level.setBlock(abs, block.defaultBlockState(), 3);
        return (MachineBlockEntity) level.getBlockEntity(abs);
    }

    private static void tick(GameTestHelper helper, MachineBlockEntity be) {
        ServerLevel level = helper.getLevel();
        be.tick(level, be.getBlockPos(), level.getBlockState(be.getBlockPos()));
    }

    /** Coque 5×5×5 (x, z = 2..6 ; y = 2..6), cellule de combustible et deux dissipateurs à l'intérieur ; renvoie le contrôleur (absolu). */
    private static BlockPos buildReactor(GameTestHelper helper) {
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
        return helper.absolutePos(new BlockPos(4, 4, 2));
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

    /** Trois générateurs pleins poussent dans le même tick de jeu : la cellule ne reçoit que son débit d'entrée (400 En). */
    @GameTest(template = AREA)
    public static void batteryInputIsCappedPerTick(GameTestHelper helper) {
        MachineBlockEntity battery = machine(helper, new BlockPos(4, 2, 4), ModBlocks.ENERGY_CELL.get());
        List<MachineBlockEntity> generators = List.of(
                machine(helper, new BlockPos(3, 2, 4), ModBlocks.THERMAL_GENERATOR.get()),
                machine(helper, new BlockPos(5, 2, 4), ModBlocks.THERMAL_GENERATOR.get()),
                machine(helper, new BlockPos(4, 2, 3), ModBlocks.THERMAL_GENERATOR.get()));
        for (MachineBlockEntity generator : generators) {
            generator.energyBuffer().setStored(10_000);
        }
        for (MachineBlockEntity generator : generators) {
            tick(helper, generator);
        }
        helper.assertTrue(battery.energyBuffer().stored() == 400,
                "débit d'entrée total par tick : " + battery.energyBuffer().stored());
        helper.succeed();
    }

    /**
     * Nation A : sa capitale est le chunk de la zone de test. Le chunk voisin à l'est est un territoire libre,
     * où un câble et une foreuse sont posés contre le câble de la capitale : aucune énergie n'y passe.
     */
    @GameTest(template = AREA, batch = "sf_energy_border")
    public static void energyStopsAtNationBorder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos home = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 2;
        int z = home.getMinBlockZ() + 8;
        int east = home.getMaxBlockX();
        ServerPlayer chef = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "sf_frontiere"));
        chef.moveTo(home.getMiddleBlockX() + 0.5D, y, z + 0.5D);
        String name = "TestFrontiere" + Math.floorMod(home.x ^ home.z, 100000);
        helper.assertTrue(NationManager.create(chef, name).success(), "fondation de la nation");
        try {
            MachineBlockEntity generator = placeMachine(level, new BlockPos(east - 4, y, z), ModBlocks.THERMAL_GENERATOR.get());
            for (int x = east - 3; x <= east; x++) {
                level.setBlock(new BlockPos(x, y, z), ModBlocks.CABLE.get().defaultBlockState(), 3);
            }
            MachineBlockEntity ownDrill = placeMachine(level, new BlockPos(east - 2, y + 1, z), ModBlocks.GAS_DRILL.get());
            level.setBlock(new BlockPos(east + 1, y, z), ModBlocks.CABLE.get().defaultBlockState(), 3);
            MachineBlockEntity wildDrill = placeMachine(level, new BlockPos(east + 2, y, z), ModBlocks.GAS_DRILL.get());
            generator.energyBuffer().setStored(10_000);
            tick(helper, generator);
            helper.assertTrue(ownDrill.energyBuffer().stored() == 200,
                    "la foreuse du territoire est servie : " + ownDrill.energyBuffer().stored());
            helper.assertTrue(wildDrill.energyBuffer().stored() == 0,
                    "aucune énergie au-delà de la frontière : " + wildDrill.energyBuffer().stored());
        } finally {
            NationManager.disband(chef);
            for (int x = east - 4; x <= east + 2; x++) {
                for (int dy = 0; dy <= 1; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
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
        BlockPos controller = buildReactor(helper);
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

    /** Un réacteur neuf, même avec du combustible enrichi, ne produit rien tant que « Démarrer » n'a pas été pressé. */
    @GameTest(template = AREA)
    public static void reactorStartsStopped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controller = buildReactor(helper);
        level.setBlock(controller, ModBlocks.REACTOR_CONTROLLER.get().defaultBlockState().setValue(MachineBlock.FACING, Direction.NORTH), 3);
        MachineBlockEntity reactor = (MachineBlockEntity) level.getBlockEntity(controller);
        reactor.items().setItem(0, new ItemStack(ModItems.ENRICHED_FUEL.get()));
        tick(helper, reactor);
        helper.assertTrue(reactor.structureValid(), "structure valide");
        helper.assertTrue(reactor.energyBuffer().stored() == 0 && reactor.heat() == 0,
                "réacteur neuf arrêté : aucune production avant « Démarrer »");
        reactor.toggleRunning();
        tick(helper, reactor);
        helper.assertTrue(reactor.energyBuffer().stored() > 0, "« Démarrer » lance la production");
        helper.succeed();
    }

    /** La chaleur (au-delà de 65 535), sa limite et la réserve de gaz arrivent entières dans le menu. */
    @GameTest(template = AREA)
    public static void machineMenuSyncsFullRange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MachineBlockEntity reactor = machine(helper, new BlockPos(4, 2, 4), ModBlocks.REACTOR_CONTROLLER.get());
        CompoundTag tag = reactor.saveWithoutMetadata(level.registryAccess());
        tag.putInt("heat", 70_000);
        reactor.loadWithComponents(tag, level.registryAccess());
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "sf_pupitre"));
        MachineMenu menu = (MachineMenu) reactor.createMenu(0, player.getInventory(), player);
        helper.assertTrue(menu.wide(4) == 70_000, "chaleur synchronisée sur 32 bits : " + menu.wide(4));
        helper.assertTrue(menu.wide(8) == SFConfig.REACTOR_MAX_HEAT.get(), "limite synchronisée : " + menu.wide(8));
        // La réserve de la dimension n'est affichée et mise à jour (à chaque tick) que par la foreuse à gaz.
        MachineBlockEntity drill = machine(helper, new BlockPos(1, 2, 1), ModBlocks.GAS_DRILL.get());
        tick(helper, drill);
        long gas = GasData.get(level).remaining();
        MachineMenu drillMenu = (MachineMenu) drill.createMenu(0, player.getInventory(), player);
        helper.assertTrue(drillMenu.wide64(11) == gas, "réserve de gaz synchronisée : " + drillMenu.wide64(11) + " / " + gas);
        helper.succeed();
    }

    /** Les données envoyées à l'ouverture donnent la position et le genre de la machine, dans cet ordre. */
    @GameTest(template = AREA)
    public static void openedMachineSendsItsPositionAndKind(GameTestHelper helper) {
        MachineBlockEntity generator = machine(helper, new BlockPos(4, 2, 4), ModBlocks.THERMAL_GENERATOR.get());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        generator.writeClientSideData(null, buf);
        helper.assertTrue(buf.readBlockPos().equals(generator.getBlockPos()), "position envoyée à l'ouverture");
        helper.assertTrue(buf.readVarInt() == generator.kind().ordinal(), "genre de machine envoyé à l'ouverture");
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
