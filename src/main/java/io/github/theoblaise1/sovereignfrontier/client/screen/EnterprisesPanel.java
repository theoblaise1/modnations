// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.client.screen;

import io.github.theoblaise1.sovereignfrontier.client.ClientEconomy;
import io.github.theoblaise1.sovereignfrontier.enterprise.EnterpriseManager;
import io.github.theoblaise1.sovereignfrontier.enterprise.Sector;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.network.EnterpriseAssignC2S;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Panneau « Entreprises » de l'onglet Économie : une ligne par secteur (icône, nom, multiplicateur et argent par
 * cycle, habitants affectés avec [−] [+] — Maj = ±5 —, produits par cycle, verrou avec infobulle), puis un pied
 * « habitants libres — revenu passif — prochain cycle ».
 * <p>
 * L'affectation est absolue : un clic envoie la nouvelle valeur ({@link EnterpriseAssignC2S}) et l'affiche aussitôt
 * en attente (jaune) ; la vue suivante du serveur (succès ou renvoi après un refus) remplace ces valeurs. Pour un
 * secteur sans habitant, le serveur envoie l'argent et les quantités d'UN habitant (aperçu).
 */
@OnlyIn(Dist.CLIENT)
final class EnterprisesPanel {
    private static final int ROW_H = 26;
    private static final int ICON_X = 2;
    private static final int NAME_X = 22;
    private static final int CONTROLS_X = 118;
    private static final int BUTTON_W = 12;
    private static final int BUTTON_H = 14;
    private static final int COUNT_W = 24;
    private static final int OUTPUTS_X = CONTROLS_X + 2 * BUTTON_W + COUNT_W + 8;
    private static final int SLOT = 16;
    private static final int FOOTER_GAP = 3;
    private static final int FOOTER_LINE = 10;
    private static final int SHIFT_STEP = 5;
    private static final int TOOLTIP_W = 200;
    private static final float SMALL = 0.75F;

    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_LABEL = 0xFFAAAAAA;
    private static final int COL_LOCKED = 0xFF707070;
    private static final int COL_ERR = 0xFFFF5555;
    private static final int COL_PENDING = 0xFFFFD86B;
    private static final int COL_SEPARATOR = 0xFF3A3E4A;
    private static final int COL_LOCK_OVERLAY = 0xA0181A20;
    private static final int COL_HOVER = 0x25FFFFFF;

    private static final ItemStack[] ICONS = {
            new ItemStack(Items.WHEAT),
            new ItemStack(Items.IRON_PICKAXE),
            new ItemStack(Items.CHEST_MINECART),
            new ItemStack(Items.BLAST_FURNACE)
    };

    /** Affectations envoyées mais pas encore confirmées par une vue du serveur (-1 = aucune). */
    private final int[] pending = new int[Sector.values().length];
    @Nullable
    private EconomyView shown;
    private int x;
    private int y;
    private int w;
    private int h;

    EnterprisesPanel() {
        Arrays.fill(pending, -1);
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static MutableComponent tr(String key, Object... args) {
        return Msg.tr("gui.enterprise." + key, args);
    }

    // ------------------------------------------------------------------ widgets

    void init(Consumer<AbstractWidget> add, int x, int y, int w, int h, NationSnapshot s, EconomyView v) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        if (v != shown) {
            // Nouvelle vue du serveur : elle fait foi.
            Arrays.fill(pending, -1);
            shown = v;
        }
        EconomyView.EnterprisesView e = v.enterprises();
        int free = free(e);
        for (int i = 0; i < rows(e); i++) {
            EconomyView.SectorLine line = e.sectors().get(i);
            if (Sector.byOrdinal(line.sector()) == null) {
                continue;
            }
            int workers = workers(line);
            int ry = rowY(i);
            int bx = x + CONTROLS_X;
            Button minus = Button.builder(Component.literal("−"), b -> change(line, -1))
                    .bounds(bx, ry + 6, BUTTON_W, BUTTON_H)
                    .tooltip(Tooltip.create(tr("remove_tooltip", SHIFT_STEP)))
                    .build();
            minus.active = v.canManage() && workers > 0;
            add.accept(minus);

            Component plusTip;
            if (!line.unlocked()) {
                plusTip = line.lockReason().copy().withStyle(ChatFormatting.RED);
            } else if (free <= 0) {
                plusTip = tr("no_free").withStyle(ChatFormatting.RED);
            } else {
                plusTip = tr("add_tooltip", SHIFT_STEP);
            }
            Button plus = Button.builder(Component.literal("+"), b -> change(line, 1))
                    .bounds(bx + BUTTON_W + COUNT_W, ry + 6, BUTTON_W, BUTTON_H)
                    .tooltip(Tooltip.create(plusTip))
                    .build();
            plus.active = v.canManage() && line.unlocked() && free > 0 && workers < EnterpriseAssignC2S.MAX_WORKERS;
            add.accept(plus);
        }
    }

