package io.github.theoblaise1.sovereignfrontier.util;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * Plage d'entiers bornes incluses, écrite {@code n} ou {@code [min, max]} dans les JSON de contenu
 * (quantités et montants des caisses, par exemple). Toujours {@code min ≤ max}.
 */
public record IntRange(int min, int max) {
    /** Accepte {@code n} ou {@code [min, max]} ; réécrit {@code n} quand les deux bornes sont égales. */
    public static final Codec<IntRange> CODEC = Codec.either(Codec.INT, Codec.INT.listOf()).comapFlatMap(
            IntRange::fromEither,
            r -> r.min == r.max ? Either.left(r.min) : Either.right(List.of(r.min, r.max)));

    public IntRange {
        if (min > max) {
            throw new IllegalArgumentException("Plage invalide : " + min + " > " + max);
        }
    }

    public static IntRange of(int value) {
        return new IntRange(value, value);
    }

    public static IntRange of(int min, int max) {
        return new IntRange(min, max);
    }

    /** Codec dont les deux bornes doivent tenir dans {@code [lo, hi]}. */
    public static Codec<IntRange> codec(int lo, int hi) {
        return CODEC.validate(r -> r.min >= lo && r.max <= hi ? DataResult.success(r)
                : DataResult.error(() -> "Plage " + r + " hors de [" + lo + ", " + hi + "]"));
    }

    private static DataResult<IntRange> fromEither(Either<Integer, List<Integer>> value) {
        return value.map(n -> DataResult.success(of(n)), list -> {
            if (list.size() != 2) {
                return DataResult.error(() -> "Plage invalide : n ou [min, max] attendu, " + list.size() + " valeur(s) reçue(s)");
            }
            int a = list.get(0);
            int b = list.get(1);
            if (a > b) {
                return DataResult.error(() -> "Plage invalide : [" + a + ", " + b + "] (min > max)");
            }
            return DataResult.success(of(a, b));
        });
    }

    public boolean isConstant() {
        return min == max;
    }

    public boolean contains(int value) {
        return value >= min && value <= max;
    }

    /** Tirage uniforme dans la plage, bornes incluses. */
    public int sample(RandomSource random) {
        return min == max ? min : random.nextIntBetweenInclusive(min, max);
    }

    @Override
    public String toString() {
        return min == max ? Integer.toString(min) : "[" + min + ", " + max + "]";
    }
}
