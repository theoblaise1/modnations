package io.github.theoblaise1.sovereignfrontier.client.screen;

import io.github.theoblaise1.sovereignfrontier.client.ClientEngineeringState;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringManager;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRecipe;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringTab;
import io.github.theoblaise1.sovereignfrontier.network.EngineeringCraftC2S;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition.ItemCost;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/** Écran de la Station d'ingénierie : onglets, recettes débloquées par la recherche, ingrédients de l'inventaire. */
@OnlyIn(Dist.CLIENT)
public class EngineeringScreen extends Screen {
    private static final int W = 320;
    private static final int H = 200;
    private static final int ROW = 20;
    private static final int LIST_W = 130;
    private static final int COL_PANEL = 0xF0181A20;
    private static final int COL_BORDER = 0xFF5B6070;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_LABEL = 0xFFAAAAAA;
    private static final int COL_TITLE = 0xFFFFD86B;

    private final BlockPos station;
    private EngineeringTab tab = EngineeringTab.MACHINES;
    @Nullable
    private EngineeringRecipe selected;
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
                selected = null;
                rebuildWidgets();
            }).bounds(x, top + 20, bw, 16).build());
            b.active = t != tab;
            x += bw + 2;
        }
        List<EngineeringRecipe> recipes = visible();
        int y = top + 42;
        for (EngineeringRecipe r : recipes) {
            if (y + ROW > top + H - 30) {
                break;
            }
            ItemCost out = r.definition().result();
            Button b = addRenderableWidget(Button.builder(Component.literal(out.count() + " × ").append(out.item().getDescription()),
                    btn -> {
                        selected = r;
                        rebuildWidgets();
                    }).bounds(left + 8, y, LIST_W, ROW - 2).build());
            b.active = selected == null || !selected.id().equals(r.id());
            y += ROW;
        }
        if (selected == null && !recipes.isEmpty()) {
            selected = recipes.get(0);
        }
        if (selected != null) {
            EngineeringRecipe craft = selected;
            Button make = addRenderableWidget(Button.builder(tr("craft"),
                    btn -> PacketDistributor.sendToServer(new EngineeringCraftC2S(craft.id())))
                    .bounds(left + W - 98, top + H - 28, 90, 20).build());
            make.active = missing(craft) == 0;
        }
    }

    public void refresh() {
        rebuildWidgets();
    }

    public void showFeedback(Component message, boolean success) {
        feedback = message;
        feedbackOk = success;
        feedbackUntil = Util.getMillis() + 4000L;
        rebuildWidgets();
    }

    private boolean tabUnlocked(Set<String> unlocks) {
        return unlocks.contains(tab.unlock());
    }

    /** Recettes de l'onglet que la nation a débloquées. */
    private List<EngineeringRecipe> visible() {
        Set<String> unlocks = ClientEngineeringState.unlocks();
        if (!tabUnlocked(unlocks)) {
            return List.of();
        }
        return ClientEngineeringState.registry().tab(tab).stream()
                .filter(r -> r.definition().unlock().isBlank() || unlocks.contains(r.definition().unlock()))
                .toList();
    }

    private int missing(EngineeringRecipe r) {
        int missing = 0;
        for (ItemCost cost : r.definition().ingredients()) {
            if (EngineeringManager.count(minecraft.player.getInventory(), cost.item()) < cost.count()) {
                missing++;
            }
        }
        return missing;
    }

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
        Set<String> unlocks = ClientEngineeringState.unlocks();
        int dx = left + LIST_W + 18;
        if (!tabUnlocked(unlocks)) {
            g.drawString(font, tr("tab_locked", Msg.tr("unlock." + tab.unlock())), left + 8, top + 46, COL_LABEL);
        } else if (visible().isEmpty()) {
            g.drawString(font, tr("empty"), left + 8, top + 46, COL_LABEL);
        } else if (selected != null) {
            ItemCost out = selected.definition().result();
            g.renderItem(new ItemStack(out.item()), dx, top + 42);
            g.drawString(font, Component.literal(out.count() + " × ").append(out.item().getDescription()), dx + 20, top + 46, COL_TITLE);
            int y = top + 66;
            g.drawString(font, tr("ingredients"), dx, y, COL_LABEL);
            y += 12;
            for (ItemCost cost : selected.definition().ingredients()) {
                int have = EngineeringManager.count(minecraft.player.getInventory(), cost.item());
                boolean ok = have >= cost.count();
                g.renderItem(new ItemStack(cost.item()), dx, y - 4);
                g.drawString(font, Component.literal((ok ? "✔ " : "✘ ") + cost.count() + " × ").append(cost.item().getDescription())
                        .append(Component.literal(" (" + have + ")").withStyle(ChatFormatting.DARK_GRAY)),
                        dx + 18, y, ok ? 0xFF55FF55 : 0xFFFF5555);
                y += 18;
            }
        }
        if (feedback != null && Util.getMillis() < feedbackUntil) {
            g.drawString(font, font.plainSubstrByWidth(feedback.getString(), W - 110), left + 8, top + H - 22,
                    feedbackOk ? 0xFF55FF55 : 0xFFFF5555);
        }
    }
}
