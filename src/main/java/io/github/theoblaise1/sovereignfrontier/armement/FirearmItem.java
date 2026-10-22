// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.armement;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Arme à feu du J6 (pistolet, fusil, fusil de précision) : tir instantané côté serveur (clic droit, {@link #use}), un
 * rayon depuis les yeux, une munition consommée par tir (docs/conception/J6.md §1.1, §3).
 */
public class FirearmItem extends Item {

    /** Paramètres d'une arme : portée (blocs), dégâts de base (avant multiplicateur de munition), cadence (ticks), déblocage. */
    public record Spec(double range, float damage, int cooldownTicks, String unlockKey) {
        public static final Spec PISTOL = new Spec(24.0D, 6.0F, 8, UnlockKeys.WEAPON_PISTOL);
        public static final Spec RIFLE = new Spec(48.0D, 9.0F, 12, UnlockKeys.WEAPON_RIFLE);
        public static final Spec SNIPER = new Spec(96.0D, 18.0F, 40, UnlockKeys.WEAPON_SNIPER);
    }

    private final Spec spec;

    public FirearmItem(Item.Properties properties, Spec spec) {
        super(properties);
        this.spec = spec;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack weapon = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer shooter)) {
            return InteractionResultHolder.sidedSuccess(weapon, level.isClientSide());
        }
        Nation nation = CombatRules.nationOf(shooter);
        if (nation == null) {
            return CombatRules.refuse(shooter, weapon, Msg.err("nation.error.no_nation"));
        }
        if (!nation.research().hasUnlock(spec.unlockKey())) {
            return CombatRules.refuse(shooter, weapon, Msg.err("combat.locked"));
        }
        Inventory inventory = shooter.getInventory();
        ItemStack ammo = CombatRules.compatibleAmmo(inventory, nation);
        if (ammo == null) {
            boolean locked = CombatRules.carriesAmmo(inventory);
            return CombatRules.refuse(shooter, weapon, Msg.err(locked ? "combat.locked" : "combat.no_ammo"));
        }
        if (shooter.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(weapon);
        }

        AmmoItem.Kind kind = ((AmmoItem) ammo.getItem()).kind();
        ammo.shrink(1);
        shooter.getCooldowns().addCooldown(this, spec.cooldownTicks());

        CombatRules.Aim aim = CombatRules.aim(serverLevel, shooter, spec.range());
        if (aim.target() != null) {
            aim.target().hurt(serverLevel.damageSources().playerAttack(shooter), spec.damage() * kind.damageMultiplier());
            if (kind.fireTicks() > 0) {
                aim.target().setRemainingFireTicks(kind.fireTicks());
            }
        }
        serverLevel.playSound(null, shooter.blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 0.6F, 1.6F);
        return InteractionResultHolder.success(weapon);
    }
}
