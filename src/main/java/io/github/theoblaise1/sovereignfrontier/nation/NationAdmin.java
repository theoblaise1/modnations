package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.util.Money;
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
}
