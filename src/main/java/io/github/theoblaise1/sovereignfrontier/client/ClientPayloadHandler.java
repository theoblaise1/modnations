package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.client.screen.NationScreen;
import io.github.theoblaise1.sovereignfrontier.network.ActionFeedbackS2C;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshotS2C;
import io.github.theoblaise1.sovereignfrontier.network.NationTab;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Gestionnaires des paquets serveur → client (appelés depuis l'enregistrement commun). */
@OnlyIn(Dist.CLIENT)
public final class ClientPayloadHandler {

    private ClientPayloadHandler() {
    }

    public static void handleSnapshot(NationSnapshotS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> applySnapshot(payload));
    }

    public static void handleFeedback(ActionFeedbackS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> applyFeedback(payload));
    }

    private static void applySnapshot(NationSnapshotS2C payload) {
        ClientNationState.set(payload.snapshot());
        Minecraft mc = Minecraft.getInstance();
        NationTab tab = NationTab.byOrdinal(payload.tab());
        if (mc.screen instanceof NationScreen screen) {
            // Écran déjà ouvert : on le met à jour sans perdre la saisie en cours
            if (payload.openScreen()) {
                screen.showTab(tab);
            } else {
                screen.refresh();
            }
        } else if (payload.openScreen()) {
            mc.setScreen(new NationScreen(tab));
        }
    }

    private static void applyFeedback(ActionFeedbackS2C payload) {
        // Sans écran national ouvert, le message est déjà dans le chat
        if (Minecraft.getInstance().screen instanceof NationScreen screen) {
            screen.showFeedback(payload.message(), payload.success());
        }
    }
}
