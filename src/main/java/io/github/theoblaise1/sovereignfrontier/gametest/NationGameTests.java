package io.github.theoblaise1.sovereignfrontier.gametest;

import com.mojang.authlib.GameProfile;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.block.NationalChestBlock;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.event.TerritoryEvents;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.BuildingType;
import io.github.theoblaise1.sovereignfrontier.nation.BuildingValidator;
import io.github.theoblaise1.sovereignfrontier.nation.CivilBuildings;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationAdmin;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * GameTests du jalon J1, exécutés par {@code ./gradlew runGameTestServer} sur un serveur
 * headless (ce qui vérifie aussi que le mod se charge sans aucune classe cliente).
 * Les tests qui revendiquent des chunks sont placés dans des lots distincts pour ne pas
 * se disputer le même chunk.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class NationGameTests {
    private static final String AREA = "gametest/empty9";

    private NationGameTests() {
    }

    @GameTest(template = AREA)
    public static void claimCostFollowsFormula(GameTestHelper helper) {
        // Valeurs par défaut : base 100, croissance 1.08 → ceil(100 * 1.08^n).
        helper.assertTrue(NationManager.claimCost(0) == 100L, "coût du 1er chunk : " + NationManager.claimCost(0));
        helper.assertTrue(NationManager.claimCost(1) == 108L, "coût du 2e chunk : " + NationManager.claimCost(1));
        helper.assertTrue(NationManager.claimCost(10) == 216L, "coût du 11e chunk : " + NationManager.claimCost(10));
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_claims")
    public static void claimUnclaimAndDisbandKeepCacheConsistent(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        NationData data = NationData.get(server);
        ResourceKey<Level> dim = helper.getLevel().dimension();
        ServerPlayer chef = fakePlayer(helper, "sf_chef_a");
        ServerPlayer outsider = fakePlayer(helper, "sf_etranger");

        ChunkPos capital = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        moveTo(helper, chef, capital);
        expect(helper, NationManager.create(chef, "TestClaims"), true, "création");
        Nation nation = data.nationOf(chef.getUUID());
        helper.assertTrue(nation != null && nation.roleOf(chef.getUUID()) == NationRole.CHEF, "le fondateur est chef");
        helper.assertTrue(data.ownerAt(dim, capital.toLong()) == nation && nation.treasury() == 0L,
                "capitale offerte à la fondation");
        moveTo(helper, outsider, capital);
        expect(helper, NationManager.create(outsider, "TestRivale"), false, "fondation sur une capitale déjà prise");
        expect(helper, NationManager.unclaim(chef, dim, capital), false, "abandon du dernier chunk refusé");

        ChunkPos east = new ChunkPos(capital.x + 1, capital.z);
        expect(helper, NationManager.claim(chef, dim, east), false, "claim sans fonds");
        NationAdmin.adjustTreasury(server, nation, 10_000L);
        expect(helper, NationManager.claim(chef, dim, capital), false, "chunk déjà possédé");
        expect(helper, NationManager.claim(chef, dim, new ChunkPos(capital.x + 3, capital.z)), false, "chunk non contigu");
        expect(helper, NationManager.claim(chef, dim, east), true, "chunk contigu");
        helper.assertTrue(data.ownerAt(dim, east.toLong()) == nation, "cache mis à jour après claim");
        helper.assertTrue(nation.treasury() == 10_000L - 108L, "2e chunk : ceil(100 * 1.08^1) = 108");
        ChunkPos east2 = new ChunkPos(capital.x + 2, capital.z);
        expect(helper, NationManager.claim(chef, dim, east2), true, "3e chunk contigu");
        helper.assertTrue(nation.treasury() == 10_000L - 108L - 117L, "3e chunk : ceil(100 * 1.08^2) = 117");

        helper.assertTrue(TerritoryEvents.canBuild(chef, nation), "le chef peut construire");
        helper.assertFalse(TerritoryEvents.canBuild(outsider, nation), "un étranger ne peut pas construire");

        expect(helper, NationManager.unclaim(chef, dim, east2), true, "abandon");
        helper.assertTrue(data.ownerAt(dim, east2.toLong()) == null, "cache invalidé après unclaim");

        expect(helper, NationManager.disband(chef), true, "dissolution");
        helper.assertTrue(data.ownerAt(dim, capital.toLong()) == null && data.ownerAt(dim, east.toLong()) == null,
                "cache vidé après dissolution");
        helper.assertTrue(data.nationOf(chef.getUUID()) == null, "index des membres vidé");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_members")
    public static void recruitNeedsPermissionToClaim(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        NationData data = NationData.get(server);
        ResourceKey<Level> dim = helper.getLevel().dimension();
        ServerPlayer chef = fakePlayer(helper, "sf_chef_b");
        ServerPlayer recruit = fakePlayer(helper, "sf_recrue");

        ChunkPos capital = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        moveTo(helper, chef, capital);
        expect(helper, NationManager.create(chef, "TestMembres"), true, "création");
        Nation nation = data.nationOf(chef.getUUID());
        expect(helper, NationManager.join(recruit, "TestMembres"), false, "rejoindre sans invitation");
        expect(helper, NationManager.invite(chef, recruit), true, "invitation");
        expect(helper, NationManager.join(recruit, "TestMembres"), true, "rejoindre avec invitation");
        helper.assertTrue(nation.roleOf(recruit.getUUID()) == NationRole.RECRUE, "nouveau membre = recrue");

        NationAdmin.adjustTreasury(server, nation, 1_000L);
        ChunkPos chunk = new ChunkPos(capital.x, capital.z + 1);
        expect(helper, NationManager.claim(recruit, dim, chunk), false, "recrue sans permission de revendiquer");
        expect(helper, NationManager.setPermission(recruit, NationRole.RECRUE, NationPermission.CLAIM, true), false,
                "une recrue ne modifie pas les permissions");
        expect(helper, NationManager.setPermission(chef, NationRole.RECRUE, NationPermission.CLAIM, true), true,
                "le chef accorde la permission");
        expect(helper, NationManager.claim(recruit, dim, chunk), true, "recrue autorisée");
        expect(helper, NationManager.leave(chef), false, "le chef ne peut pas partir");
        expect(helper, NationManager.leave(recruit), true, "la recrue part");

        int before = nation.logEntries().size();
        for (int i = 0; i < 60; i++) {
            nation.log("deposit", "test", "1 cr");
        }
        helper.assertTrue(nation.logEntries().size() == Nation.LOG_SIZE, "journal plafonné à 50 (avant : " + before + ")");

        expect(helper, NationManager.disband(chef), true, "dissolution");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void houseValidation(GameTestHelper helper) {
        // Le gabarit est posé un bloc au-dessus du bloc de structure : son sol de pierre est en y = 1.
        // Pièce 5×5 : murs x/z = 1..5 sur y = 2..4, plafond en y = 5, intérieur 3×3×3.
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                helper.setBlock(new BlockPos(x, 5, z), Blocks.OAK_PLANKS);
                for (int y = 2; y <= 4; y++) {
                    if (x == 1 || x == 5 || z == 1 || z == 5) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
                    }
                }
            }
        }
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        helper.setBlock(new BlockPos(3, 2, 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(new BlockPos(3, 3, 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        helper.setBlock(new BlockPos(3, 2, 4), bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(new BlockPos(4, 2, 4), bed.setValue(BedBlock.PART, BedPart.HEAD));
        BlockPos registry = new BlockPos(2, 2, 2);
        helper.setBlock(registry, ModBlocks.CIVIL_REGISTRY.get());

        BuildingValidator.Validation house = validate(helper, registry);
        helper.assertTrue(house.type() == BuildingType.HOUSE, "maison attendue : " + house.message().getString());

        helper.setBlock(new BlockPos(3, 2, 4), Blocks.AIR);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.CRAFTING_TABLE);
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.FURNACE);
        BuildingValidator.Validation workshop = validate(helper, registry);
        helper.assertTrue(workshop.type() == BuildingType.WORKSHOP, "atelier attendu : " + workshop.message().getString());

        helper.setBlock(new BlockPos(3, 5, 3), Blocks.AIR);
        BuildingValidator.Validation open = validate(helper, registry);
        helper.assertFalse(open.valid(), "toit percé : bâtiment invalide");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void closedGreenhouseIsFarm(GameTestHelper helper) {
        // Serre close 9×9 en verre : murs x/z = 0 et 8 sur y = 2..3, toit en y = 4, sans porte ;
        // sol de terre labourée (48 blocs) sauf sous le registre, posé au centre.
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 4, z), Blocks.GLASS);
                boolean wall = x == 0 || x == 8 || z == 0 || z == 8;
                for (int y = 2; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.GLASS);
                    }
                }
                if (!wall && (x != 4 || z != 4)) {
                    helper.setBlock(new BlockPos(x, 1, z), Blocks.FARMLAND);
                }
            }
        }
        BlockPos registry = new BlockPos(4, 2, 4);
        helper.setBlock(registry, ModBlocks.CIVIL_REGISTRY.get());

        BuildingValidator.Validation farm = validate(helper, registry);
        helper.assertTrue(farm.type() == BuildingType.FARM, "serre close reconnue comme ferme : " + farm.message().getString());
        BuildingValidator.Validation foreign = BuildingValidator.validate(helper.getLevel(), helper.absolutePos(registry),
                512, 16, p -> false);
        helper.assertFalse(foreign.valid(), "terre labourée hors du territoire ignorée : " + foreign.message().getString());
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void farmSpacingMatchesCountedArea(GameTestHelper helper) {
        // Zone de comptage : ±4 blocs à l'horizontale, de y - 2 à y + 1.
        helper.assertTrue(BuildingValidator.farmAreasOverlap(BlockPos.ZERO, new BlockPos(8, 0, -8)), "fermes à 8 blocs : trop proches");
        helper.assertFalse(BuildingValidator.farmAreasOverlap(BlockPos.ZERO, new BlockPos(9, 0, 0)), "fermes à 9 blocs : acceptées");
        helper.assertTrue(BuildingValidator.farmAreasOverlap(BlockPos.ZERO, new BlockPos(0, 3, 0)), "3 blocs plus haut : trop proches");
        helper.assertFalse(BuildingValidator.farmAreasOverlap(BlockPos.ZERO, new BlockPos(0, -4, 0)), "4 blocs plus bas : acceptées");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_buildings")
    public static void mergedRoomsKeepOneRegistry(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        NationData data = NationData.get(server);
        ResourceKey<Level> dim = level.dimension();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ServerPlayer chef = fakePlayer(helper, "sf_chef_pieces");
        ChunkPos capital = new ChunkPos(origin);
        moveTo(helper, chef, capital);
        // Le monde de test persiste entre deux exécutions : nom unique dérivé de la position.
        expect(helper, NationManager.create(chef, "TestPieces" + Math.floorMod(origin.getX() ^ origin.getZ(), 100000)), true,
                "création");
        try {
            Nation nation = data.nationOf(chef.getUUID());
            NationAdmin.adjustTreasury(server, nation, 10_000L);
            // La zone peut chevaucher jusqu'à 2×2 chunks : chacun est revendiqué, de proche en proche.
            ChunkPos far = new ChunkPos(helper.absolutePos(new BlockPos(8, 0, 8)));
            for (int cx = capital.x; cx <= far.x; cx++) {
                for (int cz = capital.z; cz <= far.z; cz++) {
                    if (cx != capital.x || cz != capital.z) {
                        expect(helper, NationManager.claim(chef, dim, new ChunkPos(cx, cz)), true, "revendication de la zone");
                    }
                }
            }
            // Deux pièces (intérieurs x = 2..3 et 5..6, z = 2..4, y = 2..4) séparées par le mur x = 4.
            for (int x = 1; x <= 7; x++) {
                for (int z = 1; z <= 5; z++) {
                    helper.setBlock(new BlockPos(x, 5, z), Blocks.OAK_PLANKS);
                    for (int y = 2; y <= 4; y++) {
                        if (x == 1 || x == 4 || x == 7 || z == 1 || z == 5) {
                            helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
                        }
                    }
                }
            }
            BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
            BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
            for (int x : new int[] {2, 6}) {
                helper.setBlock(new BlockPos(x, 2, 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
                helper.setBlock(new BlockPos(x, 3, 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            }
            for (int x : new int[] {3, 5}) {
                helper.setBlock(new BlockPos(x, 2, 3), bed.setValue(BedBlock.PART, BedPart.FOOT));
                helper.setBlock(new BlockPos(x, 2, 4), bed.setValue(BedBlock.PART, BedPart.HEAD));
            }
            BlockPos registryA = helper.absolutePos(new BlockPos(2, 2, 4));
            BlockPos registryB = helper.absolutePos(new BlockPos(6, 2, 4));
            level.setBlock(registryA, ModBlocks.CIVIL_REGISTRY.get().defaultBlockState(), 3);
            level.setBlock(registryB, ModBlocks.CIVIL_REGISTRY.get().defaultBlockState(), 3);
            GlobalPos keyA = GlobalPos.of(dim, registryA);
            GlobalPos keyB = GlobalPos.of(dim, registryB);

            expect(helper, CivilBuildings.register(chef, level, registryA), true, "maison A");
            expect(helper, CivilBuildings.register(chef, level, registryB), true, "maison B");
            CivilBuildings.revalidate(server, nation, keyA);
            helper.assertTrue(nation.population() == 2, "deux pièces distinctes : deux maisons conservées");

            // Mur commun percé : une seule pièce, deux registres. La revalidation n'en garde qu'un.
            helper.setBlock(new BlockPos(4, 3, 3), Blocks.AIR);
            CivilBuildings.revalidate(server, nation, keyA);
            CivilBuildings.revalidate(server, nation, keyB);
            helper.assertTrue(nation.population() == 1 && nation.buildings().containsKey(keyB),
                    "pièces réunies : un seul registre conservé (population " + nation.population() + ")");
            expect(helper, CivilBuildings.register(chef, level, registryA), false, "second registre dans la pièce réunie");
            helper.assertTrue(nation.population() == 1, "toujours un seul habitant pour la pièce réunie");
        } finally {
            NationManager.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void nationalChestNeedsPlatingBase(GameTestHelper helper) {
        BlockPos core = new BlockPos(4, 3, 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(core.offset(dx, -1, dz), ModBlocks.ARMORED_PLATING.get());
            }
        }
        helper.setBlock(core, ModBlocks.NATIONAL_CHEST.get());
        helper.assertTrue(NationalChestBlock.isStructureValid(helper.getLevel(), helper.absolutePos(core)), "socle complet");
        helper.setBlock(core.offset(1, -1, 1), Blocks.STONE);
        helper.assertFalse(NationalChestBlock.isStructureValid(helper.getLevel(), helper.absolutePos(core)), "socle incomplet");
        helper.succeed();
    }

    /** Un Trésor posé sur la parcelle en interdit l'abandon ; une fois le bloc retiré, l'abandon redevient possible. */
    @GameTest(template = AREA, batch = "sf_core")
    public static void unclaimRefusedWhileTreasuryStands(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ResourceKey<Level> dim = helper.getLevel().dimension();
        BlockPos core = helper.absolutePos(new BlockPos(4, 2, 4));
        ChunkPos capital = new ChunkPos(core);
        ServerPlayer chef = fakePlayer(helper, "sf_coeur");
        moveTo(helper, chef, capital);
        expect(helper, NationManager.create(chef, "TestCoeur" + Math.floorMod(core.asLong(), 100000)), true, "création");
        try {
            Nation nation = NationData.get(server).nationOf(chef.getUUID());
            NationAdmin.adjustTreasury(server, nation, 10_000L);
            expect(helper, NationManager.claim(chef, dim, new ChunkPos(capital.x + 1, capital.z)), true, "second chunk");

            // Pose d'un Trésor : le bloc l'enregistre comme cœur de la nation (comme le fait le joueur).
            var treasury = ModBlocks.TREASURY.get();
            helper.getLevel().setBlock(core, treasury.defaultBlockState(), 3);
            treasury.setPlacedBy(helper.getLevel(), core, treasury.defaultBlockState(), null, ItemStack.EMPTY);
            expect(helper, NationManager.unclaim(chef, dim, capital), false, "abandon refusé : Trésor sur la parcelle");

            // Bloc retiré : l'entrée périmée est purgée et l'abandon redevient possible.
            helper.getLevel().setBlock(core, Blocks.AIR.defaultBlockState(), 3);
            expect(helper, NationManager.unclaim(chef, dim, capital), true, "abandon après retrait du Trésor");
        } finally {
            NationManager.disband(chef);
        }
        helper.succeed();
    }

    /** Au-delà du plafond d'une fenêtre, un joueur ne peut plus enchaîner les actions de nation. */
    @GameTest(template = AREA)
    public static void mutationBurstIsCapped(GameTestHelper helper) {
        ServerPlayer player = fakePlayer(helper, "sf_rafale");
        for (int i = 0; i < NationSync.MUTATIONS_PER_WINDOW; i++) {
            helper.assertTrue(NationSync.allowMutation(player), "action " + (i + 1) + " acceptée");
        }
        helper.assertFalse(NationSync.allowMutation(player), "action au-delà du plafond refusée");
        helper.succeed();
    }

    /** Deux noms ne différant que par la casse : le nom exact désigne un seul compte, une autre casse est ambiguë. */
    @GameTest(template = AREA)
    public static void accountNamesResolveExactlyOrAreAmbiguous(GameTestHelper helper) {
        EconomyData eco = EconomyData.get(helper.getLevel().getServer());
        int n = Math.floorMod(helper.absolutePos(BlockPos.ZERO).asLong(), 100000);
        String lower = "sfcasse" + n;
        String upper = "SFCASSE" + n;
        // UUID déterministes : le monde de test persiste, les comptes ne doivent pas s'accumuler à chaque exécution.
        UUID lowerId = UUID.nameUUIDFromBytes(("lower" + lower).getBytes(StandardCharsets.UTF_8));
        UUID upperId = UUID.nameUUIDFromBytes(("upper" + upper).getBytes(StandardCharsets.UTF_8));
        eco.ensureAccount(lowerId, lower, 0L);
        eco.ensureAccount(upperId, upper, 0L);
        helper.assertTrue(eco.findByName(lower).equals(List.of(lowerId)), "nom exact minuscules : un seul compte");
        helper.assertTrue(eco.findByName(upper).equals(List.of(upperId)), "nom exact majuscules : un seul compte");
        helper.assertTrue(eco.findByName("SfCasse" + n).size() == 2, "autre casse : ambigu, les deux comptes");
        helper.succeed();
    }

    private static void moveTo(GameTestHelper helper, ServerPlayer player, ChunkPos chunk) {
        BlockPos pos = chunk.getMiddleBlockPosition(helper.absolutePos(BlockPos.ZERO).getY());
        player.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    /** Faux joueur NeoForge (aucun réseau) avec un nom unique, placé dans le niveau du test. */
    private static ServerPlayer fakePlayer(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    private static BuildingValidator.Validation validate(GameTestHelper helper, BlockPos relative) {
        return BuildingValidator.validate(helper.getLevel(), helper.absolutePos(relative), 512, 16, p -> true);
    }

    private static void expect(GameTestHelper helper, ActionResult result, boolean success, String step) {
        helper.assertTrue(result.success() == success,
                step + " : attendu " + (success ? "succès" : "échec") + ", obtenu « " + result.message().getString() + " »");
    }
}
