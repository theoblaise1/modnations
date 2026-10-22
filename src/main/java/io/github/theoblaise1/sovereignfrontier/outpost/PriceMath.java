package io.github.theoblaise1.sovereignfrontier.outpost;

import io.github.theoblaise1.sovereignfrontier.network.TradeView.PriceParams;

/**
 * Tarification des marchands, pure et partagée par le serveur (revalidation) et le client (affichage, total attendu).
 * <ul>
 *   <li>le joueur <b>achète</b> un lot : {@code price(B) = ceil(base × min(plafond, (1 + hausse)^(B / pas)))} ;</li>
 *   <li>le joueur <b>vend</b> un lot : {@code payout(S) = floor(base × max(plancher, (1 − baisse)^(S / pas)))}, plafonné
 *       à {@code floor(0,8 × price(B))} quand l'avant-poste vend aussi cet objet.</li>
 * </ul>
 * Le facteur de prix est borné à 1 au moins et celui du rachat à 1 au plus, quelle que soit la configuration : la
 * pression d'achat ne fait que monter les prix et celle de vente que baisser les rachats. Avec la règle statique
 * {@link MarketRules#MAX_PAYOUT_RATIO} sur les valeurs de base, aucun aller-retour n'est jamais gagnant.
 */
public final class PriceMath {
    /** Tolérance d'arrondi : 10 × 1,3 vaut 13,000000000000002 en double, 100 × 0,7 vaut 69,99999999999999. */
    private static final double EPS = 1.0E-6D;

    private PriceMath() {
    }

    /** Prix d'un lot à la pression d'achat {@code b} (0 si le marchand ne vend pas cet objet). */
    public static long price(long base, double b, PriceParams p) {
        if (base <= 0L) {
            return 0L;
        }
        double factor = Math.min(p.ceiling(), Math.pow(1.0D + Math.max(0.0D, p.demandRise()), Math.max(0.0D, b) / p.step()));
        return (long) Math.ceil(base * Math.max(1.0D, factor) - EPS);
    }

    /** Rachat d'un lot à la pression de vente {@code s}, sans le plafond lié au prix de vente. */
    public static long payout(long base, double s, PriceParams p) {
        if (base <= 0L) {
            return 0L;
        }
        double decay = Math.pow(1.0D - Math.min(1.0D, Math.max(0.0D, p.supplyDrop())), Math.max(0.0D, s) / p.step());
        double factor = Math.min(1.0D, Math.max(p.floor(), decay));
        return (long) Math.floor(base * Math.max(0.0D, factor) + EPS);
    }

    /**
     * Rachat d'un lot, plafonné à {@code floor(0,8 × price(B))} si l'avant-poste vend aussi l'objet
     * ({@code basePrice > 0}).
     */
    public static long payout(long basePayout, double s, long basePrice, double b, PriceParams p) {
        long value = payout(basePayout, s, p);
        if (basePrice > 0L) {
            value = Math.min(value, (long) Math.floor(MarketRules.MAX_PAYOUT_RATIO * price(basePrice, b, p) + EPS));
        }
        return value;
    }

    /** Coût exact de {@code lots} lots achetés à la suite : somme lot par lot, la pression montant d'un cran à chaque lot. */
    public static long buyTotal(long basePrice, double b, int lots, PriceParams p) {
        long total = 0L;
        for (int i = 0; i < lots; i++) {
            total += price(basePrice, b + i, p);
        }
        return total;
    }

    /** Gain exact de {@code lots} lots vendus à la suite (la pression d'achat {@code b} ne bouge pas pendant une vente). */
    public static long sellTotal(long basePayout, double s, long basePrice, double b, int lots, PriceParams p) {
        long total = 0L;
        for (int i = 0; i < lots; i++) {
            total += payout(basePayout, s + i, basePrice, b, p);
        }
        return total;
    }

    /** Lots achetables d'affilée : stock, plafond par échange et fonds ({@code wallet}). */
    public static int maxBuyLots(long basePrice, double b, int stockLeft, long wallet, PriceParams p) {
        int limit = Math.min(MarketRules.MAX_LOTS, Math.max(0, stockLeft));
        long spent = 0L;
        for (int i = 0; i < limit; i++) {
            spent += price(basePrice, b + i, p);
            if (spent > wallet) {
                return i;
            }
        }
        return limit;
    }

    /** Lots vendables d'affilée : demande, plafond par échange, lots possédés et rachat encore positif. */
    public static int maxSellLots(long basePayout, double s, long basePrice, double b, int demandLeft, int lotsOwned,
                                  PriceParams p) {
        int limit = Math.min(MarketRules.MAX_LOTS, Math.min(Math.max(0, demandLeft), Math.max(0, lotsOwned)));
        for (int i = 0; i < limit; i++) {
            if (payout(basePayout, s + i, basePrice, b, p) <= 0L) {
                return i;
            }
        }
        return limit;
    }
}
