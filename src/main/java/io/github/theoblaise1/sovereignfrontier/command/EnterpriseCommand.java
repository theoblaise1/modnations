// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.theoblaise1.sovereignfrontier.enterprise.EnterpriseManager;
import io.github.theoblaise1.sovereignfrontier.enterprise.NationEnterprises;
import io.github.theoblaise1.sovereignfrontier.enterprise.Sector;
import io.github.theoblaise1.sovereignfrontier.enterprise.SectorDefinition;
import io.github.theoblaise1.sovereignfrontier.enterprise.SectorRegistry;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static io.github.theoblaise1.sovereignfrontier.command.CommandUtil.send;

/**
 * {@code /enterprise} : résumé des 4 secteurs, fiche d'un secteur, affectation absolue des habitants (permission
 * {@code economie}). {@link #registerAdmin} fournit la sous-commande {@code /sfadmin enterprise <nation> cycle}.
 */
public final class EnterpriseCommand {
    private EnterpriseCommand() {
    }

    static final SuggestionProvider<CommandSourceStack> ALL_SECTORS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(Sector.values()).map(Sector::id), builder);

    /**
     * Secteurs où l'on peut affecter : débloqués, ou verrouillés mais encore occupés (baisse seulement). Infobulle :
     * habitants affectés et argent par cycle (« 3 hab., 24 cr/cycle »).
     */
    static final SuggestionProvider<CommandSourceStack> ASSIGNABLE_SECTORS = (ctx, builder) -> {
        Nation nation = playerNation(ctx.getSource());
        if (nation == null) {
            return builder.buildFuture();
        }
        SectorRegistry registry = SectorRegistry.get();
        String typed = builder.getRemainingLowerCase();
        for (Sector sector : Sector.values()) {
            SectorDefinition def = registry.get(sector);
            int w = nation.enterprises().workers(sector);
            boolean unlocked = isUnlocked(nation, def);
            if ((unlocked || w > 0) && sector.id().startsWith(typed)) {
                long money = unlocked ? EnterpriseManager.money(def, w, EnterpriseManager.multiplier(nation, def),
                        EnterpriseManager.incomeMultiplier(nation)) : 0L;
                builder.suggest(sector.id(), Msg.tr("enterprise.suggest.sector", w, Money.format(money)));
            }
        }
        return builder.buildFuture();
    };

    /** Nombre d'habitants : 0, l'affectation actuelle et le maximum autorisé (actuels + libres, si débloqué). */
    static final SuggestionProvider<CommandSourceStack> WORKER_COUNTS = (ctx, builder) -> {
        Nation nation = playerNation(ctx.getSource());
        Sector sector = Sector.byId(StringArgumentType.getString(ctx, "secteur"));
        if (nation == null || sector == null) {
            return builder.buildFuture();
        }
        int current = nation.enterprises().workers(sector);
        int max = isUnlocked(nation, SectorRegistry.get().get(sector)) ? EnterpriseManager.maxAssignable(nation, sector) : current;
        Map<Integer, Component> values = new LinkedHashMap<>();
        values.put(0, Msg.tr("enterprise.suggest.none"));
        values.putIfAbsent(current, Msg.tr("enterprise.suggest.current"));
        values.putIfAbsent(max, Msg.tr("enterprise.suggest.max"));
        String typed = builder.getRemaining();
        values.forEach((value, tooltip) -> {
            if (String.valueOf(value).startsWith(typed)) {
                builder.suggest(value, tooltip);
            }
        });
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("enterprise")
                .executes(EnterpriseCommand::summary)
                .then(Commands.literal("info")
                        .then(Commands.argument("secteur", StringArgumentType.word())
                                .suggests(ALL_SECTORS)
                                .executes(EnterpriseCommand::info)))
                .then(Commands.literal("assign")
                        .then(Commands.argument("secteur", StringArgumentType.word())
                                .suggests(ASSIGNABLE_SECTORS)
                                .then(Commands.argument("nombre", IntegerArgumentType.integer(0, NationEnterprises.MAX_WORKERS))
                                        .suggests(WORKER_COUNTS)
                                        .executes(EnterpriseCommand::assign)))));
    }

    /**
     * Sous-commande {@code enterprise <nation> cycle} de {@code /sfadmin} (opérateurs) : force un cycle de production
     * pour cette nation (sans revenu passif ni effet sur le compteur de cycle). L'exigence de permission est répétée
     * sur la sous-commande : Brigadier fusionne les nœuds de même nom.
     */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("enterprise")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("nation", StringArgumentType.word())
                        .suggests(CommandUtil.NATIONS)
                        .then(Commands.literal("cycle").executes(EnterpriseCommand::adminCycle))));
    }

    // ------------------------------------------------------------------ utilitaires

    @Nullable
    private static Nation playerNation(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? null : NationData.get(source.getServer()).nationOf(player.getUUID());
    }

    private static boolean isUnlocked(Nation nation, @Nullable SectorDefinition def) {
        return def != null && EnterpriseManager.firstMissing(nation, def.requires()) == null;
    }

    @Nullable
    private static Nation requireNation(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Nation nation = NationData.get(ctx.getSource().getServer()).nationOf(player.getUUID());
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.no_nation_hint"));
        }
        return nation;
    }

    /** Résout l'argument « secteur » ; erreur explicite et {@code null} s'il est inconnu. */
    @Nullable
    private static Sector sectorArg(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "secteur");
        Sector sector = Sector.byId(raw.toLowerCase(Locale.ROOT));
        if (sector == null) {
            ctx.getSource().sendFailure(Msg.err("enterprise.error.unknown_sector", raw));
        }
        return sector;
    }

    private static String percent(double fraction) {
        return EnterpriseManager.formatAmount(fraction * 100.0D) + " %";
    }

    // ------------------------------------------------------------------ /enterprise

    private static int summary(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Nation nation = requireNation(ctx);
        if (nation == null) {
            return 0;
        }
        EconomyView.EnterprisesView view = EnterpriseManager.view(ctx.getSource().getServer(), nation, SectorRegistry.get());
        MutableComponent c = Msg.tr("enterprise.summary.header", nation.name()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        for (EconomyView.SectorLine line : view.sectors()) {
            Sector sector = Sector.byOrdinal(line.sector());
            if (sector == null) {
                continue;
            }
            MutableComponent row;
            if (!line.unlocked()) {
                row = Component.literal(" ✘ ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(Msg.tr("enterprise.summary.locked", sector.displayName(), line.lockReason())
                                .withStyle(ChatFormatting.GRAY));
            } else if (line.workers() > 0) {
                row = Component.literal(" ● ").withStyle(ChatFormatting.GREEN)
                        .append(Msg.tr("enterprise.summary.sector", sector.displayName(), line.workers(),
                                EnterpriseManager.formatAmount(line.mult()), Money.format(line.moneyPerCycle()))
                                .withStyle(ChatFormatting.WHITE));
            } else {
                row = Component.literal(" ○ ").withStyle(ChatFormatting.YELLOW)
                        .append(Msg.tr("enterprise.summary.sector_idle", sector.displayName(),
                                EnterpriseManager.formatAmount(line.mult()), Money.format(line.moneyPerCycle()))
                                .withStyle(ChatFormatting.GRAY));
            }
            String command = "/enterprise info " + sector.id();
            c.append("\n").append(row.withStyle(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))));
        }
        long ticks = Math.max(0, view.ticksToCycle());
        c.append("\n").append(Msg.info("enterprise.summary.footer", Msg.hl(NationManager.freeInhabitants(nation)),
                Msg.hl(view.population()), Msg.money(view.passiveIncome()), TimeUtil.duration(ticks * 50L)));
        c.append("\n").append(Msg.info("enterprise.summary.hint"));
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        Sector sector = sectorArg(ctx);
        if (sector == null) {
            return 0;
        }
        Nation nation = playerNation(ctx.getSource());
        SectorDefinition def = SectorRegistry.get().get(sector);
        MutableComponent c = sector.displayName().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        if (def == null) {
            c.append("\n").append(Msg.err("enterprise.unavailable"));
            ctx.getSource().sendSuccess(() -> c, false);
            return 1;
        }
        int tiers = nation == null ? 0 : EnterpriseManager.domainTiers(nation, def.domain());
        double mult = 1.0D + def.bonusPerTier() * tiers;
        double incomeMult = nation == null ? 1.0D : EnterpriseManager.incomeMultiplier(nation);
        int workers = nation == null ? 0 : nation.enterprises().workers(sector);
        c.append("\n").append(Msg.info("enterprise.info.domain", def.domain().displayName(), percent(def.bonusPerTier()),
                tiers, EnterpriseManager.formatAmount(mult)));
        for (ResourceLocation req : def.requires()) {
            boolean done = nation != null && nation.research().isCompleted(req);
            c.append("\n").append(Component.literal(done ? " ✔ " : " ✘ ").withStyle(done ? ChatFormatting.GREEN : ChatFormatting.RED))
                    .append(Msg.tr("enterprise.info.requires", EnterpriseManager.researchName(req))
                            .withStyle(done ? ChatFormatting.GRAY : ChatFormatting.RED));
        }
        if (nation != null) {
            c.append("\n").append(Msg.info("enterprise.info.workers", Msg.hl(workers),
                    Msg.hl(NationManager.freeInhabitants(nation)), Msg.hl(nation.effectivePopulation())));
        }
        int basis = Math.max(1, workers);
        c.append("\n").append(Msg.info("enterprise.info.money",
                EnterpriseManager.formatAmount(def.moneyPerWorker() * mult * incomeMult) + " cr",
                Money.format(EnterpriseManager.money(def, basis, mult, incomeMult)), basis));
        if (def.products().isEmpty()) {
            c.append("\n").append(Msg.info("enterprise.info.no_products"));
        } else {
            c.append("\n").append(Msg.info("enterprise.info.products", basis));
        }
        for (SectorDefinition.Product product : def.products()) {
            ResourceLocation missing = nation == null ? (product.requires().isEmpty() ? null : product.requires().get(0))
                    : EnterpriseManager.firstMissing(nation, product.requires());
            String perWorker = EnterpriseManager.formatAmount(product.perWorker() * mult);
            MutableComponent row = Component.literal(missing == null ? "  ✔ " : "  ✘ ")
                    .withStyle(missing == null ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY);
            if (missing == null) {
                row.append(Msg.tr("enterprise.info.product", product.item().getDescription(), perWorker,
                        EnterpriseManager.formatAmount(basis * product.perWorker() * mult)).withStyle(ChatFormatting.WHITE));
            } else {
                row.append(Msg.tr("enterprise.info.product_locked", product.item().getDescription(), perWorker,
                        EnterpriseManager.researchName(missing)).withStyle(ChatFormatting.GRAY));
            }
            c.append("\n").append(row);
        }
        if (nation != null) {
            NationEnterprises e = nation.enterprises();
            c.append("\n").append(Msg.info("enterprise.info.last", Msg.money(e.lastMoney(sector)),
                    Msg.hl(e.lastItems(sector)), Msg.hl(e.lastLost(sector))));
            String command = "/enterprise assign " + sector.id() + " ";
            c.append("\n").append(Msg.tr("enterprise.info.assign_button").withStyle(style -> style
                    .withColor(ChatFormatting.GREEN).withBold(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))));
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }

    private static int assign(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Sector sector = sectorArg(ctx);
        if (sector == null) {
            return 0;
        }
        return send(ctx.getSource(), EnterpriseManager.assign(ctx.getSource().getPlayerOrException(), sector,
                IntegerArgumentType.getInteger(ctx, "nombre")));
    }

    // ------------------------------------------------------------------ /sfadmin enterprise

    private static int adminCycle(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "nation");
        Nation nation = NationData.get(ctx.getSource().getServer()).byName(name);
        if (nation == null) {
            ctx.getSource().sendFailure(Msg.err("nation.error.unknown", name));
            return 0;
        }
        EnterpriseManager.CycleResult result = EnterpriseManager.runCycle(ctx.getSource().getServer(), nation,
                SectorRegistry.get());
        nation.log("enterprise_admin_cycle");
        ctx.getSource().sendSuccess(() -> Msg.ok("enterprise.admin_cycle", nation.name(), Money.format(result.money()),
                result.items(), result.lost()), true);
        return 1;
    }
}
