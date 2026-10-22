// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.dungeon;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModEntityTypes;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

/**
 * Autel du donjon à clé (J6, docs/conception/J6.md §1.3, §3). Clic droit avec une clé de donjon dans l'inventaire : la
 * clé est retirée et le gardien apparaît à 1 bloc au-dessus de l'autel. Refus avec message : pas de clé, gardien vivant,
 * recharge en cours. Seul l'Overworld est concerné (C11).
 */
public class AltarBlock extends Block {
    private static final long HOUR_MS = 3_600_000L;

    public AltarBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** Clic avec un objet en main : une clé en inventaire a priorité (activation) ; sinon l'objet agit (pose de bloc). */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!isOverworld(level) || !hasKey(player)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            activate(serverLevel, pos, serverPlayer);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Clic à main vide : avec une clé, activation ; sans clé, message. Une main pleine laisse l'objet agir (pose de
     * bloc, outil) : le clic ne fait rien ici.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!isOverworld(level) || !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            if (hasKey(player)) {
                activate(serverLevel, pos, serverPlayer);
            } else {
                serverPlayer.displayClientMessage(Msg.err("dungeon.altar_no_key"), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static boolean isOverworld(Level level) {
        return level.dimension().equals(Level.OVERWORLD);
    }

    /** Vrai si une clé de donjon se trouve dans l'inventaire (mains et armure comprises). */
    private static boolean hasKey(Player player) {
        return player.getInventory().countItem(ModItems.DUNGEON_KEY.get()) > 0;
    }

    /** Retire une clé de donjon de l'inventaire ; vrai si une clé a été retirée. */
    private static boolean takeKey(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(ModItems.DUNGEON_KEY.get())) {
                inventory.removeItem(slot, 1);
                return true;
            }
        }
        return false;
    }

    /**
     * Activation (clé présente). Ordre des contrôles : gardien vivant, recharge, puis espace libre au-dessus de l'autel.
     * Un gardien enregistré mais introuvable (chunk déchargé, supprimé) est tenu pour perdu : l'autel se réarme sans
     * récompense. L'UUID est enregistré avant l'ajout du gardien, pour que son chargement soit reconnu.
     */
    private static void activate(ServerLevel level, BlockPos pos, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        DungeonData data = DungeonData.get(server);
        DungeonData.AltarState state = data.get(pos);
        long now = TimeUtil.now();

        if (state != null && state.boss() != null) {
            if (level.getEntity(state.boss()) instanceof DungeonBossEntity boss && boss.isAlive()) {
                player.displayClientMessage(Msg.err("dungeon.altar_active"), true);
                return;
            }
            state = state.withBoss(null);
        }
        if (state != null && state.rechargeUntilEpochMs() > now) {
            long hours = (state.rechargeUntilEpochMs() - now + HOUR_MS - 1) / HOUR_MS;
            player.displayClientMessage(Msg.err("dungeon.altar_cooldown", hours), true);
            return;
        }

        BlockPos spawn = pos.above();
        if (!level.getBlockState(spawn).getCollisionShape(level, spawn).isEmpty()) {
            // Espace obstrué : refus silencieux (aucune clé de traduction prévue) ; la clé reste en inventaire.
            return;
        }
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        UUID nationId = nation == null ? null : nation.id();
        DungeonBossEntity boss = spawnBoss(level, spawn);
        if (boss == null) {
            return;
        }
        data.put(pos, new DungeonData.AltarState(boss.getUUID(), nationId, 0L));
        if (!level.addFreshEntity(boss)) {
            data.put(pos, new DungeonData.AltarState(null, nationId, 0L));
            return;
        }
        takeKey(player);
    }

    /**
     * Gardien créé à {@code spawn} avec {@link MobSpawnType#COMMAND} (hors chasses de contrat). Le groupe de spawn sans
     * bébé ni jockey évite un zombi d'enfant ; les surcharges de {@link DungeonBossEntity} suppriment l'équipement et
     * les renforts.
     */
    private static DungeonBossEntity spawnBoss(ServerLevel level, BlockPos spawn) {
        DungeonBossEntity boss = ModEntityTypes.DUNGEON_BOSS.get().create(level);
        if (boss == null) {
            return null;
        }
        boss.moveTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        boss.finalizeSpawn(level, level.getCurrentDifficultyAt(spawn), MobSpawnType.COMMAND,
                new Zombie.ZombieGroupData(false, false));
        return boss;
    }

    /** Délai de recharge en millisecondes, lu à l'exécution ({@code [dungeon] altarCooldownHours}). */
    static long cooldownMs() {
        return SFConfig.ALTAR_COOLDOWN_HOURS.get() * HOUR_MS;
    }
}
