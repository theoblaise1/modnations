package io.github.theoblaise1.sovereignfrontier.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.tree.CommandNode;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.machine.MachineKind;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.rocket.CrewTransport;
import io.github.theoblaise1.sovereignfrontier.rocket.FlightPlanner;
import io.github.theoblaise1.sovereignfrontier.rocket.RefineryLogic;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketBlock;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketBlockEntity;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketFlight;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketHold;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketProtection;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketSessions;
import io.github.theoblaise1.sovereignfrontier.space.ChunkOwners;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.space.SpaceState;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * GameTests de la fusée, des vols et de la Raffinerie (J5, WP2). Le serveur de GameTests n'a pas les dimensions des
 * planètes : les vols utilisent une planète de test liée à {@code minecraft:the_end}, dans la zone vide entre l'île
 * centrale et les îles extérieures (z ≥ 400, plateforme de pierre posée par le test). Les faux joueurs sont
 * invulnérables et sans tick, et ne figurent pas dans la liste des joueurs du serveur : la recherche de joueurs du vol
 * est injectée ({@link RocketFlight#replacePlayersForTests}) et le transport de l'équipage enregistre les transitions
 * au lieu d'appeler {@code changeDimension}. Le temps est injecté ; chaque test avance le compte à rebours par appel
 * direct, dans un seul tick (le ticker du bloc n'intervient jamais).
 * <p>
 * Tout test qui fonde une nation ou remplace un registre global a son propre lot {@code sf_j5_rocket_*}.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class RocketGameTests {
    private static final String AREA = J5TestUtil.AREA;
    private static final String PREFIX = SovereignFrontier.MODID + ".";
    /** Instant de départ injecté (epoch ms). */
    private static final long T0 = 1_000_000_000_000L;
    /** Colonne et plateforme d'arrivée dans l'End (z ≥ 400 : zone vide entre l'île centrale et les îles extérieures). */
    private static final BlockPos PAD = new BlockPos(4, 61, 404);

    private RocketGameTests() {
    }

    // ------------------------------------------------------------------ outils

    private static String key(@Nullable Component message) {
        return message != null && message.getContents() instanceof TranslatableContents t ? t.getKey() : String.valueOf(message);
    }

    private static boolean failsWith(ActionResult result, String suffix) {
        return !result.success() && key(result.message()).equals(PREFIX + suffix);
    }

    private static boolean failsWith(FlightPlanner.Check check, String suffix) {
        return !check.ok() && key(check.error()).equals(PREFIX + suffix);
    }

    private static ItemStack named(ItemStack stack, String name) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    /** Transition enregistrée par le transport de test. */
    private record Moved(ServerPlayer player, ServerLevel destination, Vec3 position) {
    }

    /** Montage commun : nation fondée, joueurs de test, fusée posée dans la capitale, nettoyage garanti à la fermeture. */
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final ServerLevel end;
        final MinecraftServer server;
        final ServerPlayer chef;
        final Nation nation;
        final ChunkPos capital;
        final Map<UUID, ServerPlayer> online = new HashMap<>();
        final RocketFlight.Players previousPlayers;
        final List<ServerPlayer> extras = new ArrayList<>();
        final List<GlobalPos> blocks = new ArrayList<>();
        final List<Moved> moved = new ArrayList<>();
        final CrewTransport transport = (player, destination, position, yRot, xRot) ->
                moved.add(new Moved(player, destination, position));
        boolean platform;
        boolean disbanded;

        Fixture(GameTestHelper helper, String name, String... tiers) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.end = server.getLevel(Level.END);
            helper.assertTrue(end != null, "l'End existe sur le serveur de GameTests");
            this.chef = J5TestUtil.fakePlayer(helper, "sf_j5_rk_" + name);
            this.previousPlayers = RocketFlight.replacePlayersForTests((srv, id) -> online.get(id));
            this.nation = J5TestUtil.foundNation(helper, chef, "SfRk" + name, tiers);
            this.capital = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
            online.put(chef.getUUID(), chef);
            BlockPos rocket = rocketPos();
            chef.moveTo(rocket.getX() + 0.5D, rocket.getY(), rocket.getZ() + 1.5D);
        }

        /**
         * Case de la fusée : au coin de la zone du test, donc dans le chunk de la capitale (chunk de l'origine) et sous le
         * ciel libre du test ({@code skyAccess}) : le plafond d'un test voisin ne la couvre jamais.
         */
        BlockPos rocketPos() {
            return helper.absolutePos(new BlockPos(0, 2, 0));
        }

        /** Case de la fusée dans un chunk voisin du chunk de la capitale ({@code dx} chunks à l'est, libre de toute nation). */
        BlockPos rocketPos(int dx) {
            return new BlockPos((capital.x + dx) * 16 + 9, helper.absolutePos(BlockPos.ZERO).getY() + 1, capital.getMinBlockZ() + 8);
        }

        RocketBlockEntity placeRocket(ServerLevel where, BlockPos pos) {
            where.setBlock(pos, ModBlocks.ROCKET.get().defaultBlockState(), 3);
            blocks.add(GlobalPos.of(where.dimension(), pos));
            RocketBlockEntity rocket = (RocketBlockEntity) where.getBlockEntity(pos);
            helper.assertTrue(rocket != null, "bloc-entité de la fusée en " + pos.toShortString());
            rocket.setOwner(nation.id());
            return rocket;
        }

        /** Vide la soute puis y met {@code count} carburants en case 0. */
        void fuel(RocketBlockEntity rocket, int count) {
            rocket.hold().clearContent();
            if (count > 0) {
                rocket.hold().setItem(0, new ItemStack(ModItems.ROCKET_FUEL.get(), count));
            }
        }

        /** Membre de la nation (recrue : elle peut construire), posé près de la fusée. */
        ServerPlayer member(String name, BlockPos near) {
            ServerPlayer p = J5TestUtil.fakePlayer(helper, name);
            J4TestUtil.expect(helper, NationManager.invite(chef, p), true, "invitation de " + name);
            J4TestUtil.expect(helper, NationManager.join(p, nation.name()), true, "adhésion de " + name);
            online.put(p.getUUID(), p);
            extras.add(p);
            p.moveTo(near.getX() + 0.5D, near.getY(), near.getZ() + 1.5D);
            return p;
        }

        /** Joueur sans nation, posé près de la fusée. */
        ServerPlayer outsider(String name, BlockPos near) {
            ServerPlayer p = J5TestUtil.fakePlayer(helper, name);
            online.put(p.getUUID(), p);
            extras.add(p);
            p.moveTo(near.getX() + 0.5D, near.getY(), near.getZ() + 1.5D);
            return p;
        }

        /** Plateforme de pierre 9 × 9 dans l'End (chunk forcé) ; renvoie la case d'arrivée de la fusée. */
        BlockPos platform() {
            end.setChunkForced(0, 25, true);
            end.getChunk(0, 25);
            for (int x = 0; x <= 8; x++) {
                for (int z = 400; z <= 408; z++) {
                    end.setBlock(new BlockPos(x, 60, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            platform = true;
            return PAD;
        }

        PlanetDefinition planet(String name) {
            return J5TestUtil.testPlanet(helper, name, 400_000L);
        }

        /** Lance et fait embarquer {@code boarders}, puis avance jusqu'au transfert. Renvoie le résultat du lancement. */
        ActionResult fly(RocketBlockEntity rocket, BlockPos pos, String destination, long now, ServerPlayer... boarders) {
            ActionResult started = RocketFlight.start(level, pos, rocket, chef, destination, now);
            if (!started.success()) {
                return started;
            }
            for (ServerPlayer p : boarders) {
                J4TestUtil.expect(helper, RocketFlight.board(p, now), true, "embarquement de " + p.getGameProfile().getName());
            }
            for (int i = 0; i < SFConfig.ROCKET_COUNTDOWN_TICKS.get() + 5 && RocketFlight.countdownAt(level, pos) != null; i++) {
                RocketFlight.advance(level, pos, rocket, now, transport);
            }
            return started;
        }

        @Override
        public void close() {
            RocketFlight.cancelAll(server);
            RocketFlight.replacePlayersForTests(previousPlayers);
            RocketSessions.clear();
            for (GlobalPos block : blocks) {
                ServerLevel where = server.getLevel(block.dimension());
                if (where != null && where.getBlockEntity(block.pos()) instanceof RocketBlockEntity rocket) {
                    rocket.hold().clearContent();
                    where.removeBlock(block.pos(), false);
                }
            }
            if (platform) {
                for (int x = 0; x <= 8; x++) {
                    for (int z = 400; z <= 408; z++) {
                        end.setBlock(new BlockPos(x, 60, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                for (int x = 3; x <= 5; x++) {
                    for (int z = 403; z <= 405; z++) {
                        end.removeBlock(new BlockPos(x, 61, z), false);
                    }
                }
                end.setChunkForced(0, 25, false);
            }
            J5TestUtil.disband(chef);
        }
    }

    // ------------------------------------------------------------------ recherche, coût, territoire, ciel

    @GameTest(template = AREA, batch = "sf_j5_rocket_locked", skyAccess = true)
    public static void destinationLockedByResearch(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper, "Lock")) {
            PlanetDefinition planet = f.planet("essai_lune");
            PlanetDefinition nether = J5TestUtil.parsePlanets("essai_n", J5TestUtil.planetJson(Level.NETHER, 1000L))
                    .validated(ResearchRegistry.server()).byName("essai_n");
            helper.assertTrue(nether != null, "seconde planète de test (Nether)");
            J5TestUtil.withPlanets(List.of(planet, nether), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                f.fuel(rocket, 3);
                PlanetRegistry registry = PlanetRegistry.get();
                FlightPlanner.Route out = FlightPlanner.route(Level.OVERWORLD, "essai_lune", registry);
                helper.assertTrue(out != null && out.towardPlanet() && out.destination().equals(Level.END)
                        && out.origin().equals(Level.OVERWORLD), "Terre vers planète de test");

                FlightPlanner.Check none = FlightPlanner.check(f.level, pos, rocket, out, f.chef, T0);
                helper.assertTrue(failsWith(none, "rocket.error.locked") && key((Component) ((TranslatableContents)
                                none.error().getContents()).getArgs()[0]).equals(PREFIX + "unlock.rocket"),
                        "sans recherche : verrouillé, première clé manquante « rocket »");
                J5TestUtil.complete(helper, f.nation, "industrie_8");
                FlightPlanner.Check half = FlightPlanner.check(f.level, pos, rocket, out, f.chef, T0);
                helper.assertTrue(failsWith(half, "rocket.error.locked") && key((Component) ((TranslatableContents)
                                half.error().getContents()).getArgs()[0]).equals(PREFIX + "unlock.dimension.lune"),
                        "fusée recherchée mais pas la destination : « dimension.lune » manque");
                J5TestUtil.complete(helper, f.nation, "technologie_2");
                FlightPlanner.Check open = FlightPlanner.check(f.level, pos, rocket, out, f.chef, T0);
                helper.assertTrue(open.ok() && open.plan().fuel().needed() == 3 && open.plan().fuel().item() == ModItems.ROCKET_FUEL.get()
                        && !open.plan().propulsor(), "avec les deux recherches : vol possible, 3 carburants");

                // Le retour ne demande aucune recherche : un membre sans recherche révoquée n'est jamais bloqué.
                helper.assertTrue(FlightPlanner.route(Level.END, "terre", registry) != null
                        && !FlightPlanner.route(Level.END, "terre", registry).towardPlanet(), "retour vers la Terre depuis la planète");

                // Topologie en étoile : jamais Nether/End vers une planète, jamais de vol entre planètes.
                helper.assertTrue(FlightPlanner.route(Level.NETHER, "essai_lune", registry) == null
                                && FlightPlanner.route(Level.END, "essai_n", registry) == null
                                && FlightPlanner.route(Level.OVERWORLD, "terre", registry) == null
                                && FlightPlanner.route(Level.OVERWORLD, "inconnue", registry) == null,
                        "pas de vol depuis le Nether, entre planètes, ni de « terre » depuis l'Overworld");
                helper.assertTrue(FlightPlanner.routesFrom(Level.OVERWORLD, registry).size() == 2
                                && FlightPlanner.routesFrom(Level.END, registry).size() == 1
                                && FlightPlanner.routesFrom(Level.NETHER, registry).size() == 1
                                && FlightPlanner.routesFrom(Level.NETHER, PlanetRegistry.EMPTY).isEmpty(),
                        "destinations proposées : planètes depuis l'Overworld, la Terre depuis une planète");
                helper.assertTrue(failsWith(FlightPlanner.check(f.level, pos, rocket, FlightPlanner.route(Level.END, "terre", registry),
                        f.chef, T0), "rocket.error.route"), "fusée de l'Overworld : trajet de retour refusé");
            });
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_fuel", skyAccess = true)
    public static void fuelOptionSelection(GameTestHelper helper) {
        PlanetDefinition lune = PlanetRegistry.get().byName("lune");
        PlanetDefinition mars = PlanetRegistry.get().byName("mars");
        helper.assertTrue(lune != null && mars != null, "Lune et Mars publiées");
        helper.assertTrue(FlightPlanner.cost(lune, true, RocketHold.detached(), false) == null, "soute vide : aucun carburant");

        RocketHold fuelOnly = RocketHold.detached();
        fuelOnly.setItem(0, new ItemStack(ModItems.ROCKET_FUEL.get(), 3));
        FlightPlanner.Fuel fuel = FlightPlanner.cost(lune, true, fuelOnly, false);
        helper.assertTrue(fuel != null && fuel.item() == ModItems.ROCKET_FUEL.get() && fuel.needed() == 3, "3 carburants : option carburant");
        helper.assertTrue(FlightPlanner.cost(lune, true, fuelOnly, false) != null && FlightPlanner.cost(mars, true, fuelOnly, false) == null,
                "3 carburants suffisent pour la Lune, pas pour Mars (6)");

        RocketHold gasOnly = RocketHold.detached();
        gasOnly.setItem(0, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 6));
        helper.assertTrue(FlightPlanner.cost(lune, true, gasOnly, false) == null, "6 bouteilles : insuffisant (12)");
        gasOnly.setItem(1, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 6));
        FlightPlanner.Fuel gas = FlightPlanner.cost(lune, true, gasOnly, false);
        helper.assertTrue(gas != null && gas.item() == ModItems.FULL_GAS_BOTTLE.get() && gas.needed() == 12,
                "12 bouteilles réparties sur deux cases : repli sur le gaz brut");
        helper.assertTrue(FlightPlanner.cost(mars, true, gasOnly, false) == null, "Mars n'accepte pas le gaz brut");

        RocketHold both = RocketHold.detached();
        both.setItem(0, new ItemStack(ModItems.ROCKET_FUEL.get(), 2));
        both.setItem(1, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 12));
        helper.assertTrue(FlightPlanner.cost(lune, true, both, false).item() == ModItems.FULL_GAS_BOTTLE.get(),
                "2 carburants + 12 bouteilles : le carburant est insuffisant, repli sur le gaz");
        both.setItem(0, new ItemStack(ModItems.ROCKET_FUEL.get(), 3));
        helper.assertTrue(FlightPlanner.cost(lune, true, both, false).item() == ModItems.ROCKET_FUEL.get(),
                "3 carburants + 12 bouteilles : le carburant est prioritaire");

        // Objets abîmés, renommés ou enchantés : ignorés.
        RocketHold damaged = RocketHold.detached();
        damaged.setItem(0, named(new ItemStack(ModItems.ROCKET_FUEL.get(), 3), "Spécial"));
        helper.assertTrue(FlightPlanner.cost(lune, true, damaged, false) == null, "carburant renommé : ignoré");
        ItemStack worn = new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 12);
        worn.set(DataComponents.DAMAGE, 1);
        damaged.setItem(1, worn);
        helper.assertTrue(FlightPlanner.cost(lune, true, damaged, false) == null, "bouteilles abîmées (composant de dégâts) : ignorées");
        damaged.setItem(2, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 12));
        helper.assertTrue(FlightPlanner.cost(lune, true, damaged, false).item() == ModItems.FULL_GAS_BOTTLE.get(),
                "des bouteilles intactes à côté des objets abîmés suffisent");

        // Retour : 1 carburant ou 4 bouteilles.
        RocketHold back = RocketHold.detached();
        back.setItem(0, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 4));
        helper.assertTrue(FlightPlanner.cost(lune, false, back, false).needed() == 4, "retour : 4 bouteilles");
        back.setItem(1, new ItemStack(ModItems.ROCKET_FUEL.get(), 1));
        helper.assertTrue(FlightPlanner.cost(lune, false, back, false).item() == ModItems.ROCKET_FUEL.get(), "retour : 1 carburant d'abord");

        // Prélèvement : seulement des objets intacts, exactement la quantité.
        RocketHold take = RocketHold.detached();
        take.setItem(0, named(new ItemStack(ModItems.ROCKET_FUEL.get(), 2), "Abîmé"));
        take.setItem(1, new ItemStack(ModItems.ROCKET_FUEL.get(), 2));
        take.setItem(2, new ItemStack(ModItems.ROCKET_FUEL.get(), 2));
        helper.assertFalse(take.consumePlain(ModItems.ROCKET_FUEL.get(), 5), "5 intacts demandés, 4 présents : refus sans effet");
        helper.assertTrue(take.countPlain(ModItems.ROCKET_FUEL.get()) == 4 && take.getItem(0).getCount() == 2, "rien n'a bougé");
        helper.assertTrue(take.consumePlain(ModItems.ROCKET_FUEL.get(), 3) && take.countPlain(ModItems.ROCKET_FUEL.get()) == 1
                && take.getItem(0).getCount() == 2, "3 prélevés sur les cases intactes, la pile renommée est épargnée");
        helper.assertTrue(FlightPlanner.describeNeeds(FlightPlanner.options(lune, true, false)).getSiblings().size() > 0,
                "besoins décrits pour le refus");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_propulsor", skyAccess = true)
    public static void propulsorNeedsUnlockAndReducesFuel(GameTestHelper helper) {
        PlanetRegistry registry = PlanetRegistry.get();
        PlanetDefinition lune = registry.byName("lune");
        PlanetDefinition mars = registry.byName("mars");
        PlanetDefinition glacee = registry.byName("planete_glacee");
        helper.assertTrue(lune != null && mars != null && glacee != null, "trois planètes publiées");

        // Barème du §1.4 : quantité × 0,75, arrondie à l'inférieur, au moins 1.
        helper.assertTrue(counts(lune, true, true).equals(List.of(2, 9)) && counts(lune, false, true).equals(List.of(1, 3))
                        && counts(mars, true, true).equals(List.of(4)) && counts(mars, false, true).equals(List.of(3))
                        && counts(glacee, true, true).equals(List.of(7)) && counts(glacee, false, true).equals(List.of(4)),
                "avec propulseur : Lune 2/9 et 1/3, Mars 4 et 3, planète glacée 7 et 4");
        helper.assertTrue(counts(lune, true, false).equals(List.of(3, 12)) && counts(lune, false, false).equals(List.of(1, 4))
                        && counts(mars, true, false).equals(List.of(6)) && counts(glacee, true, false).equals(List.of(10))
                        && counts(glacee, false, false).equals(List.of(6)), "sans propulseur : 3/12, 1/4, 6, 10 et 6");
        helper.assertTrue(FlightPlanner.adjusted(1, true) == 1 && FlightPlanner.adjusted(2, true) == 1 && FlightPlanner.adjusted(1, false) == 1,
                "au moins 1 carburant");

        try (Fixture f = new Fixture(helper, "Prop")) {
            RocketHold hold = RocketHold.detached();
            hold.setItem(0, new ItemStack(ModItems.ADVANCED_PROPULSOR.get()));
            hold.setItem(1, new ItemStack(ModItems.ROCKET_FUEL.get(), 2));
            helper.assertFalse(FlightPlanner.propulsorActive(f.nation, hold), "propulseur en soute mais recherche absente : inactif");
            helper.assertTrue(FlightPlanner.cost(lune, true, hold, FlightPlanner.propulsorActive(f.nation, hold)) == null,
                    "2 carburants ne suffisent pas pour la Lune sans l'effet du propulseur");
            J5TestUtil.complete(helper, f.nation, "technologie_5");
            helper.assertTrue(FlightPlanner.propulsorActive(f.nation, hold), "recherche faite et propulseur en soute : actif");
            FlightPlanner.Fuel cost = FlightPlanner.cost(lune, true, hold, true);
            helper.assertTrue(cost != null && cost.needed() == 2 && cost.item() == ModItems.ROCKET_FUEL.get(),
                    "Lune : 2 carburants avec propulseur");
            hold.setItem(0, ItemStack.EMPTY);
            helper.assertFalse(FlightPlanner.propulsorActive(f.nation, hold), "sans propulseur en soute : inactif");
            helper.assertFalse(FlightPlanner.propulsorActive(null, hold), "sans nation : inactif");

            // Un vol réel applique la réduction et laisse le propulseur dans la soute.
            J5TestUtil.complete(helper, f.nation, "industrie_8", "technologie_2");
            PlanetDefinition test = f.planet("essai_prop");
            J5TestUtil.withPlanets(List.of(test), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                rocket.hold().setItem(0, new ItemStack(ModItems.ROCKET_FUEL.get(), 2));
                rocket.hold().setItem(1, new ItemStack(ModItems.ADVANCED_PROPULSOR.get()));
                FlightPlanner.Check check = FlightPlanner.check(f.level, pos, rocket, FlightPlanner.route(Level.OVERWORLD, "essai_prop",
                        PlanetRegistry.get()), f.chef, T0);
                helper.assertTrue(check.ok() && check.plan().propulsor() && check.plan().fuel().needed() == 2,
                        "vol de test : propulseur actif, 2 carburants");
            });
        }
        helper.succeed();
    }

    private static List<Integer> counts(PlanetDefinition planet, boolean toward, boolean propulsor) {
        return FlightPlanner.options(planet, toward, propulsor).stream().map(FlightPlanner.Fuel::needed).toList();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_territory", skyAccess = true)
    public static void overworldLaunchNeedsOwnTerritory(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper, "Terr", "industrie_8", "technologie_2")) {
            PlanetDefinition planet = f.planet("essai_terr");
            J5TestUtil.withPlanets(List.of(planet), () -> {
                FlightPlanner.Route route = FlightPlanner.route(Level.OVERWORLD, "essai_terr", PlanetRegistry.get());
                BlockPos away = f.rocketPos(3);
                RocketBlockEntity outside = f.placeRocket(f.level, away);
                f.fuel(outside, 3);
                helper.assertTrue(failsWith(FlightPlanner.check(f.level, away, outside, route, f.chef, T0), "rocket.error.territory"),
                        "fusée hors du territoire de la nation : pas de décollage");
                f.chef.moveTo(away.getX() + 0.5D, away.getY(), away.getZ() + 1.5D);
                helper.assertTrue(failsWith(RocketFlight.start(f.level, away, outside, f.chef, "essai_terr", T0), "rocket.error.territory"),
                        "lancement refusé hors territoire");
                helper.assertTrue(!f.level.getBlockState(away).getValue(RocketBlock.LAUNCHING) && RocketFlight.countdownCount() == 0,
                        "un refus ne change rien");
                f.chef.moveTo(f.rocketPos().getX() + 0.5D, f.rocketPos().getY(), f.rocketPos().getZ() + 1.5D);

                BlockPos home = f.rocketPos();
                RocketBlockEntity inside = f.placeRocket(f.level, home);
                f.fuel(inside, 3);
                helper.assertTrue(FlightPlanner.check(f.level, home, inside, route, f.chef, T0).ok(),
                        "fusée dans la capitale de la nation : décollage permis");
                // Fusée d'une autre nation : le pilote n'en est pas membre.
                ServerPlayer stranger = f.outsider("sf_j5_rk_inconnu", home);
                helper.assertTrue(failsWith(FlightPlanner.check(f.level, home, inside, route, stranger, T0), "rocket.error.pilot"),
                        "un joueur étranger à la nation ne pilote pas");
                inside.setOwner(null);
                helper.assertTrue(failsWith(FlightPlanner.check(f.level, home, inside, route, f.chef, T0), "rocket.error.inert"),
                        "fusée sans nation : inerte");
            });
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_sky", skyAccess = true)
    public static void launchNeedsClearSky(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper, "Ciel", "industrie_8", "technologie_2")) {
            PlanetDefinition planet = f.planet("essai_ciel");
            J5TestUtil.withPlanets(List.of(planet), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                f.fuel(rocket, 3);
                FlightPlanner.Route route = FlightPlanner.route(Level.OVERWORLD, "essai_ciel", PlanetRegistry.get());
                helper.assertTrue(FlightPlanner.skyClear(f.level, pos) && FlightPlanner.check(f.level, pos, rocket, route, f.chef, T0).ok(),
                        "ciel dégagé : décollage permis");
                for (BlockPos roof : List.of(pos.above(), pos.above(5))) {
                    f.level.setBlock(roof, Blocks.GLASS.defaultBlockState(), 3);
                    helper.assertTrue(!FlightPlanner.skyClear(f.level, pos) && failsWith(FlightPlanner.check(f.level, pos, rocket, route,
                            f.chef, T0), "rocket.error.sky"), "bloc à " + (roof.getY() - pos.getY()) + " au-dessus : ciel couvert");
                    helper.assertTrue(failsWith(RocketFlight.start(f.level, pos, rocket, f.chef, "essai_ciel", T0), "rocket.error.sky"),
                            "lancement refusé sous un toit");
                    f.level.setBlock(roof, Blocks.AIR.defaultBlockState(), 3);
                }
                helper.assertTrue(FlightPlanner.skyClear(f.level, pos), "toit retiré : ciel dégagé");
            });
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ embarquement et compte à rebours

    @GameTest(template = AREA, batch = "sf_j5_rocket_boarding", skyAccess = true)
    public static void boardingMembersOnlyAndCap(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper, "Bord", "industrie_8", "technologie_2")) {
            PlanetDefinition planet = f.planet("essai_bord");
            J5TestUtil.withPlanets(List.of(planet), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                f.fuel(rocket, 3);
                ServerPlayer m1 = f.member("sf_j5_rk_m1", pos);
                ServerPlayer m2 = f.member("sf_j5_rk_m2", pos);
                ServerPlayer m3 = f.member("sf_j5_rk_m3", pos);
                ServerPlayer m4 = f.member("sf_j5_rk_m4", pos);
                ServerPlayer far = f.member("sf_j5_rk_loin", pos);
                far.moveTo(pos.getX() + 30.5D, pos.getY(), pos.getZ() + 0.5D);
                ServerPlayer stranger = f.outsider("sf_j5_rk_etranger", pos);

                helper.assertTrue(failsWith(RocketFlight.board(m1, T0), "rocket.error.nothing_to_board"), "aucun décollage à rejoindre");
                J4TestUtil.expect(helper, RocketFlight.start(f.level, pos, rocket, f.chef, "essai_bord", T0), true, "lancement");
                helper.assertTrue(failsWith(RocketFlight.start(f.level, pos, rocket, f.chef, "essai_bord", T0), "rocket.error.busy"),
                        "un seul compte à rebours par fusée");
                helper.assertTrue(failsWith(RocketFlight.board(stranger, T0), "rocket.error.not_member"), "un étranger à la nation n'embarque pas");
                helper.assertTrue(failsWith(RocketFlight.board(far, T0), "rocket.error.nothing_to_board"), "membre à plus de 4 blocs : refusé");
                helper.assertTrue(failsWith(RocketFlight.board(f.chef, T0), "rocket.error.in_crew"), "le pilote est déjà à bord");
                J4TestUtil.expect(helper, RocketFlight.board(m1, T0), true, "m1 embarque");
                helper.assertTrue(failsWith(RocketFlight.board(m1, T0), "rocket.error.in_crew"), "un joueur dans un seul équipage");
                J4TestUtil.expect(helper, RocketFlight.board(m2, T0), true, "m2 embarque");
                J4TestUtil.expect(helper, RocketFlight.board(m3, T0), true, "m3 embarque");
                helper.assertTrue(RocketFlight.crewOf(f.level, pos).size() == SFConfig.ROCKET_CREW_MAX.get(),
                        "équipage plein : pilote + 3 membres");
                helper.assertTrue(failsWith(RocketFlight.board(m4, T0), "rocket.error.crew_full"), "5e personne refusée (4 au plus)");
                helper.assertTrue(RocketFlight.crewOf(f.level, pos).get(0).equals(f.chef.getUUID())
                        && RocketFlight.countdownOf(m3.getUUID()) == RocketFlight.countdownAt(f.level, pos), "pilote en tête, tous dans le même vol");
            });
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_abort", skyAccess = true)
    public static void countdownAbortsOnPilotLeaveOrDamage(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper, "Abort", "industrie_8", "technologie_2")) {
            PlanetDefinition planet = f.planet("essai_abort");
            J5TestUtil.withPlanets(List.of(planet), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                ServerPlayer crew = f.member("sf_j5_rk_eq", pos);
                Runnable launch = () -> {
                    f.fuel(rocket, 3);
                    J4TestUtil.expect(helper, RocketFlight.start(f.level, pos, rocket, f.chef, "essai_abort", T0), true, "lancement");
                    J4TestUtil.expect(helper, RocketFlight.board(crew, T0), true, "embarquement");
                    helper.assertTrue(f.level.getBlockState(pos).getValue(RocketBlock.LAUNCHING), "LAUNCHING pendant le compte à rebours");
                };
                BiConsumer<String, Boolean> stopped = (step, blockStays) -> {
                    helper.assertTrue(RocketFlight.countdownAt(f.level, pos) == null && RocketFlight.countdownOf(f.chef.getUUID()) == null
                            && RocketFlight.countdownOf(crew.getUUID()) == null, step + " : compte à rebours annulé, équipage libéré");
                    if (blockStays) {
                        helper.assertTrue(f.level.getBlockState(pos).is(ModBlocks.ROCKET.get())
                                && !f.level.getBlockState(pos).getValue(RocketBlock.LAUNCHING), step + " : fusée au repos");
                        helper.assertTrue(rocket.hold().countPlain(ModItems.ROCKET_FUEL.get()) == 3, step + " : rien n'a été consommé");
                    }
                };

                // Dégâts du pilote.
                launch.run();
                RocketFlight.onDamage(f.chef, 0.0F);
                helper.assertTrue(RocketFlight.countdownAt(f.level, pos) != null, "dégâts nuls : le vol continue");
                RocketFlight.onDamage(f.chef, 2.0F);
                stopped.accept("dégâts du pilote", true);

                // Dégâts d'un membre : il quitte l'équipage, le vol continue.
                launch.run();
                RocketFlight.onDamage(crew, 3.0F);
                helper.assertTrue(RocketFlight.countdownAt(f.level, pos) != null && RocketFlight.crewOf(f.level, pos).equals(List.of(f.chef.getUUID()))
                        && RocketFlight.countdownOf(crew.getUUID()) == null, "dégâts d'un membre : il descend, le pilote reste");
                RocketFlight.abortBy(f.chef);
                stopped.accept("annulation par le pilote", true);

                // Le pilote s'éloigne de plus de 6 blocs : détecté à la revalidation de la seconde.
                launch.run();
                f.chef.moveTo(pos.getX() + 20.5D, pos.getY(), pos.getZ() + 0.5D);
                for (int i = 0; i < 19; i++) {
                    RocketFlight.advance(f.level, pos, rocket, T0, f.transport);
                }
                helper.assertTrue(RocketFlight.countdownAt(f.level, pos) != null, "avant la 20e tick : pas encore revalidé");
                RocketFlight.advance(f.level, pos, rocket, T0, f.transport);
                stopped.accept("pilote éloigné", true);
                f.chef.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 1.5D);

                // Un membre trop loin est retiré à la revalidation, sans annuler le vol.
                launch.run();
                crew.moveTo(pos.getX() + 20.5D, pos.getY(), pos.getZ() + 0.5D);
                for (int i = 0; i < 20; i++) {
                    RocketFlight.advance(f.level, pos, rocket, T0, f.transport);
                }
                helper.assertTrue(RocketFlight.countdownAt(f.level, pos) != null && RocketFlight.crewOf(f.level, pos).size() == 1,
                        "membre éloigné retiré, vol maintenu");
                crew.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 1.5D);
                RocketFlight.abortBy(f.chef);
                stopped.accept("annulation", true);

                // Déconnexion du pilote.
                launch.run();
                RocketFlight.onLogout(f.chef);
                stopped.accept("déconnexion du pilote", true);

                // Mort du pilote.
                launch.run();
                RocketFlight.onDeath(f.chef);
                stopped.accept("mort du pilote", true);

                // Seul le pilote annule.
                launch.run();
                helper.assertTrue(failsWith(RocketFlight.abortBy(crew), "rocket.error.not_pilot") && RocketFlight.countdownAt(f.level, pos) != null,
                        "un membre ne peut pas annuler");
                helper.assertTrue(RocketFlight.abortBy(f.chef).success(), "le pilote annule");
                stopped.accept("annulation", true);
                helper.assertTrue(failsWith(RocketFlight.abortBy(f.chef), "rocket.error.no_countdown"), "rien à annuler");

                // Fusée cassée pendant le compte à rebours : annulé sans toucher au bloc (déjà parti).
                launch.run();
                rocket.hold().clearContent();
                f.level.removeBlock(pos, false);
                stopped.accept("fusée cassée", false);
                helper.assertTrue(!f.level.getBlockState(pos).is(ModBlocks.ROCKET.get()), "fusée retirée");

                // Redémarrage pendant un compte à rebours : l'état LAUNCHING persisté revient au repos au premier tick.
                RocketBlockEntity again = f.placeRocket(f.level, pos);
                f.level.setBlock(pos, f.level.getBlockState(pos).setValue(RocketBlock.LAUNCHING, true), 3);
                RocketFlight.advance(f.level, pos, again, T0, f.transport);
                helper.assertTrue(!f.level.getBlockState(pos).getValue(RocketBlock.LAUNCHING), "sans compte à rebours connu : retour au repos");
            });
        }
        helper.succeed();
    }

    /**
     * Le vrai ticker du bloc-entité (enregistré par {@code LAUNCHING}) fait avancer le compte à rebours à chaque tick du
     * serveur ; clic droit accroupi (session), lancement par la session, annulation, commandes enregistrées.
     */
    @GameTest(template = AREA, batch = "sf_j5_rocket_ticker", skyAccess = true)
    public static void tickerDrivesCountdown(GameTestHelper helper) {
        Fixture f = new Fixture(helper, "Tic", "industrie_8", "technologie_2");
        PlanetRegistry previous = PlanetRegistry.replaceForTests(new PlanetRegistry(List.of(f.planet("essai_tic"))));
        BlockPos pos = f.rocketPos();
        try {
            RocketBlockEntity rocket = f.placeRocket(f.level, pos);
            f.fuel(rocket, 3);
            CommandNode<CommandSourceStack> root = f.server.getCommands().getDispatcher().getRoot();
            CommandNode<CommandSourceStack> player = root.getChild("rocket");
            CommandNode<CommandSourceStack> admin = root.getChild("sfadmin") == null ? null : root.getChild("sfadmin").getChild("rocket");
            helper.assertTrue(player != null && player.getChild("launch") != null && player.getChild("board") != null
                            && player.getChild("abort") != null && admin != null && admin.getChild("info") != null
                            && admin.getChild("cooldown") != null, "/rocket et /sfadmin rocket enregistrés");

            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            ServerPlayer stranger = f.outsider("sf_j5_rk_curieux", pos);
            stranger.setShiftKeyDown(true);
            f.level.getBlockState(pos).useWithoutItem(f.level, stranger, hit);
            helper.assertTrue(RocketSessions.rocketOf(stranger.getUUID(), TimeUtil.now()) == null,
                    "clic accroupi d'un étranger : ni fiche ni session");
            f.chef.setShiftKeyDown(true);
            f.level.getBlockState(pos).useWithoutItem(f.level, f.chef, hit);
            f.chef.setShiftKeyDown(false);
            helper.assertTrue(GlobalPos.of(Level.OVERWORLD, pos).equals(RocketSessions.rocketOf(f.chef.getUUID(), TimeUtil.now())),
                    "clic accroupi d'un membre : session de 60 s sur la fusée");
            J4TestUtil.expect(helper, RocketFlight.launchFromSession(f.chef, "essai_tic", TimeUtil.now()), true, "lancement par la session");
            helper.assertTrue(f.level.getBlockState(pos).getValue(RocketBlock.LAUNCHING), "LAUNCHING : le ticker est enregistré");
            helper.assertTrue(RocketSessions.rocketOf(f.chef.getUUID(), TimeUtil.now() + RocketSessions.DURATION_MS + 1L) == null,
                    "la session expire après 60 s");
            helper.runAfterDelay(5, () -> {
                try {
                    RocketFlight.Countdown countdown = RocketFlight.countdownAt(f.level, pos);
                    helper.assertTrue(countdown != null && countdown.elapsed() >= 3 && countdown.elapsed() <= 8,
                            "le ticker du bloc avance le compte à rebours à chaque tick (lu : "
                                    + (countdown == null ? "aucun" : countdown.elapsed()) + ")");
                    J4TestUtil.expect(helper, RocketFlight.abortBy(f.chef), true, "annulation par le pilote");
                    helper.assertTrue(!f.level.getBlockState(pos).getValue(RocketBlock.LAUNCHING) && RocketFlight.countdownCount() == 0,
                            "bloc remis au repos");
                    helper.succeed();
                } finally {
                    PlanetRegistry.replaceForTests(previous);
                    f.close();
                }
            });
        } catch (RuntimeException | Error e) {
            PlanetRegistry.replaceForTests(previous);
            f.close();
            throw e;
        }
    }

    // ------------------------------------------------------------------ vol complet

    @GameTest(template = AREA, batch = "sf_j5_rocket_flight", skyAccess = true)
    public static void flightMovesRocketAtomically(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper, "Vol", "industrie_8", "technologie_2")) {
            PlanetDefinition planet = f.planet("essai_vol");
            J5TestUtil.withPlanets(List.of(planet), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                f.platform();
                ServerPlayer crew = f.member("sf_j5_rk_equip", pos);
                ServerPlayer stayer = f.member("sf_j5_rk_reste", pos);
                rocket.setPad(Level.END, PAD);
                rocket.hold().setItem(0, new ItemStack(ModItems.ROCKET_FUEL.get(), 5));
                rocket.hold().setItem(1, new ItemStack(Items.COAL, 7));
                rocket.hold().setItem(2, named(new ItemStack(Items.IRON_SWORD), "Épée du bord"));
                rocket.hold().setItem(8, new ItemStack(ModItems.CRYOGENIC_MODULE.get()));
                SpaceState before = stayer.getData(ModAttachments.SPACE.get());

                // Le membre qui ne monte pas est trop loin au moment du transfert : il reste au sol.
                ActionResult started = f.fly(rocket, pos, "essai_vol", T0, crew);
                J4TestUtil.expect(helper, started, true, "vol complet");

                helper.assertTrue(RocketFlight.countdownAt(f.level, pos) == null && RocketFlight.countdownOf(f.chef.getUUID()) == null
                        && RocketFlight.countdownOf(crew.getUUID()) == null, "compte à rebours terminé");
                helper.assertTrue(f.level.getBlockState(pos).isAir() && f.level.getBlockEntity(pos) == null, "bloc retiré à l'origine");
                helper.assertTrue(f.level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4.0D)).isEmpty(),
                        "retrait sans butin : rien ne tombe au sol");
                helper.assertTrue(rocket.hold().invalidated() && rocket.isMoving(), "soute d'origine invalidée");

                helper.assertTrue(f.end.getBlockState(PAD).is(ModBlocks.ROCKET.get()) && !f.end.getBlockState(PAD).getValue(RocketBlock.LAUNCHING),
                        "fusée posée à l'arrivée (case de décollage précédente), au repos");
                f.blocks.add(GlobalPos.of(Level.END, PAD));
                RocketBlockEntity arrival = (RocketBlockEntity) f.end.getBlockEntity(PAD);
                helper.assertTrue(arrival != null && f.nation.id().equals(arrival.owner()), "propriétaire conservé");
                helper.assertTrue(GlobalPos.of(Level.OVERWORLD, pos).equals(arrival.home()), "home = départ terrestre");
                helper.assertTrue(PAD.equals(arrival.pad(Level.END)), "pads conservés");
                RocketHold hold = arrival.hold();
                helper.assertTrue(hold.countPlain(ModItems.ROCKET_FUEL.get()) == 2 && hold.getItem(0).getCount() == 2,
                        "carburant : 5 - 3 = 2");
                helper.assertTrue(hold.getItem(1).is(Items.COAL) && hold.getItem(1).getCount() == 7
                        && ItemStack.matches(hold.getItem(2), named(new ItemStack(Items.IRON_SWORD), "Épée du bord"))
                        && hold.getItem(8).is(ModItems.CRYOGENIC_MODULE.get()), "fret, objet renommé et module voyagent, même case");

                helper.assertTrue(f.moved.size() == 2 && f.moved.get(0).player() == f.chef && f.moved.get(1).player() == crew,
                        "transitions enregistrées pour le pilote puis le membre embarqué");
                for (Moved m : f.moved) {
                    helper.assertTrue(m.destination() == f.end && m.position().y == PAD.getY()
                                    && Math.abs(m.position().x - (PAD.getX() + 0.5D)) + Math.abs(m.position().z - (PAD.getZ() + 0.5D)) == 1.0D,
                            "équipage sur une case voisine de la fusée, à la hauteur du sol");
                }
                SpaceState chefState = f.chef.getData(ModAttachments.SPACE.get());
                helper.assertTrue(chefState.nextFlight() == T0 + SFConfig.ROCKET_COOLDOWN_SECONDS.get() * 1000L
                                && chefState.graceUntil() == T0 + SFConfig.SPACE_GRACE_SECONDS.get() * 1000L
                                && chefState.lastPlanet().map(l -> l.equals(Level.END.location())).orElse(false)
                                && chefState.returnPoint().map(GlobalPos.of(Level.OVERWORLD, pos)::equals).orElse(false),
                        "pilote : délai de vol, acclimatation, dernière planète, point de retour");
                helper.assertTrue(crew.getData(ModAttachments.SPACE.get()).nextFlight() == chefState.nextFlight(), "même délai pour l'équipage");
                helper.assertTrue(stayer.getData(ModAttachments.SPACE.get()).equals(before), "le membre resté au sol n'est pas touché");

                helper.assertTrue(f.nation.space().flights() == 1 && f.nation.space().hasLanded(Level.END)
                                && f.nation.space().firstLanding(Level.END) == T0,
                        "vol compté, premier atterrissage enregistré");
                helper.assertTrue(f.nation.logEntries().stream().anyMatch(e -> e.key().equals("rocket_flight"))
                        && f.nation.logEntries().stream().anyMatch(e -> e.key().equals("rocket_landing")), "journal de nation");
            });
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_hold", skyAccess = true)
    public static void holdInvalidatedAfterLaunch(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper, "Soute", "industrie_8", "technologie_2")) {
            PlanetDefinition planet = f.planet("essai_soute");
            J5TestUtil.withPlanets(List.of(planet), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                f.platform();
                rocket.setPad(Level.END, PAD);
                RocketHold origin = rocket.hold();
                origin.setItem(0, new ItemStack(ModItems.ROCKET_FUEL.get(), 3));
                origin.setItem(1, new ItemStack(Items.DIAMOND, 5));
                helper.assertTrue(origin.stillValid(f.chef), "soute valable avant le décollage (fusée présente, à portée)");
                // Un faux joueur n'ouvre pas de menu (openMenu est neutralisé) : le menu de la soute lui est affecté à la main.
                AbstractContainerMenu menu = new ChestMenu(MenuType.GENERIC_9x1, 1, f.chef.getInventory(), origin, 1);
                f.chef.containerMenu = menu;
                helper.assertTrue(menu instanceof ChestMenu && menu.getSlot(1).getItem().is(Items.DIAMOND) && origin.viewers().contains(f.chef),
                        "soute ouverte dans un coffre d'une rangée");
                f.level.setBlock(pos, f.level.getBlockState(pos).setValue(RocketBlock.LAUNCHING, true), 3);
                helper.assertFalse(origin.stillValid(f.chef), "soute scellée pendant le compte à rebours");
                f.level.setBlock(pos, f.level.getBlockState(pos).setValue(RocketBlock.LAUNCHING, false), 3);
                helper.assertTrue(origin.stillValid(f.chef), "soute rouverte hors compte à rebours");
                f.chef.moveTo(pos.getX() + 20.5D, pos.getY(), pos.getZ() + 0.5D);
                helper.assertFalse(origin.stillValid(f.chef), "soute fermée au-delà de 8 blocs");
                f.chef.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 1.5D);

                J4TestUtil.expect(helper, f.fly(rocket, pos, "essai_soute", T0), true, "vol");
                f.blocks.add(GlobalPos.of(Level.END, PAD));
                helper.assertTrue(f.end.getBlockEntity(PAD) instanceof RocketBlockEntity arrival && arrival.hold() != origin
                        && arrival.hold().getItem(1).is(Items.DIAMOND) && arrival.hold().getItem(1).getCount() == 5
                        && arrival.hold().countPlain(ModItems.ROCKET_FUEL.get()) == 0, "contenu à l'arrivée : 5 diamants, carburant consommé");

                helper.assertTrue(f.chef.containerMenu == f.chef.inventoryMenu, "menu de la soute fermé au décollage");
                helper.assertTrue(origin.invalidated() && origin.isEmpty() && !origin.stillValid(f.chef) && origin.viewers().isEmpty(),
                        "soute d'origine invalidée et vide");
                origin.setItem(0, new ItemStack(Items.GOLD_INGOT, 64));
                helper.assertTrue(origin.getItem(0).isEmpty(), "setItem refusé sur la soute d'origine");
                helper.assertTrue(origin.removeItem(1, 1).isEmpty() && origin.removeItemNoUpdate(1).isEmpty(), "removeItem refusé");
                ItemStack offered = new ItemStack(Items.EMERALD, 3);
                helper.assertTrue(origin.addItem(offered).getCount() == 3 && !origin.canAddItem(offered), "addItem refusé : la pile revient");
                helper.assertTrue(origin.countPlain(Items.DIAMOND) == 0 && origin.copyStacks().isEmpty(), "rien à lire");

                // Un menu resté en main ne duplique rien : cases vides, dépôt impossible.
                helper.assertTrue(menu.getSlot(1).getItem().isEmpty() && menu.getSlot(0).getItem().isEmpty(), "cases du menu : vides");
                menu.clicked(1, 0, ClickType.PICKUP, f.chef);
                helper.assertTrue(menu.getCarried().isEmpty(), "prendre dans la soute d'origine ne donne rien");
            });
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ atterrissage et retour

    @GameTest(template = AREA, batch = "sf_j5_rocket_landing", skyAccess = true)
    public static void landingAvoidsForeignTerritory(GameTestHelper helper) {
        UUID mine = UUID.randomUUID();
        UUID rival = UUID.randomUUID();
        Set<Long> rivals = new HashSet<>();
        AtomicInteger reads = new AtomicInteger();
        ChunkOwners owners = (dimension, chunk) -> {
            reads.incrementAndGet();
            return rivals.contains(chunk) ? rival : chunk == ChunkPos.asLong(5, 5) ? mine : null;
        };
        FlightPlanner.Column wanted = new FlightPlanner.Column(8, 8);
        long home = ChunkPos.asLong(0, 0);

        helper.assertTrue(FlightPlanner.legalColumn(Level.END, wanted, mine, owners, 4, 0).equals(wanted), "chunk libre : colonne gardée");
        rivals.add(home);
        FlightPlanner.Column moved = FlightPlanner.legalColumn(Level.END, wanted, mine, owners, 4, 0);
        helper.assertTrue(moved != null && moved.equals(new FlightPlanner.Column(-8, 8)), "chunk d'une autre nation : décalage au chunk voisin le plus proche (centre)");
        helper.assertTrue(FlightPlanner.legalColumn(Level.END, wanted, rival, owners, 4, 0).equals(wanted), "le territoire de la nation elle-même est légal");

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                rivals.add(ChunkPos.asLong(dx, dz));
            }
        }
        FlightPlanner.Column ring = FlightPlanner.legalColumn(Level.END, wanted, mine, owners, 4, 0);
        helper.assertTrue(ring != null && ring.equals(new FlightPlanner.Column(-24, 8)), "3 × 3 occupés : première case libre de la couronne suivante");
        helper.assertTrue(FlightPlanner.legalColumn(Level.END, wanted, mine, owners, 0, 0) == null, "recherche à 0 chunk : refus");

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx != 5 && dz != 5) {
                    rivals.add(ChunkPos.asLong(dx, dz));
                }
            }
        }
        reads.set(0);
        helper.assertTrue(FlightPlanner.legalColumn(Level.END, wanted, mine, owners, 4, 0) == null, "tout est pris : refus");
        helper.assertTrue(reads.get() <= 81, "au plus 81 lectures d'index (lu : " + reads.get() + ")");

        rivals.clear();
        helper.assertTrue(FlightPlanner.legalColumn(Level.END, new FlightPlanner.Column(-100_000, 0), mine, owners, 4, 2000) != null,
                "colonne hors disque, chunk libre : acceptée telle quelle (l'appelant l'a déjà ramenée dans le disque)");
        rivals.add(ChunkPos.asLong(1990 >> 4, 8 >> 4));
        FlightPlanner.Column inside = FlightPlanner.legalColumn(Level.END, new FlightPlanner.Column(1990, 8), mine, owners, 4, 1968);
        helper.assertTrue(inside != null && inside.equals(new FlightPlanner.Column(1960, 8)),
                "la spirale écarte les chunks hors du disque de la planète (1960, 8 retenu)");

        // Colonne visée : dernier décollage, sinon départ, ramenés dans le disque (rayon - 32).
        PlanetDefinition lune = PlanetRegistry.get().byName("lune");
        FlightPlanner.Route toward = new FlightPlanner.Route(lune, true);
        BlockPos spawn = new BlockPos(0, 64, 0);
        owners = (dimension, chunk) -> null;
        helper.assertTrue(FlightPlanner.targetColumn(toward, new BlockPos(100, 70, 200), null, null, mine, List.of(), owners, spawn)
                .equals(new FlightPlanner.Column(100, 200)), "sans décollage précédent : colonne de départ");
        helper.assertTrue(FlightPlanner.targetColumn(toward, new BlockPos(100, 70, 200), new BlockPos(-50, 90, 60), null, mine, List.of(), owners,
                spawn).equals(new FlightPlanner.Column(-50, 60)), "dernier décollage de cette fusée sur la planète");
        FlightPlanner.Column clamped = FlightPlanner.targetColumn(toward, new BlockPos(5000, 70, 0), null, null, mine, List.of(), owners, spawn);
        helper.assertTrue(clamped.z() == 0 && clamped.x() == lune.radius() - FlightPlanner.LANDING_MARGIN && clamped.x() < 5000,
                "départ lointain ramené à radius - 32 sur la même direction");

        // Vol réel : tout le disque d'arrivée est occupé par une autre nation -> refus au contrôle 8.
        try (Fixture f = new Fixture(helper, "Atter", "industrie_8", "technologie_2")) {
            PlanetDefinition planet = f.planet("essai_atter");
            J5TestUtil.withPlanets(List.of(planet), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                f.fuel(rocket, 3);
                FlightPlanner.Route route = FlightPlanner.route(Level.OVERWORLD, "essai_atter", PlanetRegistry.get());
                long own = f.capital.toLong();
                ChunkOwners crowded = (dimension, chunk) -> dimension.equals(Level.END) ? rival : chunk == own ? f.nation.id() : null;
                FlightPlanner.Check check = FlightPlanner.check(f.level, pos, rocket, route, f.chef, T0, crowded);
                helper.assertTrue(failsWith(check, "rocket.error.landing"), "arrivée entièrement occupée : « Zone d'atterrissage occupée »");
                ChunkOwners partly = (dimension, chunk) -> dimension.equals(Level.END) && chunk == ChunkPos.asLong(0, 0) ? rival
                        : chunk == own ? f.nation.id() : null;
                rocket.setPad(Level.END, new BlockPos(8, 61, 8));
                FlightPlanner.Check shifted = FlightPlanner.check(f.level, pos, rocket, route, f.chef, T0, partly);
                helper.assertTrue(shifted.ok() && !shifted.plan().column().equals(new FlightPlanner.Column(8, 8))
                        && !shifted.plan().column().chunk().equals(new ChunkPos(0, 0)), "chunk d'arrivée occupé : décalage à un chunk voisin libre");
            });
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_return", skyAccess = true)
    public static void returnHomeThenFallbacks(GameTestHelper helper) {
        UUID mine = UUID.randomUUID();
        UUID rival = UUID.randomUUID();
        Set<Long> rivalChunks = new HashSet<>();
        long coreChunk = ChunkPos.asLong(500 >> 4, 500 >> 4);
        ChunkOwners owners = (dimension, chunk) -> rivalChunks.contains(chunk) ? rival : chunk == coreChunk ? mine : null;
        PlanetDefinition lune = PlanetRegistry.get().byName("lune");
        FlightPlanner.Route back = new FlightPlanner.Route(lune, false);
        BlockPos spawn = new BlockPos(-300, 64, 700);
        GlobalPos home = GlobalPos.of(Level.OVERWORLD, new BlockPos(100, 70, 100));
        GlobalPos core = GlobalPos.of(Level.OVERWORLD, new BlockPos(500, 64, 500));
        BlockPos departure = new BlockPos(7, 90, 7);

        helper.assertTrue(FlightPlanner.targetColumn(back, departure, null, home, mine, List.of(core), owners, spawn)
                .equals(new FlightPlanner.Column(100, 100)), "chunk de départ libre : retour au point de départ terrestre");
        rivalChunks.add(ChunkPos.asLong(100 >> 4, 100 >> 4));
        helper.assertTrue(FlightPlanner.targetColumn(back, departure, null, home, mine, List.of(core), owners, spawn)
                .equals(new FlightPlanner.Column(500, 500)), "départ devenu chez un rival : premier Trésor ou coffre national");
        rivalChunks.add(coreChunk);
        helper.assertTrue(FlightPlanner.targetColumn(back, departure, null, home, mine, List.of(core), owners, spawn)
                .equals(new FlightPlanner.Column(-300, 700)), "noyau lui aussi perdu : point d'apparition du monde");
        helper.assertTrue(FlightPlanner.targetColumn(back, departure, null, null, mine, List.of(), owners, spawn)
                .equals(new FlightPlanner.Column(-300, 700)), "aucun départ connu, aucun noyau : point d'apparition");
        helper.assertTrue(FlightPlanner.targetColumn(back, departure, null, GlobalPos.of(Level.NETHER, home.pos()), mine, List.of(core), owners,
                spawn).equals(new FlightPlanner.Column(-300, 700)), "départ hors de l'Overworld ignoré");
        rivalChunks.clear();
        helper.assertTrue(FlightPlanner.targetColumn(back, departure, null, GlobalPos.of(Level.NETHER, home.pos()), mine, List.of(core), owners,
                spawn).equals(new FlightPlanner.Column(500, 500)), "noyau libre de rival : retenu");

        // Vol de retour complet : la fusée est dans l'End, l'équipage est de retour au point de départ.
        try (Fixture f = new Fixture(helper, "Retour", "industrie_8", "technologie_2")) {
            PlanetDefinition planet = f.planet("essai_retour");
            J5TestUtil.withPlanets(List.of(planet), () -> {
                BlockPos pad = f.platform();
                // Pilote dans l'End : un faux joueur créé dans ce niveau, membre de la nation.
                ServerPlayer pilot = FakePlayerFactory.get(f.end, new GameProfile(UUID.randomUUID(), "sf_j5_rk_retour"));
                J4TestUtil.expect(helper, NationManager.invite(f.chef, pilot), true, "invitation du pilote d'altitude");
                J4TestUtil.expect(helper, NationManager.join(pilot, f.nation.name()), true, "adhésion du pilote d'altitude");
                f.online.put(pilot.getUUID(), pilot);
                pilot.moveTo(pad.getX() + 0.5D, pad.getY(), pad.getZ() + 1.5D);

                BlockPos originPos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.end, pad);
                rocket.setHome(GlobalPos.of(Level.OVERWORLD, originPos));
                FlightPlanner.Route toEarth = FlightPlanner.route(Level.END, "terre", PlanetRegistry.get());

                helper.assertTrue(failsWith(FlightPlanner.check(f.end, pad, rocket, toEarth, pilot, T0), "rocket.error.fuel"),
                        "retour : sans carburant dans la soute, refus");
                rocket.hold().setItem(0, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 4));
                FlightPlanner.Check check = FlightPlanner.check(f.end, pad, rocket, toEarth, pilot, T0);
                helper.assertTrue(check.ok() && check.plan().fuel().item() == ModItems.FULL_GAS_BOTTLE.get() && check.plan().fuel().needed() == 4
                                && check.plan().column().equals(new FlightPlanner.Column(originPos.getX(), originPos.getZ())),
                        "retour sans recherche (aucune clé exigée) : 4 bouteilles, colonne du départ terrestre");

                ActionResult started = RocketFlight.start(f.end, pad, rocket, pilot, "terre", T0);
                J4TestUtil.expect(helper, started, true, "lancement du retour");
                for (int i = 0; i < SFConfig.ROCKET_COUNTDOWN_TICKS.get() + 5 && RocketFlight.countdownAt(f.end, pad) != null; i++) {
                    RocketFlight.advance(f.end, pad, rocket, T0, f.transport);
                }
                helper.assertTrue(f.end.getBlockState(pad).isAir(), "fusée partie de la planète");
                BlockPos landed = null;
                for (Moved m : f.moved) {
                    helper.assertTrue(m.destination() == f.level && (int) Math.floor(m.position().x) - originPos.getX() >= -1
                            && (int) Math.floor(m.position().x) - originPos.getX() <= 1, "équipage ramené au point de départ terrestre");
                }
                helper.assertTrue(f.moved.size() == 1 && f.moved.get(0).player() == pilot, "le pilote est seul à bord");
                int x = originPos.getX();
                int z = originPos.getZ();
                for (int y = f.level.getMinBuildHeight(); y < f.level.getMaxBuildHeight() && landed == null; y++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (f.level.getBlockState(candidate).is(ModBlocks.ROCKET.get())) {
                        landed = candidate;
                    }
                }
                helper.assertTrue(landed != null, "fusée posée dans l'Overworld sur la colonne du départ");
                f.blocks.add(GlobalPos.of(Level.OVERWORLD, landed));
                RocketBlockEntity arrival = (RocketBlockEntity) f.level.getBlockEntity(landed);
                helper.assertTrue(arrival.hold().countPlain(ModItems.FULL_GAS_BOTTLE.get()) == 0 && pad.equals(arrival.pad(Level.END))
                                && GlobalPos.of(Level.OVERWORLD, originPos).equals(arrival.home()),
                        "carburant du retour consommé, dernier décollage de la planète mémorisé, départ terrestre conservé");
                SpaceState state = pilot.getData(ModAttachments.SPACE.get());
                helper.assertTrue(state.lastPlanet().isEmpty() && state.nextFlight() == T0 + SFConfig.ROCKET_COOLDOWN_SECONDS.get() * 1000L,
                        "retour sur Terre : dernière planète effacée, délai de vol posé");
                helper.assertTrue(f.nation.space().flights() == 1 && !f.nation.space().hasLanded(Level.END),
                        "un retour est un vol, mais pas un premier atterrissage planétaire");
            });
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_module", skyAccess = true)
    public static void glaceeNeedsModule(GameTestHelper helper) {
        PlanetDefinition real = PlanetRegistry.get().byName("planete_glacee");
        helper.assertTrue(real != null && real.module().orElse(null) == ModItems.CRYOGENIC_MODULE.get(),
                "la planète glacée exige le module cryogénique");
        String withModule = J5TestUtil.planetJson(Level.END, 1000L).replace("\"radius\": 2000",
                "\"module\": \"sovereignfrontier:module_cryogenique\", \"radius\": 2000");
        PlanetDefinition planet = J5TestUtil.parsePlanets("essai_glace", withModule).validated(ResearchRegistry.server()).byName("essai_glace");
        helper.assertTrue(planet != null && planet.module().isPresent(), "planète de test avec module");
        try (Fixture f = new Fixture(helper, "Module", "industrie_8", "technologie_2")) {
            J5TestUtil.withPlanets(List.of(planet), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                f.fuel(rocket, 3);
                FlightPlanner.Route route = FlightPlanner.route(Level.OVERWORLD, "essai_glace", PlanetRegistry.get());
                helper.assertTrue(failsWith(FlightPlanner.check(f.level, pos, rocket, route, f.chef, T0), "rocket.error.module"),
                        "sans module dans la soute : refus");
                rocket.hold().setItem(1, new ItemStack(ModItems.CRYOGENIC_MODULE.get()));
                helper.assertTrue(FlightPlanner.check(f.level, pos, rocket, route, f.chef, T0).ok(), "avec le module dans la soute : vol permis");
                rocket.hold().setItem(1, new ItemStack(Items.NETHER_STAR));
                helper.assertTrue(failsWith(FlightPlanner.check(f.level, pos, rocket, route, f.chef, T0), "rocket.error.module"),
                        "un autre objet ne remplace pas le module");
                rocket.hold().setItem(1, new ItemStack(ModItems.CRYOGENIC_MODULE.get()));
                // Le module n'est pas consommé par le vol : il voyage avec la fusée.
                f.platform();
                rocket.setPad(Level.END, PAD);
                J4TestUtil.expect(helper, f.fly(rocket, pos, "essai_glace", T0), true, "vol avec module");
                f.blocks.add(GlobalPos.of(Level.END, PAD));
                helper.assertTrue(f.end.getBlockEntity(PAD) instanceof RocketBlockEntity arrival
                                && arrival.hold().getItem(1).is(ModItems.CRYOGENIC_MODULE.get()) && arrival.hold().countPlain(ModItems.ROCKET_FUEL.get()) == 0,
                        "module conservé en soute à l'arrivée, carburant consommé");
            });
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_cooldown", skyAccess = true)
    public static void flightCooldownPerPlayer(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper, "Delai", "industrie_8", "technologie_2")) {
            PlanetDefinition planet = f.planet("essai_delai");
            J5TestUtil.withPlanets(List.of(planet), () -> {
                BlockPos pos = f.rocketPos();
                RocketBlockEntity rocket = f.placeRocket(f.level, pos);
                f.platform();
                rocket.setPad(Level.END, PAD);
                ServerPlayer crew = f.member("sf_j5_rk_delai", pos);
                f.fuel(rocket, 6);
                f.chef.setData(ModAttachments.SPACE.get(), f.chef.getData(ModAttachments.SPACE.get()).withNextFlight(T0 + 30_000L));
                helper.assertTrue(failsWith(RocketFlight.start(f.level, pos, rocket, f.chef, "essai_delai", T0), "rocket.error.cooldown"),
                        "pilote en refroidissement : refus (30 s restantes)");
                J4TestUtil.expect(helper, RocketFlight.start(f.level, pos, rocket, f.chef, "essai_delai", T0 + 30_000L), true,
                        "délai écoulé : le vol part");
                crew.setData(ModAttachments.SPACE.get(), crew.getData(ModAttachments.SPACE.get()).withNextFlight(T0 + 90_000L));
                helper.assertTrue(failsWith(RocketFlight.board(crew, T0 + 30_000L), "rocket.error.cooldown"),
                        "membre en refroidissement : il n'embarque pas");
                RocketFlight.abortBy(f.chef);
                helper.assertTrue(f.chef.getData(ModAttachments.SPACE.get()).nextFlight() == T0 + 30_000L, "un vol annulé ne pose pas de délai");

                J4TestUtil.expect(helper, f.fly(rocket, pos, "essai_delai", T0 + 100_000L), true, "vol");
                f.blocks.add(GlobalPos.of(Level.END, PAD));
                long next = f.chef.getData(ModAttachments.SPACE.get()).nextFlight();
                helper.assertTrue(next == T0 + 100_000L + SFConfig.ROCKET_COOLDOWN_SECONDS.get() * 1000L, "délai de vol posé en epoch");

                // Casser et reposer la fusée ne remet pas le délai à zéro : il est porté par le joueur.
                BlockPos second = f.rocketPos();
                RocketBlockEntity other = f.placeRocket(f.level, second);
                f.fuel(other, 3);
                FlightPlanner.Route route = FlightPlanner.route(Level.OVERWORLD, "essai_delai", PlanetRegistry.get());
                helper.assertTrue(failsWith(FlightPlanner.check(f.level, second, other, route, f.chef, next - 1L), "rocket.error.cooldown"),
                        "autre fusée, même joueur : toujours en refroidissement");
                helper.assertTrue(FlightPlanner.check(f.level, second, other, route, f.chef, next).ok(), "à l'instant exact : permis");
                f.chef.setData(ModAttachments.SPACE.get(), f.chef.getData(ModAttachments.SPACE.get()).withNextFlight(0L));
                helper.assertTrue(FlightPlanner.check(f.level, second, other, route, f.chef, next - 1L).ok(),
                        "remise à zéro par un administrateur : permis");
            });
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ raffinerie

    @GameTest(template = AREA, batch = "sf_j5_rocket_refinery", skyAccess = true)
    public static void refineryNeedsUnlockTerritoryEnergy(GameTestHelper helper) {
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_rk_raffine");
        ServerLevel level = helper.getLevel();
        Nation nation = J5TestUtil.foundNation(helper, chef, "SfRkRaff");
        ChunkPos capital = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int y = helper.absolutePos(BlockPos.ZERO).getY() + 1;
        BlockPos inside = new BlockPos(capital.getMinBlockX() + 3, y, capital.getMinBlockZ() + 3);
        BlockPos outside = new BlockPos((capital.x + 4) * 16 + 3, y, capital.getMinBlockZ() + 3);
        try {
            MachineBlockEntity machine = placeRefinery(helper, level, inside);
            MachineBlockEntity free = placeRefinery(helper, level, outside);
            for (MachineBlockEntity m : List.of(machine, free)) {
                helper.assertTrue(m.kind() == MachineKind.REFINERY && m.items().getContainerSize() == 4, "raffinerie à 4 cases");
                load(m, 3, 1);
                m.energyBuffer().setStored(50_000);
            }

            // Sans la recherche rocket.fuel : rien ne se passe, rien n'est consommé.
            run(level, inside, machine, 250);
            helper.assertTrue(output(machine, MachineKind.REFINERY_FUEL_OUT) == 0 && inputs(machine) && machine.energyBuffer().stored() == 50_000
                    && !machine.active() && machine.progress() == 0, "sans rocket.fuel : machine inerte, énergie intacte");

            // Hors du territoire d'une nation : rien non plus, même avec la recherche.
            J5TestUtil.complete(helper, nation, "industrie_9");
            helper.assertTrue(nation.research().hasUnlock("rocket.fuel"), "industrie_9 accorde rocket.fuel");
            run(level, outside, free, 250);
            helper.assertTrue(output(free, MachineKind.REFINERY_FUEL_OUT) == 0 && inputs(free) && free.energyBuffer().stored() == 50_000,
                    "hors territoire : machine inerte");

            // Sans énergie : le cycle est en pause.
            machine.energyBuffer().setStored(0);
            run(level, inside, machine, 50);
            helper.assertTrue(machine.progress() == 0 && !machine.active() && inputs(machine), "sans énergie : pas de progression");
            machine.energyBuffer().setStored(20_000);
            run(level, inside, machine, 100);
            helper.assertTrue(machine.progress() == 100 && machine.active() && machine.energyBuffer().stored() == 0
                    && output(machine, MachineKind.REFINERY_FUEL_OUT) == 0, "100 ticks d'énergie : cycle à moitié");
            run(level, inside, machine, 30);
            helper.assertTrue(machine.progress() == 100 && !machine.active(), "énergie épuisée en plein cycle : pause, progression gardée");
            machine.energyBuffer().setStored(50_000);
            run(level, inside, machine, 100);
            helper.assertTrue(output(machine, MachineKind.REFINERY_FUEL_OUT) == 1 && output(machine, MachineKind.REFINERY_EMPTY_OUT) == 3
                    && !inputs(machine) && machine.progress() == 0, "cycle terminé : 1 carburant, 3 bouteilles vides, ingrédients consommés");
            helper.assertTrue(machine.items().getItem(MachineKind.REFINERY_FUEL_OUT).is(ModItems.ROCKET_FUEL.get())
                    && machine.items().getItem(MachineKind.REFINERY_EMPTY_OUT).is(ModItems.GAS_BOTTLE.get())
                    && machine.energyBuffer().stored() == 50_000 - 100 * SFConfig.REFINERY_ENERGY_PER_TICK.get(),
                    "sorties : carburant de fusée et bouteilles de gaz vides, jamais autre chose ; énergie dépensée");

            // Sortie pleine : la machine s'arrête sans rien perdre.
            load(machine, 3, 1);
            machine.items().setItem(MachineKind.REFINERY_FUEL_OUT, new ItemStack(ModItems.ROCKET_FUEL.get(), 16));
            machine.energyBuffer().setStored(50_000);
            run(level, inside, machine, 250);
            helper.assertTrue(output(machine, MachineKind.REFINERY_FUEL_OUT) == 16 && inputs(machine) && machine.progress() == 0
                    && machine.energyBuffer().stored() == 50_000, "sortie de carburant pleine : arrêt, ingrédients et énergie intacts");
            machine.items().setItem(MachineKind.REFINERY_FUEL_OUT, ItemStack.EMPTY);
            machine.items().setItem(MachineKind.REFINERY_EMPTY_OUT, new ItemStack(ModItems.GAS_BOTTLE.get(), 62));
            run(level, inside, machine, 250);
            helper.assertTrue(output(machine, MachineKind.REFINERY_FUEL_OUT) == 0 && inputs(machine), "sortie de bouteilles sans place pour 3 : arrêt");
            machine.items().setItem(MachineKind.REFINERY_EMPTY_OUT, new ItemStack(ModItems.GAS_BOTTLE.get(), 61));
            run(level, inside, machine, 250);
            helper.assertTrue(output(machine, MachineKind.REFINERY_FUEL_OUT) == 1 && output(machine, MachineKind.REFINERY_EMPTY_OUT) == 64,
                    "place pour 3 bouteilles : le cycle s'achève");

            // Ingrédients insuffisants ou mauvais objets : rien. Aucune sortie n'est un module.
            load(machine, 2, 1);
            machine.energyBuffer().setStored(50_000);
            machine.items().setItem(MachineKind.REFINERY_FUEL_OUT, ItemStack.EMPTY);
            machine.items().setItem(MachineKind.REFINERY_EMPTY_OUT, ItemStack.EMPTY);
            run(level, inside, machine, 250);
            helper.assertTrue(output(machine, MachineKind.REFINERY_FUEL_OUT) == 0 && machine.energyBuffer().stored() == 50_000,
                    "2 bouteilles seulement : rien");
            helper.assertFalse(machine.items().getItem(MachineKind.REFINERY_FUEL_OUT).is(ModItems.CRYOGENIC_MODULE.get()), "jamais de module");
        } finally {
            level.removeBlock(inside, false);
            level.removeBlock(outside, false);
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }

    private static MachineBlockEntity placeRefinery(GameTestHelper helper, ServerLevel level, BlockPos pos) {
        level.setBlock(pos, ModBlocks.REFINERY.get().defaultBlockState(), 3);
        MachineBlockEntity machine = (MachineBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(machine != null, "raffinerie posée en " + pos.toShortString());
        return machine;
    }

    private static void load(MachineBlockEntity machine, int bottles, int blaze) {
        machine.items().setItem(MachineKind.REFINERY_GAS, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), bottles));
        machine.items().setItem(MachineKind.REFINERY_BLAZE, new ItemStack(Items.BLAZE_POWDER, blaze));
    }

    private static boolean inputs(MachineBlockEntity machine) {
        return machine.items().getItem(MachineKind.REFINERY_GAS).getCount() == 3 && machine.items().getItem(MachineKind.REFINERY_BLAZE).getCount() == 1;
    }

    private static int output(MachineBlockEntity machine, int slot) {
        return machine.items().getItem(slot).getCount();
    }

    private static void run(ServerLevel level, BlockPos pos, MachineBlockEntity machine, int ticks) {
        for (int i = 0; i < ticks; i++) {
            RefineryLogic.tick(level, pos, machine);
        }
    }

    // ------------------------------------------------------------------ protection et persistance

    @GameTest(template = AREA, batch = "sf_j5_rocket_protect", skyAccess = true)
    public static void rocketProtectedFromOutsiders(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper, "Prot")) {
            ServerPlayer chef2 = J5TestUtil.fakePlayer(helper, "sf_j5_rk_chef2");
            J4TestUtil.moveTo(helper, chef2, new ChunkPos(f.capital.x + 2, f.capital.z));
            J4TestUtil.expect(helper, NationManager.create(chef2, J4TestUtil.unique(helper, "SfRkAutre")), true, "seconde nation");
            Nation other = NationData.get(f.server).nationOf(chef2.getUUID());
            try {
                UUID stranger = UUID.randomUUID();
                helper.assertTrue(RocketProtection.mayBreak(f.chef.getUUID(), false, f.nation, null), "membre de la nation propriétaire : casse permise");
                helper.assertFalse(RocketProtection.mayBreak(stranger, false, f.nation, null), "étranger en zone libre : casse refusée");
                helper.assertFalse(RocketProtection.mayBreak(chef2.getUUID(), false, f.nation, null), "chef d'une autre nation en zone libre : refusée");
                helper.assertTrue(RocketProtection.mayBreak(chef2.getUUID(), false, f.nation, other), "bâtisseur de la nation du chunk : permise");
                helper.assertFalse(RocketProtection.mayBreak(stranger, false, f.nation, other), "étranger dans une nation qui n'est pas la sienne : refusée");
                helper.assertTrue(RocketProtection.mayBreak(stranger, true, f.nation, null), "administrateur en contournement : permise");
                helper.assertTrue(RocketProtection.mayBreak(stranger, false, null, null), "nation propriétaire disparue (ou fusée inerte) : tous");
                helper.assertTrue(RocketProtection.mayBoard(f.nation, f.chef.getUUID()) && !RocketProtection.mayBoard(f.nation, stranger)
                        && !RocketProtection.mayBoard(null, stranger), "embarquement : membres seulement");

                // Événement réel, fusée en zone libre : la protection de la fusée s'applique seule.
                BlockPos wild = f.rocketPos(6);
                RocketBlockEntity rocket = f.placeRocket(f.level, wild);
                ServerPlayer outsider = f.outsider("sf_j5_rk_vandale", wild);
                helper.assertTrue(breakCanceled(f.level, wild, outsider), "étranger : casse annulée par l'événement");
                helper.assertFalse(breakCanceled(f.level, wild, f.chef), "membre de la nation : casse permise");
                helper.assertTrue(rocket.getBlockState().is(ModBlocks.ROCKET.get()), "la fusée est toujours là");
                // Le bloc résiste aux explosions et aux pistons, et ne se casse qu'à la pioche en fer.
                helper.assertTrue(f.level.getBlockState(wild).getBlock().getExplosionResistance() >= 1200.0F
                                && f.level.getBlockState(wild).getPistonPushReaction() == PushReaction.BLOCK,
                        "résistance 1 200, non poussable");
                // Nation propriétaire dissoute : la fusée devient cassable par tous.
                J5TestUtil.disband(f.chef);
                helper.assertFalse(breakCanceled(f.level, wild, outsider), "nation dissoute : n'importe qui peut casser");
                // Ouverture de la soute : membres de la nation propriétaire seulement.
                helper.assertFalse(RocketProtection.mayOpenHold(null, outsider), "sans nation propriétaire : soute fermée");
            } finally {
                J5TestUtil.disband(chef2);
            }
        }
        helper.succeed();
    }

    private static boolean breakCanceled(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    @GameTest(template = AREA, batch = "sf_j5_rocket_nbt", skyAccess = true)
    public static void rocketBlockEntityRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockState state = ModBlocks.ROCKET.get().defaultBlockState();
        RocketBlockEntity original = new RocketBlockEntity(pos, state);
        UUID owner = UUID.randomUUID();
        GlobalPos home = GlobalPos.of(Level.OVERWORLD, new BlockPos(12, 70, -34));
        original.setOwner(owner);
        original.setHome(home);
        original.setPad(Level.END, new BlockPos(4, 61, 404));
        original.setPad(Level.NETHER, new BlockPos(-8, 33, 9));
        ItemStack renamed = named(new ItemStack(Items.DIAMOND_PICKAXE), "Pioche du bord");
        original.hold().setItem(0, new ItemStack(ModItems.ROCKET_FUEL.get(), 12));
        original.hold().setItem(4, renamed.copy());
        original.hold().setItem(8, new ItemStack(ModItems.CRYOGENIC_MODULE.get()));

        CompoundTag tag = original.saveWithoutMetadata(level.registryAccess());
        RocketBlockEntity copy = new RocketBlockEntity(pos, state);
        copy.loadWithComponents(tag, level.registryAccess());
        helper.assertTrue(owner.equals(copy.owner()) && home.equals(copy.home()), "propriétaire et départ terrestre relus");
        helper.assertTrue(new BlockPos(4, 61, 404).equals(copy.pad(Level.END)) && new BlockPos(-8, 33, 9).equals(copy.pad(Level.NETHER))
                && copy.pad(Level.OVERWORLD) == null && copy.pads().size() == 2, "dernier décollage par dimension relu");
        helper.assertTrue(copy.hold().getContainerSize() == 9 && copy.hold().getItem(0).is(ModItems.ROCKET_FUEL.get())
                && copy.hold().getItem(0).getCount() == 12 && ItemStack.matches(copy.hold().getItem(4), renamed)
                && copy.hold().getItem(8).is(ModItems.CRYOGENIC_MODULE.get()) && copy.hold().getItem(1).isEmpty(),
                "soute de 9 cases relue (cases, piles, composants)");
        RocketBlockEntity empty = new RocketBlockEntity(pos, state);
        empty.loadWithComponents(new RocketBlockEntity(pos, state).saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        helper.assertTrue(empty.owner() == null && empty.home() == null && empty.pads().isEmpty() && empty.hold().isEmpty(),
                "valeurs absentes : aucun propriétaire, départ ni décollage, soute vide");

        RocketBlockEntity.Data data = original.snapshot();
        RocketBlockEntity target = new RocketBlockEntity(pos, state);
        target.restore(data);
        helper.assertTrue(owner.equals(target.owner()) && home.equals(target.home()) && target.pads().equals(original.pads())
                && ItemStack.matches(target.hold().getItem(4), renamed) && data.items().size() == 9, "instantané et restauration (vol)");
        original.hold().invalidate();
        helper.assertTrue(original.snapshot().items().stream().allMatch(ItemStack::isEmpty), "soute invalidée : instantané vide");
        helper.assertTrue(original.saveWithoutMetadata(level.registryAccess()).getList("Items", 10).isEmpty(), "soute invalidée : rien n'est sauvegardé");

        // Pose : la nation du poseur devient propriétaire ; sans nation, la fusée est inerte.
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_rk_poseur");
        ServerPlayer loner = J5TestUtil.fakePlayer(helper, "sf_j5_rk_seul");
        Nation nation = J5TestUtil.foundNation(helper, chef, "SfRkPose");
        BlockPos placeAt = new ChunkPos(helper.absolutePos(BlockPos.ZERO)).getMiddleBlockPosition(helper.absolutePos(BlockPos.ZERO).getY() + 1);
        try {
            level.setBlock(placeAt, state, 3);
            ModBlocks.ROCKET.get().setPlacedBy(level, placeAt, state, chef, new ItemStack(ModItems.ROCKET.get()));
            helper.assertTrue(level.getBlockEntity(placeAt) instanceof RocketBlockEntity placed && nation.id().equals(placed.owner()),
                    "fusée posée par un membre : sa nation est propriétaire");
            ModBlocks.ROCKET.get().setPlacedBy(level, placeAt, state, loner, new ItemStack(ModItems.ROCKET.get()));
            helper.assertTrue(level.getBlockEntity(placeAt) instanceof RocketBlockEntity placed && placed.owner() == null,
                    "fusée posée par un joueur sans nation : inerte");
        } finally {
            if (level.getBlockEntity(placeAt) instanceof RocketBlockEntity placed) {
                placed.hold().clearContent();
            }
            level.removeBlock(placeAt, false);
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }
}
