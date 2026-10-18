package io.github.theoblaise1.sovereignfrontier.reward;

import io.github.theoblaise1.sovereignfrontier.util.WeightedPicker;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tirage d'une ouverture de caisse, avec pitié par entrée rare (fonctions pures, hasard injecté) :
 * <pre>
 * forcée  = argmax{ miss[e] : e rare, miss[e] ≥ seuil }   (égalité → ordre du JSON) ; sinon aucune
 * tirages = (forcée ? [forcée] : []) + sansRemise(tirables − tirages, rolls − |tirages|)
 * pour chaque e rare : miss[e] = (e ∈ tirages) ? 0 : miss[e] + 1
 * </pre>
 * « Toute récompense rare non obtenue en 20 ouvertures devient garantie à la 21e » ; deux rares qui atteignent le
 * seuil ensemble tombent à la 21e puis à la 22e ouverture. Les compteurs d'identifiants inconnus sont purgés.
 */
public final class CrateRoller {
    private CrateRoller() {
    }

    /**
     * Résultat du tirage.
     *
     * @param drawn  entrées tirées, dans l'ordre (l'entrée forcée en premier)
     * @param forced entrée rare garantie par la pitié, ou {@code null}
     * @param miss   nouveaux compteurs de pitié (exactement les identifiants rares de la table)
     */
    public record Roll(List<CrateEntry> drawn, @Nullable CrateEntry forced, Map<String, Integer> miss) {
    }

    /**
     * Gain concret d'une entrée tirée.
     *
     * @param stack objets (vide pour un gain d'argent)
     * @param money crédits (0 pour un gain d'objets)
     */
    public record Gain(CrateEntry entry, ItemStack stack, long money) {
        public boolean isMoney() {
            return stack.isEmpty();
        }
    }

    public static Roll roll(CrateTable table, Map<String, Integer> miss, int threshold, RandomSource random) {
        CrateEntry forced = null;
        int best = -1;
        for (CrateEntry e : table.rares()) {
            int m = miss.getOrDefault(e.key(), 0);
            if (m >= threshold && m > best) {
                forced = e;
                best = m;
            }
        }
        List<CrateEntry> drawn = new ArrayList<>(table.rolls());
        List<CrateEntry> pool = table.eligible();
        if (forced != null) {
            drawn.add(forced);
            pool.remove(forced);
        }
        drawn.addAll(WeightedPicker.pickWithoutReplacement(pool, CrateEntry::weight, table.rolls() - drawn.size(), random));
        Map<String, Integer> next = new LinkedHashMap<>();
        for (CrateEntry e : table.rares()) {
            next.put(e.key(), drawn.contains(e) ? 0 : miss.getOrDefault(e.key(), 0) + 1);
        }
        return new Roll(List.copyOf(drawn), forced, next);
    }

    /** Quantités et montants concrets des entrées tirées (bornes incluses). */
    public static List<Gain> realize(List<CrateEntry> drawn, RandomSource random) {
        List<Gain> out = new ArrayList<>(drawn.size());
        for (CrateEntry e : drawn) {
            switch (e.reward()) {
                case CrateEntry.ItemReward it -> out.add(new Gain(e, it.stack(it.count().sample(random)), 0L));
                case CrateEntry.MoneyReward m -> out.add(new Gain(e, ItemStack.EMPTY, m.amount().sample(random)));
                case CrateEntry.Placeholder p -> {
                    // Jamais tirée : exclue de table.eligible().
                }
            }
        }
        return out;
    }
}
