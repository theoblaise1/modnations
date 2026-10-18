// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.outpost;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.network.TradeActionC2S;
import io.github.theoblaise1.sovereignfrontier.network.TradeView;
import io.github.theoblaise1.sovereignfrontier.network.TradeView.OfferLine;
import io.github.theoblaise1.sovereignfrontier.network.TradeView.PriceParams;
import io.github.theoblaise1.sovereignfrontier.network.TradeViewS2C;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Achats et ventes auprès des marchands. Aucune session : chaque échange (paquet ou commande) revalide tout, dans
 * l'ordre, sans effet partiel — avant-poste et comptoir, distance, rotation, offre, lots, total attendu, fonds ou
 * objets intacts, place — puis applique débit, remise, stock et pression sur le thread principal.
 */
public final class MarketManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MarketManager() {
    }

    /** Appelé par {@code ModPayloads} (thread principal, {@code lots} déjà borné à 1..64) ; la réponse est envoyée par l'appelant. */
    public static ActionResult handleTrade(ServerPlayer player, TradeActionC2S payload) {
        return trade(player, payload.outpost(), payload.rotation(), payload.item(), payload.buy(), payload.lots(),
                payload.expectedTotal());
    }

    // ------------------------------------------------------------------ ouverture

    /** Avant-poste dont le comptoir est à portée de commerce du joueur (même dimension), ou {@code null}. */
    @Nullable
    public static Outpost nearestInReach(ServerPlayer player) {
        return OutpostData.get(player.server).nearest(player.level().dimension(), player.blockPosition(), MarketRules.reach());
    }

    /** {@code /trade} : ouvre l'écran du marchand à portée (le marchand absent réapparaît). */
    public static ActionResult openNearest(ServerPlayer player) {
        Outpost outpost = nearestInReach(player);
        if (outpost == null) {
            return ActionResult.fail(Msg.err("outpost.trade.error.no_merchant", MarketRules.reach()));
        }
        Component problem = reachProblem(player, outpost);
        if (problem != null) {
            return ActionResult.fail(problem);
        }
        OutpostManager.ensureMerchant(player.serverLevel(), outpost);
        open(player, outpost);
        return ActionResult.ok(Msg.info("outpost.trade.opened", OutpostManager.merchantName(outpost.variant())));
    }

    /** Clic droit sur un comptoir : liaison immédiate s'il ne l'est pas encore, réapparition du marchand, écran. */
    static void openFromCounter(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Outpost outpost = OutpostData.get(level.getServer()).atAnchor(level.dimension(), pos);
        if (outpost == null && level.getBlockEntity(pos) instanceof ComptoirBlockEntity be) {
            outpost = OutpostManager.bind(level, pos, be);
        }
        if (outpost == null) {
            player.displayClientMessage(Msg.err("outpost.trade.error.unavailable"), true);
            return;
        }
        OutpostManager.ensureMerchant(level, outpost);
        open(player, outpost);
    }

    /** Clic droit sur un marchand. */
    static void openFromMerchant(ServerPlayer player, ServerLevel level, MerchantEntity merchant) {
        Outpost outpost = merchant.outpostId() == null ? null : OutpostData.get(level.getServer()).get(merchant.outpostId());
        if (outpost == null) {
            player.displayClientMessage(Msg.err("outpost.trade.error.unavailable"), true);
            return;
        }
        Component problem = reachProblem(player, outpost);
        if (problem != null) {
            player.displayClientMessage(problem, true);
            return;
        }
        open(player, outpost);
    }

    /** Renouvelle l'étal si besoin et ouvre l'écran de commerce (rien pour un faux joueur). */
    public static void open(ServerPlayer player, Outpost outpost) {
        OutpostManager.refresh(player.server, outpost);
        sendView(player, outpost, true);
    }

    public static void sendView(ServerPlayer player, Outpost outpost, boolean open) {
        if (NationSync.canReceive(player)) {
            PacketDistributor.sendToPlayer(player, new TradeViewS2C(open, view(player, outpost)));
        }
    }

    /** Étal tel qu'affiché à ce joueur (portefeuille et objets intacts possédés compris). */
    public static TradeView view(ServerPlayer player, Outpost outpost) {
        OutpostData data = OutpostData.get(player.server);
        List<OfferLine> lines = new ArrayList<>();
        for (Outpost.Slot slot : outpost.offers()) {
            if (lines.size() >= TradeView.MAX_OFFERS) {
                break;
            }
            lines.add(new OfferLine(slot.item(), slot.count(), slot.price(), slot.payout(), slot.stockLeft(), slot.demandLeft(),
                    outpost.buyPressure(slot.item()), outpost.sellPressure(slot.item()), Inventories.countPlain(player, slot.item())));
        }
        return new TradeView(outpost.id(), outpost.pos(), OutpostManager.merchantName(outpost.variant()), outpost.variant(),
                outpost.rotation(), data.ticksToRotation(MarketRules.rotationTicks()),
                EconomyData.get(player.server).balance(player.getUUID()), MarketRules.params(), lines);
    }

    /** Durée lisible jusqu'au prochain réassort. */
    public static String restockIn(MinecraftServer server) {
        return TimeUtil.duration(OutpostData.get(server).ticksToRotation(MarketRules.rotationTicks()) * 50L);
    }

    /** Problème de distance ou d'ancre (le comptoir a disparu), ou {@code null}. */
    @Nullable
    public static Component reachProblem(ServerPlayer player, Outpost outpost) {
        int reach = MarketRules.reach();
        if (!player.level().dimension().equals(outpost.dimension())
                || player.position().distanceToSqr(Vec3.atCenterOf(outpost.pos())) > (double) reach * reach) {
            return Msg.err("outpost.trade.error.too_far", reach);
        }
        if (!player.level().getBlockState(outpost.pos()).is(ModBlocks.OUTPOST_COUNTER.get())) {
            return Msg.err("outpost.trade.error.gone");
        }
        return null;
    }

    // ------------------------------------------------------------------ échanges

    /**
     * Achat ({@code buy}) ou vente de {@code lots} lots. {@code rotation} et {@code expectedTotal} viennent de l'écran
     * (vérifiés s'ils ne sont pas {@code null}) ; les commandes les omettent et paient le prix courant.
     */
    public static ActionResult trade(ServerPlayer player, UUID outpostId, @Nullable Long rotation, Item item, boolean buy,
                                     int lots, @Nullable Long expectedTotal) {
        MinecraftServer server = player.server;
        OutpostData data = OutpostData.get(server);
        Outpost outpost = data.get(outpostId);
        if (outpost == null) {
            return ActionResult.fail(Msg.err("outpost.trade.error.gone"));
        }
        Component problem = reachProblem(player, outpost);
        if (problem != null) {
            return ActionResult.fail(problem);
        }
        OutpostManager.refresh(server, outpost);
        ActionResult result;
        if (rotation != null && rotation != outpost.rotation()) {
            result = ActionResult.fail(Msg.err("outpost.trade.error.rotation"));
        } else if (lots < 1 || lots > MarketRules.MAX_LOTS) {
            result = ActionResult.fail(Msg.err("outpost.trade.error.lots", MarketRules.MAX_LOTS));
        } else {
            result = buy ? buy(player, data, outpost, item, lots, expectedTotal)
                    : sell(player, data, outpost, item, lots, expectedTotal);
        }
        // Succès comme échec : l'écran ouvert est resynchronisé (stock, pression, portefeuille, rotation).
        sendView(player, outpost, false);
        return result;
    }

    private static ActionResult buy(ServerPlayer player, OutpostData data, Outpost outpost, Item item, int lots,
                                    @Nullable Long expectedTotal) {
        Outpost.Slot slot = outpost.slot(item);
        Component name = item.getDescription();
        if (slot == null || slot.price() <= 0L) {
            return ActionResult.fail(Msg.err("outpost.trade.error.not_sold", name));
        }
        if (slot.stockLeft() <= 0) {
            return ActionResult.fail(Msg.err("outpost.trade.error.stock", name, restockIn(player.server)));
        }
        if (lots > slot.stockLeft()) {
            return ActionResult.fail(Msg.err("outpost.trade.error.stock_low", slot.stockLeft(), name));
        }
        PriceParams params = MarketRules.params();
        long total = PriceMath.buyTotal(slot.price(), outpost.buyPressure(item), lots, params);
        if (expectedTotal != null && expectedTotal != total) {
            return ActionResult.fail(Msg.err("outpost.trade.error.price_changed", Money.format(total), Money.format(expectedTotal)));
        }
        EconomyData eco = EconomyData.get(player.server);
        long balance = eco.balance(player.getUUID());
        if (balance < total) {
            return ActionResult.fail(Msg.err("outpost.trade.error.funds", Money.format(total), Money.format(balance)));
        }
        ItemStack goods = new ItemStack(item, slot.count() * lots);
        if (!Inventories.canFit(player, goods)) {
            return ActionResult.fail(Msg.err("outpost.trade.error.inventory", goods.getCount(), name));
        }
        if (!RestrictedItems.allowInLoot(goods, "marchand " + outpost.pool())) {
            return ActionResult.fail(Msg.err("outpost.trade.error.unavailable"));
        }
        if (!eco.trySpend(player.getUUID(), total)) {
            return ActionResult.fail(Msg.err("outpost.trade.error.funds", Money.format(total), Money.format(balance)));
        }
        Inventories.giveOrDrop(player, goods);
        slot.setStockLeft(slot.stockLeft() - lots);
        outpost.addPressure(item, true, lots);
        data.setDirty();
        NationSync.scheduleRefresh(player);
        LOGGER.info("[Économie] {} achète {} lot(s) de {} × {} à l'avant-poste {} pour {}", player.getGameProfile().getName(),
                lots, slot.count(), BuiltInRegistries.ITEM.getKey(item), outpost.id(), Money.format(total));
        return ActionResult.ok(Msg.ok("outpost.trade.bought", lots, slot.count(), name, Money.format(total)));
    }

    private static ActionResult sell(ServerPlayer player, OutpostData data, Outpost outpost, Item item, int lots,
                                     @Nullable Long expectedTotal) {
        Outpost.Slot slot = outpost.slot(item);
        Component name = item.getDescription();
        if (slot == null || slot.payout() <= 0L) {
            return ActionResult.fail(Msg.err("outpost.trade.error.not_bought", name));
        }
        if (slot.demandLeft() <= 0) {
            return ActionResult.fail(Msg.err("outpost.trade.error.demand", name, restockIn(player.server)));
        }
        if (lots > slot.demandLeft()) {
            return ActionResult.fail(Msg.err("outpost.trade.error.demand_low", slot.demandLeft(), name));
        }
        PriceParams params = MarketRules.params();
        double s = outpost.sellPressure(item);
        double b = outpost.buyPressure(item);
        if (PriceMath.payout(slot.payout(), s + lots - 1, slot.price(), b, params) <= 0L) {
            return ActionResult.fail(Msg.err("outpost.trade.error.no_payout", name));
        }
        long total = PriceMath.sellTotal(slot.payout(), s, slot.price(), b, lots, params);
        if (expectedTotal != null && expectedTotal != total) {
            return ActionResult.fail(Msg.err("outpost.trade.error.price_changed", Money.format(total), Money.format(expectedTotal)));
        }
        int needed = slot.count() * lots;
        int have = Inventories.countPlain(player, item);
        if (have < needed) {
            return ActionResult.fail(Msg.err("outpost.trade.error.items", needed - have, name));
        }
        int removed = Inventories.consumePlain(player, item, needed);
        if (removed != needed) {
            // Impossible après le comptage (même tick) ; par prudence, rien n'est payé pour une remise partielle.
            Inventories.giveOrDrop(player, new ItemStack(item, removed));
            return ActionResult.fail(Msg.err("outpost.trade.error.items", needed - removed, name));
        }
        EconomyData.get(player.server).add(player.getUUID(), total);
        slot.setDemandLeft(slot.demandLeft() - lots);
        outpost.addPressure(item, false, lots);
        data.setDirty();
        NationSync.scheduleRefresh(player);
        LOGGER.info("[Économie] {} vend {} lot(s) de {} × {} à l'avant-poste {} pour {}", player.getGameProfile().getName(),
                lots, slot.count(), BuiltInRegistries.ITEM.getKey(item), outpost.id(), Money.format(total));
        return ActionResult.ok(Msg.ok("outpost.trade.sold", lots, slot.count(), name, Money.format(total)));
    }

    /** Lots qu'une vente « tout » propose : demande, lots intacts possédés, plafond par échange, rachat positif. */
    public static int maxSellLots(ServerPlayer player, Outpost outpost, Outpost.Slot slot) {
        return PriceMath.maxSellLots(slot.payout(), outpost.sellPressure(slot.item()), slot.price(),
                outpost.buyPressure(slot.item()), slot.demandLeft(), Inventories.countPlain(player, slot.item()) / slot.count(),
                MarketRules.params());
    }
}
