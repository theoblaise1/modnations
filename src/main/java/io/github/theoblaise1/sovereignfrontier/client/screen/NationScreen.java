package io.github.theoblaise1.sovereignfrontier.client.screen;

import io.github.theoblaise1.sovereignfrontier.client.ClientNationState;
import io.github.theoblaise1.sovereignfrontier.client.ModKeyMappings;
import io.github.theoblaise1.sovereignfrontier.nation.LogEntry;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.network.ClaimChunkC2S;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot.MapView;
import io.github.theoblaise1.sovereignfrontier.network.NationSnapshot.MemberInfo;
import io.github.theoblaise1.sovereignfrontier.network.NationTab;
import io.github.theoblaise1.sovereignfrontier.network.RequestNationDataC2S;
import io.github.theoblaise1.sovereignfrontier.network.SetRolePermissionC2S;
import io.github.theoblaise1.sovereignfrontier.network.TreasuryTransferC2S;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Écran national (touche N) : panneau centré à onglets, alimenté par le dernier
 * {@link NationSnapshot} reçu. Toutes les actions passent par des paquets revalidés côté serveur.
 */
@OnlyIn(Dist.CLIENT)
public class NationScreen extends Screen implements FeedbackScreen {
    // Géométrie du panneau (tient dans une hauteur GUI de 240)
    private static final int PANEL_W = 320;
    private static final int PANEL_H = 220;
    private static final int PAD = 8;
    private static final int TAB_Y = 18;
    private static final int TAB_H = 16;
    private static final int TAB_GAP = 2;
    private static final int CONTENT_Y = 40;
    private static final int CONTENT_W = PANEL_W - 2 * PAD;
    private static final int CONTENT_H = 160;
    private static final int STATUS_Y = CONTENT_Y + CONTENT_H + 7;
    private static final int LINE = 11;
    private static final int LIST_LINE = 10;

    // Aperçu
    private static final int STATS_W = 156;

    // Trésorerie (décalages verticaux depuis le haut du contenu)
    private static final int FIELD_DY = 44;
    private static final int BUTTON_DY = 68;
    private static final int HINT_DY = 96;

    // Rôles (12 permissions + l'aide tiennent dans les 160 px du contenu)
    private static final int ROLE_COL_W = 40;
    private static final int ROLE_ROW_H = 11;
    private static final int ROLE_HEADER_H = 12;
    private static final int BOX = 9;

    // Couleurs (ARGB)
    private static final int COL_PANEL = 0xF0181A20;
    private static final int COL_BORDER = 0xFF5B6070;
    private static final int COL_SEPARATOR = 0xFF3A3E4A;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_LABEL = 0xFFAAAAAA;
    private static final int COL_TITLE = 0xFFFFD86B;
    private static final int COL_WARN = 0xFFFFAA00;
    private static final int COL_OK = 0xFF55FF55;
    private static final int COL_ERR = 0xFFFF5555;
    private static final int COL_OFFLINE = 0xFF808080;
    private static final int COL_WILD = 0xFF3A3A3A;
    private static final int COL_GRANTED = 0xFF2E7D32;
    private static final int COL_GRANTED_LOCKED = 0xFF1F4F22;
    private static final int COL_DENIED = 0xFF8E2424;

    private static final String ELLIPSIS = "…";
    private static final String CHECK = "✔";
    private static final String CROSS = "✘";
    private static final long FEEDBACK_MS = 5000L;

    private NationTab tab;
    /** État de l'onglet Recherche (domaine et palier sélectionnés), conservé entre deux rafraîchissements. */
    private final ResearchTab researchTab = new ResearchTab();
    /** Onglet Économie (contrats, entreprises) : section affichée conservée entre deux rafraîchissements. */
    private final EconomyTab economyTab = new EconomyTab();
    private int left;
    private int top;
    /** Défilement (en lignes) de la liste des membres ou du journal. */
    private int scroll;
    /** Montant saisi, conservé entre reconstructions et changements d'onglet. */
    private String amountText = "";
    @Nullable
    private EditBox amountBox;

    // Barre d'état (retour des actions serveur)
    @Nullable
    private Component feedback;
    private boolean feedbackSuccess;
    private long feedbackUntil;

    public NationScreen(NationTab tab) {
        super(tr("title"));
        this.tab = tab;
    }

