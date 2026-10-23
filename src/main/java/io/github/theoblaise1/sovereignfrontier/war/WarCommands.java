package io.github.theoblaise1.sovereignfrontier.war;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sous-arbres {@code /nation war} et {@code /nation treaty} (J7, docs/conception/J7.md §3). Branchés à la racine
 * {@code /nation} par {@code NationCommand}. Les effets et les messages des nations sont dans {@link WarTransitions} ;
 * ici : permissions, recherche des nations, verdicts affichés au joueur.
 */
public final class WarCommands {
    private static final SuggestionProvider<CommandSourceStack> NATIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(NationData.get(ctx.getSource().getServer()).nations().stream()
                    .map(Nation::name), builder);

    private WarCommands() {
    }

    /** {@code /nation war declare <nation>} (permission {@code declarer_guerre}), {@code cancel} (chef), {@code status}. */
    public static LiteralArgumentBuilder<CommandSourceStack> war() {
        return Commands.literal("war")
                .then(Commands.literal("declare")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .suggests(NATIONS)
                                .executes(WarCommands::declare)))
                .then(Commands.literal("cancel").executes(WarCommands::cancel))
                .then(Commands.literal("status").executes(WarCommands::status));
    }

    /**
     * {@code /nation treaty propose <nation>} (permission {@code signer_traite}), {@code accept [<nation>]} et
     * {@code refuse [<nation>]} (même permission, côté destinataire). Sans nation : la proposition en attente est
     * acceptée ou refusée si elle est unique.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> treaty() {
        return Commands.literal("treaty")
                .then(Commands.literal("propose")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .suggests(NATIONS)
                                .executes(WarCommands::propose)))
                .then(Commands.literal("accept")
                        .executes(ctx -> answer(ctx, null, true))
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .suggests(NATIONS)
                                .executes(ctx -> answer(ctx, StringArgumentType.getString(ctx, "nation"), true))))
                .then(Commands.literal("refuse")
                        .executes(ctx -> answer(ctx, null, false))
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .suggests(NATIONS)
                                .executes(ctx -> answer(ctx, StringArgumentType.getString(ctx, "nation"), false))));
    }

    // ------------------------------------------------------------------ guerre

    private static int declare(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayerOrException();
        NationData nations = NationData.get(server);
        Nation attacker = nations.nationOf(player.getUUID());
        if (attacker == null) {
            return fail(source, Msg.err("nation.error.no_nation"));
        }
        if (!attacker.hasPermission(player.getUUID(), NationPermission.DECLARE_WAR)) {
            return fail(source, noPermission(NationPermission.DECLARE_WAR));
        }
        String name = StringArgumentType.getString(ctx, "nation");
        Nation defender = nations.byName(name);
        if (defender == null) {
            return fail(source, Msg.err("nation.error.unknown", name));
        }
        int minimum = SFConfig.MIN_MEMBERS_TO_DECLARE.get();
        return switch (WarTransitions.declare(server, attacker, defender)) {
            case OK -> 1;
            case SAME_NATION -> fail(source, Msg.err("war.error.same_nation"));
            case ATTACKER_TOO_SMALL -> fail(source, Msg.err("war.error.attacker_too_small", minimum));
            case DEFENDER_TOO_SMALL -> fail(source, Msg.err("war.error.defender_too_small", defender.name(), minimum));
            case ENGAGED -> fail(source, Msg.err("war.error.engaged", defender.name()));
            case TRUCE -> fail(source, Msg.err("war.error.truce", defender.name(),
                    TimeUtil.duration(truceLeft(server, attacker.id(), defender.id()))));
        };
    }

    /** Annule les préparations déclarées par ma nation (chef seulement). */
    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayerOrException();
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        if (nation == null) {
            return fail(source, Msg.err("nation.error.no_nation"));
        }
        if (nation.roleOf(player.getUUID()) != NationRole.CHEF) {
            return fail(source, Msg.err("nation.error.leader_only"));
        }
        int cancelled = 0;
        // Copie : l'annulation retire la relation parcourue.
        for (Map.Entry<WarData.RelationKey, WarData.Relation> entry : Map.copyOf(WarData.get(server).relations()).entrySet()) {
            WarData.Relation relation = entry.getValue();
            if (relation.state() == WarState.PREPARATION && nation.id().equals(relation.declarerNationId())) {
                WarTransitions.cancelPreparation(server, entry.getKey());
                cancelled++;
            }
        }
        if (cancelled == 0) {
            return fail(source, Msg.err("war.error.not_preparing"));
        }
        return cancelled;
    }

    /** État des guerres et trêves de ma nation : une ligne par relation hors paix. */
    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayerOrException();
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        if (nation == null) {
            return fail(source, Msg.err("nation.error.no_nation"));
        }
        NationData nations = NationData.get(server);
        int killsToWin = SFConfig.KILLS_TO_WIN.get();
        MutableComponent out = Component.empty();
        boolean found = false;
        for (Map.Entry<WarData.RelationKey, WarData.Relation> entry : WarData.get(server).relations().entrySet()) {
            WarData.RelationKey key = entry.getKey();
            WarData.Relation relation = entry.getValue();
            if (!key.low().equals(nation.id()) && !key.high().equals(nation.id())) {
                continue;
            }
            UUID otherId = key.low().equals(nation.id()) ? key.high() : key.low();
            Nation other = nations.byId(otherId);
            if (other == null) {
                continue;
            }
            if (found) {
                out.append("\n");
            }
            found = true;
            out.append(Msg.info("war.status.line", other.name(), Msg.tr(stateKey(relation.state()))));
            if (relation.state() == WarState.GUERRE) {
                out.append("\n  ").append(Msg.info("war.status.score", relation.scoreOf(key, nation.id()),
                        relation.scoreOf(key, otherId), killsToWin));
            }
            out.append("\n  ").append(Msg.info("war.status.until", TimeUtil.shortStamp(relation.untilEpochMs())));
        }
        final Component message = found ? out : Msg.info("war.status.empty");
        source.sendSuccess(() -> message, false);
        return found ? 1 : 0;
    }

    // ------------------------------------------------------------------ traités

    private static int propose(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayerOrException();
        NationData nations = NationData.get(server);
        Nation proposer = nations.nationOf(player.getUUID());
        if (proposer == null) {
            return fail(source, Msg.err("nation.error.no_nation"));
        }
        if (!proposer.hasPermission(player.getUUID(), NationPermission.SIGN_TREATY)) {
            return fail(source, noPermission(NationPermission.SIGN_TREATY));
        }
        String name = StringArgumentType.getString(ctx, "nation");
        Nation receiver = nations.byName(name);
        if (receiver == null) {
            return fail(source, Msg.err("nation.error.unknown", name));
        }
        Component error = WarTransitions.propose(server, proposer, receiver);
        return error == null ? 1 : fail(source, error);
    }

    /**
     * Accepte ({@code accept}) ou refuse ({@code refuse}) une proposition reçue. Sans nation : la proposition unique est
     * traitée ; s'il y en a plusieurs, chacune est rappelée et la nation doit être précisée.
     */
    private static int answer(CommandContext<CommandSourceStack> ctx, @Nullable String name, boolean accept)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayerOrException();
        NationData nations = NationData.get(server);
        Nation receiver = nations.nationOf(player.getUUID());
        if (receiver == null) {
            return fail(source, Msg.err("nation.error.no_nation"));
        }
        if (!receiver.hasPermission(player.getUUID(), NationPermission.SIGN_TREATY)) {
            return fail(source, noPermission(NationPermission.SIGN_TREATY));
        }
        Nation proposer;
        if (name != null) {
            proposer = nations.byName(name);
            if (proposer == null) {
                return fail(source, Msg.err("nation.error.unknown", name));
            }
        } else {
            List<WarTransitions.Incoming> incoming = WarTransitions.incoming(server, receiver);
            if (incoming.isEmpty()) {
                return fail(source, Msg.err("treaty.error.none"));
            }
            if (incoming.size() > 1) {
                for (WarTransitions.Incoming item : incoming) {
                    source.sendFailure(Msg.warn("treaty.proposed", item.proposer().name(), receiver.name(),
                            TimeUtil.duration(item.proposal().expiresEpochMs() - TimeUtil.now())));
                }
                return 0;
            }
            proposer = incoming.get(0).proposer();
        }
        Component error = accept
                ? WarTransitions.accept(server, receiver, proposer)
                : WarTransitions.refuse(server, receiver, proposer);
        return error == null ? 1 : fail(source, error);
    }

    // ------------------------------------------------------------------ aides

    private static long truceLeft(MinecraftServer server, UUID attacker, UUID defender) {
        WarData.Relation relation = WarData.get(server).relation(attacker, defender);
        return relation == null ? 0L : relation.untilEpochMs() - TimeUtil.now();
    }

    private static String stateKey(WarState state) {
        return switch (state) {
            case PAIX -> "war.state.paix";
            case PREPARATION -> "war.state.preparation";
            case GUERRE -> "war.state.guerre";
            case TREVE -> "war.state.treve";
        };
    }

    private static Component noPermission(NationPermission permission) {
        return Msg.err("nation.error.no_permission", permission.displayName());
    }

    private static int fail(CommandSourceStack source, Component message) {
        source.sendFailure(message);
        return 0;
    }
}
