// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.nation.CapitalChunk;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Protections anti-écrasement (J7, docs/conception/J7.md §1.5) : reconnaissance du chunk de capitale, qu'un missile ne
 * peut pas viser (WP3). La règle de taille minimale est dans {@link WarQueries#canDeclare}, la trêve dans les transitions.
 * <p>
 * La capitale est le chunk de fondation, lu dans le champ {@link Nation#capital()} du propriétaire du chunk. Une nation
 * fondée avant J7 n'a pas de capitale connue : aucun de ses chunks n'est protégé à ce titre.
 * <p>
 * Aucun scan de nation ni de chunk : une recherche {@code ownerAt} (O(1), cache par chunk), puis une lecture du champ.
 */
public final class WarProtections {
    private WarProtections() {
    }

    /** Surcharge Overworld de {@link #isCapitalChunk(MinecraftServer, ResourceKey, int, int)}. */
    public static boolean isCapitalChunk(MinecraftServer server, int chunkX, int chunkZ) {
        return isCapitalChunk(server, server.overworld().dimension(), chunkX, chunkZ);
    }

    /** Vrai si le chunk ({@code chunkX}, {@code chunkZ}) de {@code dimension} est la capitale de la nation qui le possède. */
    public static boolean isCapitalChunk(MinecraftServer server, ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        Nation owner = NationData.get(server).ownerAt(dimension, ChunkPos.asLong(chunkX, chunkZ));
        if (owner == null) {
            return false;
        }
        CapitalChunk capital = owner.capital();
        return capital != null && capital.dimension().equals(dimension)
                && capital.chunkX() == chunkX && capital.chunkZ() == chunkZ;
    }
}
