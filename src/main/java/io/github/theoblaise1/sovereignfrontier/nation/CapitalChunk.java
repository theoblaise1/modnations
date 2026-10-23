// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.nation;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Chunk de capitale d'une nation : le chunk de fondation (J7, docs/conception/J7.md §1.5). Champ optionnel de
 * {@link Nation}, absent pour les nations fondées avant J7. Sauvegarde NBT : {@code dim} (identifiant de dimension),
 * {@code x} et {@code z} (coordonnées de chunk).
 */
public record CapitalChunk(ResourceKey<Level> dimension, int chunkX, int chunkZ) {

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", dimension.location().toString());
        tag.putInt("x", chunkX);
        tag.putInt("z", chunkZ);
        return tag;
    }

    /** Lecture ; {@code null} si l'identifiant de dimension est mal formé. */
    @Nullable
    static CapitalChunk load(CompoundTag tag) {
        ResourceLocation loc = ResourceLocation.tryParse(tag.getString("dim"));
        if (loc == null) {
            return null;
        }
        return new CapitalChunk(ResourceKey.create(Registries.DIMENSION, loc), tag.getInt("x"), tag.getInt("z"));
    }
}
