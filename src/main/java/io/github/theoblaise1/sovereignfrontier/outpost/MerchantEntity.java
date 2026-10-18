// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.outpost;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidType;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Marchand d'avant-poste : invulnérable (sauf {@code #bypasses_invulnerability}, donc {@code /kill}), immobile (ni
 * poussée, ni courant, ni laisse, ni monture), persistant. Toutes les 100 ticks de son propre {@code tick}, il
 * disparaît s'il n'est pas le marchand enregistré de son avant-poste (doublons, {@code /summon}) et revient à son
 * poste s'il s'en est éloigné de plus de 2 blocs. Clic droit : écran de commerce.
 */
public class MerchantEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> DATA_VARIANT =
            SynchedEntityData.defineId(MerchantEntity.class, EntityDataSerializers.STRING);
    private static final int CHECK_INTERVAL = 100;

    @Nullable
    private UUID outpostId;
    @Nullable
    private BlockPos post;

    public MerchantEntity(EntityType<? extends MerchantEntity> type, Level level) {
        super(type, level);
    }

    /** 20 PV, vitesse nulle : le marchand ne se déplace jamais de lui-même. */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D);
    }

    /** Rattache le marchand à son avant-poste (avant l'ajout au monde). */
    public void setup(UUID outpost, String variant, BlockPos post) {
        this.outpostId = outpost;
        this.post = post.immutable();
        setVariant(variant);
    }

    @Nullable
    public UUID outpostId() {
        return outpostId;
    }

    /** Variante synchronisée (nom affiché ; apparence quand un rendu dédié existera). */
    public String variant() {
        return entityData.get(DATA_VARIANT);
    }

    private void setVariant(String variant) {
        entityData.set(DATA_VARIANT, variant == null ? "" : variant);
        setCustomName(OutpostManager.merchantName(variant()));
        setCustomNameVisible(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_VARIANT, "");
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    // ------------------------------------------------------------------ contrôle périodique

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && tickCount % CHECK_INTERVAL == 0 && level() instanceof ServerLevel serverLevel) {
            checkRegistration(serverLevel);
        }
    }

    private void checkRegistration(ServerLevel level) {
        Outpost outpost = outpostId == null ? null : OutpostData.get(level.getServer()).get(outpostId);
        if (outpost == null || !getUUID().equals(outpost.merchant())) {
            discard();
            return;
        }
        BlockPos home = post != null ? post : outpost.post();
        if (distanceToSqr(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D) > MarketRules.MERCHANT_LEASH * MarketRules.MERCHANT_LEASH) {
            teleportTo(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D);
            setDeltaMovement(0.0D, 0.0D, 0.0D);
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (level() instanceof ServerLevel serverLevel && outpostId != null) {
            OutpostManager.merchantDied(serverLevel.getServer(), outpostId, getUUID());
        }
    }

    // ------------------------------------------------------------------ commerce

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer && level() instanceof ServerLevel serverLevel) {
            MarketManager.openFromMerchant(serverPlayer, serverLevel, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    // ------------------------------------------------------------------ invulnérabilité et immobilité

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public boolean isPushedByFluid(FluidType type) {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean startRiding(Entity vehicle, boolean force) {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    // ------------------------------------------------------------------ persistance

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (outpostId != null) {
            tag.putUUID("Outpost", outpostId);
        }
        tag.putString("Variant", variant());
        if (post != null) {
            tag.putLong("Post", post.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        outpostId = tag.hasUUID("Outpost") ? tag.getUUID("Outpost") : null;
        post = tag.contains("Post") ? BlockPos.of(tag.getLong("Post")) : null;
        if (tag.contains("Variant")) {
            setVariant(tag.getString("Variant"));
        }
    }
}
