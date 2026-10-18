// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.theoblaise1.sovereignfrontier.contract.ContractInstance;
import io.github.theoblaise1.sovereignfrontier.contract.ContractKind;
import io.github.theoblaise1.sovereignfrontier.contract.ContractManager;
import io.github.theoblaise1.sovereignfrontier.contract.ContractTemplate;
import io.github.theoblaise1.sovereignfrontier.contract.NationContracts;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static io.github.theoblaise1.sovereignfrontier.command.CommandUtil.send;

/**
 * {@code /contract} : tableau de la nation (offres, en cours, derniers terminés) avec actions cliquables, fiche d'un
 * contrat, acceptation et abandon (permission {@code economie}), livraison (tout membre). {@link #registerAdmin} fournit
 * la sous-commande {@code /sfadmin contract <nation> refresh | complete <n> | expire <n>}.
 */
public final class ContractCommand {
    /** Lignes d'historique affichées par le tableau dans le chat. */
    private static final int BOARD_HISTORY = 3;

    private ContractCommand() {
    }

    // ------------------------------------------------------------------ suggestions

    /** Tous les contrats du tableau (offres et en cours) ; infobulle : intitulé. */
    static final SuggestionProvider<CommandSourceStack> BOARD = (ctx, builder) ->
            suggestContracts(ctx.getSource(), builder, true, true, c -> true);

    static final SuggestionProvider<CommandSourceStack> OFFERS = (ctx, builder) ->
            suggestContracts(ctx.getSource(), builder, true, false, c -> true);

    static final SuggestionProvider<CommandSourceStack> ACTIVE = (ctx, builder) ->
            suggestContracts(ctx.getSource(), builder, false, true, c -> true);

    /** Contrats de livraison en cours dont le joueur possède au moins un objet intact. */
    static final SuggestionProvider<CommandSourceStack> DELIVERABLE = (ctx, builder) -> {
        ServerPlayer player = ctx.getSource().getPlayer();
        return suggestContracts(ctx.getSource(), builder, false, true, c -> player != null
                && c.kind() == ContractKind.DELIVER && !c.isFull() && Inventories.countPlain(player, c.item()) > 0);
    };

    /** Quantité à livrer : 1, ce que le joueur possède (borné au reste) et le reste. */
    static final SuggestionProvider<CommandSourceStack> QUANTITIES = (ctx, builder) -> {
        ServerPlayer player = ctx.getSource().getPlayer();
        Nation nation = playerNation(ctx.getSource());
        ContractInstance c = nation == null ? null
                : nation.contracts().activeContract(IntegerArgumentType.getInteger(ctx, "n"));
        if (player == null || c == null || c.kind() != ContractKind.DELIVER) {
            return builder.buildFuture();
        }
        int remaining = c.remaining();
        Map<Integer, Component> values = new LinkedHashMap<>();
        values.put(1, Msg.tr("contract.suggest.one"));
        int have = Math.min(remaining, Inventories.countPlain(player, c.item()));
        if (have > 0) {
            values.putIfAbsent(have, Msg.tr("contract.suggest.have"));
        }
        values.putIfAbsent(Math.max(1, remaining), Msg.tr("contract.suggest.remaining"));
        String typed = builder.getRemaining();
        values.forEach((value, tooltip) -> {
            if (String.valueOf(value).startsWith(typed)) {
                builder.suggest(value, tooltip);
            }
        });
        return builder.buildFuture();
    };

    /** {@code /sfadmin contract <nation> complete|expire <n>} : contrats en cours de la nation nommée. */
    static final SuggestionProvider<CommandSourceStack> ADMIN_ACTIVE = (ctx, builder) -> {
        Nation nation = NationData.get(ctx.getSource().getServer()).byName(StringArgumentType.getString(ctx, "nation"));
        if (nation != null) {
            suggest(builder, nation.contracts().active(), c -> true);
        }
        return builder.buildFuture();
    };

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestContracts(
            CommandSourceStack source, SuggestionsBuilder builder, boolean offers, boolean active,
            Predicate<ContractInstance> filter) {
        Nation nation = playerNation(source);
        if (nation != null) {
            if (offers) {
                suggest(builder, nation.contracts().offers(), filter);
            }
            if (active) {
                suggest(builder, nation.contracts().active(), filter);
            }
        }
        return builder.buildFuture();
    }

