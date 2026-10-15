package io.github.theoblaise1.sovereignfrontier.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedArgument;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * GameTests des correctifs de la revue : changements de rang ou de permission sans effet,
 * nom de nation invalide qui atteint la validation du mod, rafraîchissement groupé de l'écran
 * national quand le coffre change. Chaque test qui fonde une nation a son propre lot (même
 * chunk de capitale possible d'un test à l'autre) et la dissout dans un {@code finally}.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class NationSyncGameTests {
    private static final String AREA = "gametest/empty9";

    private NationSyncGameTests() {
    }

    @GameTest(template = AREA, batch = "sf_sync_roles")
    public static void unchangedRoleOrPermissionIsNotLogged(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chef = fakePlayer(helper, "sf_chef_sync");
        ServerPlayer recruit = fakePlayer(helper, "sf_recrue_sync");
        moveToCapital(helper, chef);
        expect(helper, NationManager.create(chef, uniqueName(helper, "SyncRoles")), true, "création");
        try {
            Nation nation = NationData.get(server).nationOf(chef.getUUID());
            expect(helper, NationManager.invite(chef, recruit), true, "invitation");
            expect(helper, NationManager.join(recruit, nation.name()), true, "adhésion");

            expect(helper, NationManager.setRole(chef, "sf_recrue_sync", NationRole.OFFICIER), true, "nomination Officier");
            int logged = nation.logEntries().size();
            expect(helper, NationManager.setRole(chef, "sf_recrue_sync", NationRole.OFFICIER), false, "déjà Officier");
            helper.assertTrue(nation.logEntries().size() == logged, "rang inchangé : aucune entrée de journal");
            helper.assertTrue(nation.roleOf(recruit.getUUID()) == NationRole.OFFICIER, "toujours Officier");

            helper.assertFalse(nation.roleHas(NationRole.RECRUE, NationPermission.CLAIM), "recrue sans revendiquer par défaut");
            expect(helper, NationManager.setPermission(chef, NationRole.RECRUE, NationPermission.CLAIM, true), true, "accord");
            logged = nation.logEntries().size();
            expect(helper, NationManager.setPermission(chef, NationRole.RECRUE, NationPermission.CLAIM, true), false, "accord répété");
            helper.assertTrue(nation.logEntries().size() == logged, "permission déjà accordée : aucune entrée de journal");
            expect(helper, NationManager.setPermission(chef, NationRole.RECRUE, NationPermission.CLAIM, false), true, "retrait");
            logged = nation.logEntries().size();
            expect(helper, NationManager.setPermission(chef, NationRole.RECRUE, NationPermission.CLAIM, false), false, "retrait répété");
            helper.assertTrue(nation.logEntries().size() == logged, "permission déjà retirée : aucune entrée de journal");
        } finally {
            NationManager.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void accentedNameReachesValidation(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // L'argument <nom> accepte le texte brut : la commande s'analyse jusqu'au bout et
        // c'est NAME_PATTERN qui refuse, avec le message du mod (pas une erreur de syntaxe).
        for (String name : new String[] {"Été", "Mon Pays"}) {
            ParseResults<CommandSourceStack> parse = server.getCommands().getDispatcher()
                    .parse("nation create " + name, server.createCommandSourceStack());
            ParsedArgument<CommandSourceStack, ?> argument = parse.getContext().getArguments().get("nom");
            helper.assertTrue(parse.getExceptions().isEmpty() && !parse.getReader().canRead()
                            && parse.getContext().getCommand() != null && argument != null && name.equals(argument.getResult()),
                    "« /nation create " + name + " » doit atteindre la validation du mod");
        }
        ServerPlayer founder = fakePlayer(helper, "sf_fond_accent");
        try {
            expect(helper, NationManager.create(founder, "Été"), false, "nom accentué");
            expect(helper, NationManager.create(founder, "Mon Pays"), false, "nom avec espace");
            helper.assertTrue(NationData.get(server).nationOf(founder.getUUID()) == null, "aucune nation fondée");
        } finally {
            NationManager.disband(founder);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_sync_chest")
    public static void chestChangeSchedulesRefresh(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer chef = fakePlayer(helper, "sf_chef_coffre");
        moveToCapital(helper, chef);
        expect(helper, NationManager.create(chef, uniqueName(helper, "SyncCoffre")), true, "création");
        Nation nation = NationData.get(server).nationOf(chef.getUUID());
        try {
            // Aucun tick ne s'écoule pendant le corps du test : l'état observé est déterministe.
            NationSync.refreshNation(server, nation);
            helper.assertFalse(NationSync.isRefreshScheduled(nation), "rien en attente après un envoi immédiat");
            nation.chest().setItem(0, new ItemStack(Items.IRON_INGOT, 32));
            helper.assertTrue(NationSync.isRefreshScheduled(nation), "dépôt dans le coffre : rafraîchissement planifié");
            nation.chest().setItem(1, new ItemStack(Items.IRON_INGOT, 32));
            helper.assertTrue(NationSync.isRefreshScheduled(nation), "plusieurs dépôts : un seul rafraîchissement en attente");
            NationSync.refreshNation(server, nation);
            helper.assertFalse(NationSync.isRefreshScheduled(nation), "l'envoi immédiat couvre le rafraîchissement planifié");
        } finally {
            if (nation != null) {
                nation.chest().clearContent();
            }
            NationManager.disband(chef);
        }
        helper.succeed();
    }

    /** Le monde de test persiste entre deux exécutions : nom unique dérivé de la position. */
    private static String uniqueName(GameTestHelper helper, String prefix) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return prefix + Math.floorMod(origin.getX() ^ origin.getZ(), 100000);
    }

    private static void moveToCapital(GameTestHelper helper, ServerPlayer player) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos pos = new ChunkPos(origin).getMiddleBlockPosition(origin.getY());
        player.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    /** Faux joueur NeoForge (aucun réseau) avec un nom unique, placé dans le niveau du test. */
    private static ServerPlayer fakePlayer(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    private static void expect(GameTestHelper helper, ActionResult result, boolean success, String step) {
        helper.assertTrue(result.success() == success,
                step + " : attendu " + (success ? "succès" : "échec") + ", obtenu « " + result.message().getString() + " »");
    }
}
