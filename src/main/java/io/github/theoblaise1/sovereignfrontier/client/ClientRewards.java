// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.network.CratePreviewView;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/** Dernier aperçu de caisse reçu et ouverture de l'écran d'aperçu. Ébauche du socle : l'aperçu est seulement mémorisé. */
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
    }

    public static void clear() {
        preview = null;
    }
}
