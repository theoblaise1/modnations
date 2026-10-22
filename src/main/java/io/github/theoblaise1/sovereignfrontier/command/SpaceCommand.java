package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.space.SpaceRules;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /sfadmin space info|rescue <joueur>} (docs/conception/J5.md §3.4), opérateurs seulement :
 * <ul>
 *   <li>{@code info} : une ligne par planète publiée (dimension présente ou absente, joueurs présents, rayon, clé
 *       d'extraction) ;</li>
 *   <li>{@code rescue <joueur>} : ramène le joueur sur Terre (départ de sa dernière fusée, sinon Trésor ou coffre
 *       de sa nation, sinon point d'apparition du monde), sans condition de recherche.</li>
 * </ul>
 */
public final class SpaceCommand {
    private SpaceCommand() {
    }

    /** Greffe la sous-commande {@code space} sur l'arbre {@code /sfadmin}. */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("space")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(SpaceCommand::info))
                .then(Commands.literal("rescue")
                        .then(Commands.argument("joueur", EntityArgument.player()).executes(SpaceCommand::rescue))));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        List<PlanetDefinition> planets = PlanetRegistry.get().all();
        if (planets.isEmpty()) {
            source.sendSuccess(() -> Msg.info("space.info.none"), false);
            return 0;
        }
        MutableComponent text = Msg.tr("space.info.header", planets.size()).withStyle(ChatFormatting.AQUA);
        for (PlanetDefinition planet : planets) {
            boolean present = server.getLevel(planet.dimension()) != null;
            long players = server.getPlayerList().getPlayers().stream()
                    .filter(p -> p.level().dimension().equals(planet.dimension())).count();
            Component state = present ? Msg.ok("space.info.present") : Msg.err("space.info.absent");
            Component mining = planet.mining().isPresent()
                    ? SpaceRules.unlockName(planet.mining().get().unlock()) : Msg.info("space.info.no_mining");
            text.append("\n ").append(Msg.tr("space.info.line", planet.displayName(), planet.dimension().location().toString(),
                    state, players, planet.radius(), mining).withStyle(ChatFormatting.GRAY));
        }
        source.sendSuccess(() -> text, false);
        return planets.size();
    }

    private static int rescue(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "joueur");
        SpaceRules.RescueSite site = SpaceRules.rescue(target);
        Component where = switch (site.target().source()) {
            case HOME -> Msg.tr("space.rescue.home");
            case CORE -> Msg.tr("space.rescue.core");
            case SPAWN -> Msg.tr("space.rescue.spawn");
        };
        target.sendSystemMessage(Msg.warn("space.rescue.player"));
        ctx.getSource().sendSuccess(() -> Msg.ok("space.rescue.done", target.getDisplayName(), where), true);
        return 1;
    }
}
