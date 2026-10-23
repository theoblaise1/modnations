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
import io.github.theoblaise1.sovereignfrontier.war.WarRules;
import net.minecraft.core.BlockPos;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.nation.TerritoryRules;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Effets du territoire revendiqué. Chaque gestionnaire ne fait qu'une recherche O(1) dans le
 * cache par chunk ({@code TerritoryIndex}) : aucun parcours de la liste des nations.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class TerritoryEvents {
    /**
     * Portée, en blocs, des actions qui débordent de leur case : prise d'un enderman, signal de
     * redstone transmis à travers un bloc conducteur. Au plus 4 chunks couvrent un tel carré.
     */
    private static final int NEAR_RADIUS = 2;
    /** Nation d'origine (identifiant, ou vide hors territoire) d'un projectile sans tireur joueur. */
    private static final String ORIGIN_TAG = SovereignFrontier.MODID + ":origin";

    private TerritoryEvents() {
    }

    @Nullable
    private static Nation ownerAt(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return NationData.get(serverLevel.getServer()).ownerAt(level.dimension(), pos);
    }

    /**
     * Première nation vérifiant {@code filter} parmi les chunks (au plus 4) qui couvrent le carré
     * de rayon {@link #NEAR_RADIUS} autour de {@code pos} ; coût constant, sans parcours des nations.
     */
    @Nullable
    private static Nation ownerNear(Level level, BlockPos pos, Predicate<Nation> filter) {
        return ownerNear(level, pos, NEAR_RADIUS, filter);
    }

    @Nullable
    private static Nation ownerNear(Level level, BlockPos pos, int radius, Predicate<Nation> filter) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        NationData data = NationData.get(serverLevel.getServer());
        for (int cx = (pos.getX() - radius) >> 4; cx <= (pos.getX() + radius) >> 4; cx++) {
            for (int cz = (pos.getZ() - radius) >> 4; cz <= (pos.getZ() + radius) >> 4; cz++) {
                Nation owner = data.ownerAt(level.dimension(), ChunkPos.asLong(cx, cz));
                if (owner != null && filter.test(owner)) {
                    return owner;
                }
            }
        }
        return null;
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
        // Pose multiple (lit, porte…) : chaque bloc posé est contrôlé, pas seulement le premier.
        List<BlockPos> positions = new ArrayList<>();
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot snapshot : multi.getReplacedBlockSnapshots()) {
                positions.add(snapshot.getPos());
            }
        } else {
            positions.add(event.getPos());
        }
        Entity entity = event.getEntity();
        for (BlockPos pos : positions) {
            Nation owner = ownerAt(level, pos);
            if (owner != null) {
                if (entity instanceof Player player) {
                    if (!canBuild(player, owner)) {
                        event.setCanceled(true);
                        deny(player, owner);
                        return;
                    }
                } else if (entity != null) {
                    // Endermen et autres entités : pas de pose de bloc en territoire revendiqué.
                    event.setCanceled(true);
                    return;
                }
            }
            // Composant de redstone (levier, poussoir, fil, répéteur, observateur…) : son signal
            // porte jusqu'à deux blocs (à travers un bloc conducteur) et ouvrirait les portes ou
            // déclencherait les mécanismes du territoire voisin.
            if (entity instanceof Player player && level.getBlockState(pos).isSignalSource()) {
                Nation near = ownerNear(level, pos, n -> !canBuild(player, n));
                if (near != null) {
                    event.setCanceled(true);
                    player.displayClientMessage(Msg.err("protect.redstone", near.name()), true);
                    return;
                }
            }
        }
    }

    /** Clic gauche : {@code BlockState.attack} (œuf de dragon, bloc musical…) s'exécute avant BreakEvent. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        Nation owner = ownerAt(level, event.getPos());
        if (owner != null && !canBuild(event.getEntity(), owner)) {
            event.setCanceled(true);
            deny(event.getEntity(), owner);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        Nation owner = ownerAt(level, event.getPos());
        BlockState state = level.getBlockState(event.getPos());
        if (owner == null || canBuild(event.getEntity(), owner)) {
            // Double coffre à cheval sur une frontière (chunk revendiqué après coup) : ouvrir une
            // moitié donne accès à l'autre, dont le propriétaire compte donc aussi.
            owner = chestPartnerOwner(level, event.getPos(), state);
            if (owner == null || canBuild(event.getEntity(), owner)) {
                owner = placedNextToOwner(event);
                if (owner == null || canBuild(event.getEntity(), owner)) {
                    return;
                }
            }
        } else if (state.getBlock() instanceof NationAccessBlock) {
            // Le bloc national vérifie lui-même la permission adéquate ; on bloque seulement l'objet tenu.
            event.setUseItem(TriState.FALSE);
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        deny(event.getEntity(), owner);
    }

    /** Propriétaire de l'autre moitié d'un double coffre, ou {@code null} (coffre simple, autre bloc). */
    @Nullable
    private static Nation chestPartnerOwner(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return ownerAt(level, pos.relative(ChestBlock.getConnectedDirection(state)));
    }

    /**
     * Briquet, boule de feu, cadres et tableaux : l'effet ou l'entité naît sur la case voisine de la face
     * cliquée, sans événement de pose. Renvoie le propriétaire de cette case, s'il y en a un.
     */
    @Nullable
    private static Nation placedNextToOwner(PlayerInteractEvent.RightClickBlock event) {
        Item item = event.getItemStack().getItem();
        if (!(item instanceof FlintAndSteelItem || item instanceof FireChargeItem || item instanceof HangingEntityItem)) {
            return null;
        }
        return ownerAt(event.getLevel(), event.getPos().relative(event.getFace()));
    }

    /**
     * Seaux, nénuphars, frai de grenouille, bateaux et œufs d'apparition : posés par {@code Item#use} sans
     * événement de pose, on vérifie la case visée et la case voisine.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Level level = event.getLevel();
        Item item = event.getItemStack().getItem();
        if (level.isClientSide() || !(item instanceof BucketItem || item instanceof PlaceOnWaterBlockItem
                || item instanceof BoatItem || item instanceof SpawnEggItem)) {
            return;
        }
        // Même visée que chaque Item#use : seau vide et œuf = sources seulement, seau plein = aucun fluide,
        // bateau = tout fluide ; PlaceOnWaterBlockItem vise une source et pose le bloc juste au-dessus.
        ClipContext.Fluid mode = item instanceof BucketItem bucket && bucket.content != Fluids.EMPTY
                ? ClipContext.Fluid.NONE
                : item instanceof BoatItem ? ClipContext.Fluid.ANY : ClipContext.Fluid.SOURCE_ONLY;
        BlockHitResult hit = Item.getPlayerPOVHitResult(level, event.getEntity(), mode);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos target = item instanceof PlaceOnWaterBlockItem ? hit.getBlockPos().above() : hit.getBlockPos();
        for (BlockPos pos : new BlockPos[]{target, target.relative(hit.getDirection())}) {
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
        // L'explosion d'un réacteur en surchauffe (reconnue à son centre) frappe aussi le territoire
        // de la nation du réacteur, et lui seul : les autres nations restent protégées.
        Vec3 core = MachineBlockEntity.meltdownInProgress;
        Nation exposed = core != null && core.equals(event.getExplosion().center())
                ? data.ownerAt(level.dimension(), BlockPos.containing(core)) : null;
        event.getAffectedBlocks().removeIf(pos -> {
            Nation owner = data.ownerAt(level.dimension(), pos);
            return owner != null && owner != exposed;
        });
        // Cadres, supports d'armure, bétail… ne sont pas soufflés (joueurs et monstres le restent).
        event.getAffectedEntities().removeIf(e -> {
            Nation owner = isProtectedEntity(e) ? data.ownerAt(level.dimension(), e.blockPosition()) : null;
            return owner != null && owner != exposed;
        });
    }

    /** Entités d'un territoire que seuls les bâtisseurs peuvent frapper ou manipuler. */
    /**
     * Plaque de pression et œuf de tortue : déclenchés par le simple contact, sans événement de pose. Un joueur sans
     * droit de construction sur le territoire ne doit pas les actionner, comme il ne peut pas ouvrir une porte.
     */
    public static boolean blocksTrigger(Entity entity, Level level, BlockPos pos) {
        if (!(entity instanceof ServerPlayer player)) {
            return false;
        }
        Nation owner = ownerAt(level, pos);
        return owner != null && !canBuild(player, owner);
    }

    public static boolean isProtectedEntity(Entity entity) {
        return !(entity instanceof Player) && !(entity instanceof Enemy) && !(entity instanceof Projectile);
    }

    /** Frapper une entité protégée (animal, cadre, support d'armure, wagonnet…) en territoire. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttackEntity(AttackEntityEvent event) {
        Entity target = event.getTarget();
        if (target.level().isClientSide() || !isProtectedEntity(target)) {
            return;
        }
        Nation owner = ownerAt(target.level(), target.blockPosition());
        if (owner != null && !canBuild(event.getEntity(), owner)) {
            event.setCanceled(true);
            deny(event.getEntity(), owner);
        }
    }

    /** Clic droit sur une entité (cadre, support d'armure, coffre de bateau, laisse, cisailles…). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (denyEntityInteraction(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteractEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (denyEntityInteraction(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private static boolean denyEntityInteraction(Player player, Entity target) {
        if (target.level().isClientSide() || !isProtectedEntity(target) || target instanceof Merchant) {
            return false; // commercer avec un villageois reste permis
        }
        Nation owner = ownerAt(target.level(), target.blockPosition());
        if (owner != null && !canBuild(player, owner)) {
            deny(player, owner);
            return true;
        }
        return false;
    }

    /**
     * Projectile sans tireur joueur (distributeur, mob) : on retient la nation du chunk où il
     * apparaît. Une seule recherche O(1) ; un projectile rechargé depuis le disque garde la sienne.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof Projectile projectile)
                || !(event.getLevel() instanceof ServerLevel level) || projectile.getOwner() instanceof Player
                || projectile.getPersistentData().contains(ORIGIN_TAG)) {
            return;
        }
        Nation origin = NationData.get(level.getServer()).ownerAt(level.dimension(), projectile.blockPosition());
        projectile.getPersistentData().putString(ORIGIN_TAG, origin == null ? "" : origin.id().toString());
    }

    /**
     * Projectile d'un joueur sans droit de construire, ou projectile sans tireur joueur venu d'ailleurs
     * (distributeur, squelette, blaze…) : sans effet sur les blocs et entités protégés d'un territoire.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        Level level = projectile.level();
        if (level.isClientSide()) {
            return;
        }
        HitResult hit = event.getRayTraceResult();
        BlockPos pos;
        BlockPos front = null;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            pos = blockHit.getBlockPos();
            // Boule de feu : le feu naît sur la case devant la face touchée.
            front = pos.relative(blockHit.getDirection());
        } else if (hit instanceof EntityHitResult entityHit && isProtectedEntity(entityHit.getEntity())) {
            pos = entityHit.getEntity().blockPosition();
        } else {
            return;
        }
        if (projectile.getOwner() instanceof Player shooter) {
            Nation owner = ownerAt(level, pos);
            if (owner != null && !canBuild(shooter, owner)) {
                stopProjectile(event, projectile);
            }
            return;
        }
        // Sans tireur joueur : seul le territoire de sa nation d'origine (marque absente = hors territoire).
        String origin = projectile.getPersistentData().getString(ORIGIN_TAG);
        if (isForeign(level, pos, origin) || isForeign(level, front, origin)) {
            stopProjectile(event, projectile);
        }
    }

    /** Vrai si {@code pos} appartient à une nation autre que celle d'identifiant {@code origin}. */
    private static boolean isForeign(Level level, @Nullable BlockPos pos, String origin) {
        Nation owner = pos == null ? null : ownerAt(level, pos);
        return owner != null && !owner.id().toString().equals(origin);
    }

    /**
     * Annule l'impact et retire le projectile : annulé seul, il poursuivrait sa course à travers le
     * bloc ou l'entité protégés. Une flèche ou un trident ramassables sont rendus en objet.
     */
    private static void stopProjectile(ProjectileImpactEvent event, Projectile projectile) {
        event.setCanceled(true);
        if (projectile instanceof AbstractArrow arrow && arrow.pickup == AbstractArrow.Pickup.ALLOWED) {
            arrow.spawnAtLocation(arrow.getPickupItemStackOrigin().copy(), 0.1F);
        }
        projectile.discard();
    }

    /** Pistons : rien ne doit être poussé, tiré ou détruit à travers une frontière nationale. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PistonStructureResolver resolver = event.getStructureHelper();
        BlockPos piston = event.getPos();
        if (TerritoryRules.crossesBorder(level, piston, event.getFaceOffsetPos())) {
            event.setCanceled(true);
            return;
        }
        // Un piston simple qui se rétracte ne retire que sa tête (vérifiée ci-dessus) : il ne tire rien.
        if (!event.getPistonMoveType().isExtend && !event.getState().is(Blocks.STICKY_PISTON)) {
            return;
        }
        if (resolver == null) {
            return;
        }
        // Même quand la résolution échoue (ex. piston collant qui ne peut pas tout tirer), les listes
        // contiennent déjà les blocs retenus : elles sont contrôlées, la rétraction ne tire rien à travers la frontière.
        resolver.resolve();
        Direction move = event.getPistonMoveType().isExtend ? event.getDirection() : event.getDirection().getOpposite();
        for (BlockPos pos : resolver.getToPush()) {
            if (TerritoryRules.crossesBorder(level, piston, pos) || TerritoryRules.crossesBorder(level, piston, pos.relative(move))) {
                event.setCanceled(true);
                return;
            }
        }
        for (BlockPos pos : resolver.getToDestroy()) {
            if (TerritoryRules.crossesBorder(level, piston, pos)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * Lave qui enflamme un bloc d'un territoire voisin. NeoForge ignore l'annulation de cet
     * événement : on remet donc l'état d'origine (l'air) à la place du feu.
     */
    @SubscribeEvent
    public static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (TerritoryRules.crossesBorder(event.getLevel(), event.getLiquidPos(), event.getPos())) {
            event.setNewState(event.getOriginalState());
        }
    }

    /**
     * Vrai si un territoire tenu touche le carré de rayon {@code radius} autour de {@code pos}.
     * Sert aux mobs qui agissent sur un rayon plus large que la règle des 2 blocs (poisson d'argent).
     */
    public static boolean territoryWithin(Level level, BlockPos pos, int radius) {
        return ownerNear(level, pos, radius, owner -> true) != null;
    }

    /**
     * Mobs qui modifient des blocs autour d'eux (l'enderman prend un bloc jusqu'à 2 blocs de
     * distance, le ravageur arrache les feuilles, le lapin mange les carottes, le blaze met le feu)
     * : rien à moins de 2 blocs d'un territoire. Le poisson d'argent a sa propre règle, plus large,
     * dans {@code SilverfishWakeUpFriendsGoalMixin}, car son réveil ne passe pas par cet événement.
     */
    @SubscribeEvent
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        if ((entity instanceof EnderMan || entity instanceof Ravager || entity instanceof Silverfish
                || entity instanceof Rabbit || entity instanceof Blaze)
                && ownerNear(entity.level(), entity.blockPosition(), owner -> true) != null) {
            event.setCanGrief(false);
        }
    }

    /** Porte défoncée par un zombie, blocs brisés par le Wither ou le dragon : jamais en territoire. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        LivingEntity entity = event.getEntity();
        Nation owner = ownerAt(entity.level(), event.getPos());
        if (owner != null && !(entity instanceof Player player && canBuild(player, owner))) {
            event.setCanceled(true);
        }
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

    /** PvP entre joueurs : tir ami interdit, et combat entre nations seulement en guerre ({@link WarRules#mayHurt}). */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)
                || !(event.getSource().getEntity() instanceof ServerPlayer attacker) || attacker == victim) {
            return;
        }
        if (!WarRules.mayHurt(victim.server, attacker, victim)) {
            event.setCanceled(true);
        }
    }

    /**
     * Balayage d'épée : Player#attack frappe directement les entités voisines de la cible, sans
     * AttackEntityEvent. Si le balayage toucherait une entité protégée (animal, villageois, armure) dans un
     * territoire où le joueur ne peut pas construire, il est annulé entier. La cible elle-même est déjà
     * couverte par {@link #onAttackEntity}.
     */
    @SubscribeEvent
    public static void onSweep(SweepAttackEvent event) {
        if (!event.isSweeping() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Même boîte que Player#attack : la cible élargie d'un bloc en largeur et d'un quart en hauteur.
        AABB box = event.getTarget().getBoundingBox().inflate(1.0D, 0.25D, 1.0D);
        for (LivingEntity near : player.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (near == player || near == event.getTarget() || !isProtectedEntity(near)) {
                continue;
            }
            Nation owner = ownerAt(player.level(), near.blockPosition());
            if (owner != null && !canBuild(player, owner)) {
                event.setSweeping(false);
                return;
            }
        }
    }
}
