package io.github.theoblaise1.sovereignfrontier.client.screen;

import io.github.theoblaise1.sovereignfrontier.network.CratePreviewView;
import io.github.theoblaise1.sovereignfrontier.network.CratePreviewView.EntryLine;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;

/**
 * Aperçu d'une caisse (clic droit sans clé, {@code /crate preview <type>}) : tirages par ouverture, puis chaque entrée
 * avec sa plage, sa « chance par tirage » ({@code poids / total}) et la couleur de sa rareté ; pour une rare, le
 * nombre d'ouvertures avant la garantie de pitié ; les entrées provisoires sont grisées. Lecture seule.
 */
@OnlyIn(Dist.CLIENT)
public class CratePreviewScreen extends Screen {
    private static final int W = 300;
    private static final int H = 224;
    private static final int ROW = 22;
    private static final int LIST_TOP = 40;
    private static final int LIST_BOTTOM = H - 46;
    private static final int LIST_ROWS = (LIST_BOTTOM - LIST_TOP) / ROW;
    private static final int COL_PANEL = 0xF0181A20;
    private static final int COL_BORDER = 0xFF5B6070;
    private static final int COL_TITLE = 0xFFFFD86B;
    private static final int COL_LABEL = 0xFFAAAAAA;
    private static final int COL_COMMON = 0xFFFFFFFF;
    private static final int COL_UNCOMMON = 0xFFFFFF55;
    private static final int COL_RARE = 0xFF55FFFF;
    private static final int COL_PLACEHOLDER = 0xFF707070;
    private static final int COL_ERR = 0xFFFF5555;
    private static final int COL_HOVER = 0x25FFFFFF;
    private static final String ELLIPSIS = "…";

    private CratePreviewView view;
    private int scroll;
    private int left;
    private int top;

    public CratePreviewScreen(CratePreviewView view) {
        super(title(view));
        this.view = view;
    }

    private static Component title(CratePreviewView view) {
        CrateKind kind = CrateKind.byOrdinal(view.kind());
        return tr("title", kind == null ? Component.literal("?") : kind.block().getName());
    }

    private static MutableComponent tr(String key, Object... args) {
        return Msg.tr("gui.reward." + key, args);
    }

    /** Nouvel aperçu reçu écran ouvert (autre caisse ou pitié à jour). */
    public void update(CratePreviewView next) {
        boolean sameKind = next.kind() == view.kind();
        view = next;
        if (!sameKind) {
            scroll = 0;
        }
        rebuildWidgets();
    }

