// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.enterprise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDomain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contenu d'un fichier {@code data/sovereignfrontier/enterprises/<secteur>.json} : déblocage, domaine de recherche
 * qui augmente le rendement, argent et objets produits par habitant affecté et par cycle de revenu.
 * <p>
 * Les bornes de validation sont des constantes de code (docs/conception/J4.md §1.11), jamais lues dans
 * {@code SFConfig} : la configuration du serveur n'est pas encore chargée au premier chargement des datapacks.
 * {@code tools/economy_checks/enterprises.py} applique les mêmes bornes en CI.
 *
 * @param requires       paliers de recherche à terminer pour débloquer le secteur
 * @param domain         domaine dont chaque palier terminé ajoute {@code bonusPerTier} au multiplicateur
 * @param bonusPerTier   bonus de multiplicateur par palier terminé du domaine (0..{@value #BONUS_PER_TIER_MAX})
 * @param moneyPerWorker crédits versés à la trésorerie par habitant et par cycle (avant multiplicateurs)
 * @param products       objets produits (au plus {@link #MAX_PRODUCTS}, sans doublon)
 */
public record SectorDefinition(List<ResourceLocation> requires, ResearchDomain domain, double bonusPerTier,
                               long moneyPerWorker, List<Product> products) {
    /** {@code per_worker} dans ]0 ; 64]. */
    public static final double PER_WORKER_MAX = 64.0D;
    public static final double BONUS_PER_TIER_MAX = 0.2D;
    public static final long MONEY_PER_WORKER_MAX = 1_000_000L;
    /** Une ligne de la vue Économie affiche au plus 8 produits. */
    public static final int MAX_PRODUCTS = EconomyView.SectorLine.MAX_OUTPUTS;

    /** Champs admis (tout autre champ fait rejeter le fichier : une faute de frappe ne passe pas inaperçue). */
    public static final Set<String> FIELDS = Set.of("requires", "domain", "bonus_per_tier", "money_per_worker", "products");

    /**
     * Objet produit : {@code per_worker} unités par habitant et par cycle (fractions accumulées d'un cycle à
     * l'autre), seulement quand les paliers {@code requires} sont terminés.
     */
    public record Product(Item item, double perWorker, List<ResourceLocation> requires) {
        public static final Set<String> FIELDS = Set.of("item", "per_worker", "requires");

        private static final Codec<Product> RAW_CODEC = RecordCodecBuilder.create(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Product::item),
                Codec.DOUBLE.fieldOf("per_worker").forGetter(Product::perWorker),
                ResourceLocation.CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(Product::requires)
        ).apply(i, Product::new));

        public static final Codec<Product> CODEC = RAW_CODEC.validate(p -> {
            if (p.item == Items.AIR) {
                return DataResult.error(() -> "Produit « minecraft:air » interdit");
            }
            if (!Double.isFinite(p.perWorker) || p.perWorker <= 0.0D || p.perWorker > PER_WORKER_MAX) {
                return DataResult.error(() -> "per_worker de " + BuiltInRegistries.ITEM.getKey(p.item) + " hors de ]0 ; "
                        + PER_WORKER_MAX + "] : " + p.perWorker);
            }
            return DataResult.success(p);
        });

        public Product {
            requires = List.copyOf(requires);
        }
    }

    private static final Codec<ResearchDomain> DOMAIN_CODEC = Codec.STRING.comapFlatMap(
            s -> ResearchDomain.byId(s).filter(d -> d.id().equals(s)).map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Domaine de recherche inconnu : " + s)),
            ResearchDomain::id);

    /** Entier (sans partie décimale tronquée en silence) dans 0..{@value #MONEY_PER_WORKER_MAX}. */
    private static final Codec<Long> MONEY_CODEC = Codec.DOUBLE.comapFlatMap(d -> {
        if (!Double.isFinite(d) || d != Math.floor(d) || d < 0.0D || d > MONEY_PER_WORKER_MAX) {
            return DataResult.error(() -> "money_per_worker doit être un entier de 0 à " + MONEY_PER_WORKER_MAX + " : " + d);
        }
        return DataResult.success(d.longValue());
    }, Long::doubleValue);

    private static final Codec<SectorDefinition> RAW_CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(SectorDefinition::requires),
            DOMAIN_CODEC.fieldOf("domain").forGetter(SectorDefinition::domain),
            Codec.DOUBLE.optionalFieldOf("bonus_per_tier", 0.0D).forGetter(SectorDefinition::bonusPerTier),
            MONEY_CODEC.optionalFieldOf("money_per_worker", 0L).forGetter(SectorDefinition::moneyPerWorker),
            Product.CODEC.listOf().optionalFieldOf("products", List.of()).forGetter(SectorDefinition::products)
    ).apply(i, SectorDefinition::new));

    public static final Codec<SectorDefinition> CODEC = RAW_CODEC.validate(d -> {
        if (!Double.isFinite(d.bonusPerTier) || d.bonusPerTier < 0.0D || d.bonusPerTier > BONUS_PER_TIER_MAX) {
            return DataResult.error(() -> "bonus_per_tier hors de [0 ; " + BONUS_PER_TIER_MAX + "] : " + d.bonusPerTier);
        }
        if (d.products.size() > MAX_PRODUCTS) {
            return DataResult.error(() -> d.products.size() + " produits (maximum " + MAX_PRODUCTS + ")");
        }
        Set<Item> seen = new HashSet<>();
        for (Product p : d.products) {
            if (!seen.add(p.item)) {
                return DataResult.error(() -> "Produit en double : " + BuiltInRegistries.ITEM.getKey(p.item));
            }
        }
        if (d.moneyPerWorker == 0L && d.products.isEmpty()) {
            return DataResult.error(() -> "Le secteur ne produit rien (ni money_per_worker, ni products)");
        }
        return DataResult.success(d);
    });

    public SectorDefinition {
        requires = List.copyOf(requires);
        products = List.copyOf(products);
    }
}
