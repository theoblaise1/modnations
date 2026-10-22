// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.client.screen.CratePreviewScreen;
import io.github.theoblaise1.sovereignfrontier.network.CratePreviewView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/** Dernier aperçu de caisse reçu ; ouvre (ou met à jour) l'écran d'aperçu. */
@OnlyIn(Dist.CLIENT)
public final class ClientRewards {
    @Nullable
    private static CratePreviewView preview;

    private ClientRewards() {
    }

    @Nullable
    public static CratePreviewView preview() {
        return preview;
    }

    public static void applyPreview(CratePreviewView view) {
        preview = view;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CratePreviewScreen screen) {
            screen.update(view);
        } else if (!(mc.screen instanceof AbstractContainerScreen<?>)) {
            // Jamais par-dessus un conteneur : remplacé sans closeContainer(), son menu resterait ouvert côté serveur
            mc.setScreen(new CratePreviewScreen(view));
        }
    }

    public static void clear() {
        preview = null;
    }
}
