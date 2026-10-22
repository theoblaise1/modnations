package io.github.theoblaise1.sovereignfrontier.outpost;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModEntityTypes;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import io.github.theoblaise1.sovereignfrontier.util.WeightedPicker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Cycle de vie des avant-postes : liaison du comptoir au premier tick (enregistrement, apparition du marchand,
 * {@code BOUND}), réapparition du marchand, retrait, et renouvellement paresseux de l'étal à chaque accès.
 */
public final class OutpostManager {
    public static final ResourceLocation DEFAULT_POOL = ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "general");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long ROTATION_SALT = 0x9E3779B97F4A7C15L;
    private static final long FORCED_SALT = 0xC2B2AE3D27D4EB4FL;

    private OutpostManager() {
    }

    /** Nom traduisible du marchand d'une variante (« Marchand général »…), repli lisible pour une variante inconnue. */
    public static MutableComponent merchantName(String variant) {
        return Component.translatableWithFallback(Msg.key("outpost.merchant." + variant), variant);
    }

    /** Pool lu dans le NBT du comptoir ; {@link #DEFAULT_POOL} s'il est vide ou malformé. */
    public static ResourceLocation poolId(String raw) {
        ResourceLocation id = raw == null || raw.isBlank() ? null : ResourceLocation.tryParse(raw);
        return id == null ? DEFAULT_POOL : id;
    }

    /** Variante d'un pool : celle du pool publié, sinon le chemin de son identifiant. */
    private static String variantOf(ResourceLocation pool) {
        MerchantPool published = MerchantRegistry.get().pool(pool);
        return published != null ? published.variant() : pool.getPath();
    }

    // ------------------------------------------------------------------ liaison et retrait

    /**
     * Liaison d'un comptoir non lié (ticker serveur, ou clic droit avant le premier tick) : enregistrement dans
     * {@link OutpostData} et l'index, apparition du marchand, puis {@code BOUND=true} (le ticker disparaît). Attend
     * que les entités du chunk du poste soient chargées. Renvoie l'avant-poste, ou {@code null} si la liaison est
     * reportée.
     */
    @Nullable
    public static Outpost bind(ServerLevel level, BlockPos pos, ComptoirBlockEntity be) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModBlocks.OUTPOST_COUNTER.get())) {
            return null;
        }
        Direction facing = state.getValue(ComptoirBlock.FACING);
        if (!level.areEntitiesLoaded(ChunkPos.asLong(pos.relative(facing.getOpposite())))) {
            return null;
        }
        OutpostData data = OutpostData.get(level.getServer());
        Outpost outpost = data.atAnchor(level.dimension(), pos);
        if (outpost == null) {
            ResourceLocation pool = poolId(be.pool());
            UUID id = be.outpostId() != null && data.get(be.outpostId()) == null ? be.outpostId() : UUID.randomUUID();
            outpost = new Outpost(id, level.dimension(), pos, facing, pool, variantOf(pool), TimeUtil.now());
            data.add(outpost);
            Nation owner = NationData.get(level.getServer()).ownerAt(level.dimension(), pos);
            if (owner != null) {
                LOGGER.warn("[Économie] Avant-poste {} enregistré en {} {} dans le territoire de {} (chunk revendiqué avant sa génération)",
                        id, level.dimension().location(), pos.toShortString(), owner.name());
            } else {
                LOGGER.info("[Économie] Avant-poste {} ({}) enregistré en {} {}", id, pool, level.dimension().location(),
                        pos.toShortString());
            }
        } else if (outpost.facing() != facing) {
            outpost.setFacing(facing);
            data.setDirty();
        }
        be.setOutpostId(outpost.id());
        if (be.pool().isBlank()) {
            be.setPool(outpost.pool().toString());
        }
        ensureMerchant(level, outpost);
        if (!state.getValue(ComptoirBlock.BOUND)) {
            level.setBlock(pos, state.setValue(ComptoirBlock.BOUND, true), Block.UPDATE_ALL);
        }
        return outpost;
    }

    /**
     * Pose un comptoir lié au pool {@code pool} et l'enregistre aussitôt ({@code /sfadmin outpost create}, GameTests).
     * Ne vérifie ni le territoire ni la place : à l'appelant de le faire.
     */
    @Nullable
    public static Outpost createAt(ServerLevel level, BlockPos pos, Direction facing, ResourceLocation pool) {
        BlockState state = ModBlocks.OUTPOST_COUNTER.get().defaultBlockState()
                .setValue(ComptoirBlock.FACING, facing.getAxis().isHorizontal() ? facing : Direction.NORTH)
                .setValue(ComptoirBlock.BOUND, false);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (!(level.getBlockEntity(pos) instanceof ComptoirBlockEntity be)) {
            return null;
        }
        be.setPool(pool.toString());
        return bind(level, pos, be);
    }

    /** Désenregistre l'avant-poste et retire son marchand s'il est chargé (le comptoir reste en place). */
    public static void unregister(MinecraftServer server, Outpost outpost) {
        OutpostData.get(server).remove(outpost);
        ServerLevel level = server.getLevel(outpost.dimension());
        if (level != null && outpost.merchant() != null) {
            Entity merchant = level.getEntity(outpost.merchant());
            if (merchant != null) {
                merchant.discard();
            }
        }
        LOGGER.info("[Économie] Avant-poste {} désenregistré ({} {})", outpost.id(), outpost.dimension().location(),
                outpost.pos().toShortString());
    }

    /** Le comptoir a été retiré (commande, Créatif, nettoyage de GameTest) : l'avant-poste disparaît avec lui. */
    static void anchorRemoved(ServerLevel level, BlockPos pos) {
        Outpost outpost = OutpostData.get(level.getServer()).atAnchor(level.dimension(), pos);
        if (outpost != null) {
            unregister(level.getServer(), outpost);
        }
    }

    // ------------------------------------------------------------------ marchand

    /**
     * Fait réapparaître le marchand s'il manque : seulement si les entités du chunk de son poste sont chargées et
     * qu'aucune entité ne porte l'UUID enregistré. Renvoie vrai si un marchand est apparu.
     */
    public static boolean ensureMerchant(ServerLevel level, Outpost outpost) {
        BlockPos post = outpost.post();
        if (!level.dimension().equals(outpost.dimension()) || !level.areEntitiesLoaded(ChunkPos.asLong(post))) {
            return false;
        }
        UUID current = outpost.merchant();
        if (current != null && level.getEntity(current) instanceof MerchantEntity alive && alive.isAlive()) {
            return false;
        }
        MerchantEntity merchant = ModEntityTypes.MERCHANT.get().create(level);
        if (merchant == null) {
            return false;
        }
        merchant.setup(outpost.id(), outpost.variant(), post);
        merchant.moveTo(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, outpost.facing().toYRot(), 0.0F);
        merchant.setYHeadRot(outpost.facing().toYRot());
        merchant.setPersistenceRequired();
        if (!level.addFreshEntity(merchant)) {
            return false;
        }
        outpost.setMerchant(merchant.getUUID());
        OutpostData.get(level.getServer()).setDirty();
        return true;
    }

    /** Le marchand enregistré est mort ({@code /kill}) : la prochaine interaction en fera réapparaître un. */
    static void merchantDied(MinecraftServer server, UUID outpostId, UUID merchant) {
        OutpostData data = OutpostData.get(server);
        Outpost outpost = data.get(outpostId);
        if (outpost != null && merchant.equals(outpost.merchant())) {
            outpost.setMerchant(null);
            data.setDirty();
        }
    }

    // ------------------------------------------------------------------ étal

    /**
     * Renouvellement paresseux à chaque accès (ouverture, {@code /trade}, achat, vente) : si la rotation courante a
     * changé, la pression est amortie et l'étal retiré depuis le pool publié.
     */
    public static void refresh(MinecraftServer server, Outpost outpost) {
        OutpostData data = OutpostData.get(server);
        long rotation = data.rotationIndex(MarketRules.rotationTicks());
        if (refresh(outpost, rotation, MarketRules.pressureDecay(), MerchantRegistry.get().pool(outpost.pool()))) {
            data.setDirty();
        }
    }

    /**
     * Cœur du renouvellement, sans état global (GameTests) :
     * <ul>
     *   <li>pression : {@code b} et {@code s} × {@code decay^min(R − rot, 16)}, entrées sous 0,01 oubliées ;</li>
     *   <li>tirage : les offres garanties, puis {@code rolls} offres pondérées sans remise, graine
     *       {@code id.msb ^ id.lsb ^ R·0x9E3779B97F4A7C15} ;</li>
     *   <li>instantané : prix et rachats de base, stock et demande pleins.</li>
     * </ul>
     * Un étal resté vide (pool indisponible) est tiré dès que le pool est publié, avec la même graine.
     *
     * @return vrai si l'avant-poste a changé
     */
    public static boolean refresh(Outpost outpost, long rotation, double decay, @Nullable MerchantPool pool) {
        boolean changed = false;
        if (outpost.rotation() != rotation) {
            if (outpost.rotation() >= 0L) {
                long elapsed = rotation > outpost.rotation() ? rotation - outpost.rotation() : 1L;
                outpost.decayPressure(Math.pow(decay, Math.min(elapsed, MarketRules.MAX_DECAY_STEPS)));
            }
            outpost.setRotation(rotation);
            outpost.setForced(0);
            outpost.replaceOffers(List.of());
            changed = true;
        }
        if (outpost.offers().isEmpty() && pool != null) {
            stock(outpost, pool);
            changed = true;
        }
        return changed;
    }

    /** Réassort forcé ({@code /sfadmin outpost rotate}) : pression amortie d'un cran, nouveau tirage, stocks pleins. */
    public static void forceRotation(MinecraftServer server, Outpost outpost) {
        refresh(server, outpost);
        outpost.decayPressure(MarketRules.pressureDecay());
        outpost.setForced(outpost.forced() + 1);
        outpost.replaceOffers(List.of());
        MerchantPool pool = MerchantRegistry.get().pool(outpost.pool());
        if (pool != null) {
            stock(outpost, pool);
        }
        OutpostData.get(server).setDirty();
    }

    /** Graine déterministe du tirage d'un avant-poste pour une rotation. */
    public static long seed(UUID id, long rotation, int forced) {
        return id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ rotation * ROTATION_SALT ^ forced * FORCED_SALT;
    }

    private static void stock(Outpost outpost, MerchantPool pool) {
        List<MerchantOffer> optional = new ArrayList<>();
        Set<MerchantOffer> chosen = new HashSet<>();
        for (MerchantOffer offer : pool.offers()) {
            if (offer.guaranteed()) {
                chosen.add(offer);
            } else {
                optional.add(offer);
            }
        }
        RandomSource random = RandomSource.create(seed(outpost.id(), outpost.rotation(), outpost.forced()));
        chosen.addAll(WeightedPicker.pickWithoutReplacement(optional, MerchantOffer::weight, pool.rolls(), random));
        List<Outpost.Slot> slots = new ArrayList<>();
        // Ordre du pool : l'étal reste lisible d'une rotation à l'autre.
        for (MerchantOffer offer : pool.offers()) {
            if (chosen.contains(offer) && slots.size() < MarketRules.MAX_OFFERS) {
                slots.add(new Outpost.Slot(offer.item(), offer.count(), offer.price(), offer.payout(),
                        offer.sells() ? offer.stock() : 0, offer.buys() ? offer.demand() : 0));
            }
        }
        outpost.replaceOffers(slots);
    }
}
