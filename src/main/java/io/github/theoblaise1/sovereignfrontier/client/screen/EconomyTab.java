package io.github.theoblaise1.sovereignfrontier.client.screen;

import io.github.theoblaise1.sovereignfrontier.client.ClientEconomy;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot;
import io.github.theoblaise1.sovereignfrontier.network.RequestEconomyC2S;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

/**
 * Onglet « Économie » de l'écran national (identifiant d'onglet {@code contracts}) : bascule entre les panneaux
 * [Contrats] et [Entreprises], qui reçoivent la dernière {@link EconomyView}. La vue est demandée une fois à
 * l'ouverture de l'onglet ; le serveur pousse ensuite les changements (envoi groupé).
 */
@OnlyIn(Dist.CLIENT)
final class EconomyTab {
    private static final int TOGGLE_H = 14;
    private static final int GAP = 4;
    /** Ligne « lecture seule » réservée sous le panneau quand le joueur n'a pas la permission {@code economie}. */
    private static final int READ_ONLY_H = 10;
    private static final int COL_LABEL = 0xFFAAAAAA;
    private static final int COL_WARN = 0xFFFFAA00;

    private enum Section {
        CONTRACTS,
        ENTERPRISES
    }

    private final ContractsPanel contracts = new ContractsPanel();
    private final EnterprisesPanel enterprises = new EnterprisesPanel();
    private Section section = Section.CONTRACTS;
    /** Vue demandée depuis l'ouverture de l'onglet : une reconstruction (réception de la vue) ne redemande rien. */
    private boolean requested;
    private int x;
    private int y;
    private int w;
    private int h;

    private static MutableComponent tr(String key, Object... args) {
        return Msg.tr("gui.economy." + key, args);
    }

    // ------------------------------------------------------------------ widgets

    void init(Consumer<AbstractWidget> add, int x, int y, int w, int h, NationSnapshot s) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        if (!requested) {
            requested = true;
            PacketDistributor.sendToServer(RequestEconomyC2S.INSTANCE);
        }
        int half = (w - 2) / 2;
        Button contractsButton = Button.builder(tr("contracts"), b -> select(Section.CONTRACTS))
                .bounds(x, y, half, TOGGLE_H).build();
        contractsButton.active = section != Section.CONTRACTS;
        add.accept(contractsButton);
        Button enterprisesButton = Button.builder(tr("enterprises"), b -> select(Section.ENTERPRISES))
                .bounds(x + w - half, y, half, TOGGLE_H).build();
        enterprisesButton.active = section != Section.ENTERPRISES;
        add.accept(enterprisesButton);

        EconomyView view = ClientEconomy.get();
        if (view == null) {
            return;
        }
        if (section == Section.CONTRACTS) {
            contracts.init(add, x, panelTop(), w, panelHeight(view), s, view);
        } else {
            enterprises.init(add, x, panelTop(), w, panelHeight(view), s, view);
        }
    }

    /** L'onglet est quitté : la prochaine ouverture redemandera une vue fraîche. */
    void leave() {
        requested = false;
    }

    private void select(Section next) {
        if (next == section) {
            return;
        }
        section = next;
        if (Minecraft.getInstance().screen instanceof NationScreen screen) {
            screen.refresh();
        }
    }

    // ------------------------------------------------------------------ rendu

    void render(GuiGraphics g, NationSnapshot s, int mouseX, int mouseY, Consumer<List<FormattedCharSequence>> tooltip) {
        EconomyView view = ClientEconomy.get();
        if (view == null) {
            g.drawCenteredString(Minecraft.getInstance().font, tr("loading"), x + w / 2, panelTop() + panelHeight(null) / 2 - 4,
                    COL_LABEL);
            return;
        }
        if (section == Section.CONTRACTS) {
            contracts.render(g, s, view, mouseX, mouseY, tooltip);
        } else {
            enterprises.render(g, s, view, mouseX, mouseY, tooltip);
        }
        if (!view.canManage()) {
            g.drawString(Minecraft.getInstance().font, tr("read_only"), x, y + h - READ_ONLY_H + 2, COL_WARN);
        }
    }

    // ------------------------------------------------------------------ entrées

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (ClientEconomy.get() == null) {
            return false;
        }
        return section == Section.CONTRACTS ? contracts.mouseClicked(mouseX, mouseY, button)
                : enterprises.mouseClicked(mouseX, mouseY, button);
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (ClientEconomy.get() == null) {
            return false;
        }
        return section == Section.CONTRACTS ? contracts.mouseScrolled(mouseX, mouseY, scrollY)
                : enterprises.mouseScrolled(mouseX, mouseY, scrollY);
    }

    // ------------------------------------------------------------------ géométrie

    private int panelTop() {
        return y + TOGGLE_H + GAP;
    }

    /** Hauteur réservée au panneau (sans la ligne « lecture seule » éventuelle). */
    private int panelHeight(@Nullable EconomyView view) {
        return h - TOGGLE_H - GAP - (view != null && !view.canManage() ? READ_ONLY_H : 0);
    }
}
