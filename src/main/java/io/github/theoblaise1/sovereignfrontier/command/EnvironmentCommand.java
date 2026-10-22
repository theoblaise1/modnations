// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.space.Environment;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /oxygen} (état spatial du joueur : planète, oxygène, froid, zones, point de retour) et
 * {@code /sfadmin oxygen|cold <joueur> <secondes>} (docs/conception/J5.md §5.1), ces deux dernières greffées par
 * {@link AdminCommand} grâce à {@link #registerAdmin}. Les réglages de test fixent la réserve d'oxygène ou le compteur
 * de froid du joueur visé ; ils restent sous le préfixe du lot (« oxygen.* », « cold.* »).
 */
public final class EnvironmentCommand {
    /** Borne des valeurs que les commandes d'administration peuvent écrire (secondes). */
    private static final int MAX_SECONDS = 3600;

    private EnvironmentCommand() {
    }

    /** {@code /oxygen} : état complet du joueur. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oxygen").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            for (Component line : Environment.status(player, TimeUtil.now())) {
                ctx.getSource().sendSuccess(() -> line, false);
            }
            return 1;
        }));
    }

    /** Greffe les sous-commandes {@code oxygen} et {@code cold} sur l'arbre {@code /sfadmin}. */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("oxygen")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("joueur", EntityArgument.player())
                        .then(Commands.argument("secondes", IntegerArgumentType.integer(0, MAX_SECONDS))
                                .executes(EnvironmentCommand::setOxygen))));
        root.then(Commands.literal("cold")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("joueur", EntityArgument.player())
                        .then(Commands.argument("secondes", IntegerArgumentType.integer(0, MAX_SECONDS))
                                .executes(EnvironmentCommand::setCold))));
    }

    private static int setOxygen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "joueur");
        int seconds = IntegerArgumentType.getInteger(ctx, "secondes");
        target.setData(ModAttachments.SPACE, target.getData(ModAttachments.SPACE).withOxygen(seconds));
        ctx.getSource().sendSuccess(() -> Msg.ok("oxygen.admin.set", target.getName(), seconds), true);
        return 1;
    }

    private static int setCold(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "joueur");
        int seconds = IntegerArgumentType.getInteger(ctx, "secondes");
        target.setData(ModAttachments.SPACE, target.getData(ModAttachments.SPACE).withCold(seconds));
        ctx.getSource().sendSuccess(() -> Msg.ok("cold.admin.set", target.getName(), seconds), true);
        return 1;
    }
}
