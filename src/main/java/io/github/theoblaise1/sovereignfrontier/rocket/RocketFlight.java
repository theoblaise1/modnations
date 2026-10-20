// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.space.SpaceState;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cycle de vol d'une fusée (docs/conception/J5.md §4.2) : lancement, compte à rebours, embarquement, annulation et
 * transfert. Tout se passe sur le thread serveur ; l'état d'un compte à rebours est <b>en mémoire seulement</b> (arrêt
 * ou plantage : le vol est annulé, rien n'est consommé).
 * <ol>
 *   <li>{@link #start} : contrôles 1 à 8 ({@link FlightPlanner#check}), état {@code LAUNCHING}, ticket de chunk sur
 *       l'arrivée, offre d'embarquement ;</li>
 *   <li>{@link #advance} chaque tick : compte à rebours, revalidation et barre d'action chaque seconde ;</li>
 *   <li>transfert au premier tick qui suit, une fois le chunk d'arrivée chargé (100 ticks d'attente au plus, puis
 *       chargement synchrone) : revalidation complète, puis tout ou rien dans le même tick. Le carburant est prélevé
 *       sur une copie de la soute ; la fusée est posée et vérifiée à l'arrivée <b>avant</b> que la soute d'origine soit
 *       vidée, invalidée, et le bloc d'origine retiré sans butin.</li>
 * </ol>
 * Recherches par joueur ({@link #CREW}) et par fusée ({@link #COUNTDOWNS}) en O(1) : aucun parcours de nations ni de
 * chunks dans un événement.
 */
public final class RocketFlight {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Attente maximale du chunk d'arrivée après la fin du compte à rebours (ticks), puis chargement synchrone. */
    public static final int CHUNK_WAIT_MAX = 100;
    /** Rayon du ticket de chunk posé sur l'arrivée dès le lancement. */
    private static final int TICKET_RADIUS = 2;

    /** Compte à rebours en cours : équipage, progression et destination. */
    public static final class Countdown {
        private final GlobalPos rocket;
        private final UUID pilot;
        private final UUID nation;
        private final ResourceLocation planet;
        private final boolean towardPlanet;
        private final Set<UUID> crew = new LinkedHashSet<>();
        private int elapsed;
        private int waited;
        private ResourceKey<Level> arrivalDimension;
        private ChunkPos arrivalChunk;

        private Countdown(GlobalPos rocket, UUID pilot, UUID nation, ResourceLocation planet, boolean towardPlanet,
                          ResourceKey<Level> arrivalDimension, ChunkPos arrivalChunk) {
            this.rocket = rocket;
            this.pilot = pilot;
            this.nation = nation;
            this.planet = planet;
            this.towardPlanet = towardPlanet;
            this.arrivalDimension = arrivalDimension;
            this.arrivalChunk = arrivalChunk;
            crew.add(pilot);
        }

        public GlobalPos rocket() {
            return rocket;
        }

        public UUID pilot() {
            return pilot;
        }

        public UUID nation() {
            return nation;
        }

        public ResourceLocation planet() {
            return planet;
        }

        public boolean towardPlanet() {
            return towardPlanet;
        }

        /** Équipage dans l'ordre d'embarquement, pilote en tête. */
        public List<UUID> crew() {
            return List.copyOf(crew);
        }

        public int elapsed() {
            return elapsed;
        }
    }

    /**
     * Recherche d'un joueur par identifiant. Les joueurs réels sont ceux de la liste du serveur ; un faux joueur de
     * GameTest n'y figure jamais, d'où l'injection ({@link #replacePlayersForTests}).
     */
    @FunctionalInterface
    public interface Players {
        @Nullable
        ServerPlayer get(MinecraftServer server, UUID id);

        Players ONLINE = (server, id) -> server.getPlayerList().getPlayer(id);
    }

    private static Players players = Players.ONLINE;

    /** Remplace la recherche de joueurs (GameTests, lot propre) ; renvoie la précédente, à rétablir dans un {@code finally}. */
    public static Players replacePlayersForTests(Players next) {
        Players previous = players;
        players = next;
        return previous;
    }

    @Nullable
    private static ServerPlayer player(MinecraftServer server, UUID id) {
        return players.get(server, id);
    }

    /** Un compte à rebours par fusée. */
    private static final Map<GlobalPos, Countdown> COUNTDOWNS = new HashMap<>();
    /** Un joueur dans un seul équipage à la fois : joueur -> fusée. */
    private static final Map<UUID, GlobalPos> CREW = new HashMap<>();

    private RocketFlight() {
    }

    // ------------------------------------------------------------------ consultation

    @Nullable
    public static Countdown countdownAt(ServerLevel level, BlockPos pos) {
        return COUNTDOWNS.get(GlobalPos.of(level.dimension(), pos));
    }

    /** Compte à rebours auquel ce joueur participe (pilote ou équipage), ou {@code null}. */
    @Nullable
    public static Countdown countdownOf(UUID player) {
        GlobalPos rocket = CREW.get(player);
        return rocket == null ? null : COUNTDOWNS.get(rocket);
    }

    public static int countdownCount() {
        return COUNTDOWNS.size();
    }

    // ------------------------------------------------------------------ lancement

    /** Fusée désignée par une session : niveau, position et bloc-entité, ou le motif de l'échec. */
    public record Located(@Nullable ServerLevel level, @Nullable BlockPos pos, @Nullable RocketBlockEntity rocket,
                          @Nullable Component error) {
        public boolean ok() {
            return rocket != null;
        }
    }

    /**
     * Fusée de la session du joueur (60 s), présente et chargée. {@code maxDistance > 0} exige que le joueur soit à
     * cette distance (blocs) au plus de la fusée.
     */
    public static Located locate(ServerPlayer player, long now, double maxDistance) {
        GlobalPos target = RocketSessions.rocketOf(player.getUUID(), now);
        if (target == null) {
            return new Located(null, null, null, Msg.err("rocket.error.no_session"));
        }
        ServerLevel level = player.server.getLevel(target.dimension());
        if (level == null || !level.isLoaded(target.pos()) || !(level.getBlockEntity(target.pos()) instanceof RocketBlockEntity rocket)) {
            return new Located(null, null, null, Msg.err("rocket.error.missing"));
        }
        if (maxDistance > 0.0D && !within(player, level, target.pos(), maxDistance)) {
            return new Located(null, null, null, Msg.err("rocket.error.too_far", (int) maxDistance));
        }
        return new Located(level, target.pos(), rocket, null);
    }

    /**
     * Lancement depuis une session ({@code /rocket launch}) : fusée de la session du pilote (60 s), à
     * {@code rocketPilotLeash} blocs au plus, puis {@link #start}.
     */
    public static ActionResult launchFromSession(ServerPlayer pilot, String destination, long now) {
        Located located = locate(pilot, now, SFConfig.ROCKET_PILOT_LEASH.get());
        if (!located.ok()) {
            return ActionResult.fail(located.error());
        }
        return start(located.level(), located.pos(), located.rocket(), pilot, destination, now);
    }

    /**
     * Engage un compte à rebours (§4.2, étape 1). Tout est vérifié avant le premier effet : un refus ne change rien.
     * Le pilote doit être à {@code rocketPilotLeash} blocs au plus.
     */
    public static ActionResult start(ServerLevel level, BlockPos pos, RocketBlockEntity rocket, ServerPlayer pilot,
                                     String destination, long now) {
        purgeStale(level.getServer());
        GlobalPos key = GlobalPos.of(level.dimension(), pos.immutable());
        if (COUNTDOWNS.containsKey(key)) {
            return ActionResult.fail(Msg.err("rocket.error.busy"));
        }
        if (CREW.containsKey(pilot.getUUID())) {
            return ActionResult.fail(Msg.err("rocket.error.in_crew"));
        }
        if (!within(pilot, level, pos, SFConfig.ROCKET_PILOT_LEASH.get())) {
            return ActionResult.fail(Msg.err("rocket.error.too_far", SFConfig.ROCKET_PILOT_LEASH.get()));
        }
        FlightPlanner.Route route = FlightPlanner.route(level.dimension(), destination, PlanetRegistry.get());
        if (route == null) {
            return ActionResult.fail(Msg.err("rocket.error.route"));
        }
        FlightPlanner.Check check = FlightPlanner.check(level, pos, rocket, route, pilot, now);
        if (!check.ok()) {
            return ActionResult.fail(check.error());
        }
        FlightPlanner.Plan plan = check.plan();
        Countdown countdown = new Countdown(key, pilot.getUUID(), plan.nation().id(), plan.route().planet().id(),
                plan.route().towardPlanet(), plan.destination().dimension(), plan.column().chunk());
        COUNTDOWNS.put(key, countdown);
        CREW.put(pilot.getUUID(), key);
        BlockState state = level.getBlockState(pos);
        level.setBlock(pos, state.setValue(RocketBlock.LAUNCHING, true), Block.UPDATE_ALL);
        plan.destination().getChunkSource().addRegionTicket(TicketType.PORTAL, plan.column().chunk(), TICKET_RADIUS, key.pos());
        offerBoarding(level, pos, countdown, plan, pilot);
        int seconds = (SFConfig.ROCKET_COUNTDOWN_TICKS.get() + 19) / 20;
        return ActionResult.ok(Msg.ok("rocket.launch.started", plan.route().destinationDisplay(), seconds));
    }

    private static void offerBoarding(ServerLevel level, BlockPos pos, Countdown countdown, FlightPlanner.Plan plan,
                                      ServerPlayer pilot) {
        MinecraftServer server = level.getServer();
        MutableComponent button = Msg.ok("rocket.board.button").withStyle(Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rocket board"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Msg.info("rocket.board.hover"))));
        Component offer = Msg.info("rocket.board.offer", pilot.getGameProfile().getName(), plan.route().destinationDisplay())
                .append(Component.literal(" ")).append(button);
        for (UUID member : plan.nation().members().keySet()) {
            ServerPlayer p = player(server, member);
            if (p != null && !countdown.crew.contains(member) && within(p, level, pos, SFConfig.ROCKET_BOARD_RADIUS.get())) {
                p.sendSystemMessage(offer);
            }
        }
    }

    // ------------------------------------------------------------------ embarquement

    /**
     * {@code /rocket board} : le joueur rejoint l'équipage du décollage en cours le plus proche, à
     * {@code rocketBoardRadius} blocs au plus. Membre de la nation propriétaire seulement, équipage plafonné, un seul
     * équipage par joueur, et le délai de vol du joueur doit être écoulé.
     */
    public static ActionResult board(ServerPlayer player, long now) {
        MinecraftServer server = player.server;
        purgeStale(server);
        if (CREW.containsKey(player.getUUID())) {
            return ActionResult.fail(Msg.err("rocket.error.in_crew"));
        }
        Countdown best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = SFConfig.ROCKET_BOARD_RADIUS.get();
        for (Countdown c : COUNTDOWNS.values()) {
            if (!c.rocket.dimension().equals(player.level().dimension())) {
                continue;
            }
            double d = player.distanceToSqr(c.rocket.pos().getCenter());
            if (d <= (double) radius * radius && d < bestDistance) {
                best = c;
                bestDistance = d;
            }
        }
        if (best == null) {
            return ActionResult.fail(Msg.err("rocket.error.nothing_to_board", radius));
        }
        Nation nation = NationData.get(server).byId(best.nation);
        if (!RocketProtection.mayBoard(nation, player.getUUID())) {
            return ActionResult.fail(Msg.err("rocket.error.not_member", nation == null ? "?" : nation.name()));
        }
        if (best.crew.size() >= SFConfig.ROCKET_CREW_MAX.get()) {
            return ActionResult.fail(Msg.err("rocket.error.crew_full", SFConfig.ROCKET_CREW_MAX.get()));
        }
        long next = player.getData(ModAttachments.SPACE.get()).nextFlight();
        if (now < next) {
            return ActionResult.fail(Msg.err("rocket.error.cooldown", TimeUtil.duration(next - now)));
        }
        best.crew.add(player.getUUID());
        CREW.put(player.getUUID(), best.rocket);
        PlanetDefinition planet = PlanetRegistry.get().byId(best.planet);
        Component destination = planet == null ? Component.literal(best.planet.toString())
                : best.towardPlanet ? planet.displayName() : Msg.tr(PlanetDefinition.translationKey(PlanetDefinition.EARTH));
        ServerPlayer pilot = player(server, best.pilot);
        if (pilot != null) {
            pilot.sendSystemMessage(Msg.info("rocket.board.crew_joined", player.getGameProfile().getName(), best.crew.size(),
                    SFConfig.ROCKET_CREW_MAX.get()));
        }
        return ActionResult.ok(Msg.ok("rocket.board.joined", destination));
    }

    // ------------------------------------------------------------------ annulation

    /** {@code /rocket abort} : seul le pilote annule le décollage. */
    public static ActionResult abortBy(ServerPlayer player) {
        GlobalPos key = CREW.get(player.getUUID());
        Countdown countdown = key == null ? null : COUNTDOWNS.get(key);
        if (countdown == null) {
            return ActionResult.fail(Msg.err("rocket.error.no_countdown"));
        }
        if (!countdown.pilot.equals(player.getUUID())) {
            return ActionResult.fail(Msg.err("rocket.error.not_pilot"));
        }
        abort(player.server, countdown, Msg.tr("rocket.reason.manual"), true);
        return ActionResult.ok(Msg.ok("rocket.aborted.ok"));
    }

    /** Dégâts subis par un joueur : le pilote annule le vol, un autre membre quitte l'équipage. */
    public static void onDamage(ServerPlayer player, float amount) {
        if (amount <= 0.0F) {
            return;
        }
        leave(player.server, player.getUUID(), Msg.tr("rocket.reason.damage"));
    }

    public static void onDeath(ServerPlayer player) {
        leave(player.server, player.getUUID(), Msg.tr("rocket.reason.dead"));
    }

    public static void onLogout(ServerPlayer player) {
        leave(player.server, player.getUUID(), Msg.tr("rocket.reason.disconnect"));
    }

    private static void leave(MinecraftServer server, UUID player, Component reason) {
        GlobalPos key = CREW.get(player);
        if (key == null) {
            return;
        }
        Countdown countdown = COUNTDOWNS.get(key);
        if (countdown == null) {
            CREW.remove(player);
        } else if (countdown.pilot.equals(player)) {
            abort(server, countdown, reason, true);
        } else {
            removeFromCrew(server, countdown, player, reason);
        }
    }

    /** La fusée est cassée (ou remplacée) hors vol : son compte à rebours s'arrête, sans toucher au bloc. */
    static void onRocketRemoved(ServerLevel level, BlockPos pos) {
        Countdown countdown = COUNTDOWNS.get(GlobalPos.of(level.dimension(), pos));
        if (countdown != null) {
            abort(level.getServer(), countdown, Msg.tr("rocket.reason.destroyed"), false);
        }
    }

    /**
     * Annule un compte à rebours : prévient l'équipage, remet {@code LAUNCHING} à faux si le bloc est encore là
     * ({@code resetBlock}) et libère le ticket d'arrivée. Rien n'a été consommé.
     */
    private static void abort(MinecraftServer server, Countdown countdown, Component reason, boolean resetBlock) {
        COUNTDOWNS.remove(countdown.rocket);
        Component message = Msg.err("rocket.aborted", reason);
        for (UUID member : countdown.crew) {
            CREW.remove(member, countdown.rocket);
            ServerPlayer p = player(server, member);
            if (p != null) {
                p.sendSystemMessage(message);
            }
        }
        ServerLevel level = server.getLevel(countdown.rocket.dimension());
        if (resetBlock && level != null && level.isLoaded(countdown.rocket.pos())) {
            BlockState state = level.getBlockState(countdown.rocket.pos());
            if (state.is(ModBlocks.ROCKET.get()) && state.getValue(RocketBlock.LAUNCHING)) {
                level.setBlock(countdown.rocket.pos(), state.setValue(RocketBlock.LAUNCHING, false), Block.UPDATE_ALL);
            }
        }
        ServerLevel arrival = server.getLevel(countdown.arrivalDimension);
        if (arrival != null) {
            arrival.getChunkSource().removeRegionTicket(TicketType.PORTAL, countdown.arrivalChunk, TICKET_RADIUS,
                    countdown.rocket.pos());
        }
    }

    private static void removeFromCrew(MinecraftServer server, Countdown countdown, UUID member, Component reason) {
        countdown.crew.remove(member);
        CREW.remove(member, countdown.rocket);
        ServerPlayer p = player(server, member);
        if (p != null) {
            p.sendSystemMessage(Msg.warn("rocket.crew.left", reason));
        }
    }

    /** Arrêt du serveur : plus aucun compte à rebours, blocs remis au repos. */
    public static void cancelAll(MinecraftServer server) {
        for (Countdown countdown : new ArrayList<>(COUNTDOWNS.values())) {
            abort(server, countdown, Msg.tr("rocket.reason.shutdown"), true);
        }
        clear();
    }

    public static void clear() {
        COUNTDOWNS.clear();
        CREW.clear();
    }

    /** Écarte les comptes à rebours dont la fusée n'est plus chargée (leur ticker est arrêté avec le chunk). */
    private static void purgeStale(MinecraftServer server) {
        for (Countdown countdown : new ArrayList<>(COUNTDOWNS.values())) {
            ServerLevel level = server.getLevel(countdown.rocket.dimension());
            if (level == null || !level.isLoaded(countdown.rocket.pos())
                    || !(level.getBlockEntity(countdown.rocket.pos()) instanceof RocketBlockEntity)) {
                abort(server, countdown, Msg.tr("rocket.reason.destroyed"), false);
            }
        }
    }

    // ------------------------------------------------------------------ compte à rebours

    /** Ticker du bloc-entité (pendant {@code LAUNCHING} seulement). */
    static void tick(ServerLevel level, BlockPos pos, RocketBlockEntity rocket) {
        try {
            advance(level, pos, rocket, TimeUtil.now(), CrewTransport.REAL);
        } catch (RuntimeException e) {
            // Une erreur de vol ne doit jamais faire tomber le serveur (un ticker de bloc-entité qui lève plante le tick).
            LOGGER.error("Fusée en {} {} : erreur pendant le vol, compte à rebours annulé", level.dimension().location(),
                    pos.toShortString(), e);
            Countdown countdown = countdownAt(level, pos);
            if (countdown != null) {
                abort(level.getServer(), countdown, Msg.tr("rocket.reason.error"), true);
            }
        }
    }

    /**
     * Avance le compte à rebours d'un tick (temps et transport injectés pour les GameTests). Sans compte à rebours
     * connu (redémarrage pendant le décollage), le bloc revient simplement au repos.
     */
    public static void advance(ServerLevel level, BlockPos pos, RocketBlockEntity rocket, long now, CrewTransport transport) {
        Countdown countdown = countdownAt(level, pos);
        if (countdown == null) {
            BlockState state = level.getBlockState(pos);
            if (state.is(ModBlocks.ROCKET.get()) && state.getValue(RocketBlock.LAUNCHING)) {
                level.setBlock(pos, state.setValue(RocketBlock.LAUNCHING, false), Block.UPDATE_ALL);
            }
            return;
        }
        countdown.elapsed++;
        int total = SFConfig.ROCKET_COUNTDOWN_TICKS.get();
        if (countdown.elapsed < total) {
            if (countdown.elapsed % 20 == 0) {
                everySecond(level, pos, rocket, countdown, now, total);
            }
            return;
        }
        ServerLevel destination = level.getServer().getLevel(countdown.arrivalDimension);
        if (destination != null && countdown.waited < CHUNK_WAIT_MAX
                && destination.getChunkSource().getChunkNow(countdown.arrivalChunk.x, countdown.arrivalChunk.z) == null) {
            if (countdown.waited++ % 20 == 0) {
                notifyCrew(level.getServer(), countdown, Msg.info("rocket.sync"), true);
            }
            return;
        }
        transfer(level, pos, rocket, countdown, now, transport);
    }

    /** Chaque seconde : barre d'action, revalidation des conditions et de l'équipage. */
    private static void everySecond(ServerLevel level, BlockPos pos, RocketBlockEntity rocket, Countdown countdown,
                                    long now, int total) {
        MinecraftServer server = level.getServer();
        ServerPlayer pilot = player(server, countdown.pilot);
        if (pilot == null) {
            abort(server, countdown, Msg.tr("rocket.reason.disconnect"), true);
            return;
        }
        if (!pilot.isAlive()) {
            abort(server, countdown, Msg.tr("rocket.reason.dead"), true);
            return;
        }
        if (!within(pilot, level, pos, SFConfig.ROCKET_PILOT_LEASH.get())) {
            abort(server, countdown, Msg.tr("rocket.reason.pilot_left"), true);
            return;
        }
        FlightPlanner.Route route = FlightPlanner.route(level.dimension(), destinationName(countdown), PlanetRegistry.get());
        FlightPlanner.Check check = route == null ? null : FlightPlanner.check(level, pos, rocket, route, pilot, now);
        if (check == null || !check.ok()) {
            abort(server, countdown, check == null ? Msg.tr("rocket.error.route") : check.error(), true);
            return;
        }
        retargetTicket(server, countdown, check.plan());
        for (UUID member : new ArrayList<>(countdown.crew)) {
            if (member.equals(countdown.pilot)) {
                continue;
            }
            ServerPlayer p = player(server, member);
            if (p == null || !p.isAlive() || !within(p, level, pos, SFConfig.ROCKET_PILOT_LEASH.get())) {
                removeFromCrew(server, countdown, member, Msg.tr("rocket.reason.left"));
            }
        }
        int seconds = (total - countdown.elapsed + 19) / 20;
        notifyCrew(server, countdown, Msg.warn("rocket.countdown", seconds, check.plan().route().destinationDisplay()), true);
    }

    /** Le site d'atterrissage a pu changer (territoire) : le ticket suit le chunk visé. */
    private static void retargetTicket(MinecraftServer server, Countdown countdown, FlightPlanner.Plan plan) {
        ChunkPos wanted = plan.column().chunk();
        if (wanted.equals(countdown.arrivalChunk) && plan.destination().dimension().equals(countdown.arrivalDimension)) {
            return;
        }
        ServerLevel old = server.getLevel(countdown.arrivalDimension);
        if (old != null) {
            old.getChunkSource().removeRegionTicket(TicketType.PORTAL, countdown.arrivalChunk, TICKET_RADIUS, countdown.rocket.pos());
        }
        countdown.arrivalDimension = plan.destination().dimension();
        countdown.arrivalChunk = wanted;
        plan.destination().getChunkSource().addRegionTicket(TicketType.PORTAL, wanted, TICKET_RADIUS, countdown.rocket.pos());
    }

    private static void notifyCrew(MinecraftServer server, Countdown countdown, Component message, boolean actionBar) {
        for (UUID member : countdown.crew) {
            ServerPlayer p = player(server, member);
            if (p != null) {
                p.displayClientMessage(message, actionBar);
            }
        }
    }

    private static String destinationName(Countdown countdown) {
        return countdown.towardPlanet ? countdown.planet.getPath() : PlanetDefinition.EARTH;
    }

    // ------------------------------------------------------------------ transfert

    /** Position d'arrivée : la fusée et une case par voisin de la fusée pour l'équipage (chacune à sa hauteur). */
    record Spot(BlockPos rocket, List<Vec3> crew) {
    }

    /**
     * Site d'arrivée sur la colonne : charge le chunk (synchrone s'il ne l'est pas), hauteur
     * {@code MOTION_BLOCKING_NO_LEAVES}. Refus ({@code null}) si la colonne est vide (pas de sol), liquide, ou plus
     * haute que la hauteur logique moins 4.
     */
    @Nullable
    static Spot spot(ServerLevel destination, FlightPlanner.Column column) {
        destination.getChunk(column.x() >> 4, column.z() >> 4);
        int y = destination.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.x(), column.z());
        if (y <= destination.getMinBuildHeight() || y > destination.dimensionType().logicalHeight() - 4) {
            return null;
        }
        BlockPos rocket = new BlockPos(column.x(), y, column.z());
        BlockState below = destination.getBlockState(rocket.below());
        if (below.getCollisionShape(destination, rocket.below()).isEmpty() || !below.getFluidState().isEmpty()) {
            return null;
        }
        List<Vec3> crew = new ArrayList<>(4);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int x = rocket.getX() + direction.getStepX();
            int z = rocket.getZ() + direction.getStepZ();
            destination.getChunk(x >> 4, z >> 4);
            crew.add(new Vec3(x + 0.5D, destination.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5D));
        }
        return new Spot(rocket, crew);
    }

    private static void transfer(ServerLevel level, BlockPos pos, RocketBlockEntity rocket, Countdown countdown, long now,
                                 CrewTransport transport) {
        MinecraftServer server = level.getServer();
        ServerPlayer pilot = player(server, countdown.pilot);
        if (pilot == null || !pilot.isAlive() || !within(pilot, level, pos, SFConfig.ROCKET_PILOT_LEASH.get())) {
            abort(server, countdown, Msg.tr(pilot == null ? "rocket.reason.disconnect" : "rocket.reason.pilot_left"), true);
            return;
        }
        // 1. revalidation complète : la planète est relue (un /reload a pu la retirer), rien n'est consommé en cas d'échec.
        FlightPlanner.Route route = FlightPlanner.route(level.dimension(), destinationName(countdown), PlanetRegistry.get());
        FlightPlanner.Check check = route == null ? null : FlightPlanner.check(level, pos, rocket, route, pilot, now);
        if (check == null || !check.ok()) {
            abort(server, countdown, check == null ? Msg.tr("rocket.error.route") : check.error(), true);
            return;
        }
        FlightPlanner.Plan plan = check.plan();
        ServerLevel destination = plan.destination();
        // 2. position finale
        Spot spot = spot(destination, plan.column());
        if (spot == null) {
            abort(server, countdown, Msg.tr("rocket.error.landing"), true);
            return;
        }
        // 3. copie de la soute moins le carburant (sur une copie : l'origine n'est pas touchée avant l'arrivée)
        RocketBlockEntity.Data raw = rocket.snapshot();
        RocketHold scratch = RocketHold.detached();
        for (int i = 0; i < RocketHold.SIZE; i++) {
            scratch.setItem(i, raw.items().get(i).copy());
        }
        if (!scratch.consumePlain(plan.fuel().item(), plan.fuel().needed())) {
            abort(server, countdown, Msg.tr("rocket.error.landing"), true);
            return;
        }
        List<ItemStack> items = new ArrayList<>(RocketHold.SIZE);
        for (int i = 0; i < RocketHold.SIZE; i++) {
            items.add(scratch.getItem(i).copy());
        }
        Map<ResourceKey<Level>, Long> pads = new LinkedHashMap<>(raw.pads());
        GlobalPos home = raw.home();
        if (plan.route().towardPlanet()) {
            home = GlobalPos.of(level.dimension(), pos.immutable());
        } else {
            pads.put(level.dimension(), pos.asLong());
        }
        RocketBlockEntity.Data data = new RocketBlockEntity.Data(raw.owner(), home, pads, items);
        // 4. pose à l'arrivée, vérifiée avant toute perte
        destination.setBlock(spot.rocket(), ModBlocks.ROCKET.get().defaultBlockState(), Block.UPDATE_ALL);
        if (!(destination.getBlockEntity(spot.rocket()) instanceof RocketBlockEntity arrival)) {
            destination.removeBlock(spot.rocket(), false);
            abort(server, countdown, Msg.tr("rocket.error.landing"), true);
            return;
        }
        arrival.restore(data);
        // 5. point de non-retour : menus fermés, soute d'origine vidée et invalidée, bloc retiré sans butin
        for (Player viewer : rocket.hold().viewers()) {
            if (viewer instanceof ServerPlayer sp) {
                sp.closeContainer();
            }
        }
        rocket.hold().invalidate();
        COUNTDOWNS.remove(countdown.rocket);
        List<ServerPlayer> travellers = new ArrayList<>();
        for (UUID member : countdown.crew) {
            CREW.remove(member, countdown.rocket);
            ServerPlayer p = player(server, member);
            if (p == null || !p.isAlive() || !within(p, level, pos, SFConfig.ROCKET_PILOT_LEASH.get())) {
                if (p != null) {
                    p.sendSystemMessage(Msg.warn("rocket.crew.left", Msg.tr("rocket.reason.left")));
                }
                continue;
            }
            travellers.add(p);
        }
        rocket.markMoving();
        level.removeBlock(pos, false);
        // 6. équipage : permis de voyage et changeDimension (par le transport), puis état spatial de chaque joueur
        PlanetDefinition planet = plan.route().planet();
        long cooldownMs = SFConfig.ROCKET_COOLDOWN_SECONDS.get() * 1000L;
        long graceMs = SFConfig.SPACE_GRACE_SECONDS.get() * 1000L;
        for (int i = 0; i < travellers.size(); i++) {
            ServerPlayer p = travellers.get(i);
            p.stopRiding();
            p.closeContainer();
            SpaceState state = p.getData(ModAttachments.SPACE.get()).withNextFlight(now + cooldownMs);
            if (plan.route().towardPlanet()) {
                state = state.withGraceUntil(now + graceMs)
                        .withLastPlanet(Optional.of(planet.dimension().location()))
                        .withReturnPoint(Optional.of(GlobalPos.of(level.dimension(), pos.immutable())));
            } else {
                state = state.withLastPlanet(Optional.empty());
            }
            p.setData(ModAttachments.SPACE.get(), state);
            try {
                transport.transfer(p, destination, spot.crew().get(i % spot.crew().size()), p.getYRot(), p.getXRot());
                p.displayClientMessage(Msg.ok("rocket.landed", plan.route().destinationDisplay()), true);
            } catch (RuntimeException e) {
                // Le bloc est déjà parti : les autres membres voyagent quand même, l'échec est journalisé.
                LOGGER.error("Fusée : le déplacement de {} vers {} a échoué", p.getGameProfile().getName(),
                        destination.dimension().location(), e);
            }
        }
        // 7. journal de nation, compteur de vols, premier atterrissage
        recordFlight(server, plan, level.dimension(), pilot, travellers, now);
    }

    private static void recordFlight(MinecraftServer server, FlightPlanner.Plan plan, ResourceKey<Level> origin,
                                     ServerPlayer pilot, List<ServerPlayer> crew, long now) {
        Nation nation = plan.nation();
        PlanetDefinition planet = plan.route().planet();
        String from = "@" + PlanetDefinition.translationKey(plan.route().towardPlanet() ? PlanetDefinition.EARTH : planet.name());
        String to = "@" + PlanetDefinition.translationKey(plan.route().destinationName());
        StringBuilder names = new StringBuilder();
        for (ServerPlayer p : crew) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(p.getGameProfile().getName());
        }
        nation.space().addFlight();
        nation.log("rocket_flight", pilot.getGameProfile().getName(), from, to, names.toString());
        if (plan.route().towardPlanet() && nation.space().recordLanding(planet.dimension(), now)) {
            nation.log("rocket_landing", to);
            server.getPlayerList().broadcastSystemMessage(Msg.ok("rocket.first_landing", nation.name(), planet.displayName()), false);
        }
        nation.markDirty();
    }

    // ------------------------------------------------------------------ utilitaires

    private static boolean within(ServerPlayer player, ServerLevel level, BlockPos pos, double blocks) {
        return player.level() == level && player.distanceToSqr(pos.getCenter()) <= blocks * blocks;
    }

    /** Équipage d'un compte à rebours, pour les diagnostics (lecture seule). */
    public static List<UUID> crewOf(ServerLevel level, BlockPos pos) {
        Countdown countdown = countdownAt(level, pos);
        return countdown == null ? Collections.emptyList() : countdown.crew();
    }
}