    private static MutableComponent tr(String key, Object... args) {
        return Msg.tr("gui." + key, args);
    }

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = Math.max(0, (height - PANEL_H) / 2);
        amountBox = null;
        initTabs();
        NationSnapshot s = ClientNationState.get();
        if (s != null && s.hasNation() && tab == NationTab.TREASURY) {
            initTreasury(s);
        }
        if (s != null && s.hasNation() && tab == NationTab.RESEARCH) {
            researchTab.init(w -> addRenderableWidget(w), contentLeft(), contentTop(), CONTENT_W, CONTENT_H, s);
        }
        if (s != null && s.hasNation() && tab == NationTab.CONTRACTS) {
            economyTab.init(w -> addRenderableWidget(w), contentLeft(), contentTop(), CONTENT_W, CONTENT_H, s);
        }
    }

    /** Reconstruit les widgets depuis {@link ClientNationState} en gardant l'onglet et la saisie. */
    public void refresh() {
        if (minecraft == null) {
            return;
        }
        boolean typing = amountBox != null && amountBox.isFocused();
        rebuildWidgets();
        if (typing && amountBox != null) {
            setFocused(amountBox);
        }
    }

    /** Bascule sur un onglet imposé (par exemple par le serveur). */
    public void showTab(NationTab newTab) {
        leaving(newTab);
        tab = newTab;
        scroll = 0;
        refresh();
    }

    /** Nouvelle vue Économie reçue : seul l'onglet Économie l'affiche. */
    public void onEconomyUpdated() {
        if (tab == NationTab.CONTRACTS) {
            refresh();
        }
    }

    /** Quitter l'onglet Économie : sa vue sera redemandée à la prochaine ouverture. */
    private void leaving(NationTab next) {
        if (tab == NationTab.CONTRACTS && next != NationTab.CONTRACTS) {
            economyTab.leave();
        }
    }

    /** Affiche le résultat d'une action dans la barre d'état pendant quelques secondes. */
    @Override
    public void showFeedback(Component message, boolean success) {
        feedback = message;
        feedbackSuccess = success;
        feedbackUntil = Util.getMillis() + FEEDBACK_MS;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void selectTab(NationTab newTab) {
        if (newTab == tab) {
            return;
        }
        leaving(newTab);
        tab = newTab;
        scroll = 0;
        rebuildWidgets();
        // Données fraîches pour le nouvel onglet (le serveur répond par un rafraîchissement)
        PacketDistributor.sendToServer(new RequestNationDataC2S(false, newTab.ordinal()));
    }

    // ------------------------------------------------------------------ widgets

    private void initTabs() {
        NationTab[] tabs = NationTab.values();
        int n = tabs.length;
        int available = CONTENT_W - TAB_GAP * (n - 1);
        int[] widths = new int[n];
        int needed = 0;
        for (int i = 0; i < n; i++) {
            widths[i] = font.width(tabLabel(tabs[i])) + 5;
            needed += widths[i];
        }
        if (needed > available) {
            // Libellés trop longs : largeurs égales (le texte défilera)
            Arrays.fill(widths, available / n);
        } else {
            int extra = available - needed;
            for (int i = 0; i < n; i++) {
                widths[i] += extra / n + (i < extra % n ? 1 : 0);
            }
        }
        int x = contentLeft();
        for (int i = 0; i < n; i++) {
            NationTab t = tabs[i];
            Button button = addRenderableWidget(Button.builder(tabLabel(t), b -> selectTab(t))
                    .bounds(x, top + TAB_Y, widths[i], TAB_H)
                    .build());
            // L'onglet actif est désactivé : il apparaît grisé et n'est pas cliquable
            button.active = t != tab;
            x += widths[i] + TAB_GAP;
        }
    }

    private void initTreasury(NationSnapshot s) {
        int x = contentLeft() + 4;
        int y = contentTop() + 4;
        int labelW = font.width(tr("treasury.amount")) + 6;
        amountBox = new EditBox(font, x + labelW, y + FIELD_DY, 120, 18, tr("treasury.amount"));
        amountBox.setMaxLength(15);
        amountBox.setFilter(NationScreen::isDigits);
        amountBox.setValue(amountText);
        amountBox.setResponder(value -> amountText = value);
        amountBox.setHint(tr("treasury.amount_hint").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(amountBox);

        boolean access = s.treasuryAccess();
        Button deposit = addRenderableWidget(Button.builder(tr("treasury.deposit"), b -> transfer(true))
                .bounds(x, y + BUTTON_DY, 90, 20)
                .build());
        deposit.active = access;
        Button withdraw = addRenderableWidget(Button.builder(tr("treasury.withdraw"), b -> transfer(false))
                .bounds(x + 96, y + BUTTON_DY, 90, 20)
                .build());
        withdraw.active = access && canWithdraw(s);
    }

    private void transfer(boolean deposit) {
        long amount;
        try {
            amount = Long.parseLong(amountText.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (amount > 0) {
            PacketDistributor.sendToServer(new TreasuryTransferC2S(amount, deposit));
        }
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int right = left + PANEL_W;
        int bottom = top + PANEL_H;
        g.fill(left - 1, top - 1, right + 1, bottom + 1, COL_BORDER);
        g.fill(left, top, right, bottom, COL_PANEL);
        g.fill(left + PAD, top + CONTENT_Y - 4, right - PAD, top + CONTENT_Y - 3, COL_SEPARATOR);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        NationSnapshot s = ClientNationState.get();
        g.drawCenteredString(font, headerTitle(s), left + PANEL_W / 2, top + 6, COL_TITLE);
        if (s == null) {
            g.drawCenteredString(font, tr("loading"), left + PANEL_W / 2, contentTop() + CONTENT_H / 2 - 4, COL_LABEL);
        } else {
            switch (tab) {
                case OVERVIEW -> renderOverview(g, s);
                case RESEARCH -> {
                    if (s.hasNation()) {
                        researchTab.render(g, s, mouseX, mouseY, this::setTooltipForNextRenderPass);
                    } else {
                        renderNeedNation(g);
                    }
                }
                case TREASURY -> renderTreasury(g, s);
                case TERRITORY -> renderTerritory(g, s, mouseX, mouseY);
                case ROLES -> renderRoles(g, s, mouseX, mouseY);
                case JOURNAL -> renderJournal(g, s, mouseX, mouseY);
                case CONTRACTS -> {
                    if (s.hasNation()) {
                        economyTab.render(g, s, mouseX, mouseY, this::setTooltipForNextRenderPass);
                    } else {
                        renderNeedNation(g);
                    }
                }
            }
        }
        renderFeedback(g);
    }

    private Component headerTitle(@Nullable NationSnapshot s) {
        if (s == null) {
            return title;
        }
        return s.hasNation() ? Component.literal(s.nationName()) : tr("no_nation_title");
    }

    private void renderFeedback(GuiGraphics g) {
        if (feedback == null) {
            return;
        }
        long remaining = feedbackUntil - Util.getMillis();
        if (remaining <= 0) {
            feedback = null;
            return;
        }
        // Fondu pendant la dernière seconde
        float alpha = Math.min(1.0F, remaining / 1000.0F);
        int y = top + STATUS_Y;
        int tint = feedbackSuccess ? 0x2E7D32 : 0x8E2424;
        g.fill(left + PAD, y - 2, left + PANEL_W - PAD, y + 10, ((int) (alpha * 0x90) << 24) | tint);
        int textAlpha = Math.max(0x10, (int) (alpha * 255));
        int textColor = (textAlpha << 24) | ((feedbackSuccess ? COL_OK : COL_ERR) & 0xFFFFFF);
        g.drawCenteredString(font, trimToWidth(feedback, CONTENT_W - 8), left + PANEL_W / 2, y, textColor);
    }

    // --- Aperçu

    private void renderOverview(GuiGraphics g, NationSnapshot s) {
        if (!s.hasNation()) {
            renderNoNation(g, s);
            return;
        }
        int x = contentLeft() + 2;
        int y = contentTop() + 2;
        long online = s.members().stream().filter(MemberInfo::online).count();
        int buildings = s.houses() + s.farms() + s.workshops();

        y = statRow(g, tr("overview.leader"), Msg.hl(s.leaderName()), x, y, STATS_W);
        y = statRow(g, tr("overview.founded"), Msg.hl(TimeUtil.date(s.createdEpochMs())), x, y, STATS_W);
        y = statRow(g, tr("overview.role"), roleName(s.myRole()).withStyle(ChatFormatting.WHITE), x, y, STATS_W);
        y = statRow(g, tr("overview.members"),
                tr("overview.members_value", s.members().size(), online).withStyle(ChatFormatting.WHITE), x, y, STATS_W);
        y = separator(g, x, y, STATS_W);
        y = statRow(g, tr("overview.treasury"), Msg.money(s.treasury()), x, y, STATS_W);
        y = statRow(g, tr("overview.wallet"), Msg.money(s.wallet()), x, y, STATS_W);
        y = statRow(g, tr("overview.income"), incomeValue(s), x, y, STATS_W);
        y = separator(g, x, y, STATS_W);
        y = statRow(g, tr("overview.territory"),
                tr("overview.chunks_value", s.chunkCount()).withStyle(ChatFormatting.WHITE), x, y, STATS_W);
        y = statRow(g, tr("overview.population"),
                tr("overview.population_value", buildings).withStyle(ChatFormatting.WHITE), x, y, STATS_W);
        y = statRow(g, tr("overview.houses"), Msg.hl(s.houses()), x + 8, y, STATS_W - 8);
        y = statRow(g, tr("overview.farms"), Msg.hl(s.farms()), x + 8, y, STATS_W - 8);
        statRow(g, tr("overview.workshops"), Msg.hl(s.workshops()), x + 8, y, STATS_W - 8);

        renderMemberList(g, s);
    }

    private void renderMemberList(GuiGraphics g, NationSnapshot s) {
        int x = memberListX();
        int w = contentRight() - x;
        int y = contentTop() + 2;
        List<MemberInfo> members = s.members();
        g.drawString(font, tr("overview.member_list", members.size()), x, y, COL_TITLE);
        g.fill(x, y + 10, x + w, y + 11, COL_SEPARATOR);

        int listTop = memberListTop();
        int visible = memberVisibleRows();
        scroll = Mth.clamp(scroll, 0, maxScroll(s));
        for (int i = 0; i < visible && scroll + i < members.size(); i++) {
            MemberInfo m = members.get(scroll + i);
            int ry = listTop + i * LIST_LINE;
            int color = m.online() ? COL_OK : COL_OFFLINE;
            Component role = roleName(m.role());
            int roleW = font.width(role);
            g.fill(x, ry + 2, x + 4, ry + 6, color);
            g.drawString(font, role, x + w - 4 - roleW, ry, COL_LABEL);
            g.drawString(font, trim(m.name(), w - roleW - 16), x + 7, ry, color);
        }
        drawScrollbar(g, x + w - 2, listTop, visible * LIST_LINE, members.size(), visible, scroll);
    }

    private void renderNoNation(GuiGraphics g, NationSnapshot s) {
        int cx = left + PANEL_W / 2;
        int x = contentLeft() + 4;
        int y = contentTop() + 2;
        g.drawCenteredString(font, tr("overview.no_nation"), cx, y, COL_TITLE);
        y += 12;
        for (FormattedCharSequence line : font.split(tr("overview.no_nation_desc"), CONTENT_W - 8)) {
            g.drawCenteredString(font, line, cx, y, COL_LABEL);
            y += LIST_LINE;
        }
        y += 4;
        y = statRow(g, tr("overview.wallet"), Msg.money(s.wallet()), x, y, STATS_W);
        y += 4;

        g.drawString(font, tr("overview.invitations"), x, y, COL_TITLE);
        y += LINE;
        List<String> invitations = s.invitations();
        if (invitations.isEmpty()) {
            g.drawString(font, tr("overview.no_invitations"), x + 6, y, COL_LABEL);
            y += LIST_LINE;
        } else {
            int shown = invitations.size() > 5 ? 4 : invitations.size();
            for (int i = 0; i < shown; i++) {
                g.drawString(font, "• " + invitations.get(i), x + 6, y, COL_OK);
                y += LIST_LINE;
            }
            if (shown < invitations.size()) {
                g.drawString(font, tr("overview.more", invitations.size() - shown), x + 6, y, COL_LABEL);
                y += LIST_LINE;
            }
        }
        y += 4;

        g.drawString(font, tr("overview.commands"), x, y, COL_TITLE);
        y += LINE;
        commandLine(g, tr("overview.cmd_create"), tr("overview.cmd_create_desc"), x + 6, y);
        commandLine(g, tr("overview.cmd_join"), tr("overview.cmd_join_desc"), x + 6, y + LIST_LINE);
    }

    private void commandLine(GuiGraphics g, Component command, Component description, int x, int y) {
        int end = g.drawString(font, command, x, y, COL_TITLE);
        g.drawString(font, description, end + 4, y, COL_LABEL);
    }

    private void renderNeedNation(GuiGraphics g) {
        int cx = left + PANEL_W / 2;
        int cy = contentTop() + CONTENT_H / 2;
        g.drawCenteredString(font, tr("need_nation"), cx, cy - 10, COL_TITLE);
        g.drawCenteredString(font, tr("need_nation_hint"), cx, cy + 4, COL_LABEL);
    }

    // --- Trésorerie

    private void renderTreasury(GuiGraphics g, NationSnapshot s) {
        if (!s.hasNation()) {
            renderNeedNation(g);
            return;
        }
        int x = contentLeft() + 4;
        int y = contentTop() + 4;
        int w = 200;
        int ry = statRow(g, tr("treasury.balance"), Msg.money(s.treasury()), x, y, w);
        ry = statRow(g, tr("treasury.wallet"), Msg.money(s.wallet()), x, ry, w);
        ry = statRow(g, tr("treasury.income"), incomeValue(s), x, ry, w);
        separator(g, x, ry, w);
        g.drawString(font, tr("treasury.amount"), x, y + FIELD_DY + 5, COL_LABEL);

        Component hint;
        int color;
        if (!s.treasuryAccess()) {
            hint = tr("treasury.no_access");
            color = COL_WARN;
        } else if (!canWithdraw(s)) {
            hint = tr("treasury.no_withdraw");
            color = COL_LABEL;
        } else {
            hint = tr("treasury.access_ok");
            color = COL_OK;
        }
        g.drawWordWrap(font, hint, x, y + HINT_DY, CONTENT_W - 8, color);
    }

    // --- Territoire

    private void renderTerritory(GuiGraphics g, NationSnapshot s, int mouseX, int mouseY) {
        MapView m = s.map();
        int n = m.size();
        int cell = mapCell(m);
        int x0 = mapX();
        int y0 = mapY(m);
        if (isMapValid(m)) {
            g.fill(x0 - 1, y0 - 1, x0 + n * cell, y0 + n * cell, 0xFF000000);
            for (int dz = -m.radius(); dz <= m.radius(); dz++) {
                for (int dx = -m.radius(); dx <= m.radius(); dx++) {
                    int px = x0 + (dx + m.radius()) * cell;
                    int py = y0 + (dz + m.radius()) * cell;
                    g.fill(px, py, px + cell - 1, py + cell - 1, cellColor(m, cellValue(m, dx, dz)));
                }
            }
            int[] hovered = mapCellAt(m, mouseX, mouseY);
            if (hovered != null) {
                int px = x0 + (hovered[0] + m.radius()) * cell;
                int py = y0 + (hovered[1] + m.radius()) * cell;
                g.fill(px, py, px + cell - 1, py + cell - 1, 0x60FFFFFF);
                tooltip(chunkTooltip(s, hovered[0], hovered[1]));
            }
            // Chunk du joueur
            int c = m.radius() * cell;
            g.renderOutline(x0 + c - 1, y0 + c - 1, cell + 1, cell + 1, 0xFFFFFFFF);
        }
        renderTerritorySide(g, s, x0 + n * cell + 10);
    }

    private void renderTerritorySide(GuiGraphics g, NationSnapshot s, int x) {
        MapView m = s.map();
        int w = contentRight() - x;
        int y = contentTop() + 2;
        if (s.hasNation()) {
            y = statRow(g, tr("territory.chunks"), Msg.hl(s.chunkCount()), x, y, w);
            y = statRow(g, tr("territory.next_cost"), Msg.money(s.nextClaimCost()), x, y, w);
            y = statRow(g, tr("territory.treasury"), Msg.money(s.treasury()), x, y, w);
        } else {
            for (FormattedCharSequence line : font.split(tr("territory.no_nation"), w)) {
                g.drawString(font, line, x, y, COL_WARN);
                y += LIST_LINE;
            }
            y += 1;
        }
        y = statRow(g, tr("territory.position"),
                tr("territory.position_value", m.centerX(), m.centerZ()).withStyle(ChatFormatting.WHITE), x, y, w);
        y = separator(g, x, y, w);

        g.drawString(font, tr("territory.legend"), x, y, COL_TITLE);
        y += LINE;
        y = legendRow(g, COL_WILD, tr("territory.wild"), x, y, w);
        if (s.hasNation()) {
            y = legendRow(g, opaque(m.ownColor()), tr("territory.own"), x, y, w);
        }

        // Aide en bas ; les nations voisines remplissent l'espace restant
        List<FormattedCharSequence> help = font.split(tr("territory.help"), w);
        int helpY = contentBottom() - help.size() * LIST_LINE;
        List<String> names = m.nationNames();
        int rows = Math.max(0, (helpY - 4 - y) / LIST_LINE);
        int shown = names.size() > rows ? Math.max(0, rows - 1) : names.size();
        for (int k = 0; k < shown; k++) {
            y = legendRow(g, nationColor(m, k), Component.literal(names.get(k)), x, y, w);
        }
        if (shown < names.size() && rows > 0) {
            g.drawString(font, tr("territory.more_nations", names.size() - shown), x + 12, y, COL_LABEL);
        }
        for (FormattedCharSequence line : help) {
            g.drawString(font, line, x, helpY, COL_LABEL);
            helpY += LIST_LINE;
        }
    }

    private int legendRow(GuiGraphics g, int color, Component label, int x, int y, int w) {
        g.fill(x, y, x + 8, y + 8, 0xFF000000);
        g.fill(x + 1, y + 1, x + 7, y + 7, color);
        g.drawString(font, trimToWidth(label, w - 12), x + 12, y, COL_TEXT);
        return y + LIST_LINE;
    }

    private List<Component> chunkTooltip(NationSnapshot s, int dx, int dz) {
        MapView m = s.map();
        int value = cellValue(m, dx, dz);
        List<Component> lines = new ArrayList<>();
        lines.add(tr("territory.chunk", m.centerX() + dx, m.centerZ() + dz));
        if (value == 0) {
            lines.add(tr("territory.wild").withStyle(ChatFormatting.GRAY));
            if (s.hasNation()) {
                lines.add(tr("territory.hint_claim", Msg.money(s.nextClaimCost())).withStyle(ChatFormatting.DARK_GRAY));
            }
        } else if (value == 1) {
            lines.add(tr("territory.own").withStyle(ChatFormatting.GREEN));
            lines.add(tr("territory.hint_unclaim").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            int k = value - 2;
            String name = k < m.nationNames().size() ? m.nationNames().get(k) : "?";
            lines.add(Component.literal(name).withStyle(ChatFormatting.WHITE));
        }
        return lines;
    }

    // --- Rôles

    private void renderRoles(GuiGraphics g, NationSnapshot s, int mouseX, int mouseY) {
        if (!s.hasNation()) {
            renderNeedNation(g);
            return;
        }
        NationRole[] roles = NationRole.values();
        NationPermission[] perms = NationPermission.values();
        boolean editable = s.myRole() == NationRole.CHEF.ordinal();
        int gridX = roleGridX();
        int gridW = CONTENT_W - 4;
        int labelW = roleLabelW();
        int headerY = contentTop() + 2;

        for (int r = 0; r < roles.length; r++) {
            Component name = roles[r].displayName();
            int cx = roleColX(r) + ROLE_COL_W / 2;
            g.drawCenteredString(font, trimToWidth(name, ROLE_COL_W - 2), cx, headerY, r == s.myRole() ? COL_TITLE : COL_TEXT);
        }

        int[] hovered = roleCellAt(mouseX, mouseY);
        for (int p = 0; p < perms.length; p++) {
            int y = roleRowY(p);
            if (p % 2 == 0) {
                g.fill(gridX, y, gridX + gridW, y + ROLE_ROW_H, 0x14FFFFFF);
            }
            Component permName = perms[p].displayName();
            g.drawString(font, trimToWidth(permName, labelW - 4), gridX + 2, y + 2, COL_TEXT);
            if (mouseX >= gridX && mouseX < gridX + labelW && mouseY >= y && mouseY < y + ROLE_ROW_H) {
                tooltip(List.of(permName));
            }
            for (int r = 0; r < roles.length; r++) {
                boolean locked = r == NationRole.CHEF.ordinal();
                boolean granted = locked || (roleMask(s, r) & perms[p].bit()) != 0;
                int bx = roleColX(r) + (ROLE_COL_W - BOX) / 2;
                int by = y + 1;
                g.fill(bx, by, bx + BOX, by + BOX, granted ? (locked ? COL_GRANTED_LOCKED : COL_GRANTED) : COL_DENIED);
                g.drawCenteredString(font, granted ? CHECK : CROSS, bx + BOX / 2, by + 1, locked ? 0xFFB0B0B0 : COL_TEXT);
                if (hovered != null && hovered[0] == r && hovered[1] == p) {
                    if (editable && !locked) {
                        g.renderOutline(bx - 1, by - 1, BOX + 2, BOX + 2, 0xFFFFFFFF);
                    }
                    tooltip(roleCellTooltip(roles[r], perms[p], granted, editable, locked));
                }
            }
        }

        Component note = editable ? tr("roles.hint_chef") : tr("roles.chef_only");
        g.drawCenteredString(font, note, left + PANEL_W / 2, roleRowY(perms.length) + 4, editable ? COL_LABEL : COL_WARN);
    }

    private List<Component> roleCellTooltip(NationRole role, NationPermission perm, boolean granted, boolean editable,
                                            boolean locked) {
        List<Component> lines = new ArrayList<>();
        lines.add(tr("roles.cell", role.displayName(), perm.displayName()));
        lines.add(granted ? tr("roles.granted").withStyle(ChatFormatting.GREEN) : tr("roles.denied").withStyle(ChatFormatting.RED));
        if (locked) {
            lines.add(tr("roles.chef_all").withStyle(ChatFormatting.GRAY));
        } else if (editable) {
            lines.add(tr("roles.click_toggle").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    // --- Journal

    private void renderJournal(GuiGraphics g, NationSnapshot s, int mouseX, int mouseY) {
        if (!s.hasNation()) {
            renderNeedNation(g);
            return;
        }
        List<LogEntry> log = s.log();
        if (log.isEmpty()) {
            g.drawCenteredString(font, tr("journal.empty"), left + PANEL_W / 2, contentTop() + CONTENT_H / 2 - 4, COL_LABEL);
            return;
        }
        int visible = journalVisibleRows();
        scroll = Mth.clamp(scroll, 0, maxScroll(s));
        int x = contentLeft() + 2;
        int w = CONTENT_W - 10;
        for (int i = 0; i < visible && scroll + i < log.size(); i++) {
            // Plus récent en premier
            LogEntry entry = log.get(log.size() - 1 - (scroll + i));
            Component line = journalLine(entry);
            int y = contentTop() + 1 + i * LIST_LINE;
            g.drawString(font, trimToWidth(line, w), x, y, COL_TEXT);
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + LIST_LINE;
            if (hovered && font.width(line) > w) {
                setTooltipForNextRenderPass(font.split(line, 240));
            }
        }
        drawScrollbar(g, contentRight() - 3, contentTop(), visible * LIST_LINE, log.size(), visible, scroll);
    }

    private static Component journalLine(LogEntry entry) {
        return Component.literal("[" + TimeUtil.shortStamp(entry.epochMs()) + "] ")
                .withStyle(ChatFormatting.GRAY)
                .append(entry.toComponent().withStyle(ChatFormatting.WHITE));
    }

    // ------------------------------------------------------------------ entrées

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = amountBox != null && amountBox.isFocused();
        if (!typing && ModKeyMappings.OPEN_NATION.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        NationSnapshot s = ClientNationState.get();
        if (s == null) {
            return false;
        }
        if (tab == NationTab.TERRITORY && (button == 0 || button == 1)) {
            return clickMap(s, mouseX, mouseY, button == 0);
        }
        if (tab == NationTab.ROLES && button == 0) {
            return clickRole(s, mouseX, mouseY);
        }
        if (tab == NationTab.RESEARCH && s.hasNation()) {
            return researchTab.mouseClicked(mouseX, mouseY, button);
        }
        if (tab == NationTab.CONTRACTS && s.hasNation()) {
            return economyTab.mouseClicked(mouseX, mouseY, button);
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == NationTab.RESEARCH && researchTab.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (tab == NationTab.CONTRACTS && economyTab.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        int max = maxScroll(ClientNationState.get());
        if (max > 0 && scrollY != 0) {
            int step = tab == NationTab.JOURNAL ? 3 : 1;
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * step, 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Clic gauche : revendiquer ; clic droit : abandonner. */
    private boolean clickMap(NationSnapshot s, double mouseX, double mouseY, boolean claim) {
        MapView m = s.map();
        if (!isMapValid(m)) {
            return false;
        }
        int[] cell = mapCellAt(m, mouseX, mouseY);
        if (cell == null) {
            return false;
        }
        PacketDistributor.sendToServer(new ClaimChunkC2S(m.centerX() + cell[0], m.centerZ() + cell[1], claim));
        playClick();
        return true;
    }

    /** Seul le chef peut basculer une case (hors colonne du chef). */
    private boolean clickRole(NationSnapshot s, double mouseX, double mouseY) {
        if (!s.hasNation() || s.myRole() != NationRole.CHEF.ordinal()) {
            return false;
        }
        int[] cell = roleCellAt(mouseX, mouseY);
        if (cell == null || cell[0] == NationRole.CHEF.ordinal()) {
            return false;
        }
        NationPermission perm = NationPermission.values()[cell[1]];
        boolean current = (roleMask(s, cell[0]) & perm.bit()) != 0;
        PacketDistributor.sendToServer(new SetRolePermissionC2S(cell[0], perm.ordinal(), !current));
        playClick();
        return true;
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    // ------------------------------------------------------------------ géométrie

    private int contentLeft() {
        return left + PAD;
    }

    private int contentRight() {
        return left + PANEL_W - PAD;
    }

    private int contentTop() {
        return top + CONTENT_Y;
    }

    private int contentBottom() {
        return contentTop() + CONTENT_H;
    }

    private int memberListX() {
        return contentLeft() + STATS_W + 12;
    }

    private int memberListTop() {
        return contentTop() + 16;
    }

    private int memberVisibleRows() {
        return (contentBottom() - memberListTop()) / LIST_LINE;
    }

    private int journalVisibleRows() {
        return CONTENT_H / LIST_LINE;
    }

    private int maxScroll(@Nullable NationSnapshot s) {
        if (s == null || !s.hasNation()) {
            return 0;
        }
        return switch (tab) {
            case JOURNAL -> Math.max(0, s.log().size() - journalVisibleRows());
            case OVERVIEW -> Math.max(0, s.members().size() - memberVisibleRows());
            default -> 0;
        };
    }

    private static boolean isMapValid(MapView m) {
        return m.radius() >= 0 && m.cells().length >= m.size() * m.size();
    }

    private static int mapCell(MapView m) {
        return Mth.clamp(CONTENT_H / Math.max(1, m.size()), 2, 9);
    }

    private int mapX() {
        return contentLeft() + 2;
    }

    private int mapY(MapView m) {
        return contentTop() + (CONTENT_H - m.size() * mapCell(m)) / 2;
    }

    /** Case de carte sous la souris, en décalage {dx, dz} par rapport au joueur, ou null. */
    @Nullable
    private int[] mapCellAt(MapView m, double mouseX, double mouseY) {
        int cell = mapCell(m);
        int cx = Mth.floor((mouseX - mapX()) / cell);
        int cz = Mth.floor((mouseY - mapY(m)) / cell);
        if (cx < 0 || cz < 0 || cx >= m.size() || cz >= m.size()) {
            return null;
        }
        return new int[]{cx - m.radius(), cz - m.radius()};
    }

    private static int cellValue(MapView m, int dx, int dz) {
        return m.cell(dx, dz) & 0xFF;
    }

    private static int cellColor(MapView m, int value) {
        if (value == 0) {
            return COL_WILD;
        }
        return value == 1 ? opaque(m.ownColor()) : nationColor(m, value - 2);
    }

    private static int nationColor(MapView m, int k) {
        return k >= 0 && k < m.nationColors().length ? opaque(m.nationColors()[k]) : 0xFF888888;
    }

    private static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    private int roleGridX() {
        return contentLeft() + 2;
    }

    private int roleLabelW() {
        return CONTENT_W - 4 - NationRole.values().length * ROLE_COL_W;
    }

    private int roleColX(int role) {
        return roleGridX() + roleLabelW() + role * ROLE_COL_W;
    }

    private int roleRowY(int perm) {
        return contentTop() + 2 + ROLE_HEADER_H + perm * ROLE_ROW_H;
    }

    /** Case de la matrice sous la souris, {rôle, permission}, ou null. */
    @Nullable
    private int[] roleCellAt(double mouseX, double mouseY) {
        int gridLeft = roleColX(0);
        int rowsTop = roleRowY(0);
        int roles = NationRole.values().length;
        int perms = NationPermission.values().length;
        if (mouseX < gridLeft || mouseX >= gridLeft + roles * ROLE_COL_W
                || mouseY < rowsTop || mouseY >= rowsTop + perms * ROLE_ROW_H) {
            return null;
        }
        int r = (int) ((mouseX - gridLeft) / ROLE_COL_W);
        int p = (int) ((mouseY - rowsTop) / ROLE_ROW_H);
        return new int[]{r, p};
    }

    private static int roleMask(NationSnapshot s, int role) {
        int[] masks = s.rolePermissions();
        return role >= 0 && role < masks.length ? masks[role] : 0;
    }

    // ------------------------------------------------------------------ utilitaires de dessin

    /** Ligne « libellé ……… valeur » ; renvoie la position de la ligne suivante. */
    private int statRow(GuiGraphics g, Component label, Component value, int x, int y, int w) {
        g.drawString(font, label, x, y, COL_LABEL);
        g.drawString(font, value, x + w - font.width(value), y, COL_TEXT);
        return y + LINE;
    }

    private static int separator(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y + 1, x + w, y + 2, COL_SEPARATOR);
        return y + 5;
    }

    private static void drawScrollbar(GuiGraphics g, int x, int y, int h, int total, int visible, int offset) {
        if (total <= visible || visible <= 0) {
            return;
        }
        g.fill(x, y, x + 2, y + h, 0x40FFFFFF);
        int thumbH = Math.max(8, h * visible / total);
        int thumbY = y + (h - thumbH) * offset / (total - visible);
        g.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF);
    }

    private void tooltip(List<Component> lines) {
        setTooltipForNextRenderPass(lines.stream().map(Component::getVisualOrderText).toList());
    }

    /** Coupe un texte stylé à la largeur donnée, avec points de suspension. */
    private FormattedCharSequence trimToWidth(Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text.getVisualOrderText();
        }
        FormattedText cut = font.substrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS)));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of(ELLIPSIS)));
    }

    private String trim(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS))) + ELLIPSIS;
    }

    private static Component tabLabel(NationTab t) {
        return tr("tab." + t.id());
    }

    private static MutableComponent roleName(int ordinal) {
        return NationRole.byOrdinal(ordinal).displayName();
    }

    private static Component incomeValue(NationSnapshot s) {
        return tr("overview.income_value", Msg.money(s.incomePerCycle()), formatMinutes(s.incomeIntervalTicks()))
                .withStyle(ChatFormatting.WHITE);
    }

    /** Durée d'un cycle en minutes (1200 ticks = 1 min), avec une décimale si nécessaire. */
    private static String formatMinutes(int ticks) {
        if (ticks % 1200 == 0) {
            return Integer.toString(ticks / 1200);
        }
        return String.format(Locale.FRANCE, "%.1f", ticks / 1200.0);
    }

    private static boolean canWithdraw(NationSnapshot s) {
        return (s.myPermissions() & NationPermission.WITHDRAW.bit()) != 0;
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
