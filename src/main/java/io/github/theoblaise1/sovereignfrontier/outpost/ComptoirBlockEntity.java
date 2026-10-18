// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.outpost;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Bloc-entité du comptoir : {@code pool} (écrit par le gabarit) et {@code outpostId} une fois lié.
 * Ébauche du socle : simple conservation du NBT.
 */
public class ComptoirBlockEntity extends BlockEntity {
    private String pool = "";
    @Nullable
    private UUID outpostId;

    public ComptoirBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COMPTOIR.get(), pos, state);
    }

    public String pool() {
        return pool;
    }

    @Nullable
    public UUID outpostId() {
        return outpostId;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pool = tag.getString("pool");
        outpostId = tag.hasUUID("outpostId") ? tag.getUUID("outpostId") : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("pool", pool);
        if (outpostId != null) {
            tag.putUUID("outpostId", outpostId);
        }
    }
}
