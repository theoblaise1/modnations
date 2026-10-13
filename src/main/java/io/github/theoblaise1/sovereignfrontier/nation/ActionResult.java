package io.github.theoblaise1.sovereignfrontier.nation;

import net.minecraft.network.chat.Component;

/** Résultat d'une action métier : succès/échec et message explicite (en français) à afficher. */
public record ActionResult(boolean success, Component message) {
    public static ActionResult ok(Component message) {
        return new ActionResult(true, message);
    }

    public static ActionResult fail(Component message) {
        return new ActionResult(false, message);
    }
}
