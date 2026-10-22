// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.armement;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Règles communes aux armes du J6 (docs/conception/J6.md §1.1, §3) : nation du tireur, refus avec message, munition
 * compatible, visée. Cible : seulement les {@link Monster} (ennemis et gardien) ; joueurs et animaux ne sont jamais touchés.
 */
final class CombatRules {

    /** Point d'arrêt du rayon (bloc touché, cible, ou bout de portée) et cible éventuelle. */
    record Aim(@Nullable Monster target, Vec3 stop) {
    }

    private CombatRules() {
    }

    /** Nation du joueur, ou null s'il n'appartient à aucune nation. */
    @Nullable
    static Nation nationOf(ServerPlayer player) {
        return NationData.get(player.server).nationOf(player.getUUID());
    }

    /** Refus : message dans la barre d'action, aucune munition ni cadence consommées. */
    static InteractionResultHolder<ItemStack> refuse(ServerPlayer player, ItemStack stack, Component message) {
        player.displayClientMessage(message, true);
        return InteractionResultHolder.fail(stack);
    }

    static boolean isTarget(Entity entity) {
        return entity instanceof Monster && entity.isAlive();
    }

    /**
     * Rayon depuis les yeux, arrêté par le premier bloc solide ({@code ClipContext}), puis recherche d'un {@link Monster}
     * jusqu'à ce point d'arrêt : pas de tir à travers les murs.
     */
    static Aim aim(ServerLevel level, ServerPlayer shooter, double range) {
        Vec3 eye = shooter.getEyePosition();
        Vec3 look = shooter.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        AABB sweep = shooter.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, shooter, eye, stop, sweep, CombatRules::isTarget);
        Monster target = hit != null && hit.getEntity() instanceof Monster monster ? monster : null;
        return new Aim(target, stop);
    }

    /** Première munition compatible de l'inventaire complet (main, armure, main secondaire), ou null. */
    @Nullable
    static ItemStack compatibleAmmo(Inventory inventory, Nation nation) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof AmmoItem ammo && nation.research().hasUnlock(ammo.kind().unlockKey())) {
                return stack;
            }
        }
        return null;
    }

    /** Vrai si l'inventaire porte une munition, débloquée ou non (distingue « aucune » de « verrouillée »). */
    static boolean carriesAmmo(Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof AmmoItem) {
                return true;
            }
        }
        return false;
    }
}
