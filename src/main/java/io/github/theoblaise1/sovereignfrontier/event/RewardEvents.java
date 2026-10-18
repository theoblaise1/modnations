package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.command.RewardCommands;
import io.github.theoblaise1.sovereignfrontier.economy.ActivityTracker;
import io.github.theoblaise1.sovereignfrontier.economy.TerminalManager;
import io.github.theoblaise1.sovereignfrontier.reward.CrateRegistry;
import io.github.theoblaise1.sovereignfrontier.reward.DailyTable;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Récompenses joueur (J4) : chargement des tables de caisse et de {@code /daily} (candidat au rechargement,
 * publication atomique quand les tags sont liés), commandes, échantillonnage de l'activité (toutes les 20 ticks par
 * joueur, O(1)) et signaux d'activité (casse/pose, chat, commande, conteneur, artisanat), nettoyage à la connexion,
 * à la déconnexion et à l'arrêt. Jamais de parcours de nations ni de chunks.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class RewardEvents {
    /** Échantillonnage de l'activité : une fois par seconde (20 ticks). */
    public static final int SAMPLE_INTERVAL_TICKS = 20;

    private RewardEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new CrateRegistry.ReloadListener());
        event.addListener(new DailyTable.ReloadListener());
    }

    /**
     * Les tags sont liés : validations qui en dépendent (objets interdits) puis publication. Au premier chargement,
     * la config serveur n'est pas encore lue : la validation n'utilise que des constantes de code.
     */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            CrateRegistry.publish();
            DailyTable.publish();
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        RewardCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % SAMPLE_INTERVAL_TICKS == 0
                && TerminalManager.eligible(player)) {
            TerminalManager.tick(player, TimeUtil.now());
        }
    }

    // ------------------------------------------------------------------ signaux d'activité

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (tracked(event.getPlayer())) {
            ActivityTracker.signalAt(event.getPlayer().getUUID(), event.getPos(), TimeUtil.now());
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (tracked(event.getEntity())) {
            ActivityTracker.signalAt(event.getEntity().getUUID(), event.getPos(), TimeUtil.now());
        }
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        signal(event.getPlayer());
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        signal(event.getParseResults().getContext().getSource().getPlayer());
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        signal(event.getEntity());
    }

    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent event) {
        signal(event.getEntity());
    }

    /** Joueur réel en ligne (les faux joueurs des machines ne sont jamais suivis). */
    private static boolean tracked(Entity entity) {
        return entity instanceof ServerPlayer && !(entity instanceof FakePlayer);
    }

    private static void signal(Entity entity) {
        if (tracked(entity)) {
            ActivityTracker.signal(entity.getUUID(), TimeUtil.now());
        }
    }

    // ------------------------------------------------------------------ cycle de vie

    /** À la connexion, aucun signal : le joueur est inactif jusqu'à son premier geste. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        ActivityTracker.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ActivityTracker.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ActivityTracker.clear();
    }
}
