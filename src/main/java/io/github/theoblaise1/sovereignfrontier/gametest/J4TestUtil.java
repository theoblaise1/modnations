package io.github.theoblaise1.sovereignfrontier.gametest;

import com.mojang.authlib.GameProfile;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.BuildingType;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationAdmin;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;

/**
 * Outils communs aux GameTests du J4. Règles : gabarit {@link #AREA} sauf mention contraire ; tout test qui fonde
 * une nation a son propre lot {@code sf_j4_<test>} ; noms uniques par {@link #unique} (le monde de test persiste
 * entre deux exécutions) ; nettoyage dans un {@code finally}.
 */
public final class J4TestUtil {
    public static final String AREA = "gametest/empty9";

    private J4TestUtil() {
    }

    /** Nom unique dérivé de la position du test : {@code base} suivi d'au plus 5 chiffres (noms de nation ≤ 24). */
    public static String unique(GameTestHelper helper, String base) {
        return base + Math.floorMod(helper.absolutePos(BlockPos.ZERO).asLong(), 100000);
    }

    /** Faux joueur NeoForge (aucun réseau), placé dans le niveau du test, avec un UUID neuf. */
    public static ServerPlayer fakePlayer(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    /** Place le joueur au milieu du chunk, à la hauteur de la zone de test. */
    public static void moveTo(GameTestHelper helper, ServerPlayer player, ChunkPos chunk) {
        BlockPos pos = chunk.getMiddleBlockPosition(helper.absolutePos(BlockPos.ZERO).getY());
        player.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    public static void expect(GameTestHelper helper, ActionResult result, boolean success, String step) {
        helper.assertTrue(result.success() == success,
                step + " : attendu " + (success ? "succès" : "échec") + ", obtenu « " + result.message().getString() + " »");
    }

    /** Fonde une nation dont {@code chef} est le chef ; la capitale est le chunk de l'origine du test. */
    public static Nation foundNation(GameTestHelper helper, ServerPlayer chef, String base) {
        moveTo(helper, chef, new ChunkPos(helper.absolutePos(BlockPos.ZERO)));
        expect(helper, NationManager.create(chef, unique(helper, base)), true, "fondation de la nation de test");
        Nation nation = NationData.get(helper.getLevel().getServer()).nationOf(chef.getUUID());
        helper.assertTrue(nation != null, "nation de test introuvable après sa fondation");
        return nation;
    }

    /**
     * Ajoute {@code count} habitants (maisons enregistrées sans validation). À consommer dans le même tick : la
     * revalidation tournante retirera ces bâtiments fictifs.
     */
    public static void addInhabitants(GameTestHelper helper, Nation nation, int count) {
        for (int i = 0; i < count; i++) {
            BlockPos pos = helper.absolutePos(new BlockPos(i % 8, 2, i / 8));
            NationAdmin.addBuildingUnchecked(nation, GlobalPos.of(helper.getLevel().dimension(), pos), BuildingType.HOUSE);
        }
    }

    /** Dissolution de nettoyage (coffre national vidé d'abord : sinon la dissolution est refusée). */
    public static void disband(ServerPlayer chef) {
        Nation nation = NationData.get(chef.server).nationOf(chef.getUUID());
        if (nation != null) {
            nation.chest().clearContent();
            NationManager.disband(chef);
        }
    }
}