    @Override
    public Component getTitle() {
        return title(view);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(0, (height - H) / 2);
        scroll = Mth.clamp(scroll, 0, maxScroll());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(left + W / 2 - 50, top + H - 24, 100, 20).build());
    }

    private int maxScroll() {
        return Math.max(0, view.entries().size() - LIST_ROWS);
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, COL_BORDER);
        g.fill(left, top, left + W, top + H, COL_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, getTitle(), left + W / 2, top + 7, COL_TITLE);
        CrateKind kind = CrateKind.byOrdinal(view.kind());
        if (!view.available() || kind == null) {
            List<FormattedCharSequence> lines = font.split(tr("unavailable"), W - 24);
            for (int i = 0; i < lines.size() && i < 4; i++) {
                g.drawCenteredString(font, lines.get(i), left + W / 2, top + 60 + i * (font.lineHeight + 2), COL_ERR);
            }
            return;
        }
        g.drawCenteredString(font, trim(tr("subtitle", view.rolls(), kind.keyItem().getDescription()), W - 16),
                left + W / 2, top + 21, COL_LABEL);
        int hovered = rowAt(mouseX, mouseY);
        int x = left + 8;
        for (int i = 0; i < LIST_ROWS && scroll + i < view.entries().size(); i++) {
            int index = scroll + i;
            renderRow(g, view.entries().get(index), x, top + LIST_TOP + i * ROW, index == hovered);
        }
        if (view.entries().size() > LIST_ROWS) {
            int trackX = left + W - 6;
            int trackH = LIST_ROWS * ROW;
            int listTop = top + LIST_TOP;
            g.fill(trackX, listTop, trackX + 2, listTop + trackH, 0x40FFFFFF);
            int thumbH = Math.max(8, trackH * LIST_ROWS / view.entries().size());
            int thumbY = listTop + (trackH - thumbH) * scroll / Math.max(1, maxScroll());
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xC0FFFFFF);
        }
        List<FormattedCharSequence> hint = font.split(tr("pity_hint", view.threshold(), view.threshold() + 1), W - 16);
        for (int i = 0; i < hint.size() && i < 2; i++) {
            g.drawString(font, hint.get(i), left + 8, top + LIST_BOTTOM + 2 + i * font.lineHeight, COL_LABEL);
        }
        if (hovered >= 0) {
            EntryLine line = view.entries().get(hovered);
            if (!line.icon().isEmpty() && !line.money() && mouseX < x + 22) {
                g.renderTooltip(font, line.icon(), mouseX, mouseY);
            }
        }
    }

    private void renderRow(GuiGraphics g, EntryLine line, int x, int y, boolean hovered) {
        int rowW = W - 20;
        if (hovered) {
            g.fill(x, y, x + rowW, y + ROW - 1, COL_HOVER);
        }
        boolean placeholder = !line.label().isEmpty();
        if (placeholder) {
            g.drawString(font, "?", x + 8, y + 7, COL_PLACEHOLDER);
            g.drawString(font, trim(tr("placeholder", line.milestone(), Msg.tr("reward.placeholder." + line.label())), rowW - 26),
                    x + 24, y + 2, COL_PLACEHOLDER);
            g.drawString(font, trim(tr("placeholder_note"), rowW - 26), x + 24, y + 12, COL_PLACEHOLDER);
            return;
        }
        ItemStack icon = line.money() ? new ItemStack(Items.GOLD_INGOT) : line.icon();
        g.renderItem(icon, x + 3, y + 3);
        g.drawString(font, trim(describe(line, icon), rowW - 26), x + 24, y + 2, rarityColor(line.rarity()));
        MutableComponent detail = Msg.tr("reward.rarity." + rarityId(line.rarity()))
                .append(" · ").append(tr("chance", percent(line.weight(), line.totalWeight())));
        if (line.myMiss() >= 0) {
            int openings = Math.max(1, view.threshold() + 1 - line.myMiss());
            detail.append(" · ").append(tr("pity", openings));
        }
        g.drawString(font, trim(detail, rowW - 26), x + 24, y + 12, COL_LABEL);
    }

    private static Component describe(EntryLine line, ItemStack icon) {
        String range = line.min() == line.max() ? Integer.toString(line.min()) : line.min() + "–" + line.max();
        if (line.money()) {
            return line.min() == line.max() ? Component.literal(Money.format(line.min()))
                    : Msg.tr("reward.money_range", Money.format(line.min()), Money.format(line.max()));
        }
        return Component.literal(range + " × ").append(icon.getHoverName());
    }

    private static String percent(int weight, int total) {
        double pct = total <= 0 ? 0.0D : 100.0D * weight / total;
        return String.format(Locale.FRENCH, pct < 1.0D ? "%.2f" : "%.1f", pct);
    }

    private static String rarityId(byte rarity) {
        return switch (rarity) {
            case EntryLine.UNCOMMON -> "peu_commun";
            case EntryLine.RARE -> "rare";
            default -> "commun";
        };
    }

    private static int rarityColor(byte rarity) {
        return switch (rarity) {
            case EntryLine.UNCOMMON -> COL_UNCOMMON;
            case EntryLine.RARE -> COL_RARE;
            default -> COL_COMMON;
        };
    }

    private FormattedCharSequence trim(Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text.getVisualOrderText();
        }
        FormattedText cut = font.substrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS)));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of(ELLIPSIS)));
    }

    // ------------------------------------------------------------------ entrées

    private int rowAt(double mouseX, double mouseY) {
        int listTop = top + LIST_TOP;
        if (!view.available() || mouseX < left + 8 || mouseX >= left + W - 12 || mouseY < listTop
                || mouseY >= listTop + LIST_ROWS * ROW) {
            return -1;
        }
        int index = scroll + (int) ((mouseY - listTop) / ROW);
        return index < view.entries().size() ? index : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && scrollY != 0.0D) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
