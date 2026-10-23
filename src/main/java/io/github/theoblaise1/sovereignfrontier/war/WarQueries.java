package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.SFConfig;

import java.util.UUID;

/**
 * Fonctions pures sur {@link WarData} et {@link SFConfig} (J7, docs/conception/J7.md §1.1, §1.5). Aucune lecture de
 * nation, de chunk ni de monde : les effectifs sont passés par l'appelant, sans scan.
 */
public final class WarQueries {
    /** Verdict de {@link #canDeclare} : {@link #OK} si la déclaration est permise, sinon la première règle violée. */
    public enum DeclareCheck {
        OK,
        /** Attaquant et défenseur sont la même nation. */
        SAME_NATION,
        /** L'attaquant compte moins de {@code minMembersToDeclare} membres. */
        ATTACKER_TOO_SMALL,
        /** Le défenseur compte moins de {@code minMembersToDeclare} membres. */
        DEFENDER_TOO_SMALL,
        /** Une préparation ou une guerre est déjà en cours entre les deux nations. */
        ENGAGED,
        /** Une trêve est en vigueur entre les deux nations (après une fin de guerre). */
        TRUCE
    }

    private WarQueries() {
    }

    /** État entre {@code a} et {@code b} ; {@link WarState#PAIX} pour une même nation ou une paire sans relation. */
    public static WarState stateOf(WarData data, UUID a, UUID b) {
        if (a.equals(b)) {
            return WarState.PAIX;
        }
        return data.state(a, b);
    }

    /** Vrai si les deux nations sont en guerre ouverte (combat autorisé, score). */
    public static boolean atWar(WarData data, UUID a, UUID b) {
        return stateOf(data, a, b) == WarState.GUERRE;
    }

    /**
     * Vérifie une déclaration de guerre de {@code attacker} contre {@code defender} à l'instant {@code nowEpochMs}
     * (§1.1, D3-c). Les effectifs sont ceux des deux nations au moment de la demande.
     * <p>
     * Une trêve ne bloque que tant que {@code nowEpochMs} est avant son échéance : une relation en {@code TREVE} échue
     * ne bloque plus, même si la boucle ne l'a pas encore repassée en paix.
     */
    public static DeclareCheck canDeclare(WarData data, UUID attacker, UUID defender, int attackerMembers,
                                          int defenderMembers, long nowEpochMs) {
        if (attacker.equals(defender)) {
            return DeclareCheck.SAME_NATION;
        }
        int minimum = SFConfig.MIN_MEMBERS_TO_DECLARE.get();
        if (attackerMembers < minimum) {
            return DeclareCheck.ATTACKER_TOO_SMALL;
        }
        if (defenderMembers < minimum) {
            return DeclareCheck.DEFENDER_TOO_SMALL;
        }
        WarData.Relation relation = data.relation(attacker, defender);
        if (relation == null) {
            return DeclareCheck.OK;
        }
        return switch (relation.state()) {
            case PAIX -> DeclareCheck.OK;
            case PREPARATION, GUERRE -> DeclareCheck.ENGAGED;
            case TREVE -> nowEpochMs < relation.untilEpochMs() ? DeclareCheck.TRUCE : DeclareCheck.OK;
        };
    }
}