    /** [−] ou [+] : nouvelle affectation absolue (Maj : pas de 5), bornée aux habitants libres. */
    private void change(EconomyView.SectorLine line, int direction) {
        EconomyView v = shown;
        if (v == null || !validOrdinal(line.sector())) {
            return;
        }
        int current = workers(line);
        int step = Screen.hasShiftDown() ? SHIFT_STEP : 1;
        int target = direction > 0 ? current + Math.min(step, free(v.enterprises())) : Math.max(0, current - step);
        target = Math.min(target, EnterpriseAssignC2S.MAX_WORKERS);
        if (target == current) {
            return;
        }
        pending[line.sector()] = target;
        PacketDistributor.sendToServer(new EnterpriseAssignC2S(line.sector(), target));
        if (Minecraft.getInstance().screen instanceof NationScreen screen) {
            screen.refresh();
        }
    }

    // ------------------------------------------------------------------ rendu

    void render(GuiGraphics g, NationSnapshot s, EconomyView v, int mouseX, int mouseY,
                Consumer<List<FormattedCharSequence>> tooltip) {
        Font font = font();
        EconomyView.EnterprisesView e = v.enterprises();
        if (e.sectors().isEmpty()) {
            g.drawCenteredString(font, tr("empty"), x + w / 2, y + h / 2 - 4, COL_LABEL);
            return;
        }
        List<FormattedCharSequence> hover = null;
        for (int i = 0; i < rows(e); i++) {
            EconomyView.SectorLine line = e.sectors().get(i);
            Sector sector = Sector.byOrdinal(line.sector());
            if (sector == null) {
                continue;
            }
            int ry = rowY(i);
            if (i > 0) {
                g.fill(x, ry - 1, x + w, ry, COL_SEPARATOR);
            }
            boolean nameHovered = mouseX >= x && mouseX < x + CONTROLS_X - 2 && mouseY >= ry && mouseY < ry + ROW_H - 1;
            if (nameHovered) {
                g.fill(x, ry, x + CONTROLS_X - 2, ry + ROW_H - 1, COL_HOVER);
                hover = sectorTooltip(sector, line);
            }
            g.renderItem(ICONS[sector.ordinal()], x + ICON_X, ry + 4);
            int nameColor = line.unlocked() ? COL_TEXT : COL_LOCKED;
            g.drawString(font, sector.displayName(), x + NAME_X, ry + 4, nameColor);
            if (!line.unlocked()) {
                g.drawString(font, "✘", x + NAME_X + font.width(sector.displayName()) + 3, ry + 4, COL_ERR);
            }
            g.drawString(font, ellipsize(font, detail(line), CONTROLS_X - NAME_X - 4), x + NAME_X, ry + 15, COL_LABEL);

            int workers = workers(line);
            boolean waiting = validOrdinal(line.sector()) && pending[line.sector()] >= 0;
            g.drawCenteredString(font, String.valueOf(workers), x + CONTROLS_X + BUTTON_W + COUNT_W / 2, ry + 9,
                    waiting ? COL_PENDING : line.unlocked() ? COL_TEXT : COL_LOCKED);

            if (line.outputs().isEmpty()) {
                g.drawString(font, ellipsize(font, tr("money_only").getString(), w - OUTPUTS_X), x + OUTPUTS_X, ry + 9, COL_LABEL);
                continue;
            }
            for (int j = 0; j < line.outputs().size() && OUTPUTS_X + (j + 1) * SLOT <= w; j++) {
                EconomyView.OutputLine out = line.outputs().get(j);
                int ox = x + OUTPUTS_X + j * SLOT;
                g.renderItem(out.icon(), ox, ry + 1);
                boolean dim = out.locked() || !line.unlocked();
                if (dim) {
                    g.pose().pushPose();
                    g.pose().translate(0.0F, 0.0F, 250.0F);
                    g.fill(ox, ry + 1, ox + SLOT, ry + 1 + SLOT, COL_LOCK_OVERLAY);
                    g.pose().popPose();
                }
                drawSmall(g, font, compact(out.perCycle()), ox + SLOT / 2, ry + 18, dim ? COL_LOCKED : COL_LABEL);
                if (mouseX >= ox && mouseX < ox + SLOT && mouseY >= ry && mouseY < ry + ROW_H - 1) {
                    hover = outputTooltip(line, out);
                }
            }
        }
        renderFooter(g, font, e);
        if (hover != null) {
            tooltip.accept(hover);
        }
    }

    /** « Habitants libres : 3 / 12 — revenu passif : 15 cr/cycle — prochain cycle dans 3 min » (sur 2 lignes au plus). */
    private void renderFooter(GuiGraphics g, Font font, EconomyView.EnterprisesView e) {
        long leftMs = Math.max(0L, e.ticksToCycle() * 50L - ClientEconomy.ageMs());
        Component text = tr("footer", free(e), e.population(), Money.format(e.passiveIncome()), TimeUtil.duration(leftMs));
        int fy = rowY(rows(e)) + FOOTER_GAP;
        List<FormattedCharSequence> lines = font.split(text, w);
        for (int i = 0; i < lines.size() && i < 2 && fy + (i + 1) * FOOTER_LINE <= y + h + 1; i++) {
            g.drawString(font, lines.get(i), x, fy + i * FOOTER_LINE, COL_LABEL);
        }
    }

