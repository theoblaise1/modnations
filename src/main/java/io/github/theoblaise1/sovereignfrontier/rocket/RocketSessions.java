// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import net.minecraft.core.GlobalPos;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions de fusée (docs/conception/J5.md §4.1) : un clic droit accroupi sur une fusée ouvre, pour ce joueur, une
 * session de 60 s qui désigne la fusée pour les commandes {@code /rocket}. En mémoire seulement (vidée à l'arrêt) ; le
 * temps est injecté (epoch ms) pour les GameTests. Une commande revalide de toute façon la distance, la permission et la
 * présence du bloc : la session ne donne aucun droit, elle évite seulement de viser un bloc par coordonnées.
 */
public final class RocketSessions {
    public static final long DURATION_MS = 60_000L;

    /** Fusée désignée et fin de la session (epoch ms). */
    public record Session(GlobalPos rocket, long expiresAt) {
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private RocketSessions() {
    }

    /** Ouvre (ou remplace) la session du joueur. */
    public static void open(UUID player, GlobalPos rocket, long now) {
        SESSIONS.values().removeIf(s -> s.expiresAt() <= now);
        SESSIONS.put(player, new Session(rocket, now + DURATION_MS));
    }

    /** Fusée de la session en cours du joueur, ou {@code null} (aucune, ou expirée). */
    @Nullable
    public static GlobalPos rocketOf(UUID player, long now) {
        Session session = SESSIONS.get(player);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() <= now) {
            SESSIONS.remove(player, session);
            return null;
        }
        return session.rocket();
    }

    public static void close(UUID player) {
        SESSIONS.remove(player);
    }

    public static int size() {
        return SESSIONS.size();
    }

    public static void clear() {
        SESSIONS.clear();
    }
}
