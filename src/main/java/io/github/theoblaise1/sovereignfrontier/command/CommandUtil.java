package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Aides communes aux commandes : envoi des résultats et autocomplétion contextuelle. */
final class CommandUtil {
    private CommandUtil() {
    }

    static int send(CommandSourceStack source, ActionResult result) {
        if (result.success()) {
            source.sendSuccess(result::message, false);
            return 1;
        }
        source.sendFailure(result.message());
        return 0;
    }

    /** Joueurs connectés sans nation (cibles d'une invitation). */
    static final SuggestionProvider<CommandSourceStack> PLAYERS_WITHOUT_NATION = (ctx, builder) -> {
        NationData data = NationData.get(ctx.getSource().getServer());
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            if (data.nationOf(p.getUUID()) == null) {
                names.add(p.getGameProfile().getName());
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    /** Toutes les nations existantes. */
    static final SuggestionProvider<CommandSourceStack> NATIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(NationData.get(ctx.getSource().getServer()).nations().stream()
                    .map(Nation::name), builder);

    /** Nations qui ont invité le joueur (invitation non expirée). */
    static final SuggestionProvider<CommandSourceStack> MY_INVITATIONS = (ctx, builder) -> {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return builder.buildFuture();
        }
        long now = TimeUtil.now();
        return SharedSuggestionProvider.suggest(NationData.get(ctx.getSource().getServer()).nations().stream()
                .filter(n -> n.hasValidInvite(player.getUUID(), now)).map(Nation::name), builder);
    };

    /** Membres de ma nation de rang strictement inférieur au mien (cibles d'exclusion). */
    static final SuggestionProvider<CommandSourceStack> MEMBERS_BELOW_ME = (ctx, builder) ->
            SharedSuggestionProvider.suggest(members(ctx, (nation, self) -> {
                NationRole mine = nation.roleOf(self);
                return id -> mine != null && mine.outranks(nation.roleOf(id));
            }), builder);

    /** Membres de ma nation autres que moi. */
    static final SuggestionProvider<CommandSourceStack> OTHER_MEMBERS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(members(ctx, (nation, self) -> id -> !id.equals(self)), builder);

    /** Rôles attribuables par commande (le rôle de chef passe par /nation transfer). */
    static final SuggestionProvider<CommandSourceStack> ASSIGNABLE_ROLES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(java.util.Arrays.stream(NationRole.values())
                    .filter(r -> r != NationRole.CHEF).map(NationRole::id), builder);

    static final SuggestionProvider<CommandSourceStack> PERMISSIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(java.util.Arrays.stream(
                    io.github.theoblaise1.sovereignfrontier.nation.NationPermission.values())
                    .map(io.github.theoblaise1.sovereignfrontier.nation.NationPermission::id), builder);

    static final SuggestionProvider<CommandSourceStack> YES_NO = (ctx, builder) ->
            SharedSuggestionProvider.suggest(List.of("oui", "non"), builder);

    /** Comptes connus (y compris hors ligne) pour /pay et /balance. */
    static final SuggestionProvider<CommandSourceStack> KNOWN_ACCOUNTS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(EconomyData.get(ctx.getSource().getServer()).knownNames(), builder);

    private interface MemberFilter {
        Predicate<UUID> create(Nation nation, UUID self);
    }

    private static List<String> members(CommandContext<CommandSourceStack> ctx, MemberFilter filter) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return List.of();
        }
        Nation nation = NationData.get(ctx.getSource().getServer()).nationOf(player.getUUID());
        if (nation == null) {
            return List.of();
        }
        Predicate<UUID> predicate = filter.create(nation, player.getUUID());
        List<String> names = new ArrayList<>();
        for (UUID id : nation.members().keySet()) {
            if (predicate.test(id)) {
                names.add(nation.memberName(id));
            }
        }
        return names;
    }
}
