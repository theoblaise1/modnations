// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.space;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Règles d'accès aux planètes (docs/conception/J5.md §3.3) : revendication et fondation sur une planète, puis (WP1)
 * garde de voyage, bordure souple, lits et ancres, secours, minage. Ébauche du socle : les signatures appelées par
 * {@code NationManager} sont figées, les règles renvoient « aucun refus » jusqu'au lot WP1.
 */
public final class SpaceRules {
    private SpaceRules() {
    }

    /**
     * Motif du refus d'une revendication de la nation dans cette dimension (sans la clé {@code dimension.<planète>}),
     * ou {@code null} si elle est permise. Appelé par {@code NationManager.claim}.
     */
    @Nullable
    public static Component claimBlockReason(Nation nation, ResourceKey<Level> dimension) {
        return null;
    }

    /**
     * Motif du refus de la fondation d'une nation dans cette dimension (toute planète), ou {@code null}. Appelé par
     * {@code NationManager.create}.
     */
    @Nullable
    public static Component foundBlockReason(ResourceKey<Level> dimension) {
        return null;
    }
}
