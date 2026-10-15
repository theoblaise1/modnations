package io.github.theoblaise1.sovereignfrontier.client.screen;

import io.github.theoblaise1.sovereignfrontier.client.ClientNationState;
import io.github.theoblaise1.sovereignfrontier.client.ClientResearchState;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.network.CancelResearchC2S;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot;
import io.github.theoblaise1.sovereignfrontier.network.ResearchView;
import io.github.theoblaise1.sovereignfrontier.network.StartResearchC2S;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDomain;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager.Status;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchReward;
import io.github.theoblaise1.sovereignfrontier.research.ResearchStat;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Onglet « Recherche » de l'écran national : domaines, liste des paliers et leur état, fiche du
 * palier sélectionné (coûts colorés selon le coffre national et la trésorerie), barre de progression
 * de la recherche en cours. L'état est recalculé côté client à partir du snapshot ; le serveur
 * revalide tout au lancement.
 */
@OnlyIn(Dist.CLIENT)
final class ResearchTab {
    private static final int DOMAIN_H = 14;
    private static final int ROW = 11;
    private static final int LIST_W = 124;
    private static final int BAR_H = 22;
    private static final int TOOLTIP_WIDTH = 200;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_LABEL = 0xFFAAAAAA;
    private static final int COL_TITLE = 0xFFFFD86B;
    private static final int COL_OK = 0xFF55FF55;
    private static final int COL_ERR = 0xFFFF5555;
    private static final int COL_LOCKED = 0xFF606060;
    private static final int COL_SELECTED = 0x60FFFFFF;
    private static final int COL_HOVER = 0x25FFFFFF;
    private static final int COL_BAR_BG = 0xFF2A2D36;
    private static final int COL_BAR = 0xFF3FA34D;

    private ResearchDomain domain = ResearchDomain.CIVIL;
    @Nullable
    private ResourceLocation selected;
    private int scroll;
    private int x;
    private int y;
    private int w;
    private int h;

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static MutableComponent tr(String key, Object... args) {
        return Msg.tr("gui.research." + key, args);
    }

    // ------------------------------------------------------------------ widgets

    void init(Consumer<AbstractWidget> add, int x, int y, int w, int h, NationSnapshot s) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ResearchRegistry registry = ClientResearchState.registry();
        ResearchView view = s.research();
        ensureSelection(registry, view);
        // Largeurs proportionnelles aux libellés (« Technologie » est bien plus long que « Civil »).
        ResearchDomain[] domains = ResearchDomain.values();
        int gap = 2;
        int[] widths = new int[domains.length];
        int needed = gap * (domains.length - 1);
        for (int i = 0; i < domains.length; i++) {
            widths[i] = font().width(domains[i].displayName()) + 8;
            needed += widths[i];
        }
        int extra = w - needed;
        for (int i = 0; i < domains.length; i++) {
            widths[i] = extra >= 0 ? widths[i] + extra / domains.length + (i < extra % domains.length ? 1 : 0)
                    : (w - gap * (domains.length - 1)) / domains.length;
        }
        int bx = x;
        for (int i = 0; i < domains.length; i++) {
            ResearchDomain d = domains[i];
            int bw = widths[i];
            long done = registry.domain(d).stream().filter(t -> view.completed().contains(t.id())).count();
            Button b = Button.builder(d.displayName(), btn -> selectDomain(d))
                    .bounds(bx, y, bw, DOMAIN_H)
                    .tooltip(Tooltip.create(tr("domain_progress", d.displayName(), done, registry.domain(d).size())))
                    .build();
            b.active = d != domain;
            add.accept(b);
            bx += bw + gap;
        }

