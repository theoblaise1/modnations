// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import io.github.theoblaise1.sovereignfrontier.space.TravelPermits;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Déplacement d'un membre d'équipage vers la dimension d'arrivée (docs/conception/J5.md §4.2, étape 6). Le vol réel
 * passe par {@code changeDimension} sous un permis de voyage ({@link #REAL}) ; les GameTests enregistrent la transition
 * au lieu de l'exécuter : un faux joueur est invulnérable, sans tick, et {@code changeDimension} l'ajouterait au niveau
 * d'arrivée.
 */
@FunctionalInterface
public interface CrewTransport {
    /**
     * Déplace {@code player} en {@code position} dans {@code destination}. Le joueur a déjà quitté sa monture et
     * fermé ses menus.
     */
    void transfer(ServerPlayer player, ServerLevel destination, Vec3 position, float yRot, float xRot);

    /**
     * Transport réel : un permis de voyage couvre l'appel (la garde de {@code EntityTravelToDimensionEvent} du WP1
     * l'accepte), retiré ensuite quoi qu'il arrive ; un ticket de portail charge la zone d'arrivée autour du joueur.
     */
    CrewTransport REAL = (player, destination, position, yRot, xRot) -> TravelPermits.with(player, () ->
            player.changeDimension(new DimensionTransition(destination, position, Vec3.ZERO, yRot, xRot,
                    DimensionTransition.PLACE_PORTAL_TICKET)));
}
