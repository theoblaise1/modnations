package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.command.AdminCommand;
import io.github.theoblaise1.sovereignfrontier.command.EconomyCommands;
import io.github.theoblaise1.sovereignfrontier.command.NationCommand;
import io.github.theoblaise1.sovereignfrontier.command.ResearchCommand;
import io.github.theoblaise1.sovereignfrontier.command.TerritoryCommands;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyNetworks;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringManager;
import io.github.theoblaise1.sovereignfrontier.nation.AdminBypass;
import io.github.theoblaise1.sovereignfrontier.nation.CivilBuildings;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.TreasurySessions;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.player.PlayerProfile;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.player.TerritoryTracker;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import io.github.theoblaise1.sovereignfrontier.war.WarTicker;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/** Cycle de vie serveur : commandes, connexions, tick (revenu, revalidation), entrées de territoire. */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class ServerEvents {
    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        NationCommand.register(event.getDispatcher());
        TerritoryCommands.register(event.getDispatcher());
        EconomyCommands.register(event.getDispatcher());
        AdminCommand.register(event.getDispatcher());
        ResearchCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        NationManager.tickIncome(event.getServer());
        CivilBuildings.tickRevalidation(event.getServer());
        ResearchManager.tick(event.getServer());
        WarTicker.tick(event.getServer());
        // En dernier : regroupe les rafraîchissements planifiés pendant ce tick.
        NationSync.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String name = player.getGameProfile().getName();
        boolean created = EconomyData.get(player.server).ensureAccount(player.getUUID(), name, SFConfig.STARTING_BALANCE.get());
        PlayerProfile profile = player.getData(ModAttachments.PROFILE);
        if (profile.firstJoinEpochMs() == 0L) {
            player.setData(ModAttachments.PROFILE, profile.withFirstJoin(TimeUtil.now()));
        }
        Nation nation = NationData.get(player.server).nationOf(player.getUUID());
        if (nation != null) {
            nation.updateMemberName(player.getUUID(), name);
            // Statut « en ligne » (et nom éventuellement changé) vu par les membres dont l'écran est ouvert.
            NationSync.scheduleRefresh(nation);
            player.sendSystemMessage(Msg.info("welcome.member", nation.name(), nation.roleOf(player.getUUID()).displayName()));
        } else {
            player.sendSystemMessage(Msg.info("welcome.no_nation"));
        }
        if (created) {
            player.sendSystemMessage(Msg.ok("welcome.starting_balance", Money.format(SFConfig.STARTING_BALANCE.get())));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TreasurySessions.close(event.getEntity().getUUID());
        EngineeringManager.close(event.getEntity().getUUID());
        NationSync.forget(event.getEntity().getUUID());
        AdminBypass.forget(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            // Envoi groupé ultérieur : le joueur aura quitté la liste (et hasDisconnected() est déjà vrai).
            Nation nation = NationData.get(player.server).nationOf(player.getUUID());
            if (nation != null) {
                NationSync.scheduleRefresh(nation);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TreasurySessions.clear();
        NationSync.clear();
        AdminBypass.clear();
        EngineeringManager.clear();
        EnergyNetworks.clear();
    }

    /** Toutes les 10 ticks : message d'entrée/sortie de territoire (une recherche O(1) dans le cache). */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 10 != 0) {
            return;
        }
        TerritoryTracker tracker = player.getData(ModAttachments.TERRITORY_TRACKER);
        // Pas de raccourci par chunk : le propriétaire peut changer sous un joueur immobile (revendication ou
        // abandon), et un passage de portail peut arriver à des coordonnées de chunk identiques.
        ResourceKey<Level> dimension = player.level().dimension();
        long chunk = player.chunkPosition().toLong();
        Nation owner = NationData.get(player.server).ownerAt(dimension, chunk);
        UUID ownerId = owner == null ? null : owner.id();
        boolean changed = !tracker.initialized || !dimension.equals(tracker.lastDimension)
                || !java.util.Objects.equals(ownerId, tracker.lastOwner);
        tracker.lastDimension = dimension;
        tracker.lastOwner = ownerId;
        boolean first = !tracker.initialized;
        tracker.initialized = true;
        if (!changed || first || !player.getData(ModAttachments.PROFILE).territoryNotifications()) {
            return;
        }
        Component message = owner == null
                ? Msg.tr("territory.enter_wild").withStyle(ChatFormatting.DARK_GREEN)
                : Msg.tr("territory.enter_nation", Component.literal(owner.name()).withColor(owner.color()))
                .withStyle(ChatFormatting.GOLD);
        player.displayClientMessage(message, true);
    }
}
