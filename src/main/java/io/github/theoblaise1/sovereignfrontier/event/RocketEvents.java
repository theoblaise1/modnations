// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.command.RocketCommand;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketBlockEntity;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketFlight;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketProtection;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketSessions;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Fusée du J5 (WP2) : commande {@code /rocket}, protection de la fusée contre les casses étrangères, effet des dégâts, de
 * la mort et de la déconnexion sur un équipage en compte à rebours, et remise à zéro à l'arrêt. Chaque gestionnaire est
 * une recherche O(1) (table des équipages, bloc cassé) : aucun parcours de nations ni de chunks.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class RocketEvents {
    private RocketEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        RocketCommand.register(event.getDispatcher());
    }

    /**
     * Casse d'une fusée : réservée aux membres de la nation propriétaire, aux bâtisseurs de la nation du chunk, ou à
     * tous si la nation propriétaire n'existe plus ({@link RocketProtection}). Après la protection du territoire
     * (priorité haute), qui s'applique en plus.
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)
                || !event.getState().is(ModBlocks.ROCKET.get())
                || !(level.getBlockEntity(event.getPos()) instanceof RocketBlockEntity rocket)) {
            return;
        }
        if (!RocketProtection.mayBreak(player, level, event.getPos(), rocket)) {
            event.setCanceled(true);
            Nation owner = rocket.owner() == null ? null : NationData.get(level.getServer()).byId(rocket.owner());
            player.displayClientMessage(Msg.err("rocket.error.protected", owner == null ? "?" : owner.name()), true);
        }
    }

    /** Un joueur de l'équipage qui subit des dégâts : le pilote annule le vol, un membre quitte l'équipage. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !event.isCanceled()) {
            RocketFlight.onDamage(player, event.getAmount());
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RocketFlight.onDeath(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RocketFlight.onLogout(player);
            RocketSessions.close(player.getUUID());
        }
    }

    /** Arrêt : les comptes à rebours sont annulés et les blocs remis au repos avant la sauvegarde finale. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RocketFlight.cancelAll(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RocketFlight.clear();
        RocketSessions.clear();
    }
}
