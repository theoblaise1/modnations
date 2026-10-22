package io.github.theoblaise1.sovereignfrontier.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.theoblaise1.sovereignfrontier.client.ClientMarket;
import io.github.theoblaise1.sovereignfrontier.network.TradeActionC2S;
import io.github.theoblaise1.sovereignfrontier.network.TradeView;
import io.github.theoblaise1.sovereignfrontier.network.TradeView.OfferLine;
import io.github.theoblaise1.sovereignfrontier.outpost.MarketRules;
import io.github.theoblaise1.sovereignfrontier.outpost.PriceMath;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Écran de commerce (320 × 200) : colonnes « Le marchand vend » et « Le marchand achète » (icône, lot, prix courant
 * calculé par {@link PriceMath} avec ▲▼ par rapport à la base, stock ou demande restants), offre sélectionnée avec
 * les boutons ×1 / ×8 / max, portefeuille et réassort. Chaque échange envoie le total attendu : le serveur refuse
 * si le prix a bougé et renvoie l'étal. L'écran se ferme si le joueur s'éloigne du comptoir.
 */
@OnlyIn(Dist.CLIENT)
public class TradeScreen extends Screen implements FeedbackScreen {
    private static final int W = 320;
    private static final int H = 200;
    private static final int COL_W = 148;
    private static final int LIST_TOP = 32;
    private static final int ROW = 18;
    private static final int ROWS = 6;
    private static final int BUTTON_Y = 158;
    private static final int TOOLTIP_W = 200;
    private static final int COL_PANEL = 0xF0181A20;
    private static final int COL_BORDER = 0xFF5B6070;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_LABEL = 0xFFAAAAAA;
    private static final int COL_TITLE = 0xFFFFD86B;
    private static final int COL_OK = 0xFF55FF55;
    private static final int COL_ERR = 0xFFFF5555;
    private static final int COL_DIM = 0xFF707070;
    private static final int COL_MONEY = 0xFFFFE066;
    private static final int COL_SELECTED = 0x60FFFFFF;
    private static final int COL_HOVER = 0x25FFFFFF;

    @Nullable
    private TradeView view;
    private List<OfferLine> sells = List.of();
    private List<OfferLine> buys = List.of();
    /** Sélection gardée par objet et par colonne : relue à chaque nouvel étal. */
    @Nullable
    private Item selectedItem;
    private boolean selectedBuy = true;
    @Nullable
    private OfferLine selected;
    private final int[] scroll = new int[2];
    private final Button[] lotButtons = new Button[3];
    private final int[] lotCounts = new int[3];
    private final long[] lotTotals = new long[3];
    private int left;
    private int top;
    @Nullable
    private Component feedback;
    private boolean feedbackOk;
    private long feedbackUntil;

    public TradeScreen() {
        super(Component.empty());
    }

    private static MutableComponent tr(String key, Object... args) {
        return Msg.tr("gui.outpost." + key, args);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(0, (height - H) / 2);
        view = ClientMarket.get();
        List<OfferLine> s = new ArrayList<>();
        List<OfferLine> b = new ArrayList<>();
        if (view != null) {
            for (OfferLine line : view.offers()) {
                if (line.basePrice() > 0L) {
                    s.add(line);
                }
                if (line.basePayout() > 0L) {
                    b.add(line);
                }
            }
        }
        sells = s;
        buys = b;
        selected = resolveSelection();
        scroll[0] = Mth.clamp(scroll[0], 0, maxScroll(sells));
        scroll[1] = Mth.clamp(scroll[1], 0, maxScroll(buys));
        int bw = (W - 16 - 8) / 3;
        for (int i = 0; i < 3; i++) {
            int slot = i;
            lotButtons[i] = addRenderableWidget(Button.builder(Component.empty(), btn -> trade(slot))
                    .bounds(left + 8 + i * (bw + 4), top + BUTTON_Y, bw, 16).build());
        }
        updateButtons();
    }

    /** Nouvel étal reçu (après un échange, un refus ou un réassort) : listes, sélection et boutons relus. */
    public void refresh() {
        rebuildWidgets();
    }

    @Override
    public void showFeedback(Component message, boolean success) {
        feedback = message;
        feedbackOk = success;
        feedbackUntil = Util.getMillis() + 4000L;
    }

    @Nullable
    private OfferLine resolveSelection() {
        List<OfferLine> list = selectedBuy ? sells : buys;
        for (OfferLine line : list) {
            if (line.item() == selectedItem) {
                return line;
            }
        }
        if (!sells.isEmpty()) {
            selectedBuy = true;
            selectedItem = sells.get(0).item();
            return sells.get(0);
        }
        if (!buys.isEmpty()) {
            selectedBuy = false;
            selectedItem = buys.get(0).item();
            return buys.get(0);
        }
        selectedItem = null;
        return null;
    }

    private static int maxScroll(List<OfferLine> list) {
        return Math.max(0, list.size() - ROWS);
    }

    // ------------------------------------------------------------------ tarification (miroir du serveur)

    private long currentPrice(OfferLine line) {
        return PriceMath.price(line.basePrice(), line.b(), view.params());
    }

