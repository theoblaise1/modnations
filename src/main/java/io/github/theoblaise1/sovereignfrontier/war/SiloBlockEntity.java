// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Bloc-entité du silo à missiles (J7, docs/conception/J7.md §1.6) : nation propriétaire et munitions chargées. Les
 * munitions se chargent à la main (clic droit avec un {@code missile_guide} ou une {@code ogive_lourde}), jusqu'à
 * {@link #MAX_MISSILES} et {@link #MAX_WARHEADS} ; un tir en consomme une de chaque. Le cooldown reste dans {@link WarData}.
 */
public class SiloBlockEntity extends BlockEntity {
    /** Capacité : une pile de missiles guidés (pile de 4). */
    public static final int MAX_MISSILES = 4;
    /** Capacité : une pile d'ogives lourdes (pile de 2). */
    public static final int MAX_WARHEADS = 2;

    private static final String OWNER = "owner";
    private static final String MISSILES = "missiles";
    private static final String WARHEADS = "warheads";

    /** Nation propriétaire, ou {@code null} tant que le silo n'est pas posé sur un territoire (inerte). */
    @Nullable
    private UUID owner;
    private int missiles;
    private int warheads;

    public SiloBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SILO_MISSILES.get(), pos, state);
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    public int missiles() {
        return missiles;
    }

    public int warheads() {
        return warheads;
    }

    /** Vrai si le silo a de quoi tirer : un missile guidé et une ogive lourde. */
    public boolean canFire() {
        return missiles > 0 && warheads > 0;
    }

    /** Consomme un missile et une ogive ; sans effet si l'un des deux manque. */
    public void consumeAmmo() {
        if (canFire()) {
            missiles--;
            warheads--;
            setChanged();
        }
    }

    /** Charge jusqu'à la capacité ; renvoie le nombre de missiles accepté. */
    public int loadMissiles(int count) {
        int accepted = Math.max(0, Math.min(count, MAX_MISSILES - missiles));
        if (accepted > 0) {
            missiles += accepted;
            setChanged();
        }
        return accepted;
    }

    /** Charge jusqu'à la capacité ; renvoie le nombre d'ogives acceptées. */
    public int loadWarheads(int count) {
        int accepted = Math.max(0, Math.min(count, MAX_WARHEADS - warheads));
        if (accepted > 0) {
            warheads += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID(OWNER) ? tag.getUUID(OWNER) : null;
        missiles = Math.max(0, Math.min(tag.getInt(MISSILES), MAX_MISSILES));
        warheads = Math.max(0, Math.min(tag.getInt(WARHEADS), MAX_WARHEADS));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) {
            tag.putUUID(OWNER, owner);
        }
        tag.putInt(MISSILES, missiles);
        tag.putInt(WARHEADS, warheads);
    }
}
