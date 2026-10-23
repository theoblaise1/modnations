// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.war.missile;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import io.github.theoblaise1.sovereignfrontier.war.WarData;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * File des missiles en vol (J7, docs/conception/J7.md §4) : un passage toutes les 20 ticks. Les échéances sont des
 * timestamps epoch ({@link WarData.PendingMissile#atEpochMs()}) comparés à {@link TimeUtil#now()} ; le compteur de ticks
 * du serveur ne sert qu'à espacer les passages, et ne porte aucun état.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class MissileTicker {
    /** Intervalle entre deux passages, en ticks. */
    static final int PERIOD_TICKS = 20;

    private MissileTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % PERIOD_TICKS == 0) {
            tick(server);
        }
    }

    /** Applique les impacts échus (échéance ≤ maintenant) et les retire de la file. */
    public static void tick(MinecraftServer server) {
        WarData war = WarData.get(server);
        if (war.missiles().isEmpty()) {
            return;
        }
        long now = TimeUtil.now();
        // Copie : la file est modifiée pendant le parcours (retrait après impact).
        List<WarData.PendingMissile> due = new ArrayList<>();
        for (WarData.PendingMissile missile : war.missiles()) {
            if (missile.atEpochMs() <= now) {
                due.add(missile);
            }
        }
        for (WarData.PendingMissile missile : due) {
            if (war.removeMissile(missile)) {
                MissileImpact.impact(server, missile);
            }
        }
    }
}
