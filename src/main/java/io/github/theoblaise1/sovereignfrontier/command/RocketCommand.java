// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.rocket.FlightPlanner;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketBlockEntity;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketFlight;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketSheet;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;

import static io.github.theoblaise1.sovereignfrontier.command.CommandUtil.send;

/**
 * {@code /rocket} et {@code /sfadmin rocket} (docs/conception/J5.md §4.7). Les commandes du joueur s'appliquent à la
 * fusée de sa <b>session</b> (clic droit accroupi, 60 s) et revalident côté serveur la session, la distance (6 blocs au
 * plus), la permission et la présence du bloc : aucun paquet nouveau, rien de falsifiable.
 * <pre>
 * /rocket                          fiche de la fusée de session
 * /rocket launch &lt;destination&gt;     lune | mars | planete_glacee | terre (suggestions : destinations permises)
 * /rocket board                    embarquer (membre, 4 blocs au plus, compte à rebours en cours)
 * /rocket abort                    annuler le décollage (pilote)
 * /sfadmin rocket info             données de la fusée de session
 * /sfadmin rocket cooldown &lt;joueur&gt; reset
 * </pre>
 * {@link #registerAdmin} est appelée par {@link AdminCommand}.
 */
public final class RocketCommand {
    private RocketCommand() {
    }

    /** Destinations permises depuis la dimension du joueur, avec le nom affiché en infobulle. */
    static final SuggestionProvider<CommandSourceStack> DESTINATIONS = (ctx, builder) -> {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            String typed = builder.getRemaining();
            for (FlightPlanner.Route route : FlightPlanner.routesFrom(player.level().dimension(), PlanetRegistry.get())) {
                if (route.destinationName().startsWith(typed)) {
                    builder.suggest(route.destinationName(), route.destinationDisplay());
                }
            }
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rocket")
                .executes(RocketCommand::sheet)
                .then(Commands.literal("launch")
                        .then(Commands.argument("destination", StringArgumentType.word())
                                .suggests(DESTINATIONS)
                                .executes(ctx -> send(ctx.getSource(), RocketFlight.launchFromSession(
                                        ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "destination"),
                                        TimeUtil.now())))))
                .then(Commands.literal("board")
                        .executes(ctx -> send(ctx.getSource(), RocketFlight.board(ctx.getSource().getPlayerOrException(),
                                TimeUtil.now()))))
                .then(Commands.literal("abort")
                        .executes(ctx -> send(ctx.getSource(), RocketFlight.abortBy(ctx.getSource().getPlayerOrException())))));
    }

    private static int sheet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long now = TimeUtil.now();
        RocketFlight.Located located = RocketFlight.locate(player, now, SFConfig.ROCKET_PILOT_LEASH.get());
        if (!located.ok()) {
            ctx.getSource().sendFailure(located.error());
            return 0;
        }
        RocketSheet.show(player, located.level(), located.pos(), located.rocket(), now);
        return 1;
    }

    /**
     * Sous-commande {@code rocket info | cooldown <joueur> reset} de {@code /sfadmin} (opérateurs). L'exigence de
     * permission est répétée sur la sous-commande : Brigadier fusionne les nœuds de même nom.
     */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("rocket")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(RocketCommand::adminInfo))
                .then(Commands.literal("cooldown")
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .then(Commands.literal("reset").executes(RocketCommand::adminCooldownReset)))));
    }

    private static int adminCooldownReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "joueur");
        target.setData(ModAttachments.SPACE.get(), target.getData(ModAttachments.SPACE.get()).withNextFlight(0L));
        ctx.getSource().sendSuccess(() -> Msg.ok("rocket.admin.cooldown_reset", target.getGameProfile().getName()), true);
        return 1;
    }

    private static int adminInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        RocketFlight.Located located = RocketFlight.locate(player, TimeUtil.now(), 0.0D);
        if (!located.ok()) {
            ctx.getSource().sendFailure(located.error());
            return 0;
        }
        ServerLevel level = located.level();
        BlockPos pos = located.pos();
        RocketBlockEntity rocket = located.rocket();
        CommandSourceStack source = ctx.getSource();
        Nation nation = rocket.owner() == null ? null : NationData.get(source.getServer()).byId(rocket.owner());
        source.sendSuccess(() -> Msg.ok("rocket.admin.info", level.dimension().location().toString(), pos.getX(), pos.getY(),
                pos.getZ()), false);
        source.sendSuccess(() -> Msg.info("rocket.admin.owner", rocket.owner() == null ? "-" : rocket.owner().toString(),
                nation == null ? Msg.tr("rocket.sheet.no_owner") : Component.literal(nation.name())), false);
        source.sendSuccess(() -> Msg.info("rocket.admin.home", rocket.home() == null ? "-"
                : rocket.home().dimension().location() + " " + rocket.home().pos().toShortString()), false);
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Long> pad : rocket.pads().entrySet()) {
            source.sendSuccess(() -> Msg.info("rocket.admin.pad", pad.getKey().location().toString(),
                    BlockPos.of(pad.getValue()).toShortString()), false);
        }
        for (ItemStack stack : rocket.hold().copyStacks()) {
            source.sendSuccess(() -> Msg.info("rocket.admin.item", stack.getCount(), stack.getHoverName()), false);
        }
        RocketFlight.Countdown countdown = RocketFlight.countdownAt(level, pos);
        if (countdown != null) {
            StringBuilder crew = new StringBuilder();
            for (UUID member : countdown.crew()) {
                ServerPlayer p = source.getServer().getPlayerList().getPlayer(member);
                crew.append(crew.length() > 0 ? ", " : "").append(p == null ? member.toString() : p.getGameProfile().getName());
            }
            source.sendSuccess(() -> Msg.warn("rocket.admin.countdown", countdown.elapsed(),
                    SFConfig.ROCKET_COUNTDOWN_TICKS.get(), crew.toString()), false);
        }
        return 1;
    }
}
