package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code /balance [joueur]}, {@code /pay <joueur> <montant>}, {@code /baltop}. */
public final class EconomyCommands {
    private EconomyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("balance")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    long balance = EconomyData.get(player.server).balance(player.getUUID());
                    ctx.getSource().sendSuccess(() -> Msg.info("money.balance_self", Msg.money(balance)), false);
                    return 1;
                })
                .then(Commands.argument("joueur", StringArgumentType.word())
                        .suggests(CommandUtil.KNOWN_ACCOUNTS)
                        .executes(EconomyCommands::balanceOther)));
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("joueur", StringArgumentType.word())
                        .suggests(CommandUtil.KNOWN_ACCOUNTS)
                        .then(Commands.argument("montant", LongArgumentType.longArg(1))
                                .executes(EconomyCommands::pay))));
        dispatcher.register(Commands.literal("baltop").executes(EconomyCommands::baltop));
    }

    private static int balanceOther(CommandContext<CommandSourceStack> ctx) {
        EconomyData eco = EconomyData.get(ctx.getSource().getServer());
        String name = StringArgumentType.getString(ctx, "joueur");
        UUID id = eco.findByName(name);
        if (id == null) {
            ctx.getSource().sendFailure(Msg.err("money.error.unknown_account", name));
            return 0;
        }
        long balance = eco.balance(id);
        ctx.getSource().sendSuccess(() -> Msg.info("money.balance_other", eco.nameOf(id), Msg.money(balance)), false);
        return 1;
    }

    private static int pay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer payer = ctx.getSource().getPlayerOrException();
        EconomyData eco = EconomyData.get(payer.server);
        String name = StringArgumentType.getString(ctx, "joueur");
        long amount = LongArgumentType.getLong(ctx, "montant");
        UUID target = eco.findByName(name);
        if (target == null) {
            ctx.getSource().sendFailure(Msg.err("money.error.unknown_account", name));
            return 0;
        }
        if (target.equals(payer.getUUID())) {
            ctx.getSource().sendFailure(Msg.err("money.error.pay_self"));
            return 0;
        }
        if (!eco.trySpend(payer.getUUID(), amount)) {
            ctx.getSource().sendFailure(Msg.err("money.error.insufficient", Money.format(eco.balance(payer.getUUID()))));
            return 0;
        }
        eco.add(target, amount);
        ctx.getSource().sendSuccess(() -> Msg.ok("money.paid", Money.format(amount), eco.nameOf(target)), false);
        ServerPlayer receiver = payer.server.getPlayerList().getPlayer(target);
        if (receiver != null) {
            receiver.sendSystemMessage(Msg.ok("money.received", Money.format(amount), payer.getDisplayName()));
            NationSync.scheduleRefresh(receiver);
        }
        return 1;
    }

    private static int baltop(CommandContext<CommandSourceStack> ctx) {
        EconomyData eco = EconomyData.get(ctx.getSource().getServer());
        List<Map.Entry<UUID, Long>> top = eco.top(10);
        MutableComponent c = Msg.tr("money.baltop.header").withStyle(ChatFormatting.AQUA);
        int rank = 1;
        for (Map.Entry<UUID, Long> e : top) {
            c.append("\n").append(Component.literal(rank++ + ". ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(eco.nameOf(e.getKey()) + " ").withStyle(ChatFormatting.WHITE))
                    .append(Msg.money(e.getValue()));
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return top.size();
    }
}
