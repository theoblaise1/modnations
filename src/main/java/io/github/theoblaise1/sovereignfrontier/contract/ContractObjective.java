// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.contract;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;

/**
 * Objectif d'un modèle de contrat ({@code objective} du JSON). Interface scellée : un nouveau type de contrat (escorte,
 * guerre, diplomatie au J7) ajoute un variant et un {@code kind} JSON.
 * <ul>
 *   <li>{@link Deliver} : livrer {@code amount} objets intacts ;</li>
 *   <li>{@link Kill} : éliminer {@code amount} monstres d'un type ou d'un tag ({@code #}) ;</li>
 *   <li>{@link Explore} : atteindre une cible ({@link ExploreTarget}).</li>
 * </ul>
 */
public sealed interface ContractObjective permits ContractObjective.Deliver, ContractObjective.Kill, ContractObjective.Explore {
    ContractKind kind();

    /** Quantité de base, tirée à la génération (avant la mise à l'échelle par le nombre de membres). */
    Amount amount();

    /** Vrai si la quantité est mise à l'échelle par le nombre de membres (livraison et chasse seulement). */
    boolean scalesWithMembers();

    /**
     * Plage de quantité {@code {"min", "max", "step"}} : tirage uniforme parmi {@code min, min + step, …} (sans dépasser
     * {@code max}).
     */
    record Amount(int min, int max, int step) {
        public static final Amount ONE = new Amount(1, 1, 1);

        public Amount {
            if (min < 1 || max < min || step < 1) {
                throw new IllegalArgumentException("Quantité invalide : " + min + ".." + max + " (pas " + step + ")");
            }
        }

        /** Tirage uniforme sur la grille du pas. */
        public int sample(RandomSource random) {
            int steps = (max - min) / step;
            return steps <= 0 ? min : min + step * random.nextInt(steps + 1);
        }
    }

    /** Livrer des objets intacts (inventaire principal et main secondaire du livreur). */
    record Deliver(Item item, Amount amount) implements ContractObjective {
        @Override
        public ContractKind kind() {
            return ContractKind.DELIVER;
        }

        @Override
        public boolean scalesWithMembers() {
            return true;
        }

        public ResourceLocation itemId() {
            return BuiltInRegistries.ITEM.getKey(item);
        }
    }

    /** Éliminer des monstres d'un type ({@code minecraft:zombie}) ou d'un tag ({@code #minecraft:skeletons}). */
    record Kill(ExtraCodecs.TagOrElementLocation entity, Amount amount) implements ContractObjective {
        @Override
        public ContractKind kind() {
            return ContractKind.KILL;
        }

        @Override
        public boolean scalesWithMembers() {
            return true;
        }
    }

    /** Exploration : une seule cible ; la quantité vaut 1, sauf pour les avant-postes (nombre à visiter). */
    record Explore(ExploreTarget target) implements ContractObjective {
        @Override
        public ContractKind kind() {
            return ContractKind.EXPLORE;
        }

        @Override
        public Amount amount() {
            return target instanceof OutpostsTarget o ? new Amount(o.min(), o.max(), 1) : Amount.ONE;
        }

        @Override
        public boolean scalesWithMembers() {
            return false;
        }
    }

    /** Cible d'exploration (exactement une dans le JSON). */
    sealed interface ExploreTarget permits BiomeTarget, WaypointTarget, OutpostsTarget, DimensionTarget {
    }

    /** Être dans un biome (identifiant ou {@code #tag}) à {@code minDistance} blocs au moins de l'origine. */
    record BiomeTarget(ExtraCodecs.TagOrElementLocation biome, int minDistance) implements ExploreTarget {
    }

    /** Atteindre, à {@code radius} blocs près, un point tiré à l'acceptation entre {@code min} et {@code max} blocs de l'origine. */
    record WaypointTarget(int min, int max, int radius) implements ExploreTarget {
    }

    /** Passer à 16 blocs au plus de {@code min..max} ancres d'avant-poste distinctes, non encore visitées par ce contrat. */
    record OutpostsTarget(int min, int max) implements ExploreTarget {
    }

    /** Entrer dans une dimension ({@code minecraft:the_nether}). */
    record DimensionTarget(ResourceLocation dimension) implements ExploreTarget {
    }
}
