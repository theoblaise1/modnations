package io.github.theoblaise1.sovereignfrontier.util;

import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Tirages pondérés déterministes : avec la même graine ({@code RandomSource.create(seed)}) et la même liste,
 * le résultat est identique (étal d'un marchand, tableau de contrats, tirages de caisse rejoués en test).
 * Les candidats de poids nul ou négatif ne sont jamais tirés.
 */
public final class WeightedPicker {
    private WeightedPicker() {
    }

    /** Somme des poids strictement positifs. */
    public static <T> long totalWeight(List<? extends T> candidates, ToIntFunction<? super T> weight) {
        long total = 0L;
        for (T c : candidates) {
            total += Math.max(0, weight.applyAsInt(c));
        }
        return total;
    }

    /** Un candidat tiré au prorata de son poids, ou {@code null} si aucun n'a de poids positif. */
    @Nullable
    public static <T> T pick(List<? extends T> candidates, ToIntFunction<? super T> weight, RandomSource random) {
        List<T> one = pickWithoutReplacement(candidates, weight, 1, random);
        return one.isEmpty() ? null : one.get(0);
    }

    /**
     * Jusqu'à {@code count} candidats distincts, tirés successivement au prorata de leur poids parmi ceux qui
     * restent (sans remise), dans l'ordre du tirage. Moins de {@code count} si les candidats tirables manquent.
     */
    public static <T> List<T> pickWithoutReplacement(List<? extends T> candidates, ToIntFunction<? super T> weight,
                                                     int count, RandomSource random) {
        List<T> pool = new ArrayList<>(candidates.size());
        List<Integer> weights = new ArrayList<>(candidates.size());
        for (T c : candidates) {
            int w = weight.applyAsInt(c);
            if (w > 0) {
                pool.add(c);
                weights.add(w);
            }
        }
        List<T> out = new ArrayList<>(Math.max(0, Math.min(count, pool.size())));
        while (out.size() < count && !pool.isEmpty()) {
            long total = 0L;
            for (int w : weights) {
                total += w;
            }
            long roll = total <= Integer.MAX_VALUE ? random.nextInt((int) total) : Math.floorMod(random.nextLong(), total);
            int index = 0;
            while (roll >= weights.get(index)) {
                roll -= weights.get(index);
                index++;
            }
            out.add(pool.remove(index));
            weights.remove(index);
        }
        return out;
    }
}
