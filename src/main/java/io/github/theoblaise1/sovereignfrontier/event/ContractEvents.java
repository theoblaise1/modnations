// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.command.ContractCommand;
import io.github.theoblaise1.sovereignfrontier.contract.ContractManager;
import io.github.theoblaise1.sovereignfrontier.contract.ContractRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Contrats nationaux (J4, WP3) : chargement des modèles ({@code data/<ns>/contracts/}) et publication à la mise à jour
 * des tags, commande {@code /contract}, progression des contrats de chasse ({@code LivingDeathEvent}, O(1)) et
 * d'exploration (toutes les 40 ticks par joueur, liste en cache), entretien (échéances et rafraîchissements) toutes les
 * 1200 ticks. Aucun parcours de nations ni de chunks hors de l'entretien.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class ContractEvents {
    private ContractEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ContractRegistry.ReloadListener());
    }

    /**
     * Après tous les écouteurs de rechargement (premier chargement ou {@code /reload}) : tags liés, registres dynamiques
     * (biomes, dimensions) et registre de recherche à jour ; le candidat est validé puis publié.
     */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            ContractRegistry.publishCandidate(event.getRegistryAccess());
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ContractCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            ContractManager.onKill(event.getSource().getEntity(), event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ContractManager.exploreTick(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ContractManager.tick(event.getServer());
    }
}
