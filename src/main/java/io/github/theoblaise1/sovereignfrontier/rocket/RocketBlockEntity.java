// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bloc-entité de la fusée (docs/conception/J5.md §2.2) : {@code owner}, {@code home}, {@code pads} et la soute de 9
 * cases ({@code items}). Ébauche du socle : le NBT est conservé tel quel, pour qu'aucune donnée ne se perde avant le
 * lot WP2.
 */
public class RocketBlockEntity extends BlockEntity {
    private CompoundTag raw = new CompoundTag();

    public RocketBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROCKET.get(), pos, state);
    }

    /** NBT conservé (copie). */
    public CompoundTag raw() {
        return raw.copy();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.merge(raw.copy());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag copy = tag.copy();
        for (String meta : new String[]{"id", "x", "y", "z", "components"}) {
            copy.remove(meta);
        }
        raw = copy;
    }
}
