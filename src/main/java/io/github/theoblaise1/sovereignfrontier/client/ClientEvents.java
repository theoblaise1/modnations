package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.network.NationTab;
import io.github.theoblaise1.sovereignfrontier.network.RequestNationDataC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Événements client du bus de jeu : touche d'ouverture de l'écran national, déconnexion. */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = SovereignFrontier.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean requested = false;
        // On vide toutes les pressions en attente, mais une seule requête par tick
        while (ModKeyMappings.OPEN_NATION.consumeClick()) {
            if (!requested && mc.screen == null && mc.player != null && serverHasMod(mc)) {
                PacketDistributor.sendToServer(new RequestNationDataC2S(true, NationTab.OVERVIEW.ordinal()));
                requested = true;
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientNationState.clear();
    }

    /** Évite d'envoyer le paquet à un serveur qui n'a pas le mod. */
    private static boolean serverHasMod(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        return connection != null && connection.hasChannel(RequestNationDataC2S.TYPE);
    }
}
