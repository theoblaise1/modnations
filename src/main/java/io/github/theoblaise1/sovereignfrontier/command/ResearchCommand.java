package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.research.NationResearch;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDomain;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchReward;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

import static io.github.theoblaise1.sovereignfrontier.command.CommandUtil.send;

/** {@code /research} : état, liste par domaine, fiche d'un palier, lancement et annulation. */
public final class ResearchCommand {
    private ResearchCommand() {
    }

    /** Paliers lançables par la nation du joueur (statut « disponible »). */
    static final SuggestionProvider<CommandSourceStack> AVAILABLE = (ctx, builder) -> {
        ServerPlayer player = ctx.getSource().getPlayer();
        Nation nation = player == null ? null : NationData.get(ctx.getSource().getServer()).nationOf(player.getUUID());
        ResearchRegistry registry = ResearchRegistry.server();
        return SharedSuggestionProvider.suggest(registry.all().stream()
                .filter(t -> nation != null && ResearchManager.status(nation, registry, t) == ResearchManager.Status.AVAILABLE)
                .map(t -> shortId(t.id())), builder);
    };

    static final SuggestionProvider<CommandSourceStack> ALL_TIERS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(ResearchRegistry.server().all().stream().map(t -> shortId(t.id())), builder);

    static final SuggestionProvider<CommandSourceStack> DOMAINS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(java.util.Arrays.stream(ResearchDomain.values()).map(ResearchDomain::id), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("research")
                .executes(ResearchCommand::status)
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, null))
                        .then(Commands.argument("domaine", StringArgumentType.word())
                                .suggests(DOMAINS)
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "domaine")))))
                .then(Commands.literal("info")
                        .then(Commands.argument("palier", StringArgumentType.string())
                                .suggests(ALL_TIERS)
                                .executes(ResearchCommand::info)))
                .then(Commands.literal("start")
                        .then(Commands.argument("palier", StringArgumentType.string())
                                .suggests(AVAILABLE)
                                .executes(ctx -> {
                                    ResearchTier tier = resolve(ctx);
                                    return tier == null ? 0 : send(ctx.getSource(),
                                            ResearchManager.start(ctx.getSource().getPlayerOrException(), tier.id()));
                                })))
                .then(Commands.literal("cancel")
                        .executes(ctx -> send(ctx.getSource(), ResearchManager.cancel(ctx.getSource().getPlayerOrException())))));
    }

    /**
     * Identifiant court (« industrie_8 ») pour l'espace de noms du mod, complet sinon. Mis entre
     * guillemets si besoin (« : », « / » interdits hors guillemets dans l'argument « palier ») :
     * suggestions et liens cliquables restent analysables.
     */
    static String shortId(ResourceLocation id) {
        return StringArgumentType.escapeIfRequired(
                id.getNamespace().equals(SovereignFrontier.MODID) ? id.getPath() : id.toString());
    }

    /** Résout l'argument « palier » ; envoie une erreur explicite et renvoie null s'il est inconnu. */
    @Nullable
    static ResearchTier resolve(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "palier");
        ResourceLocation id = raw.contains(":") ? ResourceLocation.tryParse(raw)
                : ResourceLocation.tryBuild(SovereignFrontier.MODID, raw);
        ResearchTier tier = id == null ? null : ResearchRegistry.server().get(id);
        if (tier == null) {
            ctx.getSource().sendFailure(Msg.err("research.error.unknown", raw));
        }
        return tier;
    }

    private static Nation requireNation(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Nation nation = NationData.get(ctx.getSource().getServer()).nationOf(player.getUUID());
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.no_nation_hint"));
        }
        return nation;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Nation nation = requireNation(ctx);
        if (nation == null) {
            return 0;
        }
        ResearchRegistry registry = ResearchRegistry.server();
        NationResearch research = nation.research();
        MutableComponent c = Msg.tr("research.status.header", research.completed().size(), registry.size(), research.points())
                .withStyle(ChatFormatting.AQUA);
        NationResearch.Active active = research.active();
        if (active == null) {
            c.append("\n").append(Msg.info("research.status.idle"));
        } else {
            ResearchTier tier = registry.get(active.id());
            long remaining = Math.max(0L, active.endEpochMs() - TimeUtil.now());
            // Bornée des deux côtés : si l'horloge du serveur recule, le restant peut dépasser la durée totale.
            int percent = (int) Math.max(0L, Math.min(100L, 100L - remaining * 100L / active.totalMs()));
            c.append("\n").append(Msg.info("research.status.active",
                    tier == null ? Component.literal(active.id().toString()) : tier.displayName().withStyle(ChatFormatting.WHITE),
                    percent, TimeUtil.duration(remaining)));
        }
        c.append("\n").append(Msg.info("research.status.help"));
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, @Nullable String domainId) throws CommandSyntaxException {
        Nation nation = requireNation(ctx);
        if (nation == null) {
            return 0;
        }
        ResearchRegistry registry = ResearchRegistry.server();
        List<ResearchDomain> domains;
        if (domainId == null) {
            domains = List.of(ResearchDomain.values());
        } else {
            Optional<ResearchDomain> d = ResearchDomain.byId(domainId);
            if (d.isEmpty()) {
                ctx.getSource().sendFailure(Msg.err("research.error.unknown_domain", domainId));
                return 0;
            }
            domains = List.of(d.get());
        }
        MutableComponent c = Component.empty();
        for (ResearchDomain domain : domains) {
            List<ResearchTier> tiers = registry.domain(domain);
            long done = tiers.stream().filter(t -> nation.research().isCompleted(t.id())).count();
            c.append(Component.literal(c.getSiblings().isEmpty() ? "" : "\n"))
                    .append(domain.displayName().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                    .append(Component.literal(" (" + done + "/" + tiers.size() + ")").withStyle(ChatFormatting.GRAY));
            if (domainId == null) {
                continue;
            }
            for (ResearchTier tier : tiers) {
                ResearchManager.Status status = ResearchManager.status(nation, registry, tier);
                String id = shortId(tier.id());
                c.append("\n ").append(statusMark(status)).append(" ")
                        .append(tier.displayName().withStyle(style -> style
                                .withColor(status == ResearchManager.Status.LOCKED ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/research info " + id))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/research info " + id)))));
            }
        }
        if (domainId == null) {
            c.append("\n").append(Msg.info("research.list.hint"));
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ResearchTier tier = resolve(ctx);
        if (tier == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        Nation nation = player == null ? null : NationData.get(ctx.getSource().getServer()).nationOf(player.getUUID());
        ResearchRegistry registry = ResearchRegistry.server();
        ResearchDefinition def = tier.definition();

        MutableComponent c = Component.empty()
                .append(tier.displayName().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Msg.tr("research.info.domain_tier", tier.domain().displayName(), def.tier()).withStyle(ChatFormatting.GRAY));
        c.append("\n").append(tier.description().withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
        if (nation != null) {
            c.append("\n").append(Msg.info("research.info.status", statusName(ResearchManager.status(nation, registry, tier))));
        }
        long duration = nation == null ? def.baseDurationSeconds() * 1000L : ResearchManager.durationMs(nation, tier);
        c.append("\n").append(Msg.info("research.info.duration", TimeUtil.duration(duration)));
        c.append("\n").append(Msg.info("research.info.treasury", Money.format(def.cost().treasury())));
        if (def.cost().points() > 0) {
            c.append("\n").append(Msg.info("research.info.points", def.cost().points()));
        }
        if (def.minPopulation() > 0) {
            c.append("\n").append(Msg.info("research.info.population", def.minPopulation()));
        }
        for (ResearchDefinition.ItemCost cost : def.cost().items()) {
            int have = nation == null ? 0 : ResearchManager.count(nation.chest(), cost.item());
            c.append("\n  ").append(Component.literal(have >= cost.count() ? "✔ " : "✘ ")
                            .withStyle(have >= cost.count() ? ChatFormatting.GREEN : ChatFormatting.RED))
                    .append(Component.literal(cost.count() + " × ").withStyle(ChatFormatting.WHITE))
                    .append(cost.item().getDescription())
                    .append(Component.literal(" (" + have + ")").withStyle(ChatFormatting.DARK_GRAY));
        }
        for (ResourceLocation req : registry.prerequisites(tier)) {
            ResearchTier r = registry.get(req);
            boolean ok = nation != null && nation.research().isCompleted(req);
            c.append("\n  ").append(Msg.tr("research.info.requires",
                    r == null ? Component.literal(req.toString()) : r.displayName()).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        for (ResearchReward reward : def.rewards()) {
            c.append("\n  ").append(Component.literal("★ ").withStyle(ChatFormatting.GOLD)).append(reward.describe().withStyle(ChatFormatting.YELLOW));
        }
        if (nation != null && ResearchManager.status(nation, registry, tier) == ResearchManager.Status.AVAILABLE) {
            String command = "/research start " + shortId(tier.id());
            c.append("\n").append(Msg.tr("research.info.start_button").withStyle(style -> style
                    .withColor(ChatFormatting.GREEN).withBold(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))));
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }

    private static Component statusMark(ResearchManager.Status status) {
        return switch (status) {
            case COMPLETED -> Component.literal("✔").withStyle(ChatFormatting.GREEN);
            case ACTIVE -> Component.literal("⌛").withStyle(ChatFormatting.GOLD);
            case AVAILABLE -> Component.literal("●").withStyle(ChatFormatting.YELLOW);
            case LOCKED -> Component.literal("✘").withStyle(ChatFormatting.DARK_GRAY);
        };
    }

    private static Component statusName(ResearchManager.Status status) {
        return Msg.tr("research.state." + status.name().toLowerCase(java.util.Locale.ROOT));
    }
}
