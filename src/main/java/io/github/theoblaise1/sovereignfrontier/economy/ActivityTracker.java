package io.github.theoblaise1.sovereignfrontier.economy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Détection de l'activité réelle des joueurs en ligne, qui alimente le Terminal bancaire. Chaque signal fixe
 * {@code lastSignalMs = now} ; un joueur est actif tant que {@code now − lastSignalMs ≤ terminalAfkSeconds}.
 * <ul>
 *   <li><b>Regard</b> : {@code |wrapDegrees(Δlacet)| + |Δtangage| ≥ terminalLookDegrees} entre deux échantillons ;</li>
 *   <li><b>Marche</b> : déplacement horizontal ≥ {@code terminalWalkBlocks} entre deux échantillons, au sol, hors de
 *       l'eau et de la lave, sans monture ni vol plané (un déplacement passif ne compte jamais) ;</li>
 *   <li><b>Casse ou pose</b> à une position absente des 4 dernières positions de casse/pose du joueur (un générateur
 *       de pierre ne compte pas) ;</li>
 *   <li><b>Échanges</b> : chat, commande, ouverture de conteneur, artisanat.</li>
 * </ul>
 * Une attaque ou l'usage d'un objet ne comptent jamais (auto-clic sur broyeur, pêche AFK). État en mémoire, limité
 * aux joueurs en ligne : vidé à la connexion ({@code lastSignalMs = 0}), à la déconnexion et à l'arrêt.
 */
public final class ActivityTracker {
    /** Positions de casse/pose mémorisées par joueur. */
    public static final int RECENT_POSITIONS = 4;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private ActivityTracker() {
    }

    /**
     * Instantané du mouvement d'un joueur à l'échantillonnage.
     *
     * @param grounded au sol ({@code onGround()})
     * @param passive  dans l'eau ou la lave, passager ou en vol plané : la marche ne compte pas
     */
    public record Motion(float yaw, float pitch, double x, double z, boolean grounded, boolean passive) {
        public static Motion of(ServerPlayer player) {
            return new Motion(player.getYRot(), player.getXRot(), player.getX(), player.getZ(), player.onGround(),
                    player.isInWater() || player.isInLava() || player.isPassenger() || player.isFallFlying());
        }
    }

    /** Retour à afficher après un échantillonnage. */
    public enum Notice { NONE, PAUSED, RESUMED }

    private static final class State {
        boolean sampled;
        float yaw;
        float pitch;
        double x;
        double z;
        long lastSignalMs;
        final BlockPos[] recent = new BlockPos[RECENT_POSITIONS];
        int recentNext;
        /** Gains en pause (vrai au départ : aucune annonce de reprise tant qu'aucune pause n'a été annoncée). */
        boolean paused = true;
        boolean pauseAnnounced;
        long lastFullNoticeMs;
    }

    private static State state(UUID player) {
        return STATES.computeIfAbsent(player, id -> new State());
    }

    /** Signal d'activité (chat, commande, conteneur, artisanat). */
    public static void signal(UUID player, long now) {
        state(player).lastSignalMs = now;
    }

    /**
     * Casse ou pose d'un bloc : signal seulement si la position ne figure pas parmi les {@value #RECENT_POSITIONS}
     * dernières positions de casse/pose du joueur.
     *
     * @return vrai si le signal a été retenu
     */
    public static boolean signalAt(UUID player, BlockPos pos, long now) {
        State s = state(player);
        for (BlockPos p : s.recent) {
            if (pos.equals(p)) {
                return false;
            }
        }
        s.recent[s.recentNext] = pos.immutable();
        s.recentNext = (s.recentNext + 1) % RECENT_POSITIONS;
        s.lastSignalMs = now;
        return true;
    }

    /**
     * Échantillon périodique (1 s) : compare le regard et la position au précédent échantillon.
     *
     * @return vrai si l'échantillon a produit un signal (regard ou marche)
     */
    public static boolean sample(UUID player, Motion m, long now, double lookDegrees, double walkBlocks) {
        State s = state(player);
        boolean signaled = false;
        if (s.sampled) {
            float look = Math.abs(Mth.wrapDegrees(m.yaw() - s.yaw)) + Math.abs(m.pitch() - s.pitch);
            double dx = m.x() - s.x;
            double dz = m.z() - s.z;
            boolean walked = dx * dx + dz * dz >= walkBlocks * walkBlocks && m.grounded() && !m.passive();
            if (look >= lookDegrees || walked) {
                s.lastSignalMs = now;
                signaled = true;
            }
        }
        s.sampled = true;
        s.yaw = m.yaw();
        s.pitch = m.pitch();
        s.x = m.x();
        s.z = m.z();
        return signaled;
    }

    /** Actif si un signal a eu lieu il y a au plus {@code afkMs} (jamais avant le premier signal de la session). */
    public static boolean isActive(UUID player, long now, long afkMs) {
        State s = STATES.get(player);
        return s != null && s.lastSignalMs > 0L && now - s.lastSignalMs <= afkMs;
    }

    /** Dernier signal (epoch ms), 0 si aucun depuis la connexion. */
    public static long lastSignal(UUID player) {
        State s = STATES.get(player);
        return s == null ? 0L : s.lastSignalMs;
    }

    /**
     * Met à jour le drapeau de pause : la pause est annoncée une seule fois, la reprise seulement après une pause
     * annoncée (rien à la connexion).
     */
    public static Notice updatePause(UUID player, boolean active) {
        State s = state(player);
        if (active && s.paused) {
            s.paused = false;
            boolean announce = s.pauseAnnounced;
            s.pauseAnnounced = false;
            return announce ? Notice.RESUMED : Notice.NONE;
        }
        if (!active && !s.paused) {
            s.paused = true;
            s.pauseAnnounced = true;
            return Notice.PAUSED;
        }
        return Notice.NONE;
    }

    /** Vrai (et mémorise l'instant) si l'avis « épargne pleine » peut être réaffiché ({@code intervalMs} écoulé). */
    public static boolean shouldNotifyFull(UUID player, long now, long intervalMs) {
        State s = state(player);
        if (s.lastFullNoticeMs != 0L && now - s.lastFullNoticeMs < intervalMs && now >= s.lastFullNoticeMs) {
            return false;
        }
        s.lastFullNoticeMs = now;
        return true;
    }

    public static void forget(UUID player) {
        STATES.remove(player);
    }

    public static void clear() {
        STATES.clear();
    }

    /** Joueurs suivis (observé par les GameTests). */
    public static int tracked() {
        return STATES.size();
    }
}
