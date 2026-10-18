// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.reward;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bloc-entité commun aux quatre caisses : rien de persistant. L'événement de bloc {@link #EVENT_OPEN} (diffusé aux
 * clients à 64 blocs) mémorise l'objet phare de l'ouverture et la durée de l'animation, décomptée par le ticker
 * client ; un rendu pourra s'en servir.
 */
public class CrateBlockEntity extends BlockEntity {
    /** Événement de bloc « ouverture » ; paramètre : identifiant numérique de l'objet phare. */
    public static final int EVENT_OPEN = 1;
    /** Durée de l'animation côté client (valeur par défaut de {@code crateOpenTicks}). */
    public static final int ANIM_TICKS = 40;

    private Item displayItem = Items.AIR;
    private int animTicks;

    public CrateBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CRATE.get(), pos, state);
    }

    public Item displayItem() {
        return displayItem;
    }

    public int animTicks() {
        return animTicks;
    }

    /** Doit renvoyer vrai côté serveur pour que l'événement soit diffusé. */
    @Override
    public boolean triggerEvent(int id, int param) {
        if (id == EVENT_OPEN) {
            displayItem = BuiltInRegistries.ITEM.byId(param);
            animTicks = ANIM_TICKS;
            return true;
        }
        return super.triggerEvent(id, param);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, CrateBlockEntity be) {
        if (be.animTicks > 0) {
            be.animTicks--;
        }
    }
}
