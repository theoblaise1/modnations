package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;

/** Opérations réservées aux administrateurs (journalisées dans le journal national). */
public final class NationAdmin {
    private NationAdmin() {
    }

    public static void adjustTreasury(MinecraftServer server, Nation nation, long delta) {
        nation.addTreasury(delta);
        nation.log("admin_treasury", Money.format(delta));
        NationSync.refreshNation(server, nation);
    }

    /**
     * Enregistre un bâtiment civil SANS validation (ni structure, ni territoire, ni registre posé). Réservé aux tests
     * et à l'administration : la revalidation tournante le retirera s'il n'existe pas réellement.
     */
    public static void addBuildingUnchecked(Nation nation, GlobalPos pos, BuildingType type) {
        nation.addBuilding(pos, type);
        NationSync.scheduleRefresh(nation);
    }
}
