package io.github.theoblaise1.sovereignfrontier.outpost;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Une offre d'un pool marchand ({@code data/<ns>/merchants/<pool>.json}) : un lot de {@code count} objets que le
 * marchand vend ({@code price}) et/ou rachète ({@code payout}).
 *
 * @param price      prix de base d'un lot (0 = le marchand ne vend pas cet objet)
 * @param payout     rachat de base d'un lot (0 = le marchand ne l'achète pas)
 * @param stock      lots en vente par rotation (défaut 16)
 * @param demand     lots rachetés par rotation (défaut 32)
 * @param weight     poids du tirage parmi les offres non garanties (défaut 1)
 * @param guaranteed toujours présente à l'étal (défaut faux)
 */
public record MerchantOffer(Item item, int count, int price, int payout, int stock, int demand, int weight,
                            boolean guaranteed) {
    private static final Codec<Integer> VALUE = Codec.intRange(1, MarketRules.MAX_BASE_VALUE);
    private static final Codec<Integer> QUANTITY = Codec.intRange(1, MarketRules.MAX_STOCK);

    public static final Codec<MerchantOffer> CODEC = RecordCodecBuilder.create(i -> i.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(MerchantOffer::item),
            Codec.intRange(1, 99).fieldOf("count").forGetter(MerchantOffer::count),
            VALUE.optionalFieldOf("price", 0).forGetter(MerchantOffer::price),
            VALUE.optionalFieldOf("payout", 0).forGetter(MerchantOffer::payout),
            QUANTITY.optionalFieldOf("stock", 16).forGetter(MerchantOffer::stock),
            QUANTITY.optionalFieldOf("demand", 32).forGetter(MerchantOffer::demand),
            Codec.intRange(1, MarketRules.MAX_WEIGHT).optionalFieldOf("weight", 1).forGetter(MerchantOffer::weight),
            Codec.BOOL.optionalFieldOf("guaranteed", false).forGetter(MerchantOffer::guaranteed)
    ).apply(i, MerchantOffer::new));

    public boolean sells() {
        return price > 0;
    }

    public boolean buys() {
        return payout > 0;
    }
}