    /** Deuxième ligne : « ×1,08 · +12 cr » (par cycle), ou « ×1,08 · 1 cr/hab. » sans habitant. */
    private static String detail(EconomyView.SectorLine line) {
        if (!line.unlocked() && line.outputs().isEmpty() && line.moneyPerCycle() == 0L) {
            return line.lockReason().getString();
        }
        String mult = "×" + EnterpriseManager.formatAmount(line.mult());
        String money = line.workers() > 0 ? "+" + Money.format(line.moneyPerCycle())
                : tr("per_worker", Money.format(line.moneyPerCycle())).getString();
        return mult + " · " + money;
    }

    private static List<FormattedCharSequence> sectorTooltip(Sector sector, EconomyView.SectorLine line) {
        Font font = font();
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(sector.displayName().withStyle(ChatFormatting.BOLD).getVisualOrderText());
        if (!line.unlocked()) {
            lines.addAll(font.split(line.lockReason().copy().withStyle(ChatFormatting.RED), TOOLTIP_W));
        }
        lines.addAll(font.split(tr("tooltip.mult", EnterpriseManager.formatAmount(line.mult())).withStyle(ChatFormatting.GRAY), TOOLTIP_W));
        Component money = line.workers() > 0
                ? tr("tooltip.money", line.workers(), Money.format(line.moneyPerCycle()))
                : tr("tooltip.money_one", Money.format(line.moneyPerCycle()));
        lines.addAll(font.split(money.copy().withStyle(ChatFormatting.GRAY), TOOLTIP_W));
        if (line.outputs().isEmpty()) {
            lines.addAll(font.split(tr("money_only").withStyle(ChatFormatting.GRAY), TOOLTIP_W));
        }
        lines.addAll(font.split(tr("tooltip.last", Money.format(line.lastMoney()), line.lastItems(), line.lastLost())
                .withStyle(line.lastLost() > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY), TOOLTIP_W));
        return lines;
    }

    private static List<FormattedCharSequence> outputTooltip(EconomyView.SectorLine line, EconomyView.OutputLine out) {
        Font font = font();
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(out.icon().getHoverName().getVisualOrderText());
        String amount = EnterpriseManager.formatAmount(out.perCycle());
        Component detail = line.workers() > 0 ? tr("tooltip.output", amount, line.workers()) : tr("tooltip.output_one", amount);
        lines.addAll(font.split(detail.copy().withStyle(ChatFormatting.GRAY), TOOLTIP_W));
        if (out.locked()) {
            lines.addAll(font.split(tr("tooltip.output_locked").withStyle(ChatFormatting.RED), TOOLTIP_W));
        } else if (!line.unlocked()) {
            lines.addAll(font.split(line.lockReason().copy().withStyle(ChatFormatting.RED), TOOLTIP_W));
        }
        return lines;
    }

    /** Texte réduit centré (quantité sous une icône), au-dessus des objets rendus. */
    private static void drawSmall(GuiGraphics g, Font font, String text, int centerX, int top, int color) {
        g.pose().pushPose();
        g.pose().translate(centerX, top, 300.0F);
        g.pose().scale(SMALL, SMALL, 1.0F);
        g.drawString(font, text, -font.width(text) / 2, 0, color, false);
        g.pose().popPose();
    }

    /** Quantité courte sous une icône : « 12 », « 2,4 », « ,25 », « ,005 » (valeur exacte dans l'infobulle). */
    private static String compact(float value) {
        if (!Float.isFinite(value) || value <= 0.0F) {
            return "0";
        }
        if (value >= 10.0F) {
            return String.valueOf(Math.round(value));
        }
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(value >= 1.0F ? 1 : 3, RoundingMode.HALF_UP).stripTrailingZeros();
        String text = rounded.toPlainString().replace('.', ',');
        return text.startsWith("0,") ? text.substring(1) : text;
    }

    private static String ellipsize(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        return false;
    }

    // ------------------------------------------------------------------ état

    private static boolean validOrdinal(int sector) {
        return sector >= 0 && sector < Sector.values().length;
    }

    /** Habitants affichés : valeur en attente si un clic n'est pas encore confirmé, sinon celle du serveur. */
    private int workers(EconomyView.SectorLine line) {
        int p = validOrdinal(line.sector()) ? pending[line.sector()] : -1;
        return p >= 0 ? p : line.workers();
    }

    /** Habitants libres d'après les valeurs affichées (attentes comprises). */
    private int free(EconomyView.EnterprisesView e) {
        long assigned = 0L;
        for (EconomyView.SectorLine line : e.sectors()) {
            assigned += workers(line);
        }
        return (int) Math.max(0L, e.population() - assigned);
    }

    private static int rows(EconomyView.EnterprisesView e) {
        return Math.min(e.sectors().size(), Sector.values().length);
    }

    private int rowY(int row) {
        return y + row * ROW_H;
    }
}
