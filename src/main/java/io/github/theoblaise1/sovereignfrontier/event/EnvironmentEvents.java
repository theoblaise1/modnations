// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.command.EnvironmentCommand;
import io.github.theoblaise1.sovereignfrontier.space.Environment;
import io.github.theoblaise1.sovereignfrontier.space.SpaceDimensions;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Oxygène et froid (J5, WP3) : traitement des joueurs toutes les 20 ticks ({@link Environment#tick}, O(1) par joueur),
 * acclimatation à la connexion, remise à zéro à la réapparition et commande {@code /oxygen}. Les machines qui créent
 * les zones (diffuseur, chaudière, réchauffeur) sont ticquées par leur bloc-entité ; les sous-commandes
 * {@code /sfadmin oxygen|cold} sont greffées par {@code AdminCommand}.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class EnvironmentEvents {
    private EnvironmentEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        EnvironmentCommand.register(event.getDispatcher());
    }

    /** Toutes les 20 ticks, par joueur : une lecture de planète, puis la décision de {@link Environment#tick}. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % Environment.PERIOD != 0
                || player instanceof FakePlayer) {
            return;
        }
        Environment.tick(player, SpaceDimensions.planet(player.level()), TimeUtil.now());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Environment.onLogin(player, TimeUtil.now());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Environment.onRespawn(player);
        }
    }
}