    private long currentPayout(OfferLine line) {
        return PriceMath.payout(line.basePayout(), line.s(), line.basePrice(), line.b(), view.params());
    }

    private int owned(OfferLine line) {
        return minecraft != null && minecraft.player != null ? Inventories.countPlain(minecraft.player, line.item()) : line.playerHas();
    }

    private int maxLots(OfferLine line, boolean buy) {
        if (buy) {
            return PriceMath.maxBuyLots(line.basePrice(), line.b(), line.stockLeft(), view.wallet(), view.params());
        }
        return PriceMath.maxSellLots(line.basePayout(), line.s(), line.basePrice(), line.b(), line.demandLeft(),
                owned(line) / Math.max(1, line.count()), view.params());
    }

    private long total(OfferLine line, boolean buy, int lots) {
        return buy ? PriceMath.buyTotal(line.basePrice(), line.b(), lots, view.params())
                : PriceMath.sellTotal(line.basePayout(), line.s(), line.basePrice(), line.b(), lots, view.params());
    }

    /** Boutons ×1 / ×8 / max : quantité, total et activation (fonds, stock, demande, objets possédés). */
    private void updateButtons() {
        int max = selected == null || view == null ? 0 : maxLots(selected, selectedBuy);
        int[] wanted = {1, 8, max};
        for (int i = 0; i < 3; i++) {
            Button button = lotButtons[i];
            if (button == null) {
                continue;
            }
            int lots = i == 2 ? max : wanted[i];
            lotCounts[i] = lots;
            boolean possible = selected != null && lots >= 1 && lots <= max;
            lotTotals[i] = possible ? total(selected, selectedBuy, lots) : 0L;
            Component money = possible ? Component.literal(Money.format(lotTotals[i])) : Component.literal("—");
            button.setMessage(i == 2 ? tr("button_max", Math.max(lots, 0), money) : tr("button", lots, money));
            button.active = possible;
        }
    }

