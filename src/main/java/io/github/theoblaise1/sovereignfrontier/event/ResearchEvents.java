package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.network.ResearchDefinitionsS2C;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchReloadListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;

/** Chargement des paliers depuis les datapacks et synchronisation vers les clients. */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class ResearchEvents {
    private ResearchEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ResearchReloadListener());
    }

    /** À la connexion d'un joueur et après chaque /reload : envoie les définitions à jour. */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        ResearchDefinitionsS2C payload = new ResearchDefinitionsS2C(new ArrayList<>(ResearchRegistry.server().all()));
        event.getRelevantPlayers().filter(NationSync::canReceive)
                .forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }
}
