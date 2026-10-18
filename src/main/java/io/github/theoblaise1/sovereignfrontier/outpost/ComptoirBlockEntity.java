// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.outpost;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Bloc-entité du comptoir : {@code pool} (écrit par le gabarit de structure) et {@code outpostId} une fois lié.
 * Le ticker n'existe que tant que le comptoir n'est pas lié ({@code BOUND=false}) : coût nul ensuite.
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

    public void setPool(String pool) {
        this.pool = pool == null ? "" : pool;
        setChanged();
    }

    @Nullable
    public UUID outpostId() {
        return outpostId;
    }

    void setOutpostId(@Nullable UUID outpostId) {
        this.outpostId = outpostId;
        setChanged();
    }

    /** Ticker serveur d'un comptoir non lié : liaison dès que possible (thread principal). */
    static void serverTick(Level level, BlockPos pos, BlockState state, ComptoirBlockEntity be) {
        if (level instanceof ServerLevel serverLevel && !state.getValue(ComptoirBlock.BOUND)) {
            OutpostManager.bind(serverLevel, pos, be);
        }
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
