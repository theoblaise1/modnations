package io.github.theoblaise1.sovereignfrontier.space;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.AdminBypass;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * Règles d'accès aux planètes (docs/conception/J5.md §3) : revendication et fondation (appelées par
 * {@code NationManager}), garde de voyage, bordure souple, lits et ancres, minage des minerais spatiaux et secours
 * d'un joueur dont la planète a disparu. Les décisions sont des fonctions pures ou lisent des index O(1) : aucune
 * n'itère sur les nations ni ne parcourt de chunks. {@code SpaceEvents} les branche sur les événements.
 */
public final class SpaceRules {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Les trois minerais spatiaux (insensibles aux explosions, verrouillés par la recherche). */
    public static final TagKey<Block> SPACE_ORES = blockTag("minerais_spatiaux");
    private static final TagKey<Block> ORES_LUNE = blockTag("minerais/lune");
    private static final TagKey<Block> ORES_MARS = blockTag("minerais/mars");
    private static final TagKey<Block> ORES_GLACEE = blockTag("minerais/planete_glacee");

    /** Distance, en blocs, entre la bordure souple et le point où le joueur est ramené (rayon − 16). */
    public static final int BORDER_BACK = 16;
    /** Période des contrôles par joueur (bordure, repère de planète), en ticks. */
    public static final int CHECK_PERIOD = 20;
    /**
     * Repère de la dernière planète vue, dans les données persistantes du joueur. Il permet de reconnaître, à la
     * connexion, un joueur que Minecraft a replacé dans l'Overworld parce que sa dimension a disparu du serveur.
     */
    public static final String PLANET_MARK = SovereignFrontier.MODID + ":planete";

    private SpaceRules() {
    }

