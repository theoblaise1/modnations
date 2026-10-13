package io.github.theoblaise1.sovereignfrontier.research;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.MutableComponent;

import java.util.Optional;

/**
 * Bonus passifs qu'un palier peut accorder (récompense {@code "type": "bonus"}). Les montants des
 * paliers terminés s'additionnent. Ne lister ici que des bonus ayant un effet réel en jeu.
 */
public enum ResearchStat {
    /** Habitants effectifs = bâtiments × (1 + somme), arrondi à l'inférieur. */
    POPULATION_MULTIPLIER("population_multiplier"),
    /** Revenu de population × (1 + somme). */
    INCOME_MULTIPLIER("income_multiplier"),
    /** S'ajoute au bonus de rendement des cultures du territoire. */
    CROP_YIELD("crop_yield"),
    /** Coût de revendication × (1 + somme), somme bornée à -0,5 (valeurs négatives = réduction). */
    CLAIM_COST_MULTIPLIER("claim_cost_multiplier"),
    /** Membres supplémentaires autorisés (arrondi à l'inférieur). */
    MAX_MEMBERS("max_members"),
    /** Durée des recherches ÷ (1 + somme). */
    RESEARCH_SPEED("research_speed");

    private final String id;

    ResearchStat(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public MutableComponent displayName() {
        return Msg.tr("research.stat." + id);
    }

    public static Optional<ResearchStat> byId(String id) {
        for (ResearchStat s : values()) {
            if (s.id.equals(id)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
