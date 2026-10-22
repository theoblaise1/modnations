package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.network.EconomyNetwork;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Socle de l'économie (J4) : vidage groupé de la vue Économie, audit des recettes interdites au démarrage et après
 * chaque {@code /reload}, nettoyage à la déconnexion et à l'arrêt. Les systèmes J4 (avant-postes, récompenses,
 * contrats, entreprises) ont chacun leur propre classe d'événements.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class EconomyEvents {
    private EconomyEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        EconomyNetwork.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        RestrictedItems.auditRecipes(event.getServer());
    }

    /**
     * Après un {@code /reload} : les recettes du serveur sont déjà remplacées quand les tags sont publiés. Au premier
     * chargement (avant la création du serveur), aucun serveur n'est encore actif : l'audit attend
     * {@link ServerStartedEvent}.
     */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isRunning() && server.isSameThread()) {
            RestrictedItems.auditRecipes(server);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        EconomyNetwork.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        EconomyNetwork.clear();
    }
}
