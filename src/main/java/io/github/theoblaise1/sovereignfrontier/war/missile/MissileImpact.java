// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.war.missile;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.war.WarData;
import io.github.theoblaise1.sovereignfrontier.war.WarProtections;
import io.github.theoblaise1.sovereignfrontier.war.WarQueries;
import io.github.theoblaise1.sovereignfrontier.war.WarScore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Impact d'un missile sur le chunk visé (J7, docs/conception/J7.md §1.6). Règles :
 * <ul>
 *   <li>capitale visée : aucun dégât, journal {@code missile_blocked_capital} ;</li>
 *   <li>guerre terminée entre les deux nations pendant le vol (traité, victoire, délai) : ni destruction ni dégât, le
 *       tir n'a plus de base, et l'impact est journalisé sans effet ;</li>
 *   <li>sinon, destruction des blocs de rayon {@link #BLAST_RADIUS} autour du centre du chunk, à la surface ; le
 *       Trésor et le coffre national sont épargnés, et seuls les blocs du territoire visé sont touchés. La sphère
 *       reste dans le chunk visé (rayon 4 autour de son centre) : aucune destruction hors cible ;</li>
 *   <li>dégâts de {@link #DAMAGE} aux joueurs de la nation visée à {@link #HURT_RADIUS} blocs du centre ; chaque joueur
 *       tué compte pour le score de guerre (D2). Pas de dégât aux monstres ni aux animaux.</li>
 * </ul>
 * Le chunk est lu dans l'Overworld : les missiles ne visent que l'Overworld au J7 ({@link WarData.PendingMissile}
 * ne porte pas de dimension).
 */
public final class MissileImpact {
    /** Rayon de destruction (blocs) autour du centre du chunk visé. */
    static final int BLAST_RADIUS = 4;
    /** Rayon des dégâts aux joueurs (blocs) autour du même centre. */
    static final double HURT_RADIUS = 6.0D;
    /** Dégâts par joueur touché. */
    static final float DAMAGE = 12.0F;

    private MissileImpact() {
    }

    /**
     * Centre de l'impact : milieu du chunk, sur le bloc le plus haut de sa colonne (hauteur lue sur le chunk chargé).
     */
    public static BlockPos epicenter(ServerLevel level, ChunkPos chunk) {
        int x = chunk.getMiddleBlockX();
        int z = chunk.getMiddleBlockZ();
        level.getChunk(chunk.x, chunk.z);
        return new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
    }

    /** Applique l'impact de {@code missile} (déjà retiré de la file par {@link MissileTicker}). */
    public static void impact(MinecraftServer server, WarData.PendingMissile missile) {
        NationData data = NationData.get(server);
        WarData war = WarData.get(server);
        Nation launcher = data.byId(missile.launcherNationId());
        ChunkPos chunk = new ChunkPos(missile.chunkX(), missile.chunkZ());
        Nation target = data.ownerAt(Level.OVERWORLD, chunk.toLong());
        String where = chunk.x + ", " + chunk.z;

        if (WarProtections.isCapitalChunk(server, chunk.x, chunk.z)) {
            journal(launcher, target, "missile_blocked_capital", where);
            notify(server, launcher, target, Msg.warn("missile.blocked_capital"));
            return;
        }
        int destroyed = 0;
        int hurt = 0;
        if (launcher != null && target != null && WarQueries.atWar(war, launcher.id(), target.id())) {
            ServerLevel level = server.overworld();
            BlockPos center = epicenter(level, chunk);
            destroyed = destroyBlocks(level, data, target, chunk, center);
            hurt = hurtDefenders(server, level, launcher, target, center);
        }
        journal(launcher, target, "missile_hit", where, String.valueOf(destroyed), String.valueOf(hurt));
        notify(server, launcher, target, Msg.info("missile.hit", where, destroyed, hurt));
    }

    /** Détruit les blocs de la sphère de rayon {@link #BLAST_RADIUS} qui sont dans {@code chunk}, appartiennent à {@code target}. */
    private static int destroyBlocks(ServerLevel level, NationData data, Nation target, ChunkPos chunk, BlockPos center) {
        int destroyed = 0;
        int radiusSq = BLAST_RADIUS * BLAST_RADIUS;
        long chunkKey = chunk.toLong();
        for (int dx = -BLAST_RADIUS; dx <= BLAST_RADIUS; dx++) {
            for (int dy = -BLAST_RADIUS; dy <= BLAST_RADIUS; dy++) {
                for (int dz = -BLAST_RADIUS; dz <= BLAST_RADIUS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (ChunkPos.asLong(pos) != chunkKey || pos.getY() < level.getMinBuildHeight()
                            || pos.getY() >= level.getMaxBuildHeight()) {
                        continue;
                    }
                    if (data.ownerAt(level.dimension(), pos) != target) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || protectedBlock(state) || state.getDestroySpeed(level, pos) < 0.0F) {
                        continue;
                    }
                    if (level.removeBlock(pos, false)) {
                        destroyed++;
                    }
                }
            }
        }
        return destroyed;
    }

    /** Trésor et coffre national : les blocs de la nation ne sont jamais détruits par un missile. */
    private static boolean protectedBlock(BlockState state) {
        return state.is(ModBlocks.TREASURY.get()) || state.is(ModBlocks.NATIONAL_CHEST.get());
    }

    /**
     * Dégâts aux joueurs de {@code target} dans le rayon ; un joueur tué compte pour {@code launcher}. Le parcours
     * s'arrête si la guerre se termine en cours de route (un meurtre peut conclure la guerre par la victoire).
     */
    private static int hurtDefenders(MinecraftServer server, ServerLevel level, Nation launcher, Nation target, BlockPos center) {
        Vec3 point = Vec3.atCenterOf(center);
        WarData war = WarData.get(server);
        int hurt = 0;
        for (ServerPlayer player : level.players()) {
            if (!WarQueries.atWar(war, launcher.id(), target.id())) {
                break;
            }
            if (!target.isMember(player.getUUID()) || player.position().distanceTo(point) > HURT_RADIUS) {
                continue;
            }
            if (!player.hurt(level.damageSources().generic(), DAMAGE)) {
                continue;
            }
            hurt++;
            if (player.isDeadOrDying()) {
                WarScore.recordKill(server, launcher.id(), target.id());
            }
        }
        return hurt;
    }

    /** Journal des deux nations (la tireuse et la visée, si elles existent et sont distinctes). */
    private static void journal(@Nullable Nation launcher, @Nullable Nation target, String key, String... args) {
        if (launcher != null) {
            launcher.log(key, args);
        }
        if (target != null && target != launcher) {
            target.log(key, args);
        }
    }

    /** Message aux membres en ligne des deux nations. */
    private static void notify(MinecraftServer server, @Nullable Nation launcher, @Nullable Nation target,
                               Component text) {
        if (launcher != null) {
            NationManager.broadcast(server, launcher, text);
        }
        if (target != null && target != launcher) {
            NationManager.broadcast(server, target, text);
        }
    }
}
