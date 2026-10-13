package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.block.NationAccessBlock;
import io.github.theoblaise1.sovereignfrontier.nation.AdminBypass;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.research.ResearchStat;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import javax.annotation.Nullable;

/**
 * Effets du territoire revendiqué. Chaque gestionnaire ne fait qu'une recherche O(1) dans le
 * cache par chunk ({@code TerritoryIndex}) : aucun parcours de la liste des nations.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class TerritoryEvents {
    private TerritoryEvents() {
    }

    @Nullable
    private static Nation ownerAt(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return NationData.get(serverLevel.getServer()).ownerAt(level.dimension(), pos);
    }

    /** Vrai si le joueur peut construire/interagir dans un chunk appartenant à {@code owner}. */
    public static boolean canBuild(Player player, Nation owner) {
        if (player instanceof ServerPlayer sp && AdminBypass.isActive(sp)) {
            return true;
        }
        return owner.hasPermission(player.getUUID(), NationPermission.BUILD);
    }

    private static void deny(Player player, Nation owner) {
        player.displayClientMessage(Msg.err("protect.denied", owner.name()), true);
    }

    // ------------------------------------------------------------------ protection des blocs

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof Level level)) {
            return;
        }
        Nation owner = ownerAt(level, event.getPos());
        if (owner != null && !canBuild(event.getPlayer(), owner)) {
            event.setCanceled(true);
            deny(event.getPlayer(), owner);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof Level level)) {
            return;
        }
        Nation owner = ownerAt(level, event.getPos());
        if (owner == null) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Player player) {
            if (!canBuild(player, owner)) {
                event.setCanceled(true);
                deny(player, owner);
            }
        } else if (entity != null) {
            // Endermen et autres entités : pas de pose de bloc en territoire revendiqué.
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        Nation owner = ownerAt(level, event.getPos());
        if (owner == null || canBuild(event.getEntity(), owner)) {
            return;
        }
        BlockState state = level.getBlockState(event.getPos());
        if (state.getBlock() instanceof NationAccessBlock) {
            // Le bloc national vérifie lui-même la permission adéquate ; on bloque seulement l'objet tenu.
            event.setUseItem(TriState.FALSE);
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        deny(event.getEntity(), owner);
    }

    /** Seaux (et autres objets visant un bloc à distance) : vérifie le bloc visé et la case voisine. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Level level = event.getLevel();
        ItemStack stack = event.getItemStack();
        if (level.isClientSide() || !(stack.getItem() instanceof BucketItem)) {
            return;
        }
        BlockHitResult hit = Item.getPlayerPOVHitResult(level, event.getEntity(), ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        for (BlockPos pos : new BlockPos[]{hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection())}) {
            Nation owner = ownerAt(level, pos);
            if (owner != null && !canBuild(event.getEntity(), owner)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                deny(event.getEntity(), owner);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getLevel() instanceof Level level)) {
            return;
        }
        Nation owner = ownerAt(level, event.getPos());
        if (owner != null && !(event.getEntity() instanceof Player p && canBuild(p, owner))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!SFConfig.PROTECT_FROM_EXPLOSIONS.get() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        NationData data = NationData.get(level.getServer());
        event.getAffectedBlocks().removeIf(pos -> data.ownerAt(level.dimension(), pos) != null);
    }

    // ------------------------------------------------------------------ apparitions et cultures

    /**
     * Les monstres de surface n'apparaissent pas naturellement en territoire revendiqué.
     * Seules les apparitions du thread principal (NATURAL, PATROL) sont filtrées : celles de la
     * génération de chunks tournent sur d'autres threads, et un chunk revendiqué est déjà généré.
     */
    @SubscribeEvent
    public static void onSpawnPosition(MobSpawnEvent.PositionCheck event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.PATROL) {
            return;
        }
        if (!(event.getEntity() instanceof Enemy) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event.getY() < level.getSeaLevel() + SFConfig.SURFACE_SPAWN_Y_OFFSET.get()) {
            return;
        }
        BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
        if (NationData.get(level.getServer()).ownerAt(level.dimension(), pos) != null) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    /** +5 % (configurable, + bonus de recherche de la nation) de rendement des cultures mûres récoltées en territoire. */
    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        if (!(event.getState().getBlock() instanceof CropBlock crop) || !crop.isMaxAge(event.getState())) {
            return;
        }
        ServerLevel level = event.getLevel();
        Nation owner = NationData.get(level.getServer()).ownerAt(level.dimension(), event.getPos());
        if (owner == null) {
            return;
        }
        double bonus = SFConfig.CROP_YIELD_BONUS.get() + owner.research().bonus(ResearchStat.CROP_YIELD);
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            double expected = stack.getCount() * bonus;
            int extra = (int) Math.floor(expected);
            if (level.random.nextDouble() < expected - extra) {
                extra++;
            }
            if (extra > 0) {
                stack.grow(extra);
                drop.setItem(stack);
            }
        }
    }

    // ------------------------------------------------------------------ combat

    /** Tir ami désactivé entre membres d'une même nation. */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)
                || !(event.getSource().getEntity() instanceof ServerPlayer attacker) || attacker == victim) {
            return;
        }
        NationData data = NationData.get(victim.server);
        Nation nation = data.nationOf(victim.getUUID());
        if (nation != null && nation == data.nationOf(attacker.getUUID())) {
            event.setCanceled(true);
        }
    }
}
