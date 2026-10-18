// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.command.EnterpriseCommand;
import io.github.theoblaise1.sovereignfrontier.enterprise.SectorRegistry;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * Entreprises (J4, WP4) : chargement des secteurs ({@code data/<ns>/enterprises/}), publication à la mise à jour des
 * tags (validations qui exigent les tags liés), commande {@code /enterprise}. La production elle-même est appelée par
 * {@code NationManager.tickIncome} : aucun traitement par tick ici.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class EnterpriseEvents {
    private EnterpriseEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SectorRegistry.ReloadListener());
    }

    /**
     * Après tous les écouteurs de rechargement (premier chargement ou {@code /reload}) : tags liés et registre de
     * recherche à jour, le candidat est validé puis publié. La synchronisation vers un client n'est pas concernée.
     */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            SectorRegistry.publishCandidate();
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        EnterpriseCommand.register(event.getDispatcher());
    }
}
