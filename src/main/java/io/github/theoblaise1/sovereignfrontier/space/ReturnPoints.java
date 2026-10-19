package io.github.theoblaise1.sovereignfrontier.space;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Point de retour sur Terre (docs/conception/J5.md §1.3 et §4.4), sans condition de recherche : un joueur n'est
 * jamais bloqué par une recherche révoquée ou un territoire perdu. Dans l'ordre :
 * <ol>
 *   <li>{@link Source#HOME} : le départ terrestre, s'il est dans l'Overworld et que son chunk est à la nation ou
 *       libre ;</li>
 *   <li>{@link Source#CORE} : le premier Trésor ou coffre national de la nation dans l'Overworld, sur son
 *       territoire ;</li>
 *   <li>{@link Source#SPAWN} : le point d'apparition partagé du monde.</li>
 * </ol>
 * Le résultat est une colonne : l'appelant (vol, secours) choisit la hauteur au moment du transfert. Lectures d'index
 * seulement, aucun chargement de chunk.
 */
public final class ReturnPoints {
    public enum Source {
        HOME,
        CORE,
        SPAWN
    }

    /** Colonne d'arrivée dans l'Overworld et règle qui l'a choisie. */
    public record Target(GlobalPos column, Source source) {
    }

    private ReturnPoints() {
    }

    /** Point de retour réel : territoire du serveur et point d'apparition de l'Overworld. */
    public static Target resolve(MinecraftServer server, @Nullable Nation nation, @Nullable GlobalPos home) {
        return resolve(home, nation == null ? null : nation.id(), nation == null ? List.of() : nation.cores(),
                ChunkOwners.of(server), server.overworld().getSharedSpawnPos());
    }

    /**
     * Fonction pure (GameTests).
     *
     * @param nation     nation dont le territoire est accepté (celle de la fusée ou du joueur), ou {@code null}
     * @param cores      Trésors et coffres nationaux de cette nation, dans l'ordre d'enregistrement
     * @param worldSpawn point d'apparition partagé de l'Overworld
     */
    public static Target resolve(@Nullable GlobalPos home, @Nullable UUID nation, Collection<GlobalPos> cores,
                                 ChunkOwners owners, BlockPos worldSpawn) {
        if (home != null && home.dimension().equals(Level.OVERWORLD)) {
            UUID owner = owners.ownerAt(Level.OVERWORLD, ChunkPos.asLong(home.pos()));
            if (owner == null || owner.equals(nation)) {
                return new Target(home, Source.HOME);
            }
        }
        if (nation != null) {
            for (GlobalPos core : cores) {
                if (core.dimension().equals(Level.OVERWORLD)
                        && nation.equals(owners.ownerAt(Level.OVERWORLD, ChunkPos.asLong(core.pos())))) {
                    return new Target(core, Source.CORE);
                }
            }
        }
        return new Target(GlobalPos.of(Level.OVERWORLD, worldSpawn.immutable()), Source.SPAWN);
    }
}
