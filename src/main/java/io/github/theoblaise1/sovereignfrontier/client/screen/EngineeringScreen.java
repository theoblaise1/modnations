package io.github.theoblaise1.sovereignfrontier.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.theoblaise1.sovereignfrontier.client.ClientEngineeringState;
import io.github.theoblaise1.sovereignfrontier.client.ClientResearchState;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringManager;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRecipe;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringTab;
import io.github.theoblaise1.sovereignfrontier.network.EngineeringCraftC2S;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition.ItemCost;
import io.github.theoblaise1.sovereignfrontier.research.ResearchReward;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Écran de la Station d'ingénierie : onglets, liste défilante de toutes les recettes de l'onglet (les
 * verrouillées grisées, avec la recherche requise), fiche de la recette sélectionnée (ingrédients de
 * l'inventaire) et ligne d'état expliquant pourquoi « Fabriquer » est désactivé. Le serveur revalide tout.
 */
@OnlyIn(Dist.CLIENT)
public class EngineeringScreen extends Screen implements FeedbackScreen {
    private static final int W = 320;
    private static final int H = 200;
    private static final int ROW = 20;
    private static final int LIST_W = 130;
    private static final int LIST_TOP = 42;
    private static final int LIST_ROWS = (H - 30 - LIST_TOP) / ROW;
    private static final int STATUS_W = W - 110;
    private static final int TOOLTIP_W = 200;
    private static final int COL_PANEL = 0xF0181A20;
    private static final int COL_BORDER = 0xFF5B6070;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_LABEL = 0xFFAAAAAA;
    private static final int COL_TITLE = 0xFFFFD86B;
    private static final int COL_OK = 0xFF55FF55;
    private static final int COL_ERR = 0xFFFF5555;
    private static final int COL_LOCKED = 0xFF606060;
    private static final int COL_VEIL = 0xB0181A20;
    private static final int COL_SELECTED = 0x60FFFFFF;
    private static final int COL_HOVER = 0x25FFFFFF;
    private static final String ELLIPSIS = "…";

    private final BlockPos station;
    private EngineeringTab tab = EngineeringTab.MACHINES;
    /** Sélection gardée par identifiant : la recette est relue à chaque reconstruction (snapshot, /reload). */
    @Nullable
    private ResourceLocation selectedId;
    @Nullable
    private EngineeringRecipe selected;
    private List<EngineeringRecipe> recipes = List.of();
    private Set<String> unlocks = Set.of();
    /** Nom de la recherche qui accorde chaque clé de déblocage (vidé à chaque reconstruction). */
    private final Map<String, Component> requirements = new HashMap<>();
    private int scroll;
    @Nullable
    private Button craftButton;
    /** Motif du refus de la recette sélectionnée, ou {@code null} si elle est fabricable. */
    @Nullable
    private Component blocker;
    private int left;
    private int top;
    private Component feedback;
    private boolean feedbackOk;
    private long feedbackUntil;

    public EngineeringScreen(BlockPos station) {
        super(Msg.tr("engineering.title"));
        this.station = station;
    }

    private static MutableComponent tr(String key, Object... args) {
        return Msg.tr("gui.engineering." + key, args);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(0, (height - H) / 2);
        int bw = (W - 16 - 4) / 3;
        int x = left + 8;
        for (EngineeringTab t : EngineeringTab.values()) {
            Button b = addRenderableWidget(Button.builder(t.displayName(), btn -> {
                tab = t;
                selectedId = null;
                scroll = 0;
                rebuildWidgets();
            }).bounds(x, top + 20, bw, 16).build());
            b.active = t != tab;
            x += bw + 2;
        }
        unlocks = ClientEngineeringState.unlocks();
        requirements.clear();
        recipes = ClientEngineeringState.registry().tab(tab);
        // Sélection par défaut choisie avant tout dessin : la fiche et la ligne surlignée désignent la même recette.
        selected = resolveSelection();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        craftButton = null;
        if (selected != null) {
            craftButton = addRenderableWidget(Button.builder(tr("craft"), btn -> {
                if (selected != null) {
                    PacketDistributor.sendToServer(new EngineeringCraftC2S(selected.id()));
                }
            }).bounds(left + W - 98, top + H - 28, 90, 20).build());
        }
        updateStatus();
    }

    /** Nouveau snapshot, recettes ou définitions de recherche : liste, verrous et sélection relus. */
    public void refresh() {
        rebuildWidgets();
    }

    @Override
    public void showFeedback(Component message, boolean success) {
        feedback = message;
        feedbackOk = success;
        feedbackUntil = Util.getMillis() + 4000L;
        rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        // L'inventaire peut changer écran ouvert (objets ramassés) : bouton et motif réévalués à chaque tick.
        updateStatus();
    }

    private void updateStatus() {
        blocker = selected == null ? null : blocker(selected);
        if (craftButton != null) {
            craftButton.active = selected != null && blocker == null;
        }
    }

    /** Recette sélectionnée relue dans la liste courante (la liste défile pour qu'elle reste visible), sinon la première de l'onglet. */
    @Nullable
    private EngineeringRecipe resolveSelection() {
        for (int i = 0; i < recipes.size(); i++) {
            EngineeringRecipe r = recipes.get(i);
            if (r.id().equals(selectedId)) {
                scroll = Mth.clamp(scroll, i - LIST_ROWS + 1, i);
                return r;
            }
        }
        scroll = 0;
        EngineeringRecipe first = recipes.isEmpty() ? null : recipes.get(0);
        selectedId = first == null ? null : first.id();
        return first;
    }

    private int maxScroll() {
        return Math.max(0, recipes.size() - LIST_ROWS);
    }

    // ------------------------------------------------------------------ règles (miroir du serveur)

    /** Même règle que {@code EngineeringManager#unlocked}, évaluée sur le snapshot. */
    private boolean locked(EngineeringRecipe r) {
        String key = r.definition().unlock();
        return !unlocks.contains(r.definition().tab().unlock()) || !key.isBlank() && !unlocks.contains(key);
    }

    @Nullable
    private MutableComponent lockReason(EngineeringRecipe r) {
        String tabKey = r.definition().tab().unlock();
        if (!unlocks.contains(tabKey)) {
            return tr("tab_locked", requirement(tabKey));
        }
        String key = r.definition().unlock();
        if (!key.isBlank() && !unlocks.contains(key)) {
            return tr("locked", requirement(key));
        }
        return null;
    }

    /** Pourquoi « Fabriquer » est refusé (verrou, puis premier ingrédient manquant), ou {@code null}. */
    @Nullable
    private Component blocker(EngineeringRecipe r) {
        Component lock = lockReason(r);
        if (lock != null) {
            return lock;
        }
        Inventory inv = minecraft.player.getInventory();
        for (ItemCost cost : r.definition().ingredients()) {
            int have = EngineeringManager.count(inv, cost.item());
            if (have < cost.count()) {
                return Msg.tr("engineering.error.missing", cost.count() - have, cost.item().getDescription());
            }
        }
        return null;
    }

    /** Palier de recherche qui accorde la clé (nom, domaine, palier) ; à défaut, nom du déblocage. */
    private Component requirement(String unlock) {
        return requirements.computeIfAbsent(unlock, k -> {
            for (ResearchTier t : ClientResearchState.registry().all()) {
                for (ResearchReward reward : t.definition().rewards()) {
                    if (reward.type() == ResearchReward.Type.UNLOCK && reward.key().equals(k)) {
                        return tr("research", t.displayName(), t.domain().displayName(), t.tier());
                    }
                }
            }
            return Msg.tr("unlock." + k);
        });
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
        g.drawCenteredString(font, title, left + W / 2, top + 6, COL_TITLE);
        if (recipes.isEmpty()) {
            Component message = unlocks.contains(tab.unlock()) ? tr("empty") : tr("tab_locked", requirement(tab.unlock()));
            drawWrapped(g, message, left + 8, top + 46, W - 16, 4, COL_LABEL);
        } else {
            renderList(g, mouseX, mouseY);
            if (selected != null) {
                renderDetail(g, selected, mouseX, mouseY);
            }
        }
        renderStatus(g);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 8;
        int listTop = top + LIST_TOP;
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < LIST_ROWS && scroll + i < recipes.size(); i++) {
            int index = scroll + i;
            EngineeringRecipe r = recipes.get(index);
            int ry = listTop + i * ROW;
            if (r.id().equals(selectedId)) {
                g.fill(x, ry, x + LIST_W, ry + ROW - 1, COL_SELECTED);
                g.fill(x, ry, x + 2, ry + ROW - 1, COL_TITLE);
            } else if (index == hovered) {
                g.fill(x, ry, x + LIST_W, ry + ROW - 1, COL_HOVER);
            }
            boolean locked = locked(r);
            g.renderItem(new ItemStack(r.definition().result().item()), x + 3, ry + 2);
            if (locked) {
                // Voile au-dessus de l'icône (calque sans test de profondeur, comme la surbrillance des cases)
                g.fill(RenderType.guiOverlay(), x + 3, ry + 2, x + 19, ry + 18, COL_VEIL);
            }
            g.drawString(font, trim(label(r.definition().result()), LIST_W - 26), x + 22, ry + 6, locked ? COL_LOCKED : COL_TEXT);
        }
        if (recipes.size() > LIST_ROWS) {
            int trackX = x + LIST_W + 1;
            int trackH = LIST_ROWS * ROW;
            g.fill(trackX, listTop, trackX + 2, listTop + trackH, 0x40FFFFFF);
            int thumbH = Math.max(8, trackH * LIST_ROWS / recipes.size());
            int thumbY = listTop + (trackH - thumbH) * scroll / Math.max(1, maxScroll());
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xC0FFFFFF);
        }
        if (hovered >= 0) {
            EngineeringRecipe r = recipes.get(hovered);
            Component name = label(r.definition().result());
            MutableComponent reason = lockReason(r);
            if (reason != null || font.width(name) > LIST_W - 26) {
                List<FormattedCharSequence> tip = new ArrayList<>(font.split(name, TOOLTIP_W));
                if (reason != null) {
                    tip.addAll(font.split(reason.withStyle(ChatFormatting.RED), TOOLTIP_W));
                }
                setTooltipForNextRenderPass(tip);
            }
        }
    }

    private void renderDetail(GuiGraphics g, EngineeringRecipe r, int mouseX, int mouseY) {
        int dx = left + LIST_W + 18;
        int right = left + W - 8;
        ItemCost out = r.definition().result();
        g.renderItem(new ItemStack(out.item()), dx, top + 42);
        drawTrimmed(g, label(out), dx + 20, top + 46, right - dx - 20, locked(r) ? COL_LOCKED : COL_TITLE, mouseX, mouseY);
        int y = top + 66;
        g.drawString(font, trim(tr("ingredients"), right - dx), dx, y, COL_LABEL);
        y += 12;
        // Lignes de 18 px jusqu'à la ligne d'état ; au-delà, « … +N ».
        int maxRows = (top + H - 44 - y) / 18 + 1;
        Inventory inv = minecraft.player.getInventory();
        List<ItemCost> ingredients = r.definition().ingredients();
        for (int i = 0; i < ingredients.size(); i++) {
            if (i == maxRows - 1 && ingredients.size() > maxRows) {
                g.drawString(font, ELLIPSIS + " +" + (ingredients.size() - i), dx + 18, y, COL_LABEL);
                break;
            }
            ItemCost cost = ingredients.get(i);
            int have = EngineeringManager.count(inv, cost.item());
            boolean ok = have >= cost.count();
            g.renderItem(new ItemStack(cost.item()), dx, y - 4);
            Component line = Component.literal((ok ? "✔ " : "✘ ") + cost.count() + " × ").append(cost.item().getDescription())
                    .append(Component.literal(" (" + have + ")").withStyle(ChatFormatting.DARK_GRAY));
            drawTrimmed(g, line, dx + 18, y, right - dx - 18, ok ? COL_OK : COL_ERR, mouseX, mouseY);
            y += 18;
        }
    }

    /** Ligne d'état à gauche de « Fabriquer » : retour du serveur (4 s), sinon motif du refus ou « prêt ». */
    private void renderStatus(GuiGraphics g) {
        Component text;
        int color;
        if (feedback != null && Util.getMillis() < feedbackUntil) {
            text = feedback;
            color = feedbackOk ? COL_OK : COL_ERR;
        } else if (selected == null) {
            return;
        } else if (blocker != null) {
            text = blocker;
            color = COL_ERR;
        } else {
            text = tr("ready");
            color = COL_OK;
        }
        List<FormattedCharSequence> lines = font.split(text, STATUS_W);
        int n = Math.min(lines.size(), 3);
        int y = top + H - 18 - n * font.lineHeight / 2;
        for (int i = 0; i < n; i++) {
            g.drawString(font, lines.get(i), left + 8, y + i * font.lineHeight, color);
        }
    }

    private void drawWrapped(GuiGraphics g, Component text, int x, int y, int width, int maxLines, int color) {
        List<FormattedCharSequence> lines = font.split(text, width);
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            g.drawString(font, lines.get(i), x, y + i * (font.lineHeight + 1), color);
        }
    }

    /** Texte coupé avec « … » à la largeur donnée ; texte complet en infobulle s'il a été coupé. */
    private void drawTrimmed(GuiGraphics g, Component text, int x, int y, int maxWidth, int color, int mouseX, int mouseY) {
        g.drawString(font, trim(text, maxWidth), x, y, color);
        if (font.width(text) > maxWidth && mouseX >= x && mouseX < x + maxWidth && mouseY >= y - 4 && mouseY < y + 12) {
            setTooltipForNextRenderPass(font.split(text, TOOLTIP_W));
        }
    }

    private FormattedCharSequence trim(Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text.getVisualOrderText();
        }
        FormattedText cut = font.substrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS)));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of(ELLIPSIS)));
    }

    private static MutableComponent label(ItemCost stack) {
        return Component.literal(stack.count() + " × ").append(stack.item().getDescription());
    }

    // ------------------------------------------------------------------ entrées

    /** Index de la recette sous la souris dans la liste, ou -1. */
    private int rowAt(double mouseX, double mouseY) {
        int listTop = top + LIST_TOP;
        if (mouseX < left + 8 || mouseX >= left + 8 + LIST_W || mouseY < listTop || mouseY >= listTop + LIST_ROWS * ROW) {
            return -1;
        }
        int index = scroll + (int) ((mouseY - listTop) / ROW);
        return index < recipes.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int index = button == 0 ? rowAt(mouseX, mouseY) : -1;
        if (index < 0) {
            return false;
        }
        EngineeringRecipe r = recipes.get(index);
        if (!r.id().equals(selectedId)) {
            selectedId = r.id();
            selected = r;
            updateStatus();
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int listTop = top + LIST_TOP;
        if (scrollY != 0 && mouseX >= left + 8 && mouseX < left + 8 + LIST_W + 4
                && mouseY >= listTop && mouseY < listTop + LIST_ROWS * ROW) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Comme les écrans de conteneur : la touche Inventaire (E par défaut) ferme aussi la station. */
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
