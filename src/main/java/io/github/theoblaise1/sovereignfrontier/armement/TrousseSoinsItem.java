// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.armement;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * Trousse de soins du J6 : clic droit maintenu pendant 1 s (20 ticks), rend 6 PV, consommée (docs/conception/J6.md §1.4).
 * Aucune recette : caisse militaire et butin du gardien seulement.
 */
public class TrousseSoinsItem extends Item {
    public static final int USE_TICKS = 20;
    public static final float HEAL = 6.0F;

    public TrousseSoinsItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.EAT;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide()) {
            entity.heal(HEAL);
            stack.shrink(1);
        }
        return stack;
    }
}