    private static TagKey<Block> blockTag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path));
    }

    // ------------------------------------------------------------------ dimensions

    /**
     * Vrai si la dimension est une planète : publiée par le registre, ou l'une des trois dimensions du mod (si le JSON
     * d'une planète est rejeté, sa dimension reste interdite d'accès).
     */
    public static boolean isSpaceDimension(ResourceKey<Level> dimension) {
        return SpaceDimensions.isPlanet(dimension) || SpaceDimensions.ALL.contains(dimension);
    }

    /**
     * Clé de déblocage qui ouvre la revendication sur cette dimension ({@code dimension.<planète>}), ou {@code null}
     * hors d'une planète.
     */
    @Nullable
    public static String claimKey(ResourceKey<Level> dimension) {
        PlanetDefinition planet = PlanetRegistry.get().byDimension(dimension);
        if (planet != null) {
            return "dimension." + planet.name();
        }
        if (SpaceDimensions.LUNE.equals(dimension)) {
            return UnlockKeys.DIMENSION_LUNE;
        }
        if (SpaceDimensions.MARS.equals(dimension)) {
            return UnlockKeys.DIMENSION_MARS;
        }
        if (SpaceDimensions.PLANETE_GLACEE.equals(dimension)) {
            return UnlockKeys.DIMENSION_PLANETE_GLACEE;
        }
        return null;
    }

    /** Nom affiché d'une clé de déblocage (la clé brute si aucune traduction : déblocage d'un datapack tiers). */
    public static Component unlockName(String key) {
        return Component.translatableWithFallback(Msg.key("unlock." + key), key);
    }

    private static Component planetName(ResourceKey<Level> dimension) {
        PlanetDefinition planet = PlanetRegistry.get().byDimension(dimension);
        return planet != null ? planet.displayName() : Component.literal(dimension.location().toString());
    }

    /**
     * Motif du refus d'une revendication de la nation dans cette dimension (sans la clé {@code dimension.<planète>}),
     * ou {@code null} si elle est permise. Appelé par {@code NationManager.claim}.
     */
    @Nullable
    public static Component claimBlockReason(Nation nation, ResourceKey<Level> dimension) {
        String key = claimKey(dimension);
        if (key == null || nation.research().hasUnlock(key)) {
            return null;
        }
        return Msg.err("space.claim.locked", planetName(dimension), unlockName(key));
    }

    /**
     * Motif du refus de la fondation d'une nation dans cette dimension (toute planète), ou {@code null}. Appelé par
     * {@code NationManager.create}.
     */
    @Nullable
    public static Component foundBlockReason(ResourceKey<Level> dimension) {
        return isSpaceDimension(dimension) ? Msg.err("space.found.planet") : null;
    }

    // ------------------------------------------------------------------ voyage

    /**
     * Décision de la garde de voyage vers une planète : accepté avec un permis (posé par le vol en fusée) ou pour un
     * opérateur de niveau 2 ou plus.
     */
    public static boolean travelAllowed(boolean permit, boolean operator) {
        return permit || operator;
    }

    /**
     * Vrai si {@code entity} peut entrer dans {@code destination}. Toute destination qui n'est pas une planète est
     * libre (le retour vers la Terre ne demande jamais de recherche).
     */
    public static boolean mayTravel(Entity entity, ResourceKey<Level> destination) {
        if (!isSpaceDimension(destination)) {
            return true;
        }
        boolean permit = TravelPermits.has(entity.getUUID());
        boolean operator = entity instanceof ServerPlayer player && player.hasPermissions(2);
        boolean allowed = travelAllowed(permit, operator);
        if (allowed && !permit) {
            LOGGER.info("Opérateur {} voyage vers la planète {} sans fusée", entity.getName().getString(),
                    destination.location());
        }
        return allowed;
    }

    // ------------------------------------------------------------------ bordure souple

    /** Position horizontale (x, z) de retour. */
    public record Point(double x, double z) {
    }

    /**
     * Bordure souple (fonction pure) : {@code null} si (x, z) est dans le disque de rayon {@code radius} autour de
     * (0, 0) ; sinon le point de la même direction à {@code radius − }{@value #BORDER_BACK}.
     */
    @Nullable
    public static Point borderClamp(int radius, double x, double z) {
        double distanceSq = x * x + z * z;
        if (distanceSq <= (double) radius * radius) {
            return null;
        }
        double scale = (radius - BORDER_BACK) / Math.sqrt(distanceSq);
        return new Point(x * scale, z * scale);
    }

    /** Hauteur de marche (au-dessus de la surface) de la colonne, chunk chargé au besoin. */
    public static int surfaceY(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return Math.max(level.getMinBuildHeight() + 1, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
    }

    /** Vrai pour les joueurs soumis aux contraintes des planètes (ni Créatif ni spectateur). */
    public static boolean constrained(ServerPlayer player) {
        return !player.isCreative() && !player.isSpectator();
    }

    /**
     * Contrôle périodique d'un joueur (toutes les {@value #CHECK_PERIOD} ticks) : met à jour le repère de planète puis
     * ramène dans la bordure le joueur qui la dépasse. Un joueur hors planète ne coûte qu'une recherche de table.
     */
    public static void tickPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        PlanetDefinition planet = SpaceDimensions.planet(level);
        markPlanet(player, isSpaceDimension(level.dimension()) ? level.dimension() : null);
        if (planet == null || !constrained(player)) {
            return;
        }
        Point back = borderClamp(planet.radius(), player.getX(), player.getZ());
        if (back == null) {
            return;
        }
        int x = (int) Math.floor(back.x());
        int z = (int) Math.floor(back.z());
        int y = surfaceY(level, x, z);
        player.teleportTo(level, back.x(), y, back.z(), player.getYRot(), player.getXRot());
        player.resetFallDistance();
        player.displayClientMessage(Msg.warn("space.border"), true);
    }

    // ------------------------------------------------------------------ lits et ancres

    /**
     * Vrai si l'utilisation de ce bloc doit être refusée dans ce niveau : lit ou ancre de réapparition sur une planète
     * (aucun point de réapparition, aucune explosion).
     */
    public static boolean refusesRespawnBlock(Level level, BlockState state) {
        return (state.getBlock() instanceof BedBlock || state.getBlock() instanceof RespawnAnchorBlock)
                && isSpaceDimension(level.dimension());
    }

    // ------------------------------------------------------------------ minage

    /**
     * Clé d'extraction qu'exige ce bloc, ou {@code null} s'il n'est pas un minerai spatial. Les planètes publiées font
     * foi ({@code mining}) ; à défaut (JSON de planète rejeté), la table intégrée des trois minerais du mod.
     */
    @Nullable
    public static String miningKey(BlockState state) {
        if (!state.is(SPACE_ORES)) {
            return null;
        }
        for (PlanetDefinition planet : PlanetRegistry.get().all()) {
            if (planet.mining().isPresent() && state.is(planet.mining().get().blocks())) {
                return planet.mining().get().unlock();
            }
        }
        if (state.is(ORES_LUNE)) {
            return UnlockKeys.MINING_LUNAR;
        }
        if (state.is(ORES_MARS)) {
            return UnlockKeys.MINING_MARTIAN;
        }
        if (state.is(ORES_GLACEE)) {
            return UnlockKeys.MINING_CRYOGENIC;
        }
        return null;
    }

    /**
     * Motif du refus de casser ce bloc pour ce joueur, ou {@code null} si c'est permis (autre bloc, clé d'extraction
     * possédée par la nation du joueur, Créatif, ou contournement administrateur). Valable dans toute dimension.
     */
    @Nullable
    public static Component miningBlockReason(ServerPlayer player, BlockState state) {
        String key = miningKey(state);
        if (key == null || player.isCreative() || AdminBypass.isActive(player)) {
            return null;
        }
        Nation nation = NationData.get(player.server).nationOf(player.getUUID());
        if (nation != null && nation.research().hasUnlock(key)) {
            return null;
        }
        return Msg.err("space.mining.locked", unlockName(key));
    }

    // ------------------------------------------------------------------ secours

    /**
     * Décision de secours à la connexion (fonction pure) : le joueur était sur la planète {@code planetAtLogout}
     * (repère de {@link #PLANET_MARK}), Minecraft l'a replacé dans l'Overworld parce que ce niveau n'existe plus.
     * Faux si le repère manque, si le niveau existe encore, ou si le joueur n'est pas dans l'Overworld.
     */
    public static boolean loginNeedsRescue(@Nullable ResourceLocation planetAtLogout, ResourceKey<Level> current,
                                           Predicate<ResourceKey<Level>> levelExists) {
        if (planetAtLogout == null || !Level.OVERWORLD.equals(current)) {
            return false;
        }
        return !levelExists.test(ResourceKey.create(Registries.DIMENSION, planetAtLogout));
    }

    /** Repère de planète d'un joueur (données persistantes), ou {@code null}. */
    @Nullable
    public static ResourceLocation markedPlanet(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        return data.contains(PLANET_MARK, Tag.TAG_STRING) ? ResourceLocation.tryParse(data.getString(PLANET_MARK)) : null;
    }

    /** Écrit (dimension de planète) ou efface ({@code null}) le repère ; sans effet s'il est déjà à jour. */
    public static void markPlanet(ServerPlayer player, @Nullable ResourceKey<Level> planet) {
        CompoundTag data = player.getPersistentData();
        if (planet == null) {
            if (data.contains(PLANET_MARK)) {
                data.remove(PLANET_MARK);
            }
            return;
        }
        String id = planet.location().toString();
        if (!id.equals(data.getString(PLANET_MARK))) {
            data.putString(PLANET_MARK, id);
        }
    }

    /** Destination d'un secours : point de retour résolu et position d'arrivée (au-dessus de la surface). */
    public record RescueSite(ReturnPoints.Target target, Vec3 position) {
    }

    /**
     * Destination terrestre du joueur : départ de sa dernière fusée, sinon Trésor ou coffre de sa nation, sinon point
     * d'apparition du monde (aucune condition de recherche). Charge le chunk d'arrivée pour lire la surface.
     */
    public static RescueSite rescueSite(ServerPlayer player) {
        MinecraftServer server = player.server;
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        SpaceState state = player.getData(ModAttachments.SPACE);
        ReturnPoints.Target target = ReturnPoints.resolve(server, nation, state.returnPoint().orElse(null));
        BlockPos column = target.column().pos();
        ServerLevel earth = server.overworld();
        int y = surfaceY(earth, column.getX(), column.getZ());
        return new RescueSite(target, new Vec3(column.getX() + 0.5D, y, column.getZ() + 0.5D));
    }

    /**
     * Ramène le joueur sur Terre, où qu'il soit ({@code /sfadmin space rescue}, secours à la connexion). Le retour vers
     * l'Overworld n'est jamais bloqué par la garde de voyage. Efface le repère de planète.
     */
    public static RescueSite rescue(ServerPlayer player) {
        RescueSite site = rescueSite(player);
        ServerLevel earth = player.server.overworld();
        player.teleportTo(earth, site.position().x, site.position().y, site.position().z, player.getYRot(), player.getXRot());
        player.resetFallDistance();
        markPlanet(player, null);
        return site;
    }

    /**
     * Connexion : un joueur que Minecraft a replacé dans l'Overworld parce que sa planète a disparu du serveur
     * (datapack retiré, JSON invalide) est ramené à son point de retour, avec un message.
     */
    public static void onLogin(ServerPlayer player) {
        MinecraftServer server = player.server;
        ResourceLocation marked = markedPlanet(player);
        if (loginNeedsRescue(marked, player.level().dimension(), dim -> server.getLevel(dim) != null)) {
            LOGGER.warn("Joueur {} : la planète {} n'existe plus, retour sur Terre", player.getGameProfile().getName(), marked);
            rescue(player);
            player.sendSystemMessage(Msg.warn("space.rescue.login"));
        }
    }
}
