package io.github.theoblaise1.sovereignfrontier.research;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Optional;

/**
 * Contenu d'un fichier {@code data/<ns>/research/<id>.json}. L'identifiant du palier vient du
 * chemin du fichier ; le palier précédent du même domaine est un prérequis implicite.
 */
public record ResearchDefinition(
        ResearchDomain domain,
        int tier,
        Cost cost,
        List<ResourceLocation> requires,
        int minPopulation,
        Optional<Long> durationSeconds,
        List<ResearchReward> rewards) {

    /** Durée de base : 120 s × palier^1,35 (sauf {@code duration_seconds} explicite). */
    public long baseDurationSeconds() {
        return durationSeconds.orElseGet(() -> Math.round(120.0D * Math.pow(tier, 1.35D)));
    }

    /** Objet à fournir (consommé dans le coffre national au lancement). */
    public record ItemCost(Item item, int count) {
        public static final Codec<ItemCost> CODEC = RecordCodecBuilder.create(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(ItemCost::item),
                ExtraCodecs.POSITIVE_INT.fieldOf("count").forGetter(ItemCost::count)
        ).apply(i, ItemCost::new));
    }

    /** Coût : trésorerie, points de recherche (gagnés par contrats, J4) et objets. */
    public record Cost(long treasury, long points, List<ItemCost> items) {
        public static final Cost FREE = new Cost(0L, 0L, List.of());
        private static final Codec<Long> NON_NEGATIVE_LONG = Codec.LONG.validate(v -> v >= 0
                ? DataResult.success(v) : DataResult.error(() -> "Valeur négative : " + v));
        public static final Codec<Cost> CODEC = RecordCodecBuilder.create(i -> i.group(
                NON_NEGATIVE_LONG.optionalFieldOf("treasury", 0L).forGetter(Cost::treasury),
                NON_NEGATIVE_LONG.optionalFieldOf("points", 0L).forGetter(Cost::points),
                ItemCost.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(Cost::items)
        ).apply(i, Cost::new));
    }

    private static final Codec<ResearchDomain> DOMAIN_CODEC = Codec.STRING.comapFlatMap(
            s -> ResearchDomain.byId(s).map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Domaine inconnu : " + s)),
            ResearchDomain::id);

    private static final Codec<ResearchDefinition> RAW_CODEC = RecordCodecBuilder.create(i -> i.group(
            DOMAIN_CODEC.fieldOf("domain").forGetter(ResearchDefinition::domain),
            ExtraCodecs.POSITIVE_INT.fieldOf("tier").forGetter(ResearchDefinition::tier),
            Cost.CODEC.optionalFieldOf("cost", Cost.FREE).forGetter(ResearchDefinition::cost),
            ResourceLocation.CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(ResearchDefinition::requires),
            ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("min_population", 0).forGetter(ResearchDefinition::minPopulation),
            Codec.LONG.optionalFieldOf("duration_seconds").forGetter(ResearchDefinition::durationSeconds),
            ResearchReward.CODEC.listOf().optionalFieldOf("rewards", List.of()).forGetter(ResearchDefinition::rewards)
    ).apply(i, ResearchDefinition::new));

    public static final Codec<ResearchDefinition> CODEC = RAW_CODEC.validate(d -> {
        if (d.tier > d.domain.tierCount()) {
            return DataResult.error(() -> "Palier " + d.tier + " hors limites pour " + d.domain.id()
                    + " (max " + d.domain.tierCount() + ")");
        }
        if (d.durationSeconds.isPresent() && d.durationSeconds.get() <= 0) {
            return DataResult.error(() -> "duration_seconds doit être strictement positif");
        }
        return DataResult.success(d);
    });
}
