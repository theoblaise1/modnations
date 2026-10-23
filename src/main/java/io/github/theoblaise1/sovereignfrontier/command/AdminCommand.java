package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.nation.AdminBypass;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationAdmin;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.research.NationResearch;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.war.WarCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** {@code /sfadmin} (opérateurs, niveau 2) : outils de test et de modération. */
public final class AdminCommand {
    private AdminCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("sfadmin")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("money")
                        .then(moneyOp("give", 1))
                        .then(moneyOp("take", -1))
                        .then(moneyOp("set", 0)))
                .then(Commands.literal("treasury")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .suggests(CommandUtil.NATIONS)
                                .then(Commands.argument("montant", LongArgumentType.longArg())
                                        .executes(AdminCommand::treasury))))
                .then(Commands.literal("research")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .suggests(CommandUtil.NATIONS)
                                .then(Commands.literal("complete")
                                        .then(Commands.argument("palier", StringArgumentType.string())
                                                .suggests(ResearchCommand.ALL_TIERS)
                                                .executes(AdminCommand::researchComplete)))
                                .then(Commands.literal("finish").executes(AdminCommand::researchFinish))
                                .then(Commands.literal("points")
                                        .then(Commands.argument("montant", LongArgumentType.longArg(0))
                                                .executes(AdminCommand::researchPoints)))))
                .then(Commands.literal("bypass").executes(ctx -> {
                    boolean on = AdminBypass.toggle(ctx.getSource().getPlayerOrException());
                    ctx.getSource().sendSuccess(() -> Msg.warn(on ? "admin.bypass_on" : "admin.bypass_off"), true);
                    return 1;
                }));
        TradeCommand.registerAdmin(root);
        RewardCommands.registerAdmin(root);
        EnterpriseCommand.registerAdmin(root);
        ContractCommand.registerAdmin(root);
        SpaceCommand.registerAdmin(root);
        RocketCommand.registerAdmin(root);
        EnvironmentCommand.registerAdmin(root);
        WarCommands.registerAdmin(root);
        dispatcher.register(root);
    }

    /** {@code sign} : 1 = ajouter, -1 = retirer, 0 = fixer. */
    private static LiteralArgumentBuilder<CommandSourceStack> moneyOp(String literal, int sign) {
        return Commands.literal(literal)
                .then(Commands.argument("joueur", StringArgumentType.word())
                        .suggests(CommandUtil.KNOWN_ACCOUNTS)
                        .then(Commands.argument("montant", LongArgumentType.longArg(0))
                                .executes(ctx -> money(ctx, sign))));
    }

    private static int money(CommandContext<CommandSourceStack> ctx, int sign) {
        EconomyData eco = EconomyData.get(ctx.getSource().getServer());
        String name = StringArgumentType.getString(ctx, "joueur");
        long amount = LongArgumentType.getLong(ctx, "montant");
        UUID id = EconomyCommands.resolveAccount(ctx.getSource(), eco, name);
        if (id == null) {
            return 0;
        }
        switch (sign) {
            case 1 -> eco.add(id, amount);
            case -1 -> eco.add(id, -amount);
            default -> eco.set(id, amount);
        }
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(id);
        if (online != null) {
            NationSync.scheduleRefresh(online);
        }
        long balance = eco.balance(id);
        ctx.getSource().sendSuccess(() -> Msg.ok("admin.money_set", eco.nameOf(id), Money.format(balance)), true);
        return 1;
    }

    private static Nation nationArg(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "nation");
        Nation nation = NationData.get(ctx.getSource().getServer()).byName(name);
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.unknown", name));
        }
        return nation;
    }

    private static int researchComplete(CommandContext<CommandSourceStack> ctx) {
        Nation nation = nationArg(ctx);
        ResearchTier tier = nation == null ? null : ResearchCommand.resolve(ctx);
        if (tier == null) {
            return 0;
        }
        ResearchManager.adminComplete(ctx.getSource().getServer(), nation, tier);
        ctx.getSource().sendSuccess(() -> Msg.ok("admin.research_completed", tier.displayName(), nation.name()), true);
        return 1;
    }

    private static int researchFinish(CommandContext<CommandSourceStack> ctx) {
        Nation nation = nationArg(ctx);
        if (nation == null) {
            return 0;
        }
        NationResearch.Active active = nation.research().active();
        if (active == null) {
            ctx.getSource().sendFailure(Msg.err("research.error.no_active"));
            return 0;
        }
        ResearchManager.finish(ctx.getSource().getServer(), nation, active.id());
        ctx.getSource().sendSuccess(() -> Msg.ok("admin.research_finished", nation.name()), true);
        return 1;
    }

    private static int researchPoints(CommandContext<CommandSourceStack> ctx) {
        Nation nation = nationArg(ctx);
        if (nation == null) {
            return 0;
        }
        long points = LongArgumentType.getLong(ctx, "montant");
        ResearchManager.adminSetPoints(ctx.getSource().getServer(), nation, points);
        ctx.getSource().sendSuccess(() -> Msg.ok("admin.research_points", nation.name(), points), true);
        return 1;
    }

    private static int treasury(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "nation");
        long amount = LongArgumentType.getLong(ctx, "montant");
        Nation nation = NationData.get(ctx.getSource().getServer()).byName(name);
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.unknown", name));
            return 0;
        }
        NationAdmin.adjustTreasury(ctx.getSource().getServer(), nation, amount);
        ctx.getSource().sendSuccess(() -> Msg.ok("admin.treasury_set", nation.name(), Money.format(nation.treasury())), true);
        return 1;
    }
}