    private void trade(int slot) {
        if (view == null || selected == null || lotCounts[slot] < 1) {
            return;
        }
        PacketDistributor.sendToServer(new TradeActionC2S(view.outpost(), view.rotation(), selected.item(), selectedBuy,
                lotCounts[slot], lotTotals[slot]));
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft == null || minecraft.player == null || view == null) {
            onClose();
            return;
        }
        double limit = MarketRules.reach() + 2.0D;
        if (minecraft.player.distanceToSqr(Vec3.atCenterOf(view.anchor())) > limit * limit) {
            onClose();
            return;
        }
        // Inventaire et portefeuille peuvent changer écran ouvert : quantités et totaux réévalués à chaque tick.
        updateButtons();
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, COL_BORDER);
        g.fill(left, top, left + W, top + H, COL_PANEL);
        g.fill(left + W / 2, top + LIST_TOP - 2, left + W / 2 + 1, top + LIST_TOP + ROWS * ROW, 0x40FFFFFF);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (view == null) {
            return;
        }
        g.drawCenteredString(font, view.title(), left + W / 2, top + 6, COL_TITLE);
        g.drawString(font, tr("sells"), left + 8, top + 20, COL_LABEL);
        g.drawString(font, tr("buys"), left + 164, top + 20, COL_LABEL);
        renderColumn(g, sells, true, left + 8, mouseX, mouseY);
        renderColumn(g, buys, false, left + 164, mouseX, mouseY);
        renderDetail(g);
        renderFooter(g);
    }

    private void renderColumn(GuiGraphics g, List<OfferLine> list, boolean buy, int x, int mouseX, int mouseY) {
        int listTop = top + LIST_TOP;
        if (list.isEmpty()) {
            g.drawString(font, tr("empty"), x + 4, listTop + 5, COL_DIM);
            return;
        }
        int col = buy ? 0 : 1;
        OfferLine hovered = rowAt(mouseX, mouseY, buy);
        for (int i = 0; i < ROWS && scroll[col] + i < list.size(); i++) {
            OfferLine line = list.get(scroll[col] + i);
            int ry = listTop + i * ROW;
            if (line == selected && buy == selectedBuy) {
                g.fill(x, ry, x + COL_W, ry + ROW - 1, COL_SELECTED);
                g.fill(x, ry, x + 2, ry + ROW - 1, COL_TITLE);
            } else if (line == hovered) {
                g.fill(x, ry, x + COL_W, ry + ROW - 1, COL_HOVER);
            }
            g.renderItem(new ItemStack(line.item()), x + 3, ry + 1);
            g.drawString(font, "×" + line.count(), x + 21, ry + 5, COL_LABEL);
            boolean empty = buy ? line.stockLeft() <= 0 : line.demandLeft() <= 0;
            long value = buy ? currentPrice(line) : currentPayout(line);
            long base = buy ? line.basePrice() : line.basePayout();
            MutableComponent price = Component.literal(Money.format(value));
            if (value > base) {
                price.append(Component.literal(" ▲").withStyle(buy ? ChatFormatting.RED : ChatFormatting.GREEN));
            } else if (value < base) {
                price.append(Component.literal(" ▼").withStyle(buy ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            g.drawString(font, price, x + 44, ry + 5, empty ? COL_DIM : COL_MONEY);
            Component remaining = empty ? tr(buy ? "sold_out" : "no_demand")
                    : Component.literal(Integer.toString(buy ? line.stockLeft() : line.demandLeft()));
            g.drawString(font, remaining, x + COL_W - 3 - font.width(remaining), ry + 5, empty ? COL_ERR : COL_LABEL);
        }
        if (list.size() > ROWS) {
            int trackX = x + COL_W + 1;
            int trackH = ROWS * ROW;
            g.fill(trackX, listTop, trackX + 2, listTop + trackH, 0x40FFFFFF);
            int thumbH = Math.max(8, trackH * ROWS / list.size());
            int thumbY = listTop + (trackH - thumbH) * scroll[col] / Math.max(1, maxScroll(list));
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xC0FFFFFF);
        }
        if (hovered != null) {
            setTooltipForNextRenderPass(tooltip(hovered, buy));
        }
    }

    private List<FormattedCharSequence> tooltip(OfferLine line, boolean buy) {
        List<FormattedCharSequence> tip = new ArrayList<>(font.split(Component.literal(line.count() + " × ")
                .append(line.item().getDescription()).withStyle(ChatFormatting.WHITE), TOOLTIP_W));
        List<Component> rows = new ArrayList<>();
        if (buy) {
            rows.add(tr("tip.price", Money.format(currentPrice(line)), Money.format(line.basePrice())));
            rows.add(tr("tip.stock", line.stockLeft()));
        } else {
            rows.add(tr("tip.payout", Money.format(currentPayout(line)), Money.format(line.basePayout())));
            rows.add(tr("tip.demand", line.demandLeft()));
        }
        rows.add(tr("tip.owned", owned(line)));
        for (Component row : rows) {
            tip.addAll(font.split(row.copy().withStyle(ChatFormatting.GRAY), TOOLTIP_W));
        }
        return tip;
    }

    private void renderDetail(GuiGraphics g) {
        int y = top + BUTTON_Y - 12;
        if (selected == null) {
            g.drawString(font, tr("select"), left + 8, y, COL_DIM);
            return;
        }
        Component name = selected.item().getDescription();
        Component text = selectedBuy
                ? tr("detail_buy", selected.count(), name, Money.format(currentPrice(selected)))
                : tr("detail_sell", selected.count(), name, Money.format(currentPayout(selected)), owned(selected) / Math.max(1, selected.count()));
        List<FormattedCharSequence> lines = font.split(text, W - 16);
        g.drawString(font, lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0), left + 8, y, COL_TEXT);
    }

    private void renderFooter(GuiGraphics g) {
        if (feedback != null && Util.getMillis() < feedbackUntil) {
            List<FormattedCharSequence> lines = font.split(feedback, W - 16);
            for (int i = 0; i < lines.size() && i < 1; i++) {
                g.drawString(font, lines.get(i), left + 8, top + H - 23, feedbackOk ? COL_OK : COL_ERR);
            }
        }
        Component wallet = tr("wallet", Money.format(view.wallet()));
        g.drawString(font, wallet, left + 8, top + H - 11, COL_MONEY);
        long restockMs = Math.max(0L, view.ticksToRotation() * 50L - ClientMarket.ageMs());
        Component restock = tr("restock", TimeUtil.duration(restockMs));
        g.drawString(font, restock, left + W - 8 - font.width(restock), top + H - 11, COL_LABEL);
    }

    // ------------------------------------------------------------------ entrées

    /** Offre sous la souris dans la colonne {@code buy} (marchand vend) ou l'autre, ou {@code null}. */
    @Nullable
    private OfferLine rowAt(double mouseX, double mouseY, boolean buy) {
        int x = buy ? left + 8 : left + 164;
        int listTop = top + LIST_TOP;
        if (mouseX < x || mouseX >= x + COL_W || mouseY < listTop || mouseY >= listTop + ROWS * ROW) {
            return null;
        }
        List<OfferLine> list = buy ? sells : buys;
        int index = scroll[buy ? 0 : 1] + (int) ((mouseY - listTop) / ROW);
        return index < list.size() ? list.get(index) : null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        for (boolean buy : new boolean[]{true, false}) {
            OfferLine line = rowAt(mouseX, mouseY, buy);
            if (line != null) {
                if (line != selected || buy != selectedBuy) {
                    selected = line;
                    selectedItem = line.item();
                    selectedBuy = buy;
                    updateButtons();
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int listTop = top + LIST_TOP;
        if (scrollY != 0 && mouseY >= listTop && mouseY < listTop + ROWS * ROW) {
            boolean buy = mouseX < left + W / 2;
            int col = buy ? 0 : 1;
            scroll[col] = Mth.clamp(scroll[col] - (int) Math.signum(scrollY), 0, maxScroll(buy ? sells : buys));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Comme les écrans de conteneur : la touche Inventaire (E par défaut) ferme aussi l'écran. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode))) {
            onClose();
            return true;
        }
        return false;
    }
}
