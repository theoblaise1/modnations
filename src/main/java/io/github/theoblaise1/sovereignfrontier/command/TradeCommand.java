package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.outpost.MarketManager;
import io.github.theoblaise1.sovereignfrontier.outpost.MarketRules;
import io.github.theoblaise1.sovereignfrontier.outpost.MerchantRegistry;
import io.github.theoblaise1.sovereignfrontier.outpost.Outpost;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostData;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostManager;
import io.github.theoblaise1.sovereignfrontier.outpost.PriceMath;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /trade} (commerce avec le marchand à portée) et la branche {@code outpost} de {@code /sfadmin}, exposée par
 * {@link #registerAdmin} pour être greffée sur l'arbre d'administration.
 */
public final class TradeCommand {
    private static final String ALL = "tout";

    private TradeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("trade")
                .executes(ctx -> CommandUtil.send(ctx.getSource(), MarketManager.openNearest(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("list").executes(TradeCommand::list))
                .then(Commands.literal("buy")
                        .then(Commands.argument("objet", ResourceLocationArgument.id())
                                .suggests(ON_SALE)
                                .executes(ctx -> buy(ctx, 1))
                                .then(Commands.argument("lots", IntegerArgumentType.integer(1, MarketRules.MAX_LOTS))
                                        .suggests(BUY_LOTS)
                                        .executes(ctx -> buy(ctx, IntegerArgumentType.getInteger(ctx, "lots"))))))
                .then(Commands.literal("sell")
                        .then(Commands.argument("objet", ResourceLocationArgument.id())
                                .suggests(SELLABLE)
                                .executes(ctx -> sell(ctx, "1"))
                                .then(Commands.argument("lots", StringArgumentType.word())
                                        .suggests(SELL_LOTS)
                                        .executes(ctx -> sell(ctx, StringArgumentType.getString(ctx, "lots")))))));
    }

    /**
     * Sous-commande {@code /sfadmin outpost create <pool> | info | remove | rotate | respawn} (avant-poste le plus
     * proche, à {@value MarketRules#ADMIN_RADIUS} blocs au plus). L'exigence d'opérateur est répétée ici : Brigadier
     * garde celle du premier enregistrement d'un nœud fusionné.
     */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("outpost")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("create")
                        .then(Commands.argument("pool", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(MerchantRegistry.get().ids(), builder))
                                .executes(TradeCommand::adminCreate)))
                .then(Commands.literal("info").executes(TradeCommand::adminInfo))
                .then(Commands.literal("remove").executes(TradeCommand::adminRemove))
                .then(Commands.literal("rotate").executes(TradeCommand::adminRotate))
                .then(Commands.literal("respawn").executes(TradeCommand::adminRespawn)));
    }

    // ------------------------------------------------------------------ autocomplétion

    /** Étal du marchand à portée (lecture seule : l'étal est renouvelé au prochain accès réel). */
    @Nullable
    private static Outpost reachable(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        return player == null ? null : MarketManager.nearestInReach(player);
    }

    /** Objets en vente avec du stock. */
    private static final SuggestionProvider<CommandSourceStack> ON_SALE = (ctx, builder) -> {
        Outpost outpost = reachable(ctx);
        List<ResourceLocation> ids = new ArrayList<>();
        if (outpost != null) {
            for (Outpost.Slot slot : outpost.offers()) {
                if (slot.price() > 0L && slot.stockLeft() > 0) {
                    ids.add(BuiltInRegistries.ITEM.getKey(slot.item()));
                }
            }
        }
        return SharedSuggestionProvider.suggestResource(ids, builder);
    };

    /** Objets rachetés dont le joueur possède au moins un lot intact. */
    private static final SuggestionProvider<CommandSourceStack> SELLABLE = (ctx, builder) -> {
        Outpost outpost = reachable(ctx);
        ServerPlayer player = ctx.getSource().getPlayer();
        List<ResourceLocation> ids = new ArrayList<>();
        if (outpost != null && player != null) {
            for (Outpost.Slot slot : outpost.offers()) {
                if (slot.payout() > 0L && slot.demandLeft() > 0 && Inventories.countPlain(player, slot.item()) >= slot.count()) {
                    ids.add(BuiltInRegistries.ITEM.getKey(slot.item()));
                }
            }
        }
        return SharedSuggestionProvider.suggestResource(ids, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> BUY_LOTS = (ctx, builder) -> {
        Outpost.Slot slot = slot(ctx);
        int max = slot == null ? MarketRules.MAX_LOTS : Math.max(1, Math.min(MarketRules.MAX_LOTS, slot.stockLeft()));
        return SharedSuggestionProvider.suggest(lotSuggestions(max), builder);
    };

    private static final SuggestionProvider<CommandSourceStack> SELL_LOTS = (ctx, builder) -> {
        Outpost outpost = reachable(ctx);
        Outpost.Slot slot = slot(ctx);
        ServerPlayer player = ctx.getSource().getPlayer();
        int max = MarketRules.MAX_LOTS;
        if (outpost != null && slot != null && player != null) {
            max = Math.max(1, MarketManager.maxSellLots(player, outpost, slot));
        }
        List<String> out = new ArrayList<>(lotSuggestions(max));
        out.add(ALL);
        return SharedSuggestionProvider.suggest(out, builder);
    };

    private static List<String> lotSuggestions(int max) {
        List<String> out = new ArrayList<>();
        out.add("1");
        if (max >= 8) {
            out.add("8");
        }
        if (max > 1 && max != 8) {
            out.add(Integer.toString(max));
        }
        return out;
    }

    @Nullable
    private static Outpost.Slot slot(CommandContext<CommandSourceStack> ctx) {
        Outpost outpost = reachable(ctx);
        if (outpost == null) {
            return null;
        }
        try {
            Item item = item(ResourceLocationArgument.getId(ctx, "objet"));
            return item == null ? null : outpost.slot(item);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static Item item(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? null : item;
    }

    // ------------------------------------------------------------------ joueur

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Outpost outpost = MarketManager.nearestInReach(player);
        if (outpost == null) {
            ctx.getSource().sendFailure(Msg.err("outpost.trade.error.no_merchant", MarketRules.reach()));
            return 0;
        }
        OutpostManager.refresh(player.server, outpost);
        var params = MarketRules.params();
        MutableComponent c = Msg.tr("outpost.trade.list.header", OutpostManager.merchantName(outpost.variant()),
                MarketManager.restockIn(player.server)).withStyle(ChatFormatting.AQUA);
        c.append("\n").append(Msg.tr("outpost.trade.list.sells").withStyle(ChatFormatting.GOLD));
        int sells = 0;
        for (Outpost.Slot slot : outpost.offers()) {
            if (slot.price() <= 0L) {
                continue;
            }
            sells++;
            String id = BuiltInRegistries.ITEM.getKey(slot.item()).toString();
            long price = PriceMath.price(slot.price(), outpost.buyPressure(slot.item()), params);
            c.append("\n ").append(button("outpost.trade.list.buy_button", "/trade buy " + id + " 1", slot.stockLeft() > 0))
                    .append(" ").append(Msg.info("outpost.trade.list.sell_line", slot.count(), slot.item().getDescription(),
                            Msg.money(price), Msg.hl(slot.stockLeft())));
        }
        if (sells == 0) {
            c.append("\n ").append(Msg.info("outpost.trade.list.none"));
        }
        c.append("\n").append(Msg.tr("outpost.trade.list.buys").withStyle(ChatFormatting.GOLD));
        int buys = 0;
        for (Outpost.Slot slot : outpost.offers()) {
            if (slot.payout() <= 0L) {
                continue;
            }
            buys++;
            String id = BuiltInRegistries.ITEM.getKey(slot.item()).toString();
            long payout = PriceMath.payout(slot.payout(), outpost.sellPressure(slot.item()), slot.price(),
                    outpost.buyPressure(slot.item()), params);
            int lotsOwned = Inventories.countPlain(player, slot.item()) / slot.count();
            c.append("\n ").append(button("outpost.trade.list.sell_button", "/trade sell " + id + " 1",
                            slot.demandLeft() > 0 && lotsOwned > 0 && payout > 0L))
                    .append(" ").append(Msg.info("outpost.trade.list.buy_line", slot.count(), slot.item().getDescription(),
                            Msg.money(payout), Msg.hl(slot.demandLeft()), Msg.hl(lotsOwned)));
        }
        if (buys == 0) {
            c.append("\n ").append(Msg.info("outpost.trade.list.none"));
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }

    /** Bouton cliquable ({@code active}) ou grisé. */
    private static MutableComponent button(String key, String command, boolean active) {
        if (!active) {
            return Msg.tr(key).withStyle(ChatFormatting.DARK_GRAY);
        }
        return Msg.tr(key).withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    private static int buy(CommandContext<CommandSourceStack> ctx, int lots) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "objet");
        Item item = item(id);
        if (item == null) {
            ctx.getSource().sendFailure(Msg.err("outpost.trade.error.unknown_item", id.toString()));
            return 0;
        }
        Outpost outpost = MarketManager.nearestInReach(player);
        if (outpost == null) {
            ctx.getSource().sendFailure(Msg.err("outpost.trade.error.no_merchant", MarketRules.reach()));
            return 0;
        }
        return CommandUtil.send(ctx.getSource(), MarketManager.trade(player, outpost.id(), null, item, true, lots, null));
    }

    private static int sell(CommandContext<CommandSourceStack> ctx, String lotsArg) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "objet");
        Item item = item(id);
        if (item == null) {
            ctx.getSource().sendFailure(Msg.err("outpost.trade.error.unknown_item", id.toString()));
            return 0;
        }
        Outpost outpost = MarketManager.nearestInReach(player);
        if (outpost == null) {
            ctx.getSource().sendFailure(Msg.err("outpost.trade.error.no_merchant", MarketRules.reach()));
            return 0;
        }
        int lots;
        if (ALL.equals(lotsArg.toLowerCase(Locale.ROOT))) {
            OutpostManager.refresh(player.server, outpost);
            Outpost.Slot slot = outpost.slot(item);
            lots = slot == null || slot.payout() <= 0L ? 1 : MarketManager.maxSellLots(player, outpost, slot);
            if (lots <= 0) {
                Component reason;
                if (slot.demandLeft() <= 0) {
                    reason = Msg.err("outpost.trade.error.demand", item.getDescription(), MarketManager.restockIn(player.server));
                } else if (Inventories.countPlain(player, item) < slot.count()) {
                    reason = Msg.err("outpost.trade.error.nothing_to_sell", item.getDescription());
                } else {
                    reason = Msg.err("outpost.trade.error.no_payout", item.getDescription());
                }
                return CommandUtil.send(ctx.getSource(), ActionResult.fail(reason));
            }
        } else {
            try {
                lots = Integer.parseInt(lotsArg);
            } catch (NumberFormatException e) {
                ctx.getSource().sendFailure(Msg.err("outpost.trade.error.lots", MarketRules.MAX_LOTS));
                return 0;
            }
        }
        return CommandUtil.send(ctx.getSource(), MarketManager.trade(player, outpost.id(), null, item, false, lots, null));
    }

    // ------------------------------------------------------------------ administration

    @Nullable
    private static Outpost nearestAdmin(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Outpost outpost = OutpostData.get(source.getServer()).nearest(source.getLevel().dimension(),
                BlockPos.containing(source.getPosition()), MarketRules.ADMIN_RADIUS);
        if (outpost == null) {
            source.sendFailure(Msg.err("outpost.admin.none", MarketRules.ADMIN_RADIUS));
        }
        return outpost;
    }

    private static int adminCreate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ResourceLocation pool = ResourceLocationArgument.getId(ctx, "pool");
        if (MerchantRegistry.get().pool(pool) == null) {
            ctx.getSource().sendFailure(Msg.err("outpost.admin.unknown_pool", pool.toString()));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        // Juste devant les pieds de l'opérateur, tourné vers lui ; le marchand se tient derrière le comptoir.
        BlockPos pos = player.blockPosition().relative(player.getDirection());
        if (!level.getBlockState(pos).canBeReplaced()) {
            ctx.getSource().sendFailure(Msg.err("outpost.admin.blocked", pos.toShortString()));
            return 0;
        }
        NationData nations = NationData.get(player.server);
        ChunkPos center = new ChunkPos(pos);
        int r = MarketRules.CLAIM_RADIUS_CHUNKS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Nation owner = nations.ownerAt(level.dimension(), ChunkPos.asLong(center.x + dx, center.z + dz));
                if (owner != null) {
                    ctx.getSource().sendFailure(Msg.err("outpost.admin.territory", owner.name()));
                    return 0;
                }
            }
        }
        Outpost outpost = OutpostManager.createAt(level, pos, player.getDirection().getOpposite(), pool);
        if (outpost == null) {
            ctx.getSource().sendFailure(Msg.err("outpost.trade.error.unavailable"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Msg.ok("outpost.admin.created", pool.toString(), pos.toShortString()), true);
        return 1;
    }

    private static int adminInfo(CommandContext<CommandSourceStack> ctx) {
        Outpost o = nearestAdmin(ctx);
        if (o == null) {
            return 0;
        }
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getServer().getLevel(o.dimension());
        Entity merchant = level == null || o.merchant() == null ? null : level.getEntity(o.merchant());
        OutpostData data = OutpostData.get(source.getServer());
        MutableComponent c = Component.literal("=== ").append(OutpostManager.merchantName(o.variant())).append(" ===")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        c.append("\n").append(Msg.info("outpost.admin.info.id", Msg.hl(o.id()), Msg.hl(o.pool()), Msg.hl(o.faction())));
        c.append("\n").append(Msg.info("outpost.admin.info.position", Msg.hl(o.dimension().location()),
                Msg.hl(o.pos().toShortString()), Msg.hl(o.facing().getSerializedName()), Msg.hl(TimeUtil.date(o.created()))));
        c.append("\n").append(merchant != null
                ? Msg.info("outpost.admin.info.merchant", Msg.hl(merchant.getUUID()))
                : Msg.warn("outpost.admin.info.no_merchant"));
        c.append("\n").append(Msg.info("outpost.admin.info.rotation", Msg.hl(o.rotation()),
                Msg.hl(data.rotationIndex(MarketRules.rotationTicks())), Msg.hl(MarketManager.restockIn(source.getServer()))));
        for (Outpost.Slot slot : o.offers()) {
            c.append("\n  ").append(Msg.info("outpost.admin.info.offer", slot.count(), slot.item().getDescription(),
                    Msg.hl(Money.format(slot.price())), Msg.hl(Money.format(slot.payout())), Msg.hl(slot.stockLeft()),
                    Msg.hl(slot.demandLeft()),
                    Msg.hl(String.format(Locale.ROOT, "%.2f", o.buyPressure(slot.item()))),
                    Msg.hl(String.format(Locale.ROOT, "%.2f", o.sellPressure(slot.item())))));
        }
        if (o.grievances().isEmpty()) {
            c.append("\n").append(Msg.info("outpost.admin.info.no_grievance"));
        }
        NationData nations = NationData.get(source.getServer());
        for (Map.Entry<UUID, Outpost.Grievance> e : o.grievances().entrySet()) {
            Nation nation = nations.byId(e.getKey());
            c.append("\n  ").append(Msg.info("outpost.admin.info.grievance",
                    Msg.hl(nation == null ? e.getKey().toString() : nation.name()), Msg.hl(e.getValue().count()),
                    Msg.hl(TimeUtil.shortStamp(e.getValue().last()))));
        }
        source.sendSuccess(() -> c, false);
        return 1;
    }

    private static int adminRemove(CommandContext<CommandSourceStack> ctx) {
        Outpost o = nearestAdmin(ctx);
        if (o == null) {
            return 0;
        }
        ServerLevel level = ctx.getSource().getServer().getLevel(o.dimension());
        OutpostManager.unregister(ctx.getSource().getServer(), o);
        if (level != null && level.isLoaded(o.pos())) {
            level.removeBlock(o.pos(), false);
        }
        ctx.getSource().sendSuccess(() -> Msg.ok("outpost.admin.removed", o.pos().toShortString()), true);
        return 1;
    }

    private static int adminRotate(CommandContext<CommandSourceStack> ctx) {
        Outpost o = nearestAdmin(ctx);
        if (o == null) {
            return 0;
        }
        OutpostManager.forceRotation(ctx.getSource().getServer(), o);
        ctx.getSource().sendSuccess(() -> Msg.ok("outpost.admin.rotated", o.pos().toShortString()), true);
        return 1;
    }

    private static int adminRespawn(CommandContext<CommandSourceStack> ctx) {
        Outpost o = nearestAdmin(ctx);
        if (o == null) {
            return 0;
        }
        ServerLevel level = ctx.getSource().getServer().getLevel(o.dimension());
        if (level == null || !level.areEntitiesLoaded(ChunkPos.asLong(o.post()))) {
            ctx.getSource().sendFailure(Msg.err("outpost.admin.unloaded"));
            return 0;
        }
        if (!OutpostManager.ensureMerchant(level, o)) {
            ctx.getSource().sendSuccess(() -> Msg.info("outpost.admin.present", o.pos().toShortString()), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Msg.ok("outpost.admin.respawned", o.pos().toShortString()), true);
        return 1;
    }
}
