// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.client.screen;

import io.github.theoblaise1.sovereignfrontier.client.ClientEconomy;
import io.github.theoblaise1.sovereignfrontier.contract.ContractKind;
import io.github.theoblaise1.sovereignfrontier.network.ContractActionC2S;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Panneau « Contrats » de l'onglet Économie : à gauche la liste Offres / En cours / Historique (défilable), à droite la
 * fiche du contrat sélectionné (objectif, barre de progression, récompenses dont la part personnelle estimée, échéance,
 * 3 meilleurs contributeurs) et les boutons [Accepter], [Livrer] et [Abandonner], grisés sans la permission
 * {@code economie}. Appelé par {@link EconomyTab} avec la zone qui lui est réservée ; le serveur revalide toute action.
 */
@OnlyIn(Dist.CLIENT)
final class ContractsPanel {
    private static final int LIST_W = 120;
    private static final int ROW = 10;
    private static final int GAP = 6;
    private static final int BUTTON_H = 16;
    private static final int BUTTON_W = 58;
    private static final int FOOTER_H = 11;
    private static final int LINE = 10;
    private static final int BAR_H = 10;
    private static final int TOOLTIP_W = 200;
    /** Échéance affichée en rouge en deçà d'une heure. */
    private static final long SOON_MS = 3_600_000L;

    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_LABEL = 0xFFAAAAAA;
    private static final int COL_TITLE = 0xFFFFD86B;
    private static final int COL_SECTION = 0xFFFFAA00;
    private static final int COL_OK = 0xFF55FF55;
    private static final int COL_ERR = 0xFFFF5555;
    private static final int COL_MUTED = 0xFF707070;
    private static final int COL_SELECTED = 0x60FFFFFF;
    private static final int COL_HOVER = 0x25FFFFFF;
    private static final int COL_SEPARATOR = 0xFF3A3E4A;
    private static final int COL_BAR_BG = 0xFF2A2D36;
    private static final int COL_BAR = 0xFF3FA34D;

    /** Section d'une ligne de la liste. */
    private enum Section {
        OFFERS,
        ACTIVE,
        HISTORY
    }

    /** Ligne de la liste : en-tête de section ({@code index < 0}) ou entrée. */
    private record Row(Section section, int index) {
        boolean header() {
            return index < 0;
        }
    }

    @Nullable
    private Section selectedSection;
    private int selectedId = -1;
    private int scroll;
    private int x;
    private int y;
    private int w;
    private int h;

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static MutableComponent tr(String key, Object... args) {
        return Msg.tr("gui.contract." + key, args);
    }

    // ------------------------------------------------------------------ widgets

