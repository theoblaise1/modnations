// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.dungeon;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Gardien du donjon (J6, docs/conception/J6.md §1.3) : zombi renforcé, invoqué par l'autel, rendu vanilla du zombi.
 * Ne brûle pas au soleil, ne disparaît jamais, barre de boss vanilla. Butin : {@code entities/gardien_donjon} ; la clé
 * militaire de la récompense est remise par {@link DungeonEvents} à la mort.
 */
public class DungeonBossEntity extends Zombie {
    private static final ResourceKey<LootTable> LOOT = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "entities/gardien_donjon"));

    /** Barre de boss, affichée aux joueurs qui suivent ce gardien (ajout et retrait dans les méthodes ci-dessous). */
    private final ServerBossEvent bossEvent = new ServerBossEvent(Msg.tr("dungeon.boss_name"),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);

    public DungeonBossEntity(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    /** 200 PV, armure 6, dégâts 8, résistance au recul 0,5 (par-dessus les attributs du zombi vanilla). */
    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 200.0D)
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.ATTACK_DAMAGE, 8.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5D);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) {
            this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
        }
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        super.remove(reason);
        this.bossEvent.removeAllPlayers();
    }

    /** Ne brûle pas au soleil. */
    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    /** Attributs fixes : pas de bonus de renfort ni de chef de meute au spawn (PV et renforts figés par createAttributes). */
    @Override
    protected void handleAttributes(float difficulty) {
    }

    /** Pas d'équipement aléatoire. */
    @Override
    protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty) {
    }

    /**
     * Table de butin : Mob#getLootTable() est final et renvoie getDefaultLootTable() (type ResourceKey de LootTable) ; la
     * surcharge du point d'accroche protégé désigne donc {@code sovereignfrontier:entities/gardien_donjon}.
     */
    @Override
    protected ResourceKey<LootTable> getDefaultLootTable() {
        return LOOT;
    }
}
