package io.github.theoblaise1.sovereignfrontier.outpost;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pool marchand : variante du marchand (nom affiché, apparence), nombre d'offres tirées à chaque rotation et offres.
 * Validé au chargement avec des constantes de code seulement ({@link MarketRules}) ; les contrôles qui demandent les
 * tags (objets interdits) et l'écart global sont faits à la publication ({@link MerchantRegistry#validate}).
 *
 * @param rolls offres non garanties tirées (sans remise) à chaque rotation, en plus des offres garanties
 */
public record MerchantPool(ResourceLocation id, String variant, int rolls, List<MerchantOffer> offers) {
    private static final Pattern VARIANT = Pattern.compile("[a-z0-9_]{1,32}");

    private record Raw(String variant, int rolls, List<MerchantOffer> offers) {
    }

    private static final Codec<Raw> RAW_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("variant").forGetter(Raw::variant),
            Codec.intRange(0, MarketRules.MAX_OFFERS).fieldOf("rolls").forGetter(Raw::rolls),
            MerchantOffer.CODEC.listOf().fieldOf("offers").forGetter(Raw::offers)
    ).apply(i, Raw::new));

    public MerchantPool {
        offers = List.copyOf(offers);
    }

    /**
     * Analyse stricte d'un fichier : une erreur de format (même partielle, ex. une offre invalide retirée de la liste)
     * ou une contrainte non respectée rejette le pool entier.
     */
    public static DataResult<MerchantPool> parse(ResourceLocation id, JsonElement json, DynamicOps<JsonElement> ops) {
        DataResult<Raw> raw = RAW_CODEC.parse(ops, json);
        if (raw.error().isPresent()) {
            return DataResult.error(() -> raw.error().get().message());
        }
        Raw r = raw.getOrThrow();
        MerchantPool pool = new MerchantPool(id, r.variant(), r.rolls(), r.offers());
        String problem = pool.problem();
        return problem == null ? DataResult.success(pool) : DataResult.error(() -> problem);
    }

    /** Première contrainte non respectée (constantes de code, sans les tags), ou {@code null}. */
    @Nullable
    public String problem() {
        if (!VARIANT.matcher(variant).matches()) {
            return "variante « " + variant + " » invalide (a-z, 0-9, _)";
        }
        if (offers.isEmpty() || offers.size() > MarketRules.MAX_OFFERS) {
            return "entre 1 et " + MarketRules.MAX_OFFERS + " offres attendues (" + offers.size() + ")";
        }
        Set<Item> seen = new HashSet<>();
        int optional = 0;
        int guaranteed = 0;
        for (MerchantOffer offer : offers) {
            String name = BuiltInRegistries.ITEM.getKey(offer.item()).toString();
            if (offer.item() == Items.AIR) {
                return "objet vide (minecraft:air)";
            }
            if (!seen.add(offer.item())) {
                return "objet en double : " + name;
            }
            if (!offer.sells() && !offer.buys()) {
                return name + " : ni « price » ni « payout »";
            }
            int maxStack = offer.item().getDefaultMaxStackSize();
            if (offer.count() > maxStack) {
                return name + " : lot de " + offer.count() + " au-delà de la taille de pile (" + maxStack + ")";
            }
            if (offer.guaranteed()) {
                guaranteed++;
            } else {
                optional++;
            }
        }
        if (rolls > optional) {
            return "« rolls » (" + rolls + ") dépasse le nombre d'offres non garanties (" + optional + ")";
        }
        if (guaranteed + rolls < 1) {
            return "étal toujours vide : aucune offre garantie et « rolls » nul";
        }
        return null;
    }

    @Nullable
    public MerchantOffer offer(Item item) {
        for (MerchantOffer offer : offers) {
            if (offer.item() == item) {
                return offer;
            }
        }
        return null;
    }
}
