package io.github.theoblaise1.sovereignfrontier.outpost;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.network.TradeActionC2S;
import io.github.theoblaise1.sovereignfrontier.network.TradeView;
import io.github.theoblaise1.sovereignfrontier.network.TradeView.PriceParams;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Règles du commerce avec les marchands. Les constantes de validation sont du code : la configuration SERVER n'est
 * chargée qu'après le premier chargement des datapacks, elle n'est donc lue qu'à l'exécution ({@link #params()},
 * {@link #reach()}…), jamais pendant la validation des pools.
 */
public final class MarketRules {
    /** Règle statique d'écart : pour chaque objet, sur tous les pools, rachat/u maximal ≤ 0,8 × prix/u minimal. */
    public static final double MAX_PAYOUT_RATIO = 0.8D;
    public static final int MAX_OFFERS = TradeView.MAX_OFFERS;
    public static final int MAX_LOTS = TradeActionC2S.MAX_LOTS;
    /** Prix ou rachat de base maximal d'un lot (cr). */
    public static final int MAX_BASE_VALUE = 1_000_000;
    public static final int MAX_STOCK = 10_000;
    public static final int MAX_WEIGHT = 10_000;
    /** La pression est divisée au plus 16 fois par le facteur d'amortissement, même après une longue absence. */
    public static final int MAX_DECAY_STEPS = 16;
    /** Pression en dessous de laquelle une entrée est oubliée. */
    public static final double MIN_PRESSURE = 0.01D;
    /** Boîte protégée autour du comptoir : ±7 en X et Z, de −2 à +7 en Y. */
    public static final int BOX_HORIZONTAL = 7;
    public static final int BOX_DOWN = 2;
    public static final int BOX_UP = 7;
    /** Chunks non revendicables autour du chunk du comptoir (1 = 3 × 3). */
    public static final int CLAIM_RADIUS_CHUNKS = 1;
    /** Distance au poste au-delà de laquelle le marchand y est ramené. */
    public static final double MERCHANT_LEASH = 2.0D;
    /** Rayon de recherche des commandes d'administration (avant-poste le plus proche). */
    public static final int ADMIN_RADIUS = 32;
    /** Valeurs par défaut de la section {@code [market]} (tests et repli quand la configuration n'est pas chargée). */
    public static final PriceParams DEFAULT_PARAMS = new PriceParams(20, 0.30D, 0.30D, 0.25D, 2.0D);
    public static final int DEFAULT_REACH = 8;
    public static final int DEFAULT_ROTATION_TICKS = 24000;

    private MarketRules() {
    }

    /** Paramètres de tarification courants ({@code SFConfig [market]}), lus à l'exécution. */
    public static PriceParams params() {
        if (!SFConfig.SPEC.isLoaded()) {
            return DEFAULT_PARAMS;
        }
        return new PriceParams(SFConfig.MERCHANT_PRESSURE_STEP.get(), SFConfig.MERCHANT_SUPPLY_DROP.get(),
                SFConfig.MERCHANT_DEMAND_RISE.get(), SFConfig.MERCHANT_PAYOUT_FLOOR.get(), SFConfig.MERCHANT_PRICE_CEILING.get());
    }

    /** Distance maximale (blocs) au comptoir pour commercer. */
    public static int reach() {
        return SFConfig.SPEC.isLoaded() ? SFConfig.MERCHANT_TRADE_REACH.get() : DEFAULT_REACH;
    }

    public static int rotationTicks() {
        return SFConfig.SPEC.isLoaded() ? SFConfig.MERCHANT_ROTATION_TICKS.get() : DEFAULT_ROTATION_TICKS;
    }

    public static double pressureDecay() {
        return SFConfig.SPEC.isLoaded() ? SFConfig.MERCHANT_PRESSURE_DECAY.get() : 0.5D;
    }

    public static boolean protectionEnabled() {
        return !SFConfig.SPEC.isLoaded() || SFConfig.PROTECT_OUTPOSTS.get();
    }

    /** Vrai si un rachat de {@code payout} cr par {@code payoutCount} objets respecte l'écart face à un prix de {@code price} cr par {@code priceCount}. */
    public static boolean spreadOk(long payout, int payoutCount, long price, int priceCount) {
        return (double) payout * priceCount <= MAX_PAYOUT_RATIO * price * payoutCount + 1.0E-9D;
    }

    /**
     * Applique la règle d'écart global : un pool qui rachète un objet plus de 0,8 × le prix unitaire minimal de cet
     * objet (tous pools confondus) est rejeté en entier ; on recommence jusqu'à stabilité (retirer un pool peut
     * relever un prix minimal). {@code rejected} reçoit un message par pool écarté.
     */
    public static Map<ResourceLocation, MerchantPool> enforceSpread(Map<ResourceLocation, MerchantPool> pools,
                                                                    Consumer<String> rejected) {
        Map<ResourceLocation, MerchantPool> kept = new LinkedHashMap<>(pools);
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<Item, Cheapest> cheapest = cheapestPrices(kept.values());
            for (MerchantPool pool : new ArrayList<>(kept.values())) {
                String violation = violation(pool, cheapest);
                if (violation != null) {
                    kept.remove(pool.id());
                    rejected.accept("Pool marchand " + pool.id() + " ignoré : " + violation);
                    changed = true;
                    break;
                }
            }
        }
        return kept;
    }

    /** Toutes les violations de l'écart global dans cet ensemble de pools (vide si la règle est respectée). */
    public static List<String> spreadViolations(Collection<MerchantPool> pools) {
        Map<Item, Cheapest> cheapest = cheapestPrices(pools);
        List<String> out = new ArrayList<>();
        for (MerchantPool pool : pools) {
            String violation = violation(pool, cheapest);
            if (violation != null) {
                out.add(pool.id() + " : " + violation);
            }
        }
        return out;
    }

    private record Cheapest(long price, int count, ResourceLocation pool) {
        double unit() {
            return (double) price / count;
        }
    }

    private static Map<Item, Cheapest> cheapestPrices(Collection<MerchantPool> pools) {
        Map<Item, Cheapest> out = new HashMap<>();
        for (MerchantPool pool : pools) {
            for (MerchantOffer offer : pool.offers()) {
                if (offer.price() <= 0) {
                    continue;
                }
                Cheapest current = out.get(offer.item());
                Cheapest candidate = new Cheapest(offer.price(), offer.count(), pool.id());
                if (current == null || candidate.unit() < current.unit()) {
                    out.put(offer.item(), candidate);
                }
            }
        }
        return out;
    }

    private static String violation(MerchantPool pool, Map<Item, Cheapest> cheapest) {
        for (MerchantOffer offer : pool.offers()) {
            Cheapest min = offer.payout() > 0 ? cheapest.get(offer.item()) : null;
            if (min != null && !spreadOk(offer.payout(), offer.count(), min.price(), min.count())) {
                return String.format(java.util.Locale.ROOT,
                        "rachat de %s à %.3f cr/u, au-delà de %.1f × le prix minimal %.3f cr/u (pool %s)",
                        BuiltInRegistries.ITEM.getKey(offer.item()), (double) offer.payout() / offer.count(),
                        MAX_PAYOUT_RATIO, min.unit(), min.pool());
            }
        }
        return null;
    }
}
