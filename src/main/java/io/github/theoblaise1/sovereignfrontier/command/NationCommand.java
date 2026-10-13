package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.LogEntry;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.network.NationTab;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

import static io.github.theoblaise1.sovereignfrontier.command.CommandUtil.send;

/**
 * {@code /nation} : create, invite, join, kick, leave, transfer, disband, info, list, log,
 * role, perm, perms, gui.
 */
public final class NationCommand {
    private NationCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nation")
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("create")
                        .then(Commands.argument("nom", StringArgumentType.word())
                                .executes(ctx -> send(ctx.getSource(), NationManager.create(
                                        ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "nom"))))))
                .then(Commands.literal("invite")
                        .then(Commands.argument("joueur", StringArgumentType.word())
                                .suggests(CommandUtil.PLAYERS_WITHOUT_NATION)
                                .executes(NationCommand::invite)))
                .then(Commands.literal("join")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .suggests(CommandUtil.MY_INVITATIONS)
                                .executes(ctx -> send(ctx.getSource(), NationManager.join(
                                        ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "nation"))))))
                .then(Commands.literal("kick")
                        .then(Commands.argument("membre", StringArgumentType.word())
                                .suggests(CommandUtil.MEMBERS_BELOW_ME)
                                .executes(ctx -> send(ctx.getSource(), NationManager.kick(
                                        ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "membre"))))))
                .then(Commands.literal("leave")
                        .executes(ctx -> send(ctx.getSource(), NationManager.leave(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("transfer")
                        .then(Commands.argument("membre", StringArgumentType.word())
                                .suggests(CommandUtil.OTHER_MEMBERS)
                                .executes(ctx -> send(ctx.getSource(), NationManager.transfer(
                                        ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "membre"))))))
                .then(Commands.literal("disband")
                        .executes(ctx -> {
                            ctx.getSource().sendFailure(Msg.warn("nation.disband.confirm_hint"));
                            return 0;
                        })
                        .then(Commands.literal("confirm")
                                .executes(ctx -> send(ctx.getSource(), NationManager.disband(ctx.getSource().getPlayerOrException())))))
                .then(Commands.literal("info")
                        .executes(NationCommand::infoOwn)
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .suggests(CommandUtil.NATIONS)
                                .executes(NationCommand::infoOther)))
                .then(Commands.literal("list").executes(NationCommand::list))
                .then(Commands.literal("log").executes(NationCommand::log))
                .then(Commands.literal("role")
                        .then(Commands.argument("membre", StringArgumentType.word())
                                .suggests(CommandUtil.OTHER_MEMBERS)
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .suggests(CommandUtil.ASSIGNABLE_ROLES)
                                        .executes(NationCommand::role))))
                .then(Commands.literal("perm")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(CommandUtil.ASSIGNABLE_ROLES)
                                .then(Commands.argument("permission", StringArgumentType.word())
                                        .suggests(CommandUtil.PERMISSIONS)
                                        .then(Commands.argument("valeur", StringArgumentType.word())
                                                .suggests(CommandUtil.YES_NO)
                                                .executes(NationCommand::perm)))))
                .then(Commands.literal("perms").executes(NationCommand::perms))
                .then(Commands.literal("gui").executes(ctx -> {
                    NationSync.openScreen(ctx.getSource().getPlayerOrException(), NationTab.OVERVIEW);
                    return 1;
                })));
    }

    private static int help(CommandSourceStack source) {
        MutableComponent c = Component.literal("=== /nation ===").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        for (String sub : List.of("create", "invite", "join", "kick", "leave", "transfer", "disband", "info", "list",
                "log", "role", "perm", "perms", "gui")) {
            c.append("\n").append(Component.literal("/nation " + sub + " ").withStyle(ChatFormatting.YELLOW))
                    .append(Msg.info("help.nation." + sub));
        }
        source.sendSuccess(() -> c, false);
        return 1;
    }

    private static int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer actor = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "joueur");
        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            ctx.getSource().sendFailure(Msg.err("error.player_offline", name));
            return 0;
        }
        return send(ctx.getSource(), NationManager.invite(actor, target));
    }

    private static int infoOwn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Nation nation = NationData.get(ctx.getSource().getServer()).nationOf(player.getUUID());
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.no_nation_hint"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> NationManager.describe(ctx.getSource().getServer(), nation), false);
        return 1;
    }

    private static int infoOther(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "nation");
        Nation nation = NationData.get(ctx.getSource().getServer()).byName(name);
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.unknown", name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> NationManager.describe(ctx.getSource().getServer(), nation), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        NationData data = NationData.get(ctx.getSource().getServer());
        if (data.nations().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Msg.info("nation.list.empty"), false);
            return 1;
        }
        MutableComponent c = Msg.tr("nation.list.header", data.nations().size()).withStyle(ChatFormatting.AQUA);
        for (Nation n : data.nations()) {
            c.append("\n - ").append(Component.literal(n.name()).withColor(n.color()))
                    .append(Msg.info("nation.list.entry", n.memberCount(), n.claimCount(), n.population()));
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return data.nations().size();
    }

    private static int log(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Nation nation = NationData.get(ctx.getSource().getServer()).nationOf(player.getUUID());
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.no_nation"));
            return 0;
        }
        List<LogEntry> entries = nation.logEntries();
        MutableComponent c = Msg.tr("nation.log.header", nation.name(), entries.size(), Nation.LOG_SIZE).withStyle(ChatFormatting.AQUA);
        for (int i = entries.size() - 1; i >= 0; i--) {
            LogEntry e = entries.get(i);
            c.append("\n").append(Component.literal("[" + TimeUtil.shortStamp(e.epochMs()) + "] ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(e.toComponent().withStyle(ChatFormatting.GRAY));
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return entries.size();
    }

    private static int role(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String roleId = StringArgumentType.getString(ctx, "role");
        Optional<NationRole> role = NationRole.byId(roleId);
        if (role.isEmpty()) {
            ctx.getSource().sendFailure(Msg.err("error.unknown_role", roleId));
            return 0;
        }
        return send(ctx.getSource(), NationManager.setRole(ctx.getSource().getPlayerOrException(),
                StringArgumentType.getString(ctx, "membre"), role.get()));
    }

    private static int perm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String roleId = StringArgumentType.getString(ctx, "role");
        String permId = StringArgumentType.getString(ctx, "permission");
        String value = StringArgumentType.getString(ctx, "valeur");
        Optional<NationRole> role = NationRole.byId(roleId);
        Optional<NationPermission> permission = NationPermission.byId(permId);
        if (role.isEmpty()) {
            ctx.getSource().sendFailure(Msg.err("error.unknown_role", roleId));
            return 0;
        }
        if (permission.isEmpty()) {
            ctx.getSource().sendFailure(Msg.err("error.unknown_permission", permId));
            return 0;
        }
        boolean granted;
        if (value.equalsIgnoreCase("oui")) {
            granted = true;
        } else if (value.equalsIgnoreCase("non")) {
            granted = false;
        } else {
            ctx.getSource().sendFailure(Msg.err("error.yes_no", value));
            return 0;
        }
        ActionResult result = NationManager.setPermission(ctx.getSource().getPlayerOrException(), role.get(), permission.get(), granted);
        return send(ctx.getSource(), result);
    }

    private static int perms(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Nation nation = NationData.get(ctx.getSource().getServer()).nationOf(player.getUUID());
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.no_nation"));
            return 0;
        }
        MutableComponent c = Msg.tr("nation.perms.header").withStyle(ChatFormatting.AQUA);
        for (NationPermission p : NationPermission.values()) {
            c.append("\n").append(Component.literal(String.format("%-16s", p.id())).withStyle(ChatFormatting.YELLOW));
            for (NationRole r : NationRole.values()) {
                boolean has = nation.roleHas(r, p);
                c.append(Component.literal(" " + (has ? "✔" : "✘") + r.id().substring(0, 3))
                        .withStyle(has ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }
}