    private static void suggest(SuggestionsBuilder builder, List<ContractInstance> contracts, Predicate<ContractInstance> filter) {
        String typed = builder.getRemaining();
        for (ContractInstance c : contracts) {
            if (filter.test(c) && String.valueOf(c.id()).startsWith(typed)) {
                builder.suggest(c.id(), ContractManager.title(c));
            }
        }
    }

    // ------------------------------------------------------------------ enregistrement

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("contract")
                .executes(ContractCommand::board)
                .then(Commands.literal("info")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .suggests(BOARD)
                                .executes(ContractCommand::info)))
                .then(Commands.literal("accept")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .suggests(OFFERS)
                                .executes(ctx -> send(ctx.getSource(), ContractManager.accept(
                                        ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "n"))))))
                .then(Commands.literal("deliver")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .suggests(DELIVERABLE)
                                .executes(ctx -> deliver(ctx, Integer.MAX_VALUE))
                                .then(Commands.argument("quantite", IntegerArgumentType.integer(1))
                                        .suggests(QUANTITIES)
                                        .executes(ctx -> deliver(ctx, IntegerArgumentType.getInteger(ctx, "quantite"))))))
                .then(Commands.literal("abandon")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .suggests(ACTIVE)
                                .executes(ctx -> send(ctx.getSource(), ContractManager.abandon(
                                        ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "n")))))));
    }

    /**
     * Sous-commande {@code contract <nation> refresh | complete <n> | expire <n>} de {@code /sfadmin} (opérateurs).
     * L'exigence de permission est répétée sur la sous-commande : Brigadier fusionne les nœuds de même nom.
     */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("contract")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("nation", StringArgumentType.word())
                        .suggests(CommandUtil.NATIONS)
                        .then(Commands.literal("refresh").executes(ContractCommand::adminRefresh))
                        .then(Commands.literal("complete")
                                .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                        .suggests(ADMIN_ACTIVE)
                                        .executes(ContractCommand::adminComplete)))
                        .then(Commands.literal("expire")
                                .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                        .suggests(ADMIN_ACTIVE)
                                        .executes(ContractCommand::adminExpire)))));
    }

    // ------------------------------------------------------------------ utilitaires

    @Nullable
    private static Nation playerNation(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? null : NationData.get(source.getServer()).nationOf(player.getUUID());
    }

    @Nullable
    private static Nation requireNation(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Nation nation = NationData.get(ctx.getSource().getServer()).nationOf(player.getUUID());
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.no_nation_hint"));
        }
        return nation;
    }

    @Nullable
    private static Nation adminNation(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "nation");
        Nation nation = NationData.get(ctx.getSource().getServer()).byName(name);
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.unknown", name));
        }
        return nation;
    }

    private static MutableComponent button(Component label, String command, ChatFormatting color) {
        return Component.literal("[").append(label).append("]").withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    /** « 79 cr, 3 pt(s), 1 clé militaire » (sans part estimée). */
    private static MutableComponent rewards(ContractInstance c) {
        MutableComponent text = Msg.tr("contract.reward.summary", Money.format(c.money()), c.points());
        for (CrateKind kind : CrateKind.values()) {
            int n = c.keys(kind);
            if (n > 0) {
                text.append(Msg.tr("contract.reward.keys", n, kind.displayName()));
            }
        }
        if (!c.items().isEmpty()) {
            text.append(Msg.tr("contract.reward.items", ContractManager.itemsText(c)));
        }
        return text;
    }

    // ------------------------------------------------------------------ /contract

    private static int board(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Nation nation = requireNation(ctx);
        if (nation == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long now = TimeUtil.now();
        ContractManager.maintain(ctx.getSource().getServer(), nation, now);
        NationContracts contracts = nation.contracts();
        boolean canManage = nation.hasPermission(player.getUUID(), NationPermission.ECONOMY);
        int members = nation.memberCount();
        MutableComponent c = Msg.tr("contract.board.header", nation.name()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);

        c.append("\n").append(Msg.tr("contract.board.offers", contracts.offers().size(), ContractManager.offerSlots(members))
                .withStyle(ChatFormatting.GOLD));
        if (!ContractManager.isOldEnough(nation, now)) {
            c.append("\n ").append(Msg.info("contract.board.too_young",
                    TimeUtil.duration(ContractManager.timeUntilOpen(nation, now))));
        } else {
            for (ContractInstance offer : contracts.offers()) {
                MutableComponent row = Component.literal(" n°" + offer.id() + " ").withStyle(ChatFormatting.YELLOW)
                        .append(ContractManager.title(offer).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(ContractManager.objective(offer).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(rewards(offer).withStyle(ChatFormatting.GREEN)).append(" ");
                if (canManage) {
                    row.append(button(Msg.tr("contract.board.accept"), "/contract accept " + offer.id(), ChatFormatting.GREEN)).append(" ");
                }
                row.append(button(Msg.tr("contract.board.info"), "/contract info " + offer.id(), ChatFormatting.AQUA));
                c.append("\n").append(row);
            }
            if (contracts.offers().isEmpty()) {
                c.append("\n ").append(Msg.info("contract.board.no_offers"));
            }
            c.append("\n ").append(Msg.info("contract.board.refresh",
                    TimeUtil.duration(Math.max(0L, contracts.nextRefresh() - now))));
        }

        c.append("\n").append(Msg.tr("contract.board.active", contracts.active().size(), ContractManager.maxActive(members))
                .withStyle(ChatFormatting.GOLD));
        for (ContractInstance active : contracts.active()) {
            MutableComponent row = Component.literal(" n°" + active.id() + " ").withStyle(ChatFormatting.YELLOW)
                    .append(ContractManager.title(active).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Msg.tr("contract.board.progress", active.progress(), active.amount(),
                            TimeUtil.duration(Math.max(0L, active.deadline() - now))).withStyle(ChatFormatting.GRAY))
                    .append(" ");
            if (active.kind() == ContractKind.DELIVER && Inventories.countPlain(player, active.item()) > 0) {
                row.append(button(Msg.tr("contract.board.deliver"), "/contract deliver " + active.id(), ChatFormatting.GREEN)).append(" ");
            }
            if (canManage) {
                row.append(button(Msg.tr("contract.board.abandon"), "/contract abandon " + active.id(), ChatFormatting.RED)).append(" ");
            }
            row.append(button(Msg.tr("contract.board.info"), "/contract info " + active.id(), ChatFormatting.AQUA));
            c.append("\n").append(row);
        }
        if (contracts.active().isEmpty()) {
            c.append("\n ").append(Msg.info("contract.board.no_active"));
        }

        List<NationContracts.History> history = contracts.history();
        if (!history.isEmpty()) {
            c.append("\n").append(Msg.tr("contract.board.history").withStyle(ChatFormatting.GOLD));
            for (int i = history.size() - 1; i >= Math.max(0, history.size() - BOARD_HISTORY); i--) {
                NationContracts.History h = history.get(i);
                c.append("\n ").append(Msg.info("contract.board.history_line", h.id(), ContractTemplate.displayName(h.template()),
                        Msg.tr("contract.status." + h.status().id()), TimeUtil.shortStamp(h.epochMs())));
            }
        }
        if (!canManage) {
            c.append("\n").append(Msg.warn("contract.board.read_only"));
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Nation nation = requireNation(ctx);
        if (nation == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long now = TimeUtil.now();
        ContractManager.maintain(ctx.getSource().getServer(), nation, now);
        int id = IntegerArgumentType.getInteger(ctx, "n");
        ContractInstance offer = nation.contracts().offer(id);
        ContractInstance contract = offer != null ? offer : nation.contracts().activeContract(id);
        if (contract == null) {
            ctx.getSource().sendFailure(Msg.err("contract.error.unknown", id));
            return 0;
        }
        boolean isOffer = offer != null;
        MutableComponent c = Component.literal("n°" + id + " ").withStyle(ChatFormatting.YELLOW)
                .append(ContractManager.title(contract).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        c.append("\n").append(Msg.info("contract.info.kind", contract.kind().displayName(),
                Msg.tr(isOffer ? "contract.status.offered" : "contract.status.active")));
        c.append("\n").append(Msg.info("contract.info.objective", ContractManager.objective(contract)));
        if (isOffer) {
            c.append("\n").append(Msg.info("contract.info.duration", TimeUtil.duration(contract.durationMs())));
        } else {
            c.append("\n").append(Msg.info("contract.info.progress", Msg.hl(contract.progress()), Msg.hl(contract.amount()),
                    TimeUtil.duration(Math.max(0L, contract.deadline() - now))));
        }
        c.append("\n").append(Msg.info("contract.info.reward", rewards(contract)));
        if (!isOffer) {
            c.append("\n").append(Msg.info("contract.info.share", Msg.money(ContractManager.shareEstimate(nation, contract,
                    player.getUUID())), Msg.hl(contract.contribution(player.getUUID()))));
            List<Map.Entry<UUID, Integer>> top = new ArrayList<>(contract.contributions().entrySet());
            top.sort(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()));
            for (int i = 0; i < top.size() && i < 3; i++) {
                c.append("\n  ").append(Msg.info("contract.info.contributor", i + 1,
                        ContractManager.contributorName(ctx.getSource().getServer(), nation, top.get(i).getKey()),
                        top.get(i).getValue()));
            }
            if (contract.kind() == ContractKind.EXPLORE && contract.target().type() == ContractInstance.TargetType.OUTPOSTS) {
                c.append("\n").append(Msg.info("contract.info.visited", contract.visited().size()));
            }
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }

    private static int deliver(CommandContext<CommandSourceStack> ctx, int quantity) throws CommandSyntaxException {
        return send(ctx.getSource(), ContractManager.deliver(ctx.getSource().getPlayerOrException(),
                IntegerArgumentType.getInteger(ctx, "n"), quantity));
    }

    // ------------------------------------------------------------------ /sfadmin contract

    private static int adminRefresh(CommandContext<CommandSourceStack> ctx) {
        Nation nation = adminNation(ctx);
        if (nation == null) {
            return 0;
        }
        return admin(ctx, ContractManager.adminRefresh(ctx.getSource().getServer(), nation, TimeUtil.now()));
    }

    private static int adminComplete(CommandContext<CommandSourceStack> ctx) {
        Nation nation = adminNation(ctx);
        if (nation == null) {
            return 0;
        }
        return admin(ctx, ContractManager.adminComplete(ctx.getSource().getServer(), nation,
                IntegerArgumentType.getInteger(ctx, "n"), ctx.getSource().getPlayer(), TimeUtil.now()));
    }

    private static int adminExpire(CommandContext<CommandSourceStack> ctx) {
        Nation nation = adminNation(ctx);
        if (nation == null) {
            return 0;
        }
        return admin(ctx, ContractManager.adminExpire(ctx.getSource().getServer(), nation,
                IntegerArgumentType.getInteger(ctx, "n"), TimeUtil.now()));
    }

    /** Résultat d'une commande d'administration : succès diffusé aux opérateurs. */
    private static int admin(CommandContext<CommandSourceStack> ctx, ActionResult result) {
        if (result.success()) {
            ctx.getSource().sendSuccess(result::message, true);
            return 1;
        }
        ctx.getSource().sendFailure(result.message());
        return 0;
    }
}