    void init(Consumer<AbstractWidget> add, int x, int y, int w, int h, NationSnapshot s, EconomyView v) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        EconomyView.ContractsView view = v.contracts();
        ensureSelection(view);
        int bx = x + w - BUTTON_W;
        int by = y + h - BUTTON_H;
        EconomyView.ContractLine offer = selectedSection == Section.OFFERS ? find(view.offers(), selectedId) : null;
        EconomyView.ContractLine active = selectedSection == Section.ACTIVE ? find(view.active(), selectedId) : null;
        if (offer != null) {
            int id = offer.id();
            Button accept = Button.builder(tr("accept"), b -> send(ContractActionC2S.ACCEPT, id))
                    .bounds(bx, by, BUTTON_W, BUTTON_H).build();
            boolean full = view.active().size() >= view.maxActive();
            accept.active = v.canManage() && !full;
            if (!v.canManage()) {
                accept.setTooltip(Tooltip.create(tr("no_permission").withStyle(ChatFormatting.RED)));
            } else if (full) {
                accept.setTooltip(Tooltip.create(tr("max_active", view.active().size(), view.maxActive()).withStyle(ChatFormatting.RED)));
            }
            add.accept(accept);
        }
        if (active != null) {
            int id = active.id();
            Component title = active.title();
            Button abandon = Button.builder(tr("abandon"), b -> confirmAbandon(id, title))
                    .bounds(bx, by, BUTTON_W, BUTTON_H).build();
            abandon.active = v.canManage();
            abandon.setTooltip(Tooltip.create(v.canManage() ? tr("abandon_tooltip") : tr("no_permission").withStyle(ChatFormatting.RED)));
            add.accept(abandon);
            if (ContractKind.byOrdinal(active.kind()) == ContractKind.DELIVER) {
                int have = owned(active.icon());
                Button deliver = Button.builder(tr("deliver"), b -> send(ContractActionC2S.DELIVER, id))
                        .bounds(bx - BUTTON_W - 4, by, BUTTON_W, BUTTON_H).build();
                deliver.active = have > 0 && active.progress() < active.required();
                deliver.setTooltip(Tooltip.create(have > 0
                        ? tr("deliver_tooltip", Math.min(have, Math.max(0, active.required() - active.progress())), active.icon().getHoverName())
                        : tr("deliver_none", active.icon().getHoverName()).withStyle(ChatFormatting.RED)));
                add.accept(deliver);
            }
        }
    }

    private static void send(byte action, int id) {
        PacketDistributor.sendToServer(new ContractActionC2S(action, id));
    }

    /** Objets intacts possédés (même règle que le serveur : inventaire principal et main secondaire). */
    private static int owned(ItemStack icon) {
        Player player = Minecraft.getInstance().player;
        return player == null || icon.isEmpty() ? 0 : Inventories.countPlain(player, icon.getItem());
    }

    /** Abandonner perd la progression et laisse l'emplacement vide jusqu'au rafraîchissement : confirmation. */
    private static void confirmAbandon(int id, Component title) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen;
        ConfirmScreen confirm = new ConfirmScreen(ok -> {
            if (ok) {
                send(ContractActionC2S.ABANDON, id);
            }
            mc.setScreen(parent);
        }, tr("abandon_confirm.title", id, title), tr("abandon_confirm.message"));
        mc.setScreen(confirm);
        // Boutons inertes une demi-seconde : un clic de trop ne confirme rien.
        confirm.setDelay(10);
    }

    // ------------------------------------------------------------------ sélection

    /**
     * Garde la sélection quand c'est possible : un contrat accepté passe des offres aux contrats en cours, un contrat
     * terminé à l'historique. Sinon, premier contrat en cours, puis première offre, puis première ligne d'historique.
     */
    private void ensureSelection(EconomyView.ContractsView view) {
        if (selectedSection != null && selectedId >= 0) {
            if (find(view.offers(), selectedId) != null) {
                selectedSection = Section.OFFERS;
                return;
            }
            if (find(view.active(), selectedId) != null) {
                selectedSection = Section.ACTIVE;
                return;
            }
            for (EconomyView.HistoryLine line : view.history()) {
                if (line.id() == selectedId) {
                    selectedSection = Section.HISTORY;
                    return;
                }
            }
        }
        if (!view.active().isEmpty()) {
            select(Section.ACTIVE, view.active().get(0).id());
        } else if (!view.offers().isEmpty()) {
            select(Section.OFFERS, view.offers().get(0).id());
        } else if (!view.history().isEmpty()) {
            select(Section.HISTORY, view.history().get(0).id());
        } else {
            selectedSection = null;
            selectedId = -1;
        }
    }

    private void select(Section section, int id) {
        selectedSection = section;
        selectedId = id;
    }

    @Nullable
    private static EconomyView.ContractLine find(List<EconomyView.ContractLine> lines, int id) {
        for (EconomyView.ContractLine line : lines) {
            if (line.id() == id) {
                return line;
            }
        }
        return null;
    }

    private static List<Row> rows(EconomyView.ContractsView view) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Section.OFFERS, -1));
        for (int i = 0; i < view.offers().size(); i++) {
            rows.add(new Row(Section.OFFERS, i));
        }
        rows.add(new Row(Section.ACTIVE, -1));
        for (int i = 0; i < view.active().size(); i++) {
            rows.add(new Row(Section.ACTIVE, i));
        }
        if (!view.history().isEmpty()) {
            rows.add(new Row(Section.HISTORY, -1));
            for (int i = 0; i < view.history().size(); i++) {
                rows.add(new Row(Section.HISTORY, i));
            }
        }
        return rows;
    }

    private int visibleRows() {
        return Math.max(1, (h - FOOTER_H) / ROW);
    }

    // ------------------------------------------------------------------ rendu

    void render(GuiGraphics g, NationSnapshot s, EconomyView v, int mouseX, int mouseY,
                Consumer<List<FormattedCharSequence>> tooltip) {
        EconomyView.ContractsView view = v.contracts();
        ensureSelection(view);
        List<Row> rows = rows(view);
        int visible = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - visible));
        List<FormattedCharSequence> hover = null;
        for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
            Row row = rows.get(i + scroll);
            int ry = y + i * ROW;
            if (row.header()) {
                g.drawString(font(), trim(header(view, row.section()), LIST_W - 4), x + 1, ry + 1, COL_SECTION);
                continue;
            }
            int id = rowId(view, row);
            boolean hovered = mouseX >= x && mouseX < x + LIST_W && mouseY >= ry && mouseY < ry + ROW;
            if (row.section() == selectedSection && id == selectedId) {
                g.fill(x, ry, x + LIST_W, ry + ROW, COL_SELECTED);
            } else if (hovered) {
                g.fill(x, ry, x + LIST_W, ry + ROW, COL_HOVER);
            }
            g.drawString(font(), trim(entry(view, row), LIST_W - 6), x + 4, ry + 1, COL_TEXT);
        }
        if (rows.size() > visible) {
            int trackH = visible * ROW;
            g.fill(x + LIST_W + 1, y, x + LIST_W + 3, y + trackH, 0x40FFFFFF);
            int thumbH = Math.max(8, trackH * visible / rows.size());
            int thumbY = y + (trackH - thumbH) * scroll / Math.max(1, rows.size() - visible);
            g.fill(x + LIST_W + 1, thumbY, x + LIST_W + 3, thumbY + thumbH, 0xC0FFFFFF);
        }
        renderFooter(g, view);

        int dx = x + LIST_W + GAP;
        g.fill(dx - GAP / 2, y, dx - GAP / 2 + 1, y + h, COL_SEPARATOR);
        if (selectedSection == Section.HISTORY) {
            renderHistory(g, view, dx);
        } else {
            EconomyView.ContractLine line = selectedSection == Section.OFFERS ? find(view.offers(), selectedId)
                    : selectedSection == Section.ACTIVE ? find(view.active(), selectedId) : null;
            if (line == null) {
                g.drawWordWrap(font(), empty(view), dx, y + 2, x + w - dx, COL_LABEL);
            } else {
                hover = renderDetail(g, view, line, selectedSection == Section.OFFERS, dx, mouseX, mouseY);
            }
        }
        if (hover != null) {
            tooltip.accept(hover);
        }
    }

    private static Component header(EconomyView.ContractsView view, Section section) {
        return switch (section) {
            case OFFERS -> tr("offers", view.offers().size(), view.offerSlots());
            case ACTIVE -> tr("active", view.active().size(), view.maxActive());
            case HISTORY -> tr("history");
        };
    }

    private static int rowId(EconomyView.ContractsView view, Row row) {
        return switch (row.section()) {
            case OFFERS -> view.offers().get(row.index()).id();
            case ACTIVE -> view.active().get(row.index()).id();
            case HISTORY -> view.history().get(row.index()).id();
        };
    }

    /** « 3. Livraison de blé », « 1. Chasse aux zombies 40 % », « ✔ 2. Exploration… ». */
    private static Component entry(EconomyView.ContractsView view, Row row) {
        if (row.section() == Section.HISTORY) {
            EconomyView.HistoryLine line = view.history().get(row.index());
            return statusMark(line.status()).append(Component.literal(" " + line.id() + ". ").withStyle(ChatFormatting.GRAY))
                    .append(line.title().copy().withStyle(ChatFormatting.GRAY));
        }
        EconomyView.ContractLine line = row.section() == Section.OFFERS ? view.offers().get(row.index())
                : view.active().get(row.index());
        MutableComponent text = Component.literal(line.id() + ". ").withStyle(ChatFormatting.YELLOW).append(line.title().copy());
        if (row.section() == Section.ACTIVE) {
            text.append(Component.literal(" " + percent(line) + " %").withStyle(ChatFormatting.GREEN));
        }
        return text;
    }

    private static int percent(EconomyView.ContractLine line) {
        return line.required() <= 0 ? 0 : (int) Math.min(100L, 100L * Math.max(0, line.progress()) / line.required());
    }

    private static MutableComponent statusMark(byte status) {
        return switch (status) {
            case EconomyView.HistoryLine.COMPLETED -> Component.literal("✔").withStyle(ChatFormatting.GREEN);
            case EconomyView.HistoryLine.FAILED -> Component.literal("✘").withStyle(ChatFormatting.RED);
            default -> Component.literal("↩").withStyle(ChatFormatting.DARK_GRAY);
        };
    }

    private static MutableComponent statusName(byte status) {
        return switch (status) {
            case EconomyView.HistoryLine.COMPLETED -> Msg.tr("contract.status.completed").withStyle(ChatFormatting.GREEN);
            case EconomyView.HistoryLine.FAILED -> Msg.tr("contract.status.failed").withStyle(ChatFormatting.RED);
            default -> Msg.tr("contract.status.abandoned").withStyle(ChatFormatting.GRAY);
        };
    }

    /** Pied de la liste : « Nouvelles offres dans 5 h 12 min » (ou ouverture du tableau). */
    private void renderFooter(GuiGraphics g, EconomyView.ContractsView view) {
        long left = Math.max(0L, view.refreshInMs() - ClientEconomy.ageMs());
        Component text = view.offers().isEmpty() && view.active().isEmpty() && view.history().isEmpty() && left > 0L
                ? tr("opens_in", TimeUtil.duration(left)) : tr("refresh_in", TimeUtil.duration(left));
        g.drawString(font(), trim(text, LIST_W), x, y + h - FOOTER_H + 2, COL_LABEL);
    }

    private static Component empty(EconomyView.ContractsView view) {
        return view.offers().isEmpty() && view.active().isEmpty() ? tr("empty") : tr("select");
    }

    private void renderHistory(GuiGraphics g, EconomyView.ContractsView view, int dx) {
        int dw = x + w - dx;
        for (EconomyView.HistoryLine line : view.history()) {
            if (line.id() != selectedId) {
                continue;
            }
            int ly = y + 2;
            g.drawString(font(), trim(Component.literal(line.id() + ". ").append(line.title()).withStyle(ChatFormatting.BOLD), dw),
                    dx, ly, COL_TITLE);
            ly += LINE + 2;
            g.drawString(font(), trim(tr("status", statusName(line.status())), dw), dx, ly, COL_TEXT);
            ly += LINE;
            g.drawString(font(), trim(tr("ended", TimeUtil.shortStamp(line.epochMs())), dw), dx, ly, COL_LABEL);
            return;
        }
    }

    /** Fiche du contrat ; renvoie l'infobulle complète si la souris survole la fiche. */
    @Nullable
    private List<FormattedCharSequence> renderDetail(GuiGraphics g, EconomyView.ContractsView view, EconomyView.ContractLine line,
                                                     boolean offer, int dx, int mouseX, int mouseY) {
        Font font = font();
        int dw = x + w - dx;
        int bottom = y + h - BUTTON_H - 3;
        int ly = y;
        g.renderItem(line.icon(), dx, ly);
        g.drawString(font, trim(line.title().copy().withStyle(ChatFormatting.BOLD), dw - 20), dx + 20, ly + 1, COL_TITLE);
        ContractKind kind = ContractKind.byOrdinal(line.kind());
        Component sub = Component.literal("n°" + line.id() + " · ").append(kind == null ? Component.empty() : kind.displayName())
                .append(" · ").append(offer ? Msg.tr("contract.status.offered") : Msg.tr("contract.status.active"));
        g.drawString(font, trim(sub, dw - 20), dx + 20, ly + 9, COL_LABEL);
        ly += 19;

        List<Component> details = detailLines(line, offer);
        // Objectif sur 3 lignes au plus.
        List<FormattedCharSequence> objective = font.split(line.objective(), dw);
        for (int i = 0; i < objective.size() && i < 3 && ly + LINE <= bottom; i++) {
            g.drawString(font, i == 2 && objective.size() > 3 ? ellipsis(objective.get(i)) : objective.get(i), dx, ly, COL_TEXT);
            ly += LINE - 1;
        }
        ly += 2;
        if (!offer && ly + BAR_H <= bottom) {
            float progress = line.required() <= 0 ? 0.0F : Mth.clamp((float) line.progress() / line.required(), 0.0F, 1.0F);
            g.fill(dx, ly, dx + dw, ly + BAR_H, COL_BAR_BG);
            g.fill(dx, ly, dx + (int) (dw * progress), ly + BAR_H, COL_BAR);
            g.drawCenteredString(font, line.progress() + " / " + line.required(), dx + dw / 2, ly + 1, COL_TEXT);
            ly += BAR_H + 2;
        }
        boolean truncated = false;
        for (Component detail : details) {
            if (ly + LINE > bottom) {
                truncated = true;
                break;
            }
            g.drawString(font, trim(detail, dw), dx, ly, COL_TEXT);
            ly += LINE - 1;
        }
        if (truncated) {
            g.drawString(font, "…", x + w - font.width("…"), bottom - LINE + 2, COL_LABEL);
        }
        if (mouseX >= dx && mouseX < x + w && mouseY >= y && mouseY < bottom) {
            List<FormattedCharSequence> tip = new ArrayList<>();
            tip.add(line.title().copy().withStyle(ChatFormatting.GOLD).getVisualOrderText());
            tip.addAll(font.split(line.objective().copy().withStyle(ChatFormatting.GRAY), TOOLTIP_W));
            if (!offer) {
                tip.add(tr("progress", line.progress(), line.required()).withStyle(ChatFormatting.GRAY).getVisualOrderText());
            }
            for (Component detail : details) {
                tip.addAll(font.split(detail, TOOLTIP_W));
            }
            return tip;
        }
        return null;
    }

    /** Récompenses, part estimée, échéance, contributeurs. */
    private static List<Component> detailLines(EconomyView.ContractLine line, boolean offer) {
        List<Component> lines = new ArrayList<>();
        lines.add(tr("reward", Money.format(line.money()), line.points()).withStyle(ChatFormatting.YELLOW));
        MutableComponent keys = Component.empty();
        int[] counts = line.keys();
        for (int i = 0; i < counts.length; i++) {
            CrateKind kind = CrateKind.byOrdinal(i);
            if (kind == null || counts[i] <= 0) {
                continue;
            }
            if (!keys.getSiblings().isEmpty()) {
                keys.append(", ");
            }
            keys.append(counts[i] + " × ").append(kind.displayName());
        }
        if (!keys.getSiblings().isEmpty()) {
            lines.add(tr("keys", keys).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (offer) {
            return lines;
        }
        lines.add(tr("share", Money.format(line.myShareEstimate()), line.myContribution()).withStyle(ChatFormatting.GREEN));
        long left = Math.max(0L, line.timeLeftMs() - ClientEconomy.ageMs());
        lines.add(tr("deadline", TimeUtil.duration(left)).withStyle(left < SOON_MS ?ChatFormatting.RED : ChatFormatting.GRAY));
        if (!line.topContributors().isEmpty()) {
            MutableComponent names = Component.empty();
            for (int i = 0; i < line.topContributors().size(); i++) {
                EconomyView.Contributor c = line.topContributors().get(i);
                if (i > 0) {
                    names.append(", ");
                }
                names.append(c.name() + " (" + c.amount() + ")");
            }
            lines.add(tr("contributors", names).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    // ------------------------------------------------------------------ entrées

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        EconomyView v = ClientEconomy.get();
        if (button != 0 || v == null || mouseX < x || mouseX >= x + LIST_W || mouseY < y || mouseY >= y + visibleRows() * ROW) {
            return false;
        }
        List<Row> rows = rows(v.contracts());
        int index = scroll + (int) ((mouseY - y) / ROW);
        if (index < 0 || index >= rows.size() || rows.get(index).header()) {
            return false;
        }
        Row row = rows.get(index);
        select(row.section(), rowId(v.contracts(), row));
        if (Minecraft.getInstance().screen instanceof NationScreen screen) {
            screen.refresh();
        }
        return true;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        EconomyView v = ClientEconomy.get();
        if (v == null || mouseX < x || mouseX >= x + LIST_W + 4 || mouseY < y || mouseY >= y + h || scrollY == 0) {
            return false;
        }
        int max = Math.max(0, rows(v.contracts()).size() - visibleRows());
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, max);
        return true;
    }

    // ------------------------------------------------------------------ texte

    private static FormattedCharSequence trim(Component text, int maxWidth) {
        Font font = font();
        if (font.width(text) <= maxWidth) {
            return text.getVisualOrderText();
        }
        FormattedText cut = font.substrByWidth(text, Math.max(0, maxWidth - font.width("…")));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of("…")));
    }

    private static FormattedCharSequence ellipsis(FormattedCharSequence line) {
        return FormattedCharSequence.composite(line, Component.literal("…").getVisualOrderText());
    }
}
