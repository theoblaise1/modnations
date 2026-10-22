// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.armement;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Explosif lancé du J6 : rayon de 20 blocs, impact sur un bloc ou un {@code Monster} (ou au bout de la portée), 12 de
 * dégâts aux {@code Monster} dans un rayon de 3 blocs autour de l'impact, sans destruction de blocs ni PvP
 * (docs/conception/J6.md §1.1). Un explosif est consommé par lancer ; aucune munition.
 */
public class ExplosiveItem extends Item {
    private static final double RANGE = 20.0D;
    private static final double BLAST_RADIUS = 3.0D;
    private static final float BLAST_DAMAGE = 12.0F;
    private static final int COOLDOWN_TICKS = 20;

    public ExplosiveItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack explosive = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer thrower)) {
            return InteractionResultHolder.sidedSuccess(explosive, level.isClientSide());
        }
        Nation nation = CombatRules.nationOf(thrower);
        if (nation == null) {
            return CombatRules.refuse(thrower, explosive, Msg.err("nation.error.no_nation"));
        }
        if (!nation.research().hasUnlock(UnlockKeys.WEAPON_EXPLOSIVES)) {
            return CombatRules.refuse(thrower, explosive, Msg.err("combat.locked"));
        }
        if (thrower.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(explosive);
        }

        CombatRules.Aim aim = CombatRules.aim(serverLevel, thrower, RANGE);
        Vec3 impact = aim.target() != null ? aim.target().getBoundingBox().getCenter() : aim.stop();
        explosive.shrink(1);
        thrower.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        double radiusSq = BLAST_RADIUS * BLAST_RADIUS;
        AABB area = new AABB(impact, impact).inflate(BLAST_RADIUS);
        for (Monster monster : serverLevel.getEntitiesOfClass(Monster.class, area,
                m -> m.isAlive() && m.position().distanceToSqr(impact) <= radiusSq)) {
            monster.hurt(serverLevel.damageSources().playerAttack(thrower), BLAST_DAMAGE);
        }

        serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y, impact.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        serverLevel.playSound(null, BlockPos.containing(impact), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
        return InteractionResultHolder.success(explosive);
    }
}
