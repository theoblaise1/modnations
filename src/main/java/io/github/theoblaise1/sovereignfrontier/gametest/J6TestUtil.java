package io.github.theoblaise1.sovereignfrontier.gametest;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Outils communs aux GameTests du J6 (docs/conception/J6.md §6). Règles : gabarit {@link #AREA} ; tout test qui fonde une
 * nation a son propre lot {@code sf_j6_<test>} (noms uniques par {@link J4TestUtil#unique}) ; nettoyage dans un
 * {@code finally} par {@link #disband} ; un test qui pose un autel ou un avant-poste le retire lui-même. Délègue au J4 et
 * au J5 pour tout le reste.
 */
public final class J6TestUtil {
    public static final String AREA = J4TestUtil.AREA;

    private J6TestUtil() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path);
    }

    public static ServerPlayer fakePlayer(GameTestHelper helper, String name) {
        return J4TestUtil.fakePlayer(helper, name);
    }

    /** Fonde une nation dont {@code chef} est le chef, puis accorde les paliers {@code tiers} (voir J5TestUtil). */
    public static Nation foundNation(GameTestHelper helper, ServerPlayer chef, String base, String... tiers) {
        return J5TestUtil.foundNation(helper, chef, base, tiers);
    }

    /** Dissolution de nettoyage (coffre national vidé d'abord, comme J4TestUtil et J5TestUtil). */
    public static void disband(ServerPlayer chef) {
        J4TestUtil.disband(chef);
    }
}
