// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.client.screen;

import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;

/**
 * Panneau « Entreprises » de l'onglet Économie (4 secteurs, habitants affectés, production, verrous). Appelé par
 * {@link EconomyTab} avec la zone qui lui est réservée. Ébauche du socle : message d'attente.
 */
@OnlyIn(Dist.CLIENT)
final class EnterprisesPanel {
    private static final int COL_LABEL = 0xFFAAAAAA;

    private int x;
    private int y;
    private int w;
    private int h;

    void init(Consumer<AbstractWidget> add, int x, int y, int w, int h, NationSnapshot s, EconomyView v) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    void render(GuiGraphics g, NationSnapshot s, EconomyView v, int mouseX, int mouseY,
                Consumer<List<FormattedCharSequence>> tooltip) {
        g.drawCenteredString(Minecraft.getInstance().font, Msg.tr("gui.economy.unavailable"), x + w / 2, y + h / 2 - 4,
                COL_LABEL);
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        return false;
    }
}