        boolean canResearch = (s.myPermissions() & NationPermission.START_RESEARCH.bit()) != 0;
        int by = y + h - BAR_H + 2;
        if (view.active().isPresent()) {
            // À gauche de la barre, loin de « Lancer » : un second clic de trop tombe sur la barre inerte.
            ResourceLocation activeId = view.active().get();
            long activeStart = view.activeStartMs();
            Button cancel = Button.builder(tr("cancel"), btn -> confirmCancel(registry, activeId, activeStart))
                    .bounds(x, by, 60, 18).build();
            cancel.active = canResearch;
            add.accept(cancel);
        } else {
            ResearchTier tier = selected == null ? null : registry.get(selected);
            if (tier != null && status(registry, view, tier) == Status.AVAILABLE) {
                ResourceLocation id = tier.id();
                Button start = Button.builder(tr("start"), btn -> PacketDistributor.sendToServer(new StartResearchC2S(id)))
                        .bounds(x + w - 60, by, 60, 18).build();
                start.active = canResearch && missing(s, tier).isEmpty();
                add.accept(start);
            }
        }
    }

    /**
     * Annuler détruit les objets prélevés et ne rend qu'une partie de la trésorerie : confirmation
     * obligatoire. Sans elle, un double-clic sur « Lancer » pouvait tomber sur « Annuler », reconstruit
     * dès le retour du serveur, et annuler aussitôt la recherche qui venait d'être lancée.
     */
    private static void confirmCancel(ResearchRegistry registry, ResourceLocation activeId, long activeStart) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen;
        ResearchTier tier = registry.get(activeId);
        Component name = tier == null ? Component.literal(activeId.toString()) : tier.displayName();
        ConfirmScreen confirm = new ConfirmScreen(ok -> {
            if (ok) {
                // La recherche a pu se terminer, et un autre membre en relancer une (même palier, nouveau début)
                // pendant la confirmation : seul le début identifie la recherche affichée.
                NationSnapshot now = ClientNationState.get();
                if (now != null && now.hasNation() && now.research().active().filter(activeId::equals).isPresent()
                        && now.research().activeStartMs() == activeStart) {
                    PacketDistributor.sendToServer(new CancelResearchC2S(activeId, activeStart));
                } else if (parent instanceof NationScreen screen) {
                    screen.showFeedback(Msg.err("research.error.cancel_changed", name), false);
                }
            }
            // Retour à l'écran national : init() le reconstruit avec le dernier snapshot reçu.
            mc.setScreen(parent);
        }, tr("cancel_confirm.title", name), tr("cancel_confirm.message"));
        mc.setScreen(confirm);
        // Boutons inertes une demi-seconde (après init, qui les crée) : un clic de trop ne confirme rien.
        confirm.setDelay(10);
    }

    private void ensureSelection(ResearchRegistry registry, ResearchView view) {
        ResearchTier current = selected == null ? null : registry.get(selected);
        if (current == null || current.domain() != domain) {
            selected = firstInteresting(registry, view, registry.domain(domain));
        }
    }

    private void selectDomain(ResearchDomain d) {
        domain = d;
        scroll = 0;
        selected = null;
        refreshScreen();
    }

    private static void refreshScreen() {
        if (Minecraft.getInstance().screen instanceof NationScreen screen) {
            screen.refresh();
        }
    }

    // ------------------------------------------------------------------ rendu

    void render(GuiGraphics g, NationSnapshot s, int mouseX, int mouseY, Consumer<List<FormattedCharSequence>> tooltip) {
        ResearchRegistry registry = ClientResearchState.registry();
        ResearchView view = s.research();
        List<ResearchTier> tiers = registry.domain(domain);
        if (registry.size() == 0) {
            g.drawCenteredString(font(), tr("empty"), x + w / 2, y + h / 2, COL_LABEL);
            return;
        }
        ensureSelection(registry, view);

        // Liste des paliers
        int listTop = y + DOMAIN_H + 4;
        int rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, tiers.size() - rows));
        for (int i = 0; i < rows && i + scroll < tiers.size(); i++) {
            ResearchTier tier = tiers.get(i + scroll);
            int ry = listTop + i * ROW;
            boolean hover = mouseX >= x && mouseX < x + LIST_W && mouseY >= ry && mouseY < ry + ROW;
            if (tier.id().equals(selected)) {
                g.fill(x, ry, x + LIST_W, ry + ROW, COL_SELECTED);
            } else if (hover) {
                g.fill(x, ry, x + LIST_W, ry + ROW, COL_HOVER);
            }
            Status st = status(registry, view, tier);
            g.drawString(font(), mark(st), x + 2, ry + 2, COL_TEXT);
            Component name = Component.literal(tier.tier() + ". ").append(tier.displayName());
            g.drawString(font(), trim(name, LIST_W - 14), x + 12, ry + 2, st == Status.LOCKED ? COL_LOCKED : COL_TEXT);
        }
        if (tiers.size() > rows) {
            int trackH = rows * ROW;
            g.fill(x + LIST_W + 1, listTop, x + LIST_W + 3, listTop + trackH, 0x40FFFFFF);
            int thumbH = Math.max(8, trackH * rows / tiers.size());
            int thumbY = listTop + (trackH - thumbH) * scroll / Math.max(1, tiers.size() - rows);
            g.fill(x + LIST_W + 1, thumbY, x + LIST_W + 3, thumbY + thumbH, 0xC0FFFFFF);
        }

        // Fiche du palier
        ResearchTier tier = selected == null ? null : registry.get(selected);
        if (tier != null) {
            renderDetail(g, s, registry, tier, mouseX, mouseY, tooltip);
        }

        renderBar(g, s, registry, tier);
    }

    private void renderDetail(GuiGraphics g, NationSnapshot s, ResearchRegistry registry, ResearchTier tier,
                              int mouseX, int mouseY, Consumer<List<FormattedCharSequence>> tooltip) {
        int dx = x + LIST_W + 8;
        int dw = x + w - dx;
        int top = y + DOMAIN_H + 4;
        int bottom = y + h - BAR_H - 2;
        List<Component> lines = detailLines(s, registry, tier);
        int ly = top;
        g.drawString(font(), trim(tier.displayName().withStyle(ChatFormatting.BOLD), dw), dx, ly, COL_TITLE);
        ly += ROW;
        boolean truncated = false;
        for (Component line : lines) {
            if (ly + ROW > bottom) {
                truncated = true;
                break;
            }
            g.drawString(font(), trim(line, dw), dx, ly, COL_TEXT);
            ly += ROW - 1;
        }
        if (truncated) {
            g.drawString(font(), "…", dx + dw - font().width("…"), bottom - ROW + 2, COL_LABEL);
        }
        if (mouseX >= dx && mouseX < x + w && mouseY >= top && mouseY < bottom) {
            List<Component> full = new ArrayList<>();
            full.add(tier.displayName().withStyle(ChatFormatting.GOLD));
            full.add(tier.description().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            full.addAll(lines);
            // Retour à la ligne : une description longue ne doit pas sortir de l'écran.
            List<FormattedCharSequence> wrapped = new ArrayList<>();
            for (Component line : full) {
                wrapped.addAll(font().split(line, TOOLTIP_WIDTH));
            }
            tooltip.accept(wrapped);
        }
    }

    private List<Component> detailLines(NationSnapshot s, ResearchRegistry registry, ResearchTier tier) {
        ResearchView view = s.research();
        ResearchDefinition def = tier.definition();
        List<Component> lines = new ArrayList<>();
        Status st = status(registry, view, tier);
        lines.add(tr("tier_line", tier.domain().displayName(), def.tier(), TimeUtil.duration(durationMs(view, tier)))
                .withStyle(ChatFormatting.GRAY));
        lines.add(tr("state", Msg.tr("research.state." + st.name().toLowerCase(Locale.ROOT)))
                .withStyle(stateColor(st)));
        boolean done = st == Status.COMPLETED || st == Status.ACTIVE;
        if (def.cost().treasury() > 0) {
            lines.add(tr("cost", Money.format(def.cost().treasury()))
                    .withStyle(done || s.treasury() >= def.cost().treasury() ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        if (def.cost().points() > 0) {
            lines.add(tr("points", def.cost().points(), view.points())
                    .withStyle(done || view.points() >= def.cost().points() ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        for (Map.Entry<Item, Integer> cost : ResearchManager.itemTotals(def.cost()).entrySet()) {
            int have = view.chestCounts().getOrDefault(BuiltInRegistries.ITEM.getKey(cost.getKey()), 0);
            boolean ok = done || have >= cost.getValue();
            lines.add(Component.literal(ok ? "✔ " : "✘ ").withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED)
                    .append(tr("item", cost.getValue(), cost.getKey().getDescription(), have).withStyle(ChatFormatting.WHITE)));
        }
        if (def.minPopulation() > 0) {
            boolean ok = done || view.effectivePopulation() >= def.minPopulation();
            lines.add(tr("population", view.effectivePopulation(), def.minPopulation())
                    .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        for (ResourceLocation req : registry.prerequisites(tier)) {
            ResearchTier r = registry.get(req);
            boolean ok = view.completed().contains(req);
            lines.add(tr("requires", r == null ? Component.literal(req.toString()) : r.displayName())
                    .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        for (ResearchReward reward : def.rewards()) {
            lines.add(Component.literal("★ ").withStyle(ChatFormatting.GOLD).append(reward.describe().withStyle(ChatFormatting.YELLOW)));
        }
        return lines;
    }

    private void renderBar(GuiGraphics g, NationSnapshot s, ResearchRegistry registry, @Nullable ResearchTier selectedTier) {
        ResearchView view = s.research();
        int by = y + h - BAR_H;
        g.fill(x, by, x + w, by + 1, 0xFF3A3E4A);
        int textY = by + 7;
        int barW = w - 66;
        if (view.active().isPresent()) {
            // Barre à droite du bouton « Annuler » (placé à gauche, voir init).
            int barX = x + 66;
            ResearchTier active = registry.get(view.active().get());
            long remaining = ClientNationState.researchRemainingMs();
            long total = Math.max(1L, view.activeTotalMs());
            float progress = Mth.clamp(1.0F - (float) remaining / total, 0.0F, 1.0F);
            g.fill(barX, by + 4, barX + barW, by + 18, COL_BAR_BG);
            g.fill(barX, by + 4, barX + (int) (barW * progress), by + 18, COL_BAR);
            Component name = active == null ? Component.literal(view.active().get().toString()) : active.displayName();
            Component label = tr("active", name, (int) (progress * 100), TimeUtil.duration(remaining));
            g.drawString(font(), trim(label, barW - 6), barX + 3, textY, COL_TEXT);
            return;
        }
        Component hint;
        int color = COL_LABEL;
        if (selectedTier == null) {
            hint = tr("idle");
        } else {
            Status st = status(registry, view, selectedTier);
            if (st == Status.COMPLETED) {
                hint = tr("hint_completed");
                color = COL_OK;
            } else if (st == Status.LOCKED) {
                hint = tr("hint_locked");
                color = COL_ERR;
            } else if ((s.myPermissions() & NationPermission.START_RESEARCH.bit()) == 0) {
                hint = tr("hint_no_permission");
                color = COL_ERR;
            } else if (!missing(s, selectedTier).isEmpty()) {
                hint = tr("hint_missing");
                color = COL_ERR;
            } else {
                hint = tr("hint_ready");
                color = COL_OK;
            }
        }
        g.drawString(font(), trim(hint, barW - 4), x + 2, textY, color);
    }

    // ------------------------------------------------------------------ entrées

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int listTop = y + DOMAIN_H + 4;
        if (mouseX < x || mouseX >= x + LIST_W || mouseY < listTop || mouseY >= listTop + visibleRows() * ROW) {
            return false;
        }
        List<ResearchTier> tiers = ClientResearchState.registry().domain(domain);
        int index = scroll + (int) ((mouseY - listTop) / ROW);
        if (index < 0 || index >= tiers.size()) {
            return false;
        }
        selected = tiers.get(index).id();
        refreshScreen();
        return true;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (mouseX < x || mouseX >= x + LIST_W + 4 || scrollY == 0) {
            return false;
        }
        int max = Math.max(0, ClientResearchState.registry().domain(domain).size() - visibleRows());
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, max);
        return true;
    }

    // ------------------------------------------------------------------ logique client

    private int visibleRows() {
        return Math.max(1, (h - DOMAIN_H - 4 - BAR_H - 2) / ROW);
    }

    /** Même règle que {@code ResearchManager#status}, évaluée sur le snapshot. */
    static Status status(ResearchRegistry registry, ResearchView view, ResearchTier tier) {
        if (view.completed().contains(tier.id())) {
            return Status.COMPLETED;
        }
        if (view.active().isPresent() && view.active().get().equals(tier.id())) {
            return Status.ACTIVE;
        }
        for (ResourceLocation req : registry.prerequisites(tier)) {
            if (!view.completed().contains(req)) {
                return Status.LOCKED;
            }
        }
        return Status.AVAILABLE;
    }

    /** Ressources manquantes (trésorerie, points, objets du coffre, population) ; vide = lançable. */
    private static List<String> missing(NationSnapshot s, ResearchTier tier) {
        ResearchView view = s.research();
        ResearchDefinition def = tier.definition();
        List<String> out = new ArrayList<>();
        if (s.treasury() < def.cost().treasury()) {
            out.add("treasury");
        }
        if (view.points() < def.cost().points()) {
            out.add("points");
        }
        if (view.effectivePopulation() < def.minPopulation()) {
            out.add("population");
        }
        for (Map.Entry<Item, Integer> cost : ResearchManager.itemTotals(def.cost()).entrySet()) {
            if (view.chestCounts().getOrDefault(BuiltInRegistries.ITEM.getKey(cost.getKey()), 0) < cost.getValue()) {
                out.add(cost.getKey().toString());
            }
        }
        return out;
    }

    private static long durationMs(ResearchView view, ResearchTier tier) {
        double[] bonuses = view.bonuses();
        int i = ResearchStat.RESEARCH_SPEED.ordinal();
        double speed = i < bonuses.length ? Math.max(0.0D, bonuses[i]) : 0.0D;
        return Math.round(tier.definition().baseDurationSeconds() * 1000.0D / (1.0D + speed));
    }

    /** Palier mis en avant par défaut : la recherche en cours, sinon le premier disponible, sinon le premier. */
    @Nullable
    private static ResourceLocation firstInteresting(ResearchRegistry registry, ResearchView view, List<ResearchTier> tiers) {
        if (tiers.isEmpty()) {
            return null;
        }
        for (ResearchTier t : tiers) {
            Status st = status(registry, view, t);
            if (st == Status.ACTIVE || st == Status.AVAILABLE) {
                return t.id();
            }
        }
        return tiers.get(tiers.size() - 1).id();
    }

    private static Component mark(Status status) {
        return switch (status) {
            case COMPLETED -> Component.literal("✔").withStyle(ChatFormatting.GREEN);
            case ACTIVE -> Component.literal("⌛").withStyle(ChatFormatting.GOLD);
            case AVAILABLE -> Component.literal("●").withStyle(ChatFormatting.YELLOW);
            case LOCKED -> Component.literal("✘").withStyle(ChatFormatting.DARK_GRAY);
        };
    }

    private static ChatFormatting stateColor(Status status) {
        return switch (status) {
            case COMPLETED -> ChatFormatting.GREEN;
            case ACTIVE -> ChatFormatting.GOLD;
            case AVAILABLE -> ChatFormatting.YELLOW;
            case LOCKED -> ChatFormatting.RED;
        };
    }

    private static FormattedCharSequence trim(Component text, int maxWidth) {
        Font font = font();
        if (font.width(text) <= maxWidth) {
            return text.getVisualOrderText();
        }
        net.minecraft.network.chat.FormattedText cut = font.substrByWidth(text, Math.max(0, maxWidth - font.width("…")));
        return net.minecraft.locale.Language.getInstance().getVisualOrder(
                net.minecraft.network.chat.FormattedText.composite(cut, net.minecraft.network.chat.FormattedText.of("…")));
    }
}
