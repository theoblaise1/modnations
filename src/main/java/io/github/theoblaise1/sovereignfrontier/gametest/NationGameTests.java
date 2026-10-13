package io.github.theoblaise1.sovereignfrontier.gametest;

import com.mojang.authlib.GameProfile;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.block.NationalChestBlock;
import io.github.theoblaise1.sovereignfrontier.event.TerritoryEvents;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.BuildingType;
import io.github.theoblaise1.sovereignfrontier.nation.BuildingValidator;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationAdmin;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

        expect(helper, NationManager.create(chef, "TestClaims"), true, "création");
        Nation nation = data.nationOf(chef.getUUID());
        helper.assertTrue(nation != null && nation.roleOf(chef.getUUID()) == NationRole.CHEF, "le fondateur est chef");

        ChunkPos chunk = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        expect(helper, NationManager.claim(chef, dim, chunk), false, "claim sans fonds");
        NationAdmin.adjustTreasury(server, nation, 10_000L);
        expect(helper, NationManager.claim(chef, dim, chunk), true, "premier claim");
        helper.assertTrue(data.ownerAt(dim, chunk.toLong()) == nation, "cache mis à jour après claim");
        helper.assertTrue(nation.treasury() == 10_000L - 100L, "coût du 1er chunk débité");
        expect(helper, NationManager.claim(chef, dim, chunk), false, "chunk déjà possédé");
        expect(helper, NationManager.claim(chef, dim, new ChunkPos(chunk.x + 2, chunk.z)), false, "chunk non contigu");
        ChunkPos east = new ChunkPos(chunk.x + 1, chunk.z);
        expect(helper, NationManager.claim(chef, dim, east), true, "chunk contigu");
        helper.assertTrue(nation.treasury() == 10_000L - 100L - 108L, "coût croissant du 2e chunk");

        helper.assertTrue(TerritoryEvents.canBuild(chef, nation), "le chef peut construire");
        helper.assertFalse(TerritoryEvents.canBuild(outsider, nation), "un étranger ne peut pas construire");

        expect(helper, NationManager.unclaim(chef, dim, east), true, "abandon");
        helper.assertTrue(data.ownerAt(dim, east.toLong()) == null, "cache invalidé après unclaim");

        expect(helper, NationManager.disband(chef), true, "dissolution");
        helper.assertTrue(data.ownerAt(dim, chunk.toLong()) == null, "cache vidé après dissolution");
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

        expect(helper, NationManager.create(chef, "TestMembres"), true, "création");
        Nation nation = data.nationOf(chef.getUUID());
        expect(helper, NationManager.join(recruit, "TestMembres"), false, "rejoindre sans invitation");
        expect(helper, NationManager.invite(chef, recruit), true, "invitation");
        expect(helper, NationManager.join(recruit, "TestMembres"), true, "rejoindre avec invitation");
        helper.assertTrue(nation.roleOf(recruit.getUUID()) == NationRole.RECRUE, "nouveau membre = recrue");

        NationAdmin.adjustTreasury(server, nation, 1_000L);
        ChunkPos chunk = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
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

    /** Faux joueur NeoForge (aucun réseau) avec un nom unique, placé dans le niveau du test. */
    private static ServerPlayer fakePlayer(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    private static BuildingValidator.Validation validate(GameTestHelper helper, BlockPos relative) {
        return BuildingValidator.validate(helper.getLevel(), helper.absolutePos(relative), 512, 16);
    }

    private static void expect(GameTestHelper helper, ActionResult result, boolean success, String step) {
        helper.assertTrue(result.success() == success,
                step + " : attendu " + (success ? "succès" : "échec") + ", obtenu « " + result.message().getString() + " »");
    }
}
