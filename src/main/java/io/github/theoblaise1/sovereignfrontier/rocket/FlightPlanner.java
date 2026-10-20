// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import io.github.theoblaise1.sovereignfrontier.space.ChunkOwners;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.space.ReturnPoints;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Règles des vols en fusée (docs/conception/J5.md §4.2 à §4.4), sans effet de bord : trajet, coût en carburant,
 * contrôles du lancement (dans l'ordre du §4.2, le premier échec est renvoyé) et site d'atterrissage. Le même
 * contrôle sert au lancement, à la revalidation du compte à rebours, au transfert et à la fiche du chat.
 * <p>
 * Topologie en étoile : Overworld vers une planète, ou planète vers {@code terre} ; jamais Nether, End ni vol entre
 * planètes. Les fonctions de coût et de site sont pures (objets et territoire passés en paramètres) : les GameTests
 * leur donnent une soute et un territoire simulés.
 */
public final class FlightPlanner {
    /** Marge entre le bord du disque d'une planète et un atterrissage (blocs). */
    public static final int LANDING_MARGIN = 32;

    private FlightPlanner() {
    }

    // ------------------------------------------------------------------ trajet

    /** Trajet entre l'Overworld et une planète, dans un sens. */
    public record Route(PlanetDefinition planet, boolean towardPlanet) {
        public ResourceKey<Level> origin() {
            return towardPlanet ? Level.OVERWORLD : planet.dimension();
        }

        public ResourceKey<Level> destination() {
            return towardPlanet ? planet.dimension() : Level.OVERWORLD;
        }

        /** Nom de la destination : celui de la planète, ou {@code terre} au retour. */
        public String destinationName() {
            return towardPlanet ? planet.name() : PlanetDefinition.EARTH;
        }

        public Component destinationDisplay() {
            return towardPlanet ? planet.displayName() : Msg.tr(PlanetDefinition.translationKey(PlanetDefinition.EARTH));
        }
    }

    /**
     * Trajet permis depuis {@code from} vers la destination nommée, ou {@code null} : depuis l'Overworld vers une
     * planète publiée, ou depuis une planète vers {@code terre}.
     */
    @Nullable
    public static Route route(ResourceKey<Level> from, String destination, PlanetRegistry planets) {
        if (PlanetDefinition.EARTH.equals(destination)) {
            PlanetDefinition here = planets.byDimension(from);
            return here == null ? null : new Route(here, false);
        }
        if (!Level.OVERWORLD.equals(from)) {
            return null;
        }
        PlanetDefinition planet = planets.byName(destination);
        return planet == null ? null : new Route(planet, true);
    }

    /** Destinations proposables depuis cette dimension (suggestions et boutons de la fiche), dans l'ordre. */
    public static List<Route> routesFrom(ResourceKey<Level> from, PlanetRegistry planets) {
        List<Route> routes = new ArrayList<>();
        if (Level.OVERWORLD.equals(from)) {
            for (PlanetDefinition planet : planets.all()) {
                routes.add(new Route(planet, true));
            }
        } else {
            PlanetDefinition here = planets.byDimension(from);
            if (here != null) {
                routes.add(new Route(here, false));
            }
        }
        return routes;
    }

    // ------------------------------------------------------------------ carburant

    /** Carburant retenu : l'option du JSON et la quantité après propulseur. */
    public record Fuel(PlanetDefinition.FuelOption option, int needed) {
        public Item item() {
            return option.item();
        }
    }

    /** Vrai si la nation a {@code propulsion.advanced} et que la soute contient un Propulseur avancé. */
    public static boolean propulsorActive(@Nullable Nation nation, Container hold) {
        return nation != null && nation.research().hasUnlock(UnlockKeys.PROPULSION_ADVANCED)
                && RocketHold.contains(hold, ModItems.ADVANCED_PROPULSOR.get());
    }

    /** Quantité après propulseur : {@code max(1, floor(count × facteur))} (inchangée sans propulseur). */
    public static int adjusted(int count, boolean propulsor) {
        if (!propulsor) {
            return count;
        }
        return Math.max(1, (int) Math.floor(count * SFConfig.ROCKET_PROPULSOR_FACTOR.get()));
    }

    /** Toutes les options du trajet, quantités ajustées, dans l'ordre du JSON. */
    public static List<Fuel> options(PlanetDefinition planet, boolean towardPlanet, boolean propulsor) {
        List<Fuel> options = new ArrayList<>();
        for (PlanetDefinition.FuelOption option : planet.options(towardPlanet)) {
            options.add(new Fuel(option, adjusted(option.count(), propulsor)));
        }
        return options;
    }

    /**
     * Première option du JSON dont la quantité (après propulseur) est disponible en objets <b>intacts</b> dans la soute,
     * ou {@code null}. Un objet renommé, enchanté ou abîmé ne compte pas.
     */
    @Nullable
    public static Fuel cost(PlanetDefinition planet, boolean towardPlanet, Container hold, boolean propulsor) {
        for (Fuel fuel : options(planet, towardPlanet, propulsor)) {
            if (RocketHold.countPlain(hold, fuel.item()) >= fuel.needed()) {
                return fuel;
            }
        }
        return null;
    }

    /** « 3 × Carburant de fusée ou 12 × Bouteille de gaz pleine » : les besoins du trajet, pour les refus et la fiche. */
    public static Component describeNeeds(List<Fuel> options) {
        MutableComponent text = Component.empty();
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) {
                text.append(Msg.tr("rocket.or"));
            }
            text.append(Msg.hl(options.get(i).needed())).append(Component.literal(" × "))
                    .append(Component.translatable(options.get(i).item().getDescriptionId()));
        }
        return text;
    }

    // ------------------------------------------------------------------ atterrissage

    /** Colonne (x, z) d'un site d'atterrissage ; la hauteur est choisie au moment du transfert. */
    public record Column(int x, int z) {
        public ChunkPos chunk() {
            return new ChunkPos(x >> 4, z >> 4);
        }

        public long chunkKey() {
            return ChunkPos.asLong(x >> 4, z >> 4);
        }
    }

    /** Ramène la colonne dans le disque de rayon {@code radius} autour de (0, 0), sur la même direction. */
    public static Column clampToDisc(Column column, int radius) {
        double dist = Math.sqrt((double) column.x() * column.x() + (double) column.z() * column.z());
        if (dist <= radius) {
            return column;
        }
        double scale = radius / dist;
        return new Column((int) Math.floor(column.x() * scale), (int) Math.floor(column.z() * scale));
    }

    /**
     * Colonne visée avant contrôle de légalité (§4.4). Planète : le dernier décollage de cette fusée sur la planète
     * ({@code pad}), sinon la colonne de départ, ramenée dans le disque de rayon {@code radius − 32}. Terre : la colonne
     * du point de retour ({@link ReturnPoints#resolve}) : départ terrestre si son chunk est à la nation ou libre, sinon
     * premier Trésor ou coffre national, sinon point d'apparition du monde.
     */
    public static Column targetColumn(Route route, BlockPos departure, @Nullable BlockPos pad, @Nullable GlobalPos home,
                                      @Nullable UUID nation, Collection<GlobalPos> cores, ChunkOwners owners,
                                      BlockPos worldSpawn) {
        if (route.towardPlanet()) {
            BlockPos base = pad != null ? pad : departure;
            return clampToDisc(new Column(base.getX(), base.getZ()), Math.max(1, route.planet().radius() - LANDING_MARGIN));
        }
        BlockPos target = ReturnPoints.resolve(home, nation, cores, owners, worldSpawn).column().pos();
        return new Column(target.getX(), target.getZ());
    }

    /**
     * Colonne légale la plus proche : le chunk de {@code wanted} ne doit appartenir à aucune autre nation ; sinon
     * spirale sur les chunks à {@code searchChunks} au plus (au plus 81 lectures d'index à 4, aucun chargement),
     * premier chunk libre ou à la nation, colonne en son centre. {@code discRadius > 0} écarte les chunks hors du
     * disque d'une planète. {@code null} : zone occupée.
     */
    @Nullable
    public static Column legalColumn(ResourceKey<Level> dimension, Column wanted, @Nullable UUID nation, ChunkOwners owners,
                                     int searchChunks, int discRadius) {
        if (legal(dimension, wanted.chunkKey(), nation, owners)) {
            return wanted;
        }
        List<int[]> cells = new ArrayList<>();
        for (int dx = -searchChunks; dx <= searchChunks; dx++) {
            for (int dz = -searchChunks; dz <= searchChunks; dz++) {
                if (dx != 0 || dz != 0) {
                    cells.add(new int[]{dx, dz});
                }
            }
        }
        cells.sort(Comparator.<int[]>comparingInt(c -> c[0] * c[0] + c[1] * c[1])
                .thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
        ChunkPos base = wanted.chunk();
        for (int[] cell : cells) {
            int cx = base.x + cell[0];
            int cz = base.z + cell[1];
            Column center = new Column(cx * 16 + 8, cz * 16 + 8);
            if (discRadius > 0 && (long) center.x() * center.x() + (long) center.z() * center.z() > (long) discRadius * discRadius) {
                continue;
            }
            if (legal(dimension, ChunkPos.asLong(cx, cz), nation, owners)) {
                return center;
            }
        }
        return null;
    }

    private static boolean legal(ResourceKey<Level> dimension, long chunk, @Nullable UUID nation, ChunkOwners owners) {
        UUID owner = owners.ownerAt(dimension, chunk);
        return owner == null || owner.equals(nation);
    }

    // ------------------------------------------------------------------ contrôle du lancement

    /** Vol validé : tout ce qui sert au transfert. */
    public record Plan(Route route, ServerLevel destination, Fuel fuel, Column column, boolean propulsor, Nation nation) {
    }

    /** Résultat d'un contrôle : un plan, ou le motif du premier refus. */
    public record Check(@Nullable Plan plan, @Nullable Component error) {
        public boolean ok() {
            return plan != null;
        }

        static Check fail(Component error) {
            return new Check(null, error);
        }
    }

    /** Vrai si rien ne couvre la fusée : aucun bloc au-dessus d'elle jusqu'au sommet du monde. */
    public static boolean skyClear(Level level, BlockPos pos) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) <= pos.getY() + 1;
    }

    /**
     * Contrôles 1 à 8 du §4.2, dans l'ordre, sans aucun effet en cas d'échec :
     * fusée présente et propriétaire ; pilote membre avec {@code construire} et sans délai de vol ; trajet permis ;
     * planète publiée et son niveau présent, clés de recherche (aller), module en soute ; départ terrestre sur le
     * territoire de la nation ; ciel dégagé ; carburant ; site d'atterrissage légal.
     */
    public static Check check(ServerLevel level, BlockPos pos, RocketBlockEntity rocket, Route route, ServerPlayer pilot,
                              long now, ChunkOwners owners) {
        MinecraftServer server = level.getServer();
        // 1. fusée présente
        if (rocket.isRemoved() || level.getBlockEntity(pos) != rocket || !level.getBlockState(pos).is(ModBlocks.ROCKET.get())) {
            return Check.fail(Msg.err("rocket.error.missing"));
        }
        Nation nation = rocket.owner() == null ? null : NationData.get(server).byId(rocket.owner());
        if (nation == null) {
            return Check.fail(Msg.err("rocket.error.inert"));
        }
        // 2. pilote : membre de la nation propriétaire avec « construire », hors délai de vol
        if (!nation.hasPermission(pilot.getUUID(), NationPermission.BUILD)) {
            return Check.fail(Msg.err("rocket.error.pilot", nation.name()));
        }
        long next = pilot.getData(ModAttachments.SPACE.get()).nextFlight();
        if (now < next) {
            return Check.fail(Msg.err("rocket.error.cooldown", TimeUtil.duration(next - now)));
        }
        // 3. trajet permis depuis la dimension de la fusée
        if (!level.dimension().equals(route.origin())) {
            return Check.fail(Msg.err("rocket.error.route"));
        }
        // 4. planète publiée (relue : un /reload a pu la retirer) et son niveau présent ; recherche ; module
        PlanetDefinition planet = PlanetRegistry.get().byId(route.planet().id());
        if (planet == null) {
            return Check.fail(Msg.err("rocket.error.unavailable", route.destinationDisplay()));
        }
        Route fresh = new Route(planet, route.towardPlanet());
        ServerLevel destination = server.getLevel(fresh.destination());
        if (destination == null) {
            return Check.fail(Msg.err("rocket.error.unavailable", fresh.destinationDisplay()));
        }
        if (fresh.towardPlanet()) {
            for (String key : planet.requires()) {
                if (!nation.research().hasUnlock(key)) {
                    return Check.fail(Msg.err("rocket.error.locked", Msg.tr("unlock." + key)));
                }
            }
            if (planet.module().isPresent() && !rocket.hold().contains(planet.module().get())) {
                return Check.fail(Msg.err("rocket.error.module", Component.translatable(planet.module().get().getDescriptionId())));
            }
        }
        // 5. départ terrestre : le chunk de la fusée appartient à la nation
        if (fresh.towardPlanet() && !nation.id().equals(owners.ownerAt(level.dimension(), ChunkPos.asLong(pos)))) {
            return Check.fail(Msg.err("rocket.error.territory"));
        }
        // 6. ciel dégagé
        if (!skyClear(level, pos)) {
            return Check.fail(Msg.err("rocket.error.sky"));
        }
        // 7. carburant
        boolean propulsor = propulsorActive(nation, rocket.hold());
        Fuel fuel = cost(planet, fresh.towardPlanet(), rocket.hold(), propulsor);
        if (fuel == null) {
            return Check.fail(Msg.err("rocket.error.fuel", fresh.destinationDisplay(),
                    describeNeeds(options(planet, fresh.towardPlanet(), propulsor))));
        }
        // 8. site d'atterrissage légal
        Column wanted = targetColumn(fresh, pos, rocket.pad(destination.dimension()), rocket.home(), nation.id(),
                nation.cores(), owners, server.overworld().getSharedSpawnPos());
        Column column = legalColumn(destination.dimension(), wanted, nation.id(), owners,
                SFConfig.ROCKET_LANDING_SEARCH_CHUNKS.get(), fresh.towardPlanet() ? Math.max(1, planet.radius() - LANDING_MARGIN) : 0);
        if (column == null) {
            return Check.fail(Msg.err("rocket.error.landing"));
        }
        return new Check(new Plan(fresh, destination, fuel, column, propulsor, nation), null);
    }

    /** Variante sur le territoire réel du serveur. */
    public static Check check(ServerLevel level, BlockPos pos, RocketBlockEntity rocket, Route route, ServerPlayer pilot, long now) {
        return check(level, pos, rocket, route, pilot, now, ChunkOwners.of(level.getServer()));
    }
}
