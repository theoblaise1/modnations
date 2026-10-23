// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Mort de joueur (J7, docs/conception/J7.md §4) : un joueur tué par un joueur d'une autre nation compte pour le score de
 * guerre ({@link WarScore#recordKill}). Les joueurs sans nation, ou de la même nation, ne comptent pas. Aucun scan.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class WarEvents {
    private WarEvents() {
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)
                || !(event.getSource().getEntity() instanceof ServerPlayer killer)) {
            return;
        }
        MinecraftServer server = victim.server;
        NationData nations = NationData.get(server);
        Nation victimNation = nations.nationOf(victim.getUUID());
        Nation killerNation = nations.nationOf(killer.getUUID());
        if (victimNation == null || killerNation == null || victimNation.id().equals(killerNation.id())) {
            return;
        }
        WarScore.recordKill(server, killerNation.id(), victimNation.id());
    }
}
