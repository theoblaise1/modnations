package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.economy.TerminalManager;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKeys;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.reward.CrateManager;
import io.github.theoblaise1.sovereignfrontier.reward.CrateRegistry;
import io.github.theoblaise1.sovereignfrontier.reward.DailyManager;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.UUID;

/**
 * Commandes des récompenses joueur : {@code /terminal}, {@code /daily [info]}, {@code /crate pity|preview <type>}, et
 * les sous-commandes {@code terminal}, {@code daily} et {@code crate} de {@code /sfadmin} ({@link #registerAdmin}).
 */
public final class RewardCommands {
    /** Types de caisse dont la table est publiée. */
    private static final SuggestionProvider<CommandSourceStack> PUBLISHED_KINDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(CrateRegistry.all().keySet().stream().map(CrateKind::id), builder);
    private static final SuggestionProvider<CommandSourceStack> ALL_KINDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(CrateKind.values()).map(CrateKind::id), builder);

    private RewardCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("terminal").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            ctx.getSource().sendSuccess(() -> TerminalManager.status(player, TimeUtil.now()), false);
            return 1;
        }));
        dispatcher.register(Commands.literal("daily")
                .executes(ctx -> CommandUtil.send(ctx.getSource(), DailyManager.claim(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("info").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> DailyManager.info(player, TimeUtil.now()), false);
                    return 1;
                })));
        dispatcher.register(Commands.literal("crate")
                .then(Commands.literal("pity").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> CrateManager.pityReport(player.server, player.getUUID()), false);
                    return 1;
                }))
                .then(Commands.literal("preview")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests(PUBLISHED_KINDS)
                                .executes(RewardCommands::preview))));
    }

    /**
     * Sous-commandes {@code terminal}, {@code daily} et {@code crate} de {@code /sfadmin}, à brancher sur la racine
     * existante. Chacune répète l'exigence d'opérateur (niveau 2).
     */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("terminal")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("reset")
                        .then(Commands.argument("joueur", StringArgumentType.word())
                                .suggests(CommandUtil.KNOWN_ACCOUNTS)
                                .executes(RewardCommands::terminalReset)))
                .then(Commands.literal("give")
                        .then(Commands.argument("joueur", StringArgumentType.word())
                                .suggests(CommandUtil.KNOWN_ACCOUNTS)
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
                                        .executes(RewardCommands::terminalGive)))));
        root.then(Commands.literal("daily")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("reset")
                        .then(Commands.argument("joueur", StringArgumentType.word())
                                .suggests(CommandUtil.KNOWN_ACCOUNTS)
                                .executes(RewardCommands::dailyReset)))
                .then(Commands.literal("streak")
                        .then(Commands.argument("joueur", StringArgumentType.word())
                                .suggests(CommandUtil.KNOWN_ACCOUNTS)
                                .then(Commands.argument("n", IntegerArgumentType.integer(0, 10000))
                                        .executes(RewardCommands::dailyStreak)))));
        root.then(Commands.literal("crate")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("givekey")
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests(ALL_KINDS)
                                        .executes(ctx -> giveKey(ctx, 1))
                                        .then(Commands.argument("nombre", IntegerArgumentType.integer(1, 640))
                                                .executes(ctx -> giveKey(ctx, IntegerArgumentType.getInteger(ctx, "nombre")))))))
                .then(Commands.literal("pity")
                        .then(Commands.argument("joueur", StringArgumentType.word())
                                .suggests(CommandUtil.KNOWN_ACCOUNTS)
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests(ALL_KINDS)
                                        .then(Commands.argument("valeur", IntegerArgumentType.integer(0, 1000))
                                                .executes(RewardCommands::cratePity))))));
    }

    // ------------------------------------------------------------------ joueur

    private static int preview(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CrateKind kind = kindArg(ctx);
        if (kind == null) {
            return 0;
        }
        if (CrateRegistry.get(kind) == null) {
            ctx.getSource().sendFailure(Msg.err("reward.crate.error.unavailable", kind.block().getName()));
            return 0;
        }
        CrateManager.sendPreview(player, kind);
        return 1;
    }

    @Nullable
    private static CrateKind kindArg(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "type");
        CrateKind kind = CrateKind.byId(id);
        if (kind == null) {
            ctx.getSource().sendFailure(Msg.err("reward.crate.error.unknown_kind", id));
        }
        return kind;
    }

    // ------------------------------------------------------------------ administration

    /** Compte désigné par l'argument « joueur » (y compris hors ligne), ou {@code null} après l'erreur. */
    @Nullable
    private static UUID account(CommandContext<CommandSourceStack> ctx) {
        return EconomyCommands.resolveAccount(ctx.getSource(), EconomyData.get(ctx.getSource().getServer()),
                StringArgumentType.getString(ctx, "joueur"));
    }

    private static String nameOf(CommandContext<CommandSourceStack> ctx, UUID id) {
        return EconomyData.get(ctx.getSource().getServer()).nameOf(id);
    }

    private static int terminalReset(CommandContext<CommandSourceStack> ctx) {
        UUID id = account(ctx);
        if (id == null) {
            return 0;
        }
        TerminalManager.reset(ctx.getSource().getServer(), id);
        String name = nameOf(ctx, id);
        ctx.getSource().sendSuccess(() -> Msg.ok("terminal.admin.reset", name), true);
        return 1;
    }

    private static int terminalGive(CommandContext<CommandSourceStack> ctx) {
        UUID id = account(ctx);
        if (id == null) {
            return 0;
        }
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(id);
        long pending = TerminalManager.give(ctx.getSource().getServer(), id, minutes, TimeUtil.now(), online);
        String name = nameOf(ctx, id);
        ctx.getSource().sendSuccess(() -> Msg.ok("terminal.admin.given", name, minutes, Msg.money(pending)), true);
        return 1;
    }

    private static int dailyReset(CommandContext<CommandSourceStack> ctx) {
        UUID id = account(ctx);
        if (id == null) {
            return 0;
        }
        int streak = DailyManager.resetCooldown(ctx.getSource().getServer(), id, TimeUtil.now());
        String name = nameOf(ctx, id);
        ctx.getSource().sendSuccess(() -> Msg.ok("reward.admin.daily_reset", name, streak), true);
        return 1;
    }

    private static int dailyStreak(CommandContext<CommandSourceStack> ctx) {
        UUID id = account(ctx);
        if (id == null) {
            return 0;
        }
        int n = IntegerArgumentType.getInteger(ctx, "n");
        DailyManager.setStreak(ctx.getSource().getServer(), id, n, TimeUtil.now());
        String name = nameOf(ctx, id);
        ctx.getSource().sendSuccess(() -> Msg.ok("reward.admin.daily_streak", name, n), true);
        return 1;
    }

    private static int giveKey(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "joueur");
        CrateKind kind = kindArg(ctx);
        if (kind == null) {
            return 0;
        }
        CrateKeys.give(target, kind, count);
        Component key = kind.keyItem().getDescription();
        ctx.getSource().sendSuccess(() -> Msg.ok("reward.admin.keys_given", count, key, target.getDisplayName()), true);
        return 1;
    }

    private static int cratePity(CommandContext<CommandSourceStack> ctx) {
        UUID id = account(ctx);
        CrateKind kind = id == null ? null : kindArg(ctx);
        if (kind == null) {
            return 0;
        }
        int value = IntegerArgumentType.getInteger(ctx, "valeur");
        int entries = CrateManager.setPity(ctx.getSource().getServer(), id, kind, value);
        if (entries < 0) {
            ctx.getSource().sendFailure(Msg.err("reward.crate.error.unavailable", kind.block().getName()));
            return 0;
        }
        String name = nameOf(ctx, id);
        ctx.getSource().sendSuccess(() -> Msg.ok("reward.admin.pity_set", kind.block().getName(), name, value, entries), true);
        return 1;
    }
}
