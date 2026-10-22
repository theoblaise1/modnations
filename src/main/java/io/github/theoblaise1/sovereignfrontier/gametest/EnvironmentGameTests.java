package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.command.EnvironmentCommand;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.machine.MachineKind;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationAdmin;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.space.ChunkOwners;
import io.github.theoblaise1.sovereignfrontier.space.Environment;
import io.github.theoblaise1.sovereignfrontier.space.OxygenDiffuserLogic;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.space.SpaceState;
import io.github.theoblaise1.sovereignfrontier.space.ZoneIndex;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * GameTests de l'oxygène et du froid (J5, WP3) : consommation des bouteilles, asphyxie, acclimatation, zones du
 * Diffuseur d'oxygène (territoire, énergie, déblocage, expiration), froid, Chaudière à gaz, Réchauffeur nucléaire,
 * Plastron chauffant et joueurs exemptés. Les décisions sont des fonctions pures ({@link Environment#step}) ou des
 * passages appliqués à un faux joueur ({@link Environment#tick}) : un faux joueur est invulnérable, on vérifie donc les
 * dégâts <i>calculés</i>, pas les points de vie. Le serveur de GameTests n'a pas les planètes : les planètes de test
 * sont liées à {@code minecraft:the_end} et passées en paramètre. Les machines sont ticquées à la main, par fenêtres
 * de 20 ticks (une fenêtre = un passage de leur logique) ; le temps de jeu ne progresse pas pendant un test.
 * Tout test qui fonde une nation ou remplace le registre des planètes a son propre lot {@code sf_j5_<test>}.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class EnvironmentGameTests {
    private static final String AREA = J5TestUtil.AREA;
    /** Instant fixe de référence (epoch ms) : les tests injectent le temps. */
    private static final long NOW = 1_700_000_000_000L;
    /** Réglages explicites des décisions pures (valeurs par défaut du §1.10). */
    private static final Environment.Settings CFG = new Environment.Settings(120, 2.0F, 20, 30, 1.0F, 2, 300);
    private static final PlanetDefinition.Environment OXYGEN_ONLY = new PlanetDefinition.Environment(true, false);
    private static final PlanetDefinition.Environment COLD_ONLY = new PlanetDefinition.Environment(false, true);
    private static final PlanetDefinition.Environment BOTH = new PlanetDefinition.Environment(true, true);

    private EnvironmentGameTests() {
    }

    // ------------------------------------------------------------------ outils

    /** Capteurs simulés d'une décision pure ; {@code queries} compte les interrogations (aucune pendant l'acclimatation). */
    private static final class Probe implements Environment.Sensors {
        boolean oxygenZone;
        boolean heatZone;
        boolean plastron;
        int oxygenBottles;
        int fullGas;
        int queries;

        @Override
        public boolean oxygenZone() {
            queries++;
            return oxygenZone;
        }

        @Override
        public boolean heatZone() {
            queries++;
            return heatZone;
        }

        @Override
        public boolean plastronReady() {
            queries++;
            return plastron;
        }

        @Override
        public int oxygenBottles() {
            queries++;
            return oxygenBottles;
        }

        @Override
        public int fullGasBottles() {
            queries++;
            return fullGas;
        }
    }

    /** Planète de test (liée à l'End) avec l'environnement demandé. */
    private static PlanetDefinition planet(GameTestHelper helper, String name, boolean oxygen, boolean cold) {
        JsonObject json = JsonParser.parseString(J5TestUtil.planetJson(Level.END, 1_000L)).getAsJsonObject();
        JsonObject environment = json.getAsJsonObject("environment");
        environment.addProperty("oxygen", oxygen);
        environment.addProperty("cold", cold);
        PlanetRegistry parsed = J5TestUtil.parsePlanets(name, json.toString()).validated(ResearchRegistry.server());
        PlanetDefinition planet = parsed.byName(name);
        helper.assertTrue(planet != null && planet.environment().oxygen() == oxygen && planet.environment().cold() == cold,
                "planète de test « " + name + " » valide");
        return planet;
    }

    /** Faux joueur en survie, inventaire vide et état spatial vierge. */
    private static ServerPlayer freshPlayer(GameTestHelper helper, String name) {
        ServerPlayer player = J5TestUtil.fakePlayer(helper, name);
        reset(player);
        return player;
    }

    private static void reset(ServerPlayer player) {
        player.getInventory().clearContent();
        player.setData(ModAttachments.SPACE, SpaceState.EMPTY);
    }

    private static ServerLevel endLevel(GameTestHelper helper) {
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(end != null, "l'End existe sur le serveur de GameTests");
        return end;
    }

    private static int count(ServerPlayer player, Item item) {
        return Inventories.countPlain(player, item);
    }

    private static SpaceState state(ServerPlayer player) {
        return player.getData(ModAttachments.SPACE);
    }

    /** Position au milieu d'un chunk, à la hauteur de la zone de test. */
    private static BlockPos spot(GameTestHelper helper, ChunkPos chunk) {
        return new BlockPos(chunk.getMiddleBlockX(), helper.absolutePos(BlockPos.ZERO).getY() + 2, chunk.getMiddleBlockZ());
    }

    private static MachineBlockEntity place(ServerLevel level, BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), 3);
        return (MachineBlockEntity) level.getBlockEntity(pos);
    }

    private static void clear(ServerLevel level, BlockPos... positions) {
        for (BlockPos pos : positions) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /**
     * Fait passer {@code windows} fenêtres de travail à la machine (20 ticks chacune ; le compteur d'une machine neuve
     * vaut 0, donc chaque série de 20 ticks contient exactement un début de fenêtre).
     */
    private static void windows(ServerLevel level, MachineBlockEntity machine, int windows) {
        for (int i = 0; i < windows * OxygenDiffuserLogic.WINDOW; i++) {
            machine.tick(level, machine.getBlockPos(), level.getBlockState(machine.getBlockPos()));
        }
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    /** Clé de traduction du composant, ou de son premier élément si c'est une ligne composée (barre d'action). */
    private static String firstKey(Component component) {
        String key = key(component);
        return key.isEmpty() && !component.getSiblings().isEmpty() ? key(component.getSiblings().get(0)) : key;
    }

    private static boolean hasKey(List<Component> lines, String key) {
        return lines.stream().anyMatch(line -> key(line).equals(SovereignFrontier.MODID + "." + key));
    }

    // ------------------------------------------------------------------ oxygène

    /**
     * Hors zone, 1 s de réserve par seconde ; à 0, une bouteille intacte est prise (inventaire, puis main secondaire) et
     * une bouteille de gaz vide est rendue ; avertissements à 20 s de la dernière bouteille ; objets modifiés ignorés.
     */
    @GameTest(template = AREA)
    public static void oxygenConsumesBottlesAndReturnsEmpty(GameTestHelper helper) {
        PlanetDefinition planet = planet(helper, "essai_o2", true, false);
        ServerPlayer player = freshPlayer(helper, "sf_j5_o2");
        Environment.Settings cfg = Environment.Settings.current();
        player.getInventory().add(new ItemStack(ModItems.OXYGEN_BOTTLE.get(), 2));

        Environment.Outcome out = Environment.tick(player, planet, NOW);
        helper.assertTrue(out != null && out.takeOxygenBottle() && out.bottlesLeft() == 1 && !out.warnLastBottle()
                && out.asphyxiaDamage() == 0.0F && !out.inOxygenZone() && out.oxygenPlanet(),
                "1re seconde : une bouteille prise, une restante, aucun dégât");
        helper.assertTrue(count(player, ModItems.OXYGEN_BOTTLE.get()) == 1 && count(player, ModItems.GAS_BOTTLE.get()) == 1,
                "bouteille d'oxygène remplacée par une bouteille de gaz vide");
        helper.assertTrue(state(player).oxygen() == cfg.bottleSeconds() - 1, "réserve entamée : "
                + state(player).oxygen() + " s (attendu " + (cfg.bottleSeconds() - 1) + ")");
        for (int second = 2; second <= cfg.bottleSeconds(); second++) {
            out = Environment.tick(player, planet, NOW);
            helper.assertTrue(out != null && !out.takeOxygenBottle() && !out.warnLow() && out.asphyxiaDamage() == 0.0F
                    && state(player).oxygen() == cfg.bottleSeconds() - second,
                    "seconde " + second + " de la 1re bouteille : 1 s consommée, pas d'alerte (une bouteille reste)");
        }
        helper.assertTrue(state(player).oxygen() == 0 && count(player, ModItems.OXYGEN_BOTTLE.get()) == 1,
                "1re bouteille vidée, la 2e n'est pas encore entamée");

        out = Environment.tick(player, planet, NOW);
        helper.assertTrue(out != null && out.takeOxygenBottle() && out.bottlesLeft() == 0 && out.warnLastBottle()
                && out.asphyxiaDamage() == 0.0F, "2e bouteille prise : avertissement de la dernière bouteille");
        helper.assertTrue(count(player, ModItems.OXYGEN_BOTTLE.get()) == 0 && count(player, ModItems.GAS_BOTTLE.get()) == 2,
                "deux bouteilles de gaz vides rendues");
        int lows = 0;
        for (int second = 2; second <= cfg.bottleSeconds(); second++) {
            out = Environment.tick(player, planet, NOW);
            helper.assertTrue(out != null && out.asphyxiaDamage() == 0.0F && !out.takeOxygenBottle(),
                    "seconde " + second + " de la dernière bouteille : aucun dégât");
            if (out.warnLow()) {
                lows++;
            }
        }
        helper.assertTrue(lows == (cfg.warnSeconds() > 0 ? 1 : 0), "avertissement de réserve basse émis une seule fois (" + lows + ")");
        helper.assertTrue(state(player).oxygen() == 0, "dernière bouteille vidée");
        out = Environment.tick(player, planet, NOW);
        helper.assertTrue(out != null && out.asphyxiaDamage() == cfg.oxygenDamage() && !out.takeOxygenBottle()
                && out.bottlesLeft() == 0, "plus rien à respirer : dégâts d'asphyxie");

        // Main secondaire : consultée après l'inventaire principal.
        reset(player);
        player.getInventory().offhand.set(0, new ItemStack(ModItems.OXYGEN_BOTTLE.get()));
        out = Environment.tick(player, planet, NOW);
        helper.assertTrue(out != null && out.takeOxygenBottle() && count(player, ModItems.OXYGEN_BOTTLE.get()) == 0
                && count(player, ModItems.GAS_BOTTLE.get()) == 1, "bouteille de la main secondaire prise, bouteille vide rendue");

        // Un objet renommé n'est pas « intact » : ignoré.
        reset(player);
        ItemStack named = new ItemStack(ModItems.OXYGEN_BOTTLE.get());
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Bouteille marquée"));
        player.getInventory().add(named);
        out = Environment.tick(player, planet, NOW);
        helper.assertTrue(out != null && !out.takeOxygenBottle() && out.asphyxiaDamage() > 0.0F
                && player.getInventory().countItem(ModItems.OXYGEN_BOTTLE.get()) == 1, "bouteille renommée ignorée");

        // Planète sans oxygène : rien n'est consommé.
        reset(player);
        player.getInventory().add(new ItemStack(ModItems.OXYGEN_BOTTLE.get(), 3));
        out = Environment.tick(player, planet(helper, "essai_sans_o2", false, true), NOW);
        helper.assertTrue(out != null && !out.oxygenPlanet() && !out.takeOxygenBottle() && out.asphyxiaDamage() == 0.0F
                && count(player, ModItems.OXYGEN_BOTTLE.get()) == 3, "planète sans oxygène : bouteilles intactes");
        reset(player);
        helper.succeed();
    }

    /** Sans bouteille ni zone : dégâts d'asphyxie chaque seconde ; en zone ou sans exigence : aucun. */
    @GameTest(template = AREA, batch = "sf_j5_asphyxie")
    public static void oxygenSuffocationOutcome(GameTestHelper helper) {
        PlanetDefinition planet = planet(helper, "essai_asphyxie", true, false);
        ServerPlayer player = FakePlayerFactory.get(endLevel(helper), new GameProfile(UUID.randomUUID(), "sf_j5_asphyxie"));
        MinecraftServer server = helper.getLevel().getServer();
        reset(player);

        Probe bare = new Probe();
        SpaceState state = SpaceState.EMPTY;
        for (int second = 1; second <= 3; second++) {
            Environment.Outcome out = Environment.step(state, OXYGEN_ONLY, bare, CFG, NOW);
            helper.assertTrue(out.asphyxiaDamage() == 2.0F && !out.takeOxygenBottle() && out.bottlesLeft() == 0
                    && out.state().oxygen() == 0 && !out.grace() && !out.inOxygenZone() && out.coldDamage() == 0.0F,
                    "seconde " + second + " sans bouteille : 2,0 de dégâts d'asphyxie");
            state = out.state();
        }
        Probe inZone = new Probe();
        inZone.oxygenZone = true;
        Environment.Outcome breathing = Environment.step(SpaceState.EMPTY, OXYGEN_ONLY, inZone, CFG, NOW);
        helper.assertTrue(breathing.inOxygenZone() && breathing.asphyxiaDamage() == 0.0F && breathing.bottlesLeft() == -1
                && !breathing.takeOxygenBottle() && inZone.queries == 1 && breathing.state().equals(SpaceState.EMPTY),
                "en zone oxygénée : rien consommé, inventaire non parcouru");
        Probe none = new Probe();
        Environment.Outcome noOxygen = Environment.step(SpaceState.EMPTY, COLD_ONLY, none, CFG, NOW);
        helper.assertTrue(!noOxygen.oxygenPlanet() && noOxygen.asphyxiaDamage() == 0.0F && !noOxygen.takeOxygenBottle(),
                "planète sans oxygène : aucun dégât d'asphyxie");

        // Le dégât et son type existent : ignorent l'armure (J5.md §2.1).
        helper.assertTrue(helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).containsKey(Environment.ASPHYXIA)
                && helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).containsKey(Environment.FREEZE),
                "types de dégâts asphyxie et gel enregistrés");

        // Passage appliqué au faux joueur (invulnérable : on lit la décision, pas les points de vie).
        J5TestUtil.withPlanets(List.of(planet), () -> {
            Environment.Outcome out = Environment.tick(player, planet, NOW);
            helper.assertTrue(out != null && out.asphyxiaDamage() == Environment.Settings.current().oxygenDamage()
                    && out.bottlesLeft() == 0, "faux joueur sans bouteille : dégâts d'asphyxie calculés");

            // /oxygen : état complet (planète, oxygène, froid, point de retour).
            List<Component> status = Environment.status(player, NOW);
            helper.assertTrue(hasKey(status, "oxygen.status.header") && hasKey(status, "oxygen.status.planet")
                    && hasKey(status, "oxygen.status.zone_out") && hasKey(status, "cold.status.no_cold")
                    && hasKey(status, "oxygen.status.return"), "état affiché sur une planète sans zone ni froid");
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            EnvironmentCommand.register(dispatcher);
            try {
                helper.assertTrue(dispatcher.execute("oxygen", player.createCommandSourceStack()) == 1, "/oxygen s'exécute");
            } catch (CommandSyntaxException e) {
                helper.fail("/oxygen : " + e.getMessage());
            }
        });
        Environment.Outcome elsewhere = Environment.tick(freshPlayer(helper, "sf_j5_asphyxie_terre"), null, NOW);
        helper.assertTrue(elsewhere == null, "hors planète : aucun passage");

        // Sous-commandes d'administration : greffées sur /sfadmin, réservées aux opérateurs, secondes bornées.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("sfadmin");
        EnvironmentCommand.registerAdmin(root);
        CommandDispatcher<CommandSourceStack> admin = new CommandDispatcher<>();
        admin.register(root);
        CommandSourceStack op = server.createCommandSourceStack();
        CommandNode<CommandSourceStack> oxygenNode = admin.getRoot().getChild("sfadmin").getChild("oxygen");
        CommandNode<CommandSourceStack> coldNode = admin.getRoot().getChild("sfadmin").getChild("cold");
        helper.assertTrue(oxygenNode != null && coldNode != null && oxygenNode.canUse(op) && coldNode.canUse(op)
                && !oxygenNode.canUse(player.createCommandSourceStack()) && !coldNode.canUse(player.createCommandSourceStack()),
                "/sfadmin oxygen et cold : opérateurs seulement");
        ParseResults<CommandSourceStack> valid = admin.parse("sfadmin oxygen Joueur 90", op);
        ParseResults<CommandSourceStack> tooLong = admin.parse("sfadmin cold Joueur 99999", op);
        helper.assertTrue(valid.getExceptions().isEmpty() && !valid.getReader().canRead()
                && (!tooLong.getExceptions().isEmpty() || tooLong.getReader().canRead()),
                "secondes bornées à 3 600 par la commande");
        helper.succeed();
    }

    /**
     * Acclimatation : ni oxygène ni froid, seulement un compte à rebours (aucun capteur interrogé) ; à la connexion,
     * accordée seulement si la précédente date de plus de 5 minutes ; hors d'une planète, aucune.
     */
    @GameTest(template = AREA, batch = "sf_j5_acclimatation")
    public static void oxygenGraceAfterArrivalAndLogin(GameTestHelper helper) {
        // Règle de connexion (fonction pure).
        SpaceState first = Environment.loginGrace(SpaceState.EMPTY, NOW, 10, 5);
        helper.assertTrue(first.graceUntil() == NOW + 10_000L && first.lastLoginGrace() == NOW, "première connexion : 10 s accordées");
        helper.assertTrue(Environment.loginGrace(first, NOW + 60_000L, 10, 5).equals(first), "reconnexion après 1 min : rien");
        helper.assertTrue(Environment.loginGrace(first, NOW + 5 * 60_000L - 1L, 10, 5).equals(first),
                "reconnexion 1 ms avant les 5 min : rien");
        SpaceState later = Environment.loginGrace(first, NOW + 5 * 60_000L, 10, 5);
        helper.assertTrue(later.graceUntil() == NOW + 5 * 60_000L + 10_000L && later.lastLoginGrace() == NOW + 5 * 60_000L,
                "reconnexion après 5 min : 10 s de plus");
        helper.assertTrue(Environment.loginGrace(first, NOW - 3_600_000L, 10, 5).equals(first),
                "recul d'horloge : l'attente s'allonge, rien n'est accordé");
        SpaceState arrival = SpaceState.EMPTY.withGraceUntil(NOW + 60_000L);
        SpaceState kept = Environment.loginGrace(arrival, NOW, 10, 5);
        helper.assertTrue(kept.graceUntil() == NOW + 60_000L && kept.lastLoginGrace() == NOW,
                "une acclimatation d'arrivée plus longue n'est pas raccourcie");
        helper.assertTrue(Environment.loginGrace(SpaceState.EMPTY, NOW, 0, 5).equals(SpaceState.EMPTY), "0 s configurée : rien");

        // Décision pendant l'acclimatation : ni consommation, ni froid, aucun capteur interrogé.
        SpaceState arrived = SpaceState.EMPTY.withGraceUntil(NOW + 8_500L).withCold(12);
        Probe probe = new Probe();
        Environment.Outcome grace = Environment.step(arrived, BOTH, probe, CFG, NOW);
        helper.assertTrue(grace.grace() && grace.graceSeconds() == 9 && grace.asphyxiaDamage() == 0.0F && grace.coldDamage() == 0.0F
                && !grace.takeOxygenBottle() && grace.state().equals(arrived) && probe.queries == 0,
                "pendant l'acclimatation : état inchangé, 9 s affichées, aucun capteur");
        Environment.Outcome lastSecond = Environment.step(arrived, BOTH, probe, CFG, NOW + 8_499L);
        helper.assertTrue(lastSecond.grace() && lastSecond.graceSeconds() == 1, "dernière seconde : 1 s affichée");
        helper.assertTrue(key(Environment.bar(grace, CFG)).equals("sovereignfrontier.oxygen.bar.grace"),
                "barre d'action : « Acclimatation »");
        Environment.Outcome over = Environment.step(arrived, BOTH, probe, CFG, NOW + 8_500L);
        helper.assertTrue(!over.grace() && over.asphyxiaDamage() == 2.0F && probe.queries > 0,
                "fin de l'acclimatation : la consommation reprend");

        // Connexion réelle : seulement sur une planète (planète de test liée à l'End).
        PlanetDefinition planet = planet(helper, "essai_accl", true, false);
        ServerPlayer onPlanet = FakePlayerFactory.get(endLevel(helper), new GameProfile(UUID.randomUUID(), "sf_j5_accl"));
        ServerPlayer onEarth = freshPlayer(helper, "sf_j5_accl_terre");
        reset(onPlanet);
        J5TestUtil.withPlanets(List.of(planet), () -> {
            long graceMs = SFConfig.SPACE_GRACE_SECONDS.get() * 1000L;
            Environment.onLogin(onEarth, NOW);
            helper.assertTrue(state(onEarth).equals(SpaceState.EMPTY), "connexion sur Terre : aucune acclimatation");
            Environment.onLogin(onPlanet, NOW);
            helper.assertTrue(state(onPlanet).graceUntil() == NOW + graceMs && state(onPlanet).lastLoginGrace() == NOW,
                    "connexion sur une planète : acclimatation accordée");
            Environment.onLogin(onPlanet, NOW + 60_000L);
            helper.assertTrue(state(onPlanet).graceUntil() == NOW + graceMs, "reconnexion après 1 min : pas de nouvelle acclimatation");
            Environment.Outcome out = Environment.tick(onPlanet, planet, NOW + 1_000L);
            helper.assertTrue(out != null && out.grace() && out.asphyxiaDamage() == 0.0F, "passage pendant l'acclimatation");
            if (graceMs > 1_000L) {
                helper.assertTrue(Environment.tick(onPlanet, planet, NOW + graceMs).asphyxiaDamage() > 0.0F,
                        "acclimatation terminée : asphyxie sans bouteille");
            }
        });
        reset(onPlanet);
        helper.succeed();
    }

    // ------------------------------------------------------------------ diffuseur d'oxygène

    /**
     * Zone du diffuseur : les chunks de sa nation dans le 3 × 3 ; chunk étranger, chunk libre et chunk de la nation hors
     * du 3 × 3 exclus ; ± 32 blocs en hauteur ; un chunk abandonné sort de la zone ; tout joueur y respire.
     */
    @GameTest(template = AREA, batch = "sf_j5_diffuseur_zone")
    public static void diffuserZoneOwnChunksOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ResourceKey<Level> dim = level.dimension();
        ChunkPos home = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos east = new ChunkPos(home.x + 1, home.z);
        ChunkPos east2 = new ChunkPos(home.x + 2, home.z);
        ChunkPos north = new ChunkPos(home.x, home.z - 1);
        ChunkPos south = new ChunkPos(home.x, home.z + 1);
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_zone_a");
        ServerPlayer rival = J5TestUtil.fakePlayer(helper, "sf_j5_zone_b");
        PlanetDefinition planet = planet(helper, "essai_zone", true, false);
        BlockPos diffuser = spot(helper, home);
        try {
            Nation nation = J5TestUtil.foundNation(helper, chef, "SfJ5Zone", "technologie_3");
            J4TestUtil.moveTo(helper, rival, north);
            J4TestUtil.expect(helper, NationManager.create(rival, J4TestUtil.unique(helper, "SfJ5Rival")), true, "nation voisine au nord");
            NationAdmin.adjustTreasury(server, nation, 10_000L);
            J4TestUtil.expect(helper, NationManager.claim(chef, dim, east), true, "chunk est revendiqué");
            J4TestUtil.expect(helper, NationManager.claim(chef, dim, east2), true, "chunk est 2 revendiqué");

            MachineBlockEntity machine = place(level, diffuser, ModBlocks.OXYGEN_DIFFUSER.get());
            machine.energyBuffer().setStored(20_000L);
            windows(level, machine, 1);
            helper.assertTrue(machine.active() && ZoneIndex.zoneAt(dim, diffuser) != null, "diffuseur alimenté : zone enregistrée");
            helper.assertTrue(ZoneIndex.covers(level, diffuser, ZoneIndex.Kind.OXYGEN), "chunk du diffuseur oxygéné");
            helper.assertTrue(ZoneIndex.covers(level, spot(helper, east), ZoneIndex.Kind.OXYGEN), "chunk voisin de la nation oxygéné (3 × 3)");
            helper.assertFalse(ZoneIndex.covers(level, spot(helper, north), ZoneIndex.Kind.OXYGEN), "chunk d'une nation étrangère exclu");
            helper.assertFalse(ZoneIndex.covers(level, spot(helper, south), ZoneIndex.Kind.OXYGEN), "chunk libre exclu");
            helper.assertFalse(ZoneIndex.covers(level, spot(helper, east2), ZoneIndex.Kind.OXYGEN),
                    "chunk de la nation hors du 3 × 3 exclu");
            helper.assertFalse(ZoneIndex.covers(level, diffuser, ZoneIndex.Kind.HEAT), "une zone d'oxygène ne chauffe pas");
            int halfHeight = SFConfig.DIFFUSER_HALF_HEIGHT.get();
            helper.assertTrue(ZoneIndex.covers(level, diffuser.above(halfHeight), ZoneIndex.Kind.OXYGEN)
                    && ZoneIndex.covers(level, diffuser.below(halfHeight), ZoneIndex.Kind.OXYGEN)
                    && !ZoneIndex.covers(level, diffuser.above(halfHeight + 1), ZoneIndex.Kind.OXYGEN)
                    && !ZoneIndex.covers(level, diffuser.below(halfHeight + 1), ZoneIndex.Kind.OXYGEN), "± " + halfHeight + " blocs en hauteur");

            // Tout joueur respire dans la zone, quelle que soit sa nation ; hors zone, il manque d'air.
            rival.moveTo(diffuser.getX() + 0.5D, diffuser.getY(), diffuser.getZ() + 0.5D);
            Environment.Outcome inside = Environment.tick(rival, planet, NOW);
            helper.assertTrue(inside != null && inside.inOxygenZone() && inside.asphyxiaDamage() == 0.0F && inside.bottlesLeft() == -1,
                    "joueur d'une autre nation dans la zone : il respire");
            BlockPos ownLand = spot(helper, north);
            rival.moveTo(ownLand.getX() + 0.5D, ownLand.getY(), ownLand.getZ() + 0.5D);
            Environment.Outcome outside = Environment.tick(rival, planet, NOW);
            helper.assertTrue(outside != null && !outside.inOxygenZone() && outside.asphyxiaDamage() > 0.0F,
                    "le même joueur sur son propre chunk (hors zone) : asphyxie");

            // Un chunk abandonné sort de la zone sans réenregistrement.
            J4TestUtil.expect(helper, NationManager.unclaim(chef, dim, east2), true, "abandon du chunk est 2");
            J4TestUtil.expect(helper, NationManager.unclaim(chef, dim, east), true, "abandon du chunk est");
            helper.assertFalse(ZoneIndex.covers(level, spot(helper, east), ZoneIndex.Kind.OXYGEN), "chunk abandonné retiré de la zone");
            helper.assertTrue(ZoneIndex.covers(level, diffuser, ZoneIndex.Kind.OXYGEN), "le chunk du diffuseur reste oxygéné");
        } finally {
            clear(level, diffuser);
            J5TestUtil.disband(rival);
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /**
     * Le diffuseur exige le territoire d'une nation, sa clé {@code oxygen.systems} et son énergie (20 En/t, payés d'avance
     * par fenêtres de 20 ticks, tout ou rien).
     */
    @GameTest(template = AREA, batch = "sf_j5_diffuseur_conditions")
    public static void diffuserNeedsEnergyAndUnlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dim = level.dimension();
        ChunkPos home = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_conditions");
        BlockPos own = spot(helper, home);
        BlockPos wild = spot(helper, new ChunkPos(home.x + 1, home.z));
        long window = (long) SFConfig.DIFFUSER_ENERGY_PER_TICK.get() * OxygenDiffuserLogic.WINDOW;
        try {
            Nation nation = J5TestUtil.foundNation(helper, chef, "SfJ5Diff");
            MachineBlockEntity machine = place(level, own, ModBlocks.OXYGEN_DIFFUSER.get());
            MachineBlockEntity free = place(level, wild, ModBlocks.OXYGEN_DIFFUSER.get());
            machine.energyBuffer().setStored(20_000L);
            free.energyBuffer().setStored(20_000L);

            windows(level, machine, 1);
            windows(level, free, 1);
            helper.assertTrue(!machine.active() && ZoneIndex.zoneAt(dim, own) == null && machine.energyBuffer().stored() == 20_000L,
                    "nation sans oxygen.systems : diffuseur inerte, énergie intacte");
            helper.assertTrue(!free.active() && ZoneIndex.zoneAt(dim, wild) == null && free.energyBuffer().stored() == 20_000L,
                    "chunk libre : diffuseur inerte, énergie intacte");

            J5TestUtil.complete(helper, nation, "technologie_3");
            windows(level, machine, 1);
            windows(level, free, 1);
            helper.assertTrue(machine.active() && ZoneIndex.zoneAt(dim, own) != null
                    && machine.energyBuffer().stored() == 20_000L - window, "avec la clé : actif, " + window + " En payés d'avance");
            helper.assertTrue(!free.active() && ZoneIndex.zoneAt(dim, wild) == null && free.energyBuffer().stored() == 20_000L,
                    "chunk libre : toujours inerte avec la clé de la nation voisine");

            machine.energyBuffer().setStored(window - 1L);
            windows(level, machine, 1);
            helper.assertTrue(!machine.active() && machine.energyBuffer().stored() == window - 1L,
                    "énergie insuffisante : tout ou rien, rien n'est prélevé");
            machine.energyBuffer().setStored(window);
            windows(level, machine, 1);
            helper.assertTrue(machine.active() && machine.energyBuffer().stored() == 0L, "énergie exacte : actif, réserve vidée");
            windows(level, machine, 1);
            helper.assertTrue(!machine.active(), "réserve vide : le diffuseur s'arrête");
        } finally {
            clear(level, own, wild);
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /**
     * Une zone ne vit que 40 ticks sans rafraîchissement ; la machine qui s'arrête (énergie, territoire) ne la
     * rafraîchit plus, la machine retirée la supprime aussitôt.
     */
    @GameTest(template = AREA, batch = "sf_j5_zone_expiration")
    public static void zoneExpiresWhenMachineStops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ResourceKey<Level> dim = level.dimension();
        ChunkPos home = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos east = new ChunkPos(home.x + 1, home.z);
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_expiration");
        BlockPos diffuser = spot(helper, east);
        ChunkOwners owners = ChunkOwners.of(server);
        long window = (long) SFConfig.DIFFUSER_ENERGY_PER_TICK.get() * OxygenDiffuserLogic.WINDOW;
        try {
            Nation nation = J5TestUtil.foundNation(helper, chef, "SfJ5Expire", "technologie_3");
            NationAdmin.adjustTreasury(server, nation, 10_000L);
            J4TestUtil.expect(helper, NationManager.claim(chef, dim, east), true, "chunk est revendiqué");
            MachineBlockEntity machine = place(level, diffuser, ModBlocks.OXYGEN_DIFFUSER.get());
            long now = level.getGameTime();

            machine.energyBuffer().setStored(window);
            windows(level, machine, 1);
            ZoneIndex.Zone zone = ZoneIndex.zoneAt(dim, diffuser);
            helper.assertTrue(machine.active() && zone != null && zone.expiresAt() == now + ZoneIndex.TTL_TICKS && ZoneIndex.TTL_TICKS == 40,
                    "zone rafraîchie : échéance à 40 ticks");
            helper.assertTrue(ZoneIndex.covers(dim, diffuser, ZoneIndex.Kind.OXYGEN, now + 39L, owners)
                    && !ZoneIndex.covers(dim, diffuser, ZoneIndex.Kind.OXYGEN, now + 40L, owners), "vivante 39 ticks, expirée au 40e");

            windows(level, machine, 1);
            helper.assertTrue(!machine.active() && ZoneIndex.zoneAt(dim, diffuser) == zone && zone.expiresAt() == now + 40L
                    && !zone.alive(now + 40L), "sans énergie : zone non rafraîchie, elle expire d'elle-même");

            machine.energyBuffer().setStored(window);
            windows(level, machine, 1);
            helper.assertTrue(machine.active() && ZoneIndex.zoneAt(dim, diffuser) == zone && zone.alive(now), "énergie rendue : même zone, relancée");

            // Territoire perdu : la machine ne s'active plus et ne prélève plus rien.
            J4TestUtil.expect(helper, NationManager.unclaim(chef, dim, east), true, "abandon du chunk de la machine");
            machine.energyBuffer().setStored(window);
            windows(level, machine, 1);
            helper.assertTrue(!machine.active() && machine.energyBuffer().stored() == window
                    && !ZoneIndex.covers(level, diffuser, ZoneIndex.Kind.OXYGEN), "territoire perdu : inactif, énergie intacte, plus d'oxygène");
            J4TestUtil.expect(helper, NationManager.claim(chef, dim, east), true, "chunk est revendiqué de nouveau");
            windows(level, machine, 1);
            helper.assertTrue(machine.active() && ZoneIndex.covers(level, diffuser, ZoneIndex.Kind.OXYGEN), "territoire rendu : la zone revient");

            // Machine retirée : la zone disparaît aussitôt de l'index.
            clear(level, diffuser);
            helper.assertTrue(ZoneIndex.zoneAt(dim, diffuser) == null && !ZoneIndex.covers(level, diffuser, ZoneIndex.Kind.OXYGEN),
                    "bloc retiré : zone supprimée aussitôt");
        } finally {
            clear(level, diffuser);
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ froid

    /** Froid : + 1 par seconde sans chaleur ; au seuil, dégâts de gel toutes les 2 s ; à l'abri, − 2 par seconde. */
    @GameTest(template = AREA)
    public static void coldThresholdAndDamage(GameTestHelper helper) {
        Probe exposed = new Probe();
        SpaceState state = SpaceState.EMPTY;
        int damages = 0;
        int warnings = 0;
        for (int second = 1; second <= 60; second++) {
            Environment.Outcome out = Environment.step(state, COLD_ONLY, exposed, CFG, NOW);
            state = out.state();
            if (second < 30) {
                helper.assertTrue(out.coldDamage() == 0.0F && state.cold() == second && !out.warnFreezing() && !out.warm(),
                        "seconde " + second + " : exposition " + second + "/30, aucun dégât");
            } else if (second == 30) {
                helper.assertTrue(out.coldDamage() == 1.0F && out.warnFreezing() && state.cold() == 30, "seuil atteint : 1er dégât de gel et alerte");
            } else if (second == 31) {
                helper.assertTrue(out.coldDamage() == 0.0F && state.cold() == 31, "seconde 31 : pas de dégât (intervalle de 2 s)");
            } else if (second == 32) {
                helper.assertTrue(out.coldDamage() == 1.0F && state.cold() == 30, "seconde 32 : dégât, compteur ramené dans la fenêtre [30, 32[");
            }
            helper.assertTrue(state.cold() <= 31, "le compteur ne croît pas sans fin (" + state.cold() + ")");
            if (out.coldDamage() > 0.0F) {
                damages++;
            }
            if (out.warnFreezing()) {
                warnings++;
            }
            helper.assertTrue(out.asphyxiaDamage() == 0.0F && !out.oxygenPlanet(), "planète sans oxygène : aucune asphyxie");
        }
        helper.assertTrue(damages == 16 && warnings == 1, "30 s de gel : 16 dégâts (secondes 30, 32…60) et une seule alerte (" + damages + ", " + warnings + ")");

        // À l'abri (zone de chaleur) : le compteur retombe de 2 par seconde, sans dégât.
        Probe warm = new Probe();
        warm.heatZone = true;
        SpaceState sheltered = state;
        int seconds = 0;
        while (sheltered.cold() > 0) {
            Environment.Outcome out = Environment.step(sheltered, COLD_ONLY, warm, CFG, NOW);
            helper.assertTrue(out.warm() && out.coldDamage() == 0.0F && !out.takeGasBottle()
                    && out.state().cold() == Math.max(0, sheltered.cold() - 2), "à l'abri : − 2 par seconde");
            sheltered = out.state();
            helper.assertTrue(++seconds <= 20, "le froid se dissipe");
        }
        helper.assertTrue(seconds == 15 && sheltered.cold() == 0, "30 → 0 en 15 s (" + seconds + ")");

        // Planète sans froid et hors planète : le compteur se dissipe aussi.
        Environment.Outcome mild = Environment.step(SpaceState.EMPTY.withCold(10), OXYGEN_ONLY, new Probe(), CFG, NOW);
        helper.assertTrue(!mild.coldPlanet() && mild.coldDamage() == 0.0F && mild.state().cold() == 8, "planète sans froid : − 2 par seconde");
        helper.assertTrue(Environment.offPlanet(SpaceState.EMPTY.withCold(5)).cold() == 3
                && Environment.offPlanet(SpaceState.EMPTY.withCold(1)).cold() == 0
                && Environment.offPlanet(SpaceState.EMPTY).equals(SpaceState.EMPTY), "hors planète : − 2 par seconde, jamais négatif");

        // Passage appliqué à un faux joueur : le compteur persiste dans l'attachement.
        PlanetDefinition cold = planet(helper, "essai_froid", false, true);
        ServerPlayer player = freshPlayer(helper, "sf_j5_froid");
        Environment.Settings cfg = Environment.Settings.current();
        Environment.Outcome out = null;
        for (int second = 1; second <= cfg.coldThreshold(); second++) {
            out = Environment.tick(player, cold, NOW);
        }
        helper.assertTrue(out != null && state(player).cold() == cfg.coldThreshold() && out.coldDamage() == cfg.coldDamage()
                && out.warnFreezing(), "faux joueur : seuil atteint après " + cfg.coldThreshold() + " s, gel calculé");
        helper.assertTrue(firstKey(Environment.bar(out, cfg)).equals("sovereignfrontier.cold.bar.exposure"), "barre d'action : « Froid »");
        Environment.tick(player, null, NOW);
        helper.assertTrue(state(player).cold() == cfg.coldThreshold() - 2, "hors planète : le froid se dissipe");

        // Réapparition : oxygène, froid et charge repartent de 0, le reste est gardé.
        player.setData(ModAttachments.SPACE, new SpaceState(50, 20, 100, 11L, 22L, 33L, java.util.Optional.empty(), java.util.Optional.empty()));
        Environment.onRespawn(player);
        SpaceState respawned = state(player);
        helper.assertTrue(respawned.oxygen() == 0 && respawned.cold() == 0 && respawned.heat() == 0 && respawned.graceUntil() == 11L
                && respawned.lastLoginGrace() == 22L && respawned.nextFlight() == 33L, "réapparition : compteurs remis à zéro");
        reset(player);
        helper.succeed();
    }

    // ------------------------------------------------------------------ chaudière à gaz

    /**
     * Chaudière : territoire et clé {@code boiler.gas} exigés ; 1 bouteille pleine par 2 400 ticks (bouteille vide
     * rendue en sortie) ; ne s'allume pas si la sortie est pleine ; zone de chaleur de rayon 50 (49 dedans, 51 dehors),
     * frontières ignorées.
     */
    @GameTest(template = AREA, batch = "sf_j5_chaudiere")
    public static void boilerHeatRadius(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dim = level.dimension();
        ChunkPos home = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_chaudiere");
        BlockPos at = spot(helper, home);
        BlockPos second = at.west(4);
        BlockPos wild = spot(helper, new ChunkPos(home.x + 1, home.z));
        int radius = SFConfig.BOILER_RADIUS.get();
        int halfHeight = SFConfig.BOILER_HALF_HEIGHT.get();
        int perBottle = (SFConfig.BOILER_BOTTLE_TICKS.get() + OxygenDiffuserLogic.WINDOW - 1) / OxygenDiffuserLogic.WINDOW;
        try {
            Nation nation = J5TestUtil.foundNation(helper, chef, "SfJ5Chaudiere");
            MachineBlockEntity boiler = place(level, at, ModBlocks.GAS_BOILER.get());
            MachineBlockEntity outside = place(level, wild, ModBlocks.GAS_BOILER.get());
            boiler.items().setItem(MachineKind.BOILER_GAS, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 2));
            outside.items().setItem(MachineKind.BOILER_GAS, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 2));
            helper.assertTrue(boiler.energyBuffer().capacity() == 0 && boiler.energyRole() == MachineKind.GAS_BOILER.role(),
                    "chaudière inerte : aucune énergie");

            windows(level, boiler, 1);
            windows(level, outside, 1);
            helper.assertTrue(!boiler.active() && boiler.burn() == 0 && boiler.items().getItem(MachineKind.BOILER_GAS).getCount() == 2
                    && ZoneIndex.zoneAt(dim, at) == null, "nation sans boiler.gas : rien ne brûle, bouteilles intactes");

            J5TestUtil.complete(helper, nation, "technologie_6");
            windows(level, boiler, 1);
            windows(level, outside, 1);
            helper.assertTrue(boiler.active() && boiler.burn() == SFConfig.BOILER_BOTTLE_TICKS.get() - OxygenDiffuserLogic.WINDOW
                    && boiler.items().getItem(MachineKind.BOILER_GAS).getCount() == 1
                    && boiler.items().getItem(MachineKind.BOILER_EMPTY_OUT).is(ModItems.GAS_BOTTLE.get())
                    && boiler.items().getItem(MachineKind.BOILER_EMPTY_OUT).getCount() == 1, "avec la clé : une bouteille brûle, la vide est rendue");
            helper.assertTrue(!outside.active() && outside.items().getItem(MachineKind.BOILER_GAS).getCount() == 2
                    && ZoneIndex.zoneAt(dim, wild) == null, "chaudière en chunk libre : inerte");

            // Zone de chaleur : disque de rayon 50 (≤ r²), ± 32 en hauteur, frontières ignorées.
            helper.assertTrue(heat(level, at, radius - 1, 0) && heat(level, at, radius, 0) && !heat(level, at, radius + 1, 0)
                    && heat(level, at, -radius, 0) && !heat(level, at, -radius - 1, 0), "rayon " + radius + " : " + (radius - 1)
                    + " dedans, " + (radius + 1) + " dehors");
            helper.assertTrue(heat(level, at, radius - 1, halfHeight) && heat(level, at, radius - 1, -halfHeight)
                    && !heat(level, at, radius - 1, halfHeight + 1) && !heat(level, at, radius - 1, -halfHeight - 1),
                    "± " + halfHeight + " blocs en hauteur");
            helper.assertTrue(ZoneIndex.zoneAt(dim, at) != null && !ZoneIndex.covers(level, at, ZoneIndex.Kind.OXYGEN),
                    "une zone de chaleur n'oxygène pas");
            helper.assertTrue(heat(level, at, 35, 0, 35) && !heat(level, at, 36, 0, 36), "disque, pas un carré : (35, 35) dedans, (36, 36) dehors");
            helper.assertTrue(ZoneIndex.covers(level, at.east(radius - 1), ZoneIndex.Kind.HEAT)
                    && ChunkOwners.of(level.getServer()).ownerAt(dim, ChunkPos.asLong(at.east(radius - 1))) == null,
                    "la chaleur traverse la frontière : chunk libre chauffé");

            // Cadence : une bouteille par 2 400 ticks.
            windows(level, boiler, perBottle - 1);
            helper.assertTrue(boiler.burn() == 0 && boiler.active() && boiler.items().getItem(MachineKind.BOILER_GAS).getCount() == 1,
                    "fin de la 1re bouteille (" + perBottle + " fenêtres) : la 2e n'est pas encore allumée");
            windows(level, boiler, 1);
            helper.assertTrue(boiler.active() && boiler.items().getItem(MachineKind.BOILER_GAS).isEmpty()
                    && boiler.items().getItem(MachineKind.BOILER_EMPTY_OUT).getCount() == 2
                    && boiler.burn() == SFConfig.BOILER_BOTTLE_TICKS.get() - OxygenDiffuserLogic.WINDOW, "2e bouteille allumée à la fenêtre suivante");
            windows(level, boiler, perBottle - 1);
            helper.assertTrue(boiler.active() && boiler.burn() == 0, "fin de la 2e bouteille");
            windows(level, boiler, 1);
            helper.assertTrue(!boiler.active() && boiler.burn() == 0 && boiler.items().getItem(MachineKind.BOILER_EMPTY_OUT).getCount() == 2,
                    "plus de bouteille pleine : la chaudière s'éteint");

            // Sortie pleine : refus d'allumer ; la combustion en cours va à son terme.
            MachineBlockEntity blocked = place(level, second, ModBlocks.GAS_BOILER.get());
            blocked.items().setItem(MachineKind.BOILER_GAS, new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 2));
            ItemStack fullOutput = new ItemStack(ModItems.GAS_BOTTLE.get());
            fullOutput.setCount(fullOutput.getMaxStackSize());
            blocked.items().setItem(MachineKind.BOILER_EMPTY_OUT, fullOutput.copy());
            windows(level, blocked, 1);
            helper.assertTrue(!blocked.active() && blocked.burn() == 0 && blocked.items().getItem(MachineKind.BOILER_GAS).getCount() == 2
                    && blocked.items().getItem(MachineKind.BOILER_EMPTY_OUT).getCount() == fullOutput.getMaxStackSize(),
                    "sortie pleine : la chaudière ne s'allume pas, rien n'est consommé");
            blocked.items().setItem(MachineKind.BOILER_EMPTY_OUT, ItemStack.EMPTY);
            windows(level, blocked, 1);
            helper.assertTrue(blocked.active() && blocked.items().getItem(MachineKind.BOILER_GAS).getCount() == 1, "sortie libérée : allumée");
            blocked.items().setItem(MachineKind.BOILER_EMPTY_OUT, fullOutput.copy());
            windows(level, blocked, perBottle - 1);
            helper.assertTrue(blocked.active() && blocked.burn() == 0, "sortie pleine en cours de combustion : elle va à son terme");
            windows(level, blocked, 1);
            helper.assertTrue(!blocked.active() && blocked.items().getItem(MachineKind.BOILER_GAS).getCount() == 1,
                    "combustion terminée, sortie pleine : pas de nouvelle bouteille");
        } finally {
            clear(level, at, second, wild);
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }

    private static boolean heat(ServerLevel level, BlockPos source, int dx, int dy) {
        return heat(level, source, dx, dy, 0);
    }

    private static boolean heat(ServerLevel level, BlockPos source, int dx, int dy, int dz) {
        return ZoneIndex.covers(level, source.offset(dx, dy, dz), ZoneIndex.Kind.HEAT);
    }

    // ------------------------------------------------------------------ réchauffeur nucléaire

    /**
     * Réchauffeur : clé {@code heater.nuclear} exigée ; 1 combustible enrichi par 12 000 ticks ; zone de rayon 200 et
     * ± 96 blocs ; ne produit aucune énergie.
     */
    @GameTest(template = AREA, batch = "sf_j5_rechauffeur")
    public static void nuclearHeaterRadiusAndFuel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dim = level.dimension();
        ChunkPos home = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_rechauffeur");
        BlockPos at = spot(helper, home);
        int radius = SFConfig.NUCLEAR_HEATER_RADIUS.get();
        int halfHeight = SFConfig.NUCLEAR_HEATER_HALF_HEIGHT.get();
        int perFuel = (SFConfig.NUCLEAR_HEATER_FUEL_TICKS.get() + OxygenDiffuserLogic.WINDOW - 1) / OxygenDiffuserLogic.WINDOW;
        try {
            Nation nation = J5TestUtil.foundNation(helper, chef, "SfJ5Rechauffeur");
            MachineBlockEntity heater = place(level, at, ModBlocks.NUCLEAR_HEATER.get());
            heater.items().setItem(MachineKind.HEATER_FUEL, new ItemStack(ModItems.ENRICHED_FUEL.get(), 2));
            helper.assertTrue(heater.energyBuffer().capacity() == 0 && heater.kind().maxOut() == 0, "réchauffeur : aucune énergie produite");

            windows(level, heater, 1);
            helper.assertTrue(!heater.active() && heater.fuelTicks() == 0 && heater.items().getItem(MachineKind.HEATER_FUEL).getCount() == 2
                    && ZoneIndex.zoneAt(dim, at) == null, "nation sans heater.nuclear : rien ne brûle, combustible intact");

            J5TestUtil.complete(helper, nation, "industrie_12");
            windows(level, heater, 1);
            helper.assertTrue(heater.active() && heater.fuelTicks() == SFConfig.NUCLEAR_HEATER_FUEL_TICKS.get() - OxygenDiffuserLogic.WINDOW
                    && heater.items().getItem(MachineKind.HEATER_FUEL).getCount() == 1 && heater.energyBuffer().stored() == 0L,
                    "avec la clé : un combustible brûle, aucune énergie produite");
            helper.assertTrue(heat(level, at, radius - 1, 0) && heat(level, at, radius, 0) && !heat(level, at, radius + 1, 0),
                    "rayon " + radius + " : " + (radius - 1) + " dedans, " + (radius + 1) + " dehors");
            helper.assertTrue(heat(level, at, 0, halfHeight) && heat(level, at, 0, -halfHeight) && !heat(level, at, 0, halfHeight + 1)
                    && !heat(level, at, 0, -halfHeight - 1), "± " + halfHeight + " blocs en hauteur");
            helper.assertTrue(heat(level, at, 140, 0, 140) && !heat(level, at, 142, 0, 142), "disque, pas un carré");

            // Cadence : un combustible par 12 000 ticks.
            windows(level, heater, perFuel - 1);
            helper.assertTrue(heater.active() && heater.fuelTicks() == 0 && heater.items().getItem(MachineKind.HEATER_FUEL).getCount() == 1,
                    "fin du 1er combustible (" + perFuel + " fenêtres)");
            windows(level, heater, 1);
            helper.assertTrue(heater.active() && heater.items().getItem(MachineKind.HEATER_FUEL).isEmpty()
                    && heater.fuelTicks() == SFConfig.NUCLEAR_HEATER_FUEL_TICKS.get() - OxygenDiffuserLogic.WINDOW, "2e combustible à la fenêtre suivante");
            windows(level, heater, perFuel - 1);
            helper.assertTrue(heater.active() && heater.fuelTicks() == 0, "fin du 2e combustible");
            windows(level, heater, 1);
            helper.assertTrue(!heater.active() && heater.fuelTicks() == 0, "plus de combustible : le réchauffeur s'éteint");

            // Seul le combustible enrichi brûle.
            ItemStack wrong = new ItemStack(Items.COAL);
            heater.items().setItem(MachineKind.HEATER_FUEL, wrong);
            windows(level, heater, 1);
            helper.assertTrue(!heater.active() && heater.items().getItem(MachineKind.HEATER_FUEL).getCount() == 1,
                    "un autre combustible ne brûle pas");
        } finally {
            clear(level, at);
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ plastron chauffant

    /**
     * Plastron chauffant : clé {@code armor.gas_heated} exigée ; une bouteille de gaz pleine de l'inventaire brûle par
     * 300 s d'exposition (bouteille vide rendue) ; inactif dans une zone de chaleur, sans plastron porté ou sans gaz.
     */
    @GameTest(template = AREA, batch = "sf_j5_plastron")
    public static void heatedChestplateBurnsGas(GameTestHelper helper) {
        PlanetDefinition planet = planet(helper, "essai_plastron", false, true);
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_plastron");
        Environment.Settings cfg = Environment.Settings.current();
        try {
            Nation nation = J5TestUtil.foundNation(helper, chef, "SfJ5Plastron");
            reset(chef);
            chef.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.HEATED_CHESTPLATE.get()));
            chef.getInventory().add(new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 2));

            Environment.Outcome out = Environment.tick(chef, planet, NOW);
            helper.assertTrue(out != null && !out.warm() && !out.takeGasBottle() && state(chef).cold() == 1 && state(chef).heat() == 0
                    && count(chef, ModItems.FULL_GAS_BOTTLE.get()) == 2, "nation sans armor.gas_heated : le plastron ne protège pas");

            J5TestUtil.complete(helper, nation, "technologie_6");
            out = Environment.tick(chef, planet, NOW);
            helper.assertTrue(out != null && out.warm() && out.takeGasBottle() && out.coldDamage() == 0.0F
                    && state(chef).heat() == cfg.plastronSeconds() - 1 && state(chef).cold() == 0
                    && count(chef, ModItems.FULL_GAS_BOTTLE.get()) == 1 && count(chef, ModItems.GAS_BOTTLE.get()) == 1,
                    "avec la clé : une bouteille pleine brûle, la vide est rendue, au chaud");
            for (int s = 2; s <= cfg.plastronSeconds(); s++) {
                out = Environment.tick(chef, planet, NOW);
                helper.assertTrue(out != null && out.warm() && !out.takeGasBottle() && state(chef).heat() == cfg.plastronSeconds() - s,
                        "seconde " + s + " de la 1re bouteille : chaleur " + (cfg.plastronSeconds() - s) + " s");
            }
            helper.assertTrue(state(chef).heat() == 0 && count(chef, ModItems.FULL_GAS_BOTTLE.get()) == 1, "1re bouteille épuisée après "
                    + cfg.plastronSeconds() + " s");
            out = Environment.tick(chef, planet, NOW);
            helper.assertTrue(out != null && out.warm() && out.takeGasBottle() && count(chef, ModItems.FULL_GAS_BOTTLE.get()) == 0
                    && count(chef, ModItems.GAS_BOTTLE.get()) == 2, "2e bouteille brûlée à la seconde suivante");
            for (int s = 2; s <= cfg.plastronSeconds(); s++) {
                Environment.tick(chef, planet, NOW);
            }
            helper.assertTrue(state(chef).heat() == 0, "2e bouteille épuisée");
            out = Environment.tick(chef, planet, NOW);
            helper.assertTrue(out != null && !out.warm() && !out.takeGasBottle() && state(chef).cold() == 1,
                    "plus de gaz : le froid reprend");

            // Cas sans protection : plastron absent, autre pièce de torse, bouteille modifiée.
            reset(chef);
            chef.getInventory().add(new ItemStack(ModItems.FULL_GAS_BOTTLE.get(), 2));
            out = Environment.tick(chef, planet, NOW);
            helper.assertTrue(out != null && !out.warm() && !out.takeGasBottle() && count(chef, ModItems.FULL_GAS_BOTTLE.get()) == 2,
                    "plastron non porté : pas de chaleur, gaz intact");
            chef.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            out = Environment.tick(chef, planet, NOW);
            helper.assertTrue(out != null && !out.warm() && count(chef, ModItems.FULL_GAS_BOTTLE.get()) == 2, "autre plastron : pas de chaleur");
            reset(chef);
            chef.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.HEATED_CHESTPLATE.get()));
            ItemStack named = new ItemStack(ModItems.FULL_GAS_BOTTLE.get());
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Bouteille marquée"));
            chef.getInventory().add(named);
            out = Environment.tick(chef, planet, NOW);
            helper.assertTrue(out != null && !out.warm() && !out.takeGasBottle(), "bouteille pleine renommée : ignorée");

            // Zone de chaleur : le plastron ne brûle rien.
            Probe sheltered = new Probe();
            sheltered.heatZone = true;
            sheltered.plastron = true;
            sheltered.fullGas = 2;
            Environment.Outcome inZone = Environment.step(SpaceState.EMPTY.withCold(10), COLD_ONLY, sheltered, CFG, NOW);
            helper.assertTrue(inZone.warm() && !inZone.takeGasBottle() && inZone.state().heat() == 0 && inZone.state().cold() == 8,
                    "dans une zone de chaleur : aucune bouteille brûlée");
            Probe ready = new Probe();
            ready.plastron = true;
            ready.fullGas = 1;
            Environment.Outcome burning = Environment.step(SpaceState.EMPTY, COLD_ONLY, ready, CFG, NOW);
            helper.assertTrue(burning.warm() && burning.takeGasBottle() && burning.state().heat() == CFG.plastronSeconds() - 1,
                    "hors zone, plastron prêt et gaz disponible : une bouteille brûle");
            ready.plastron = false;
            helper.assertFalse(Environment.step(SpaceState.EMPTY, COLD_ONLY, ready, CFG, NOW).warm(), "plastron non prêt : pas de chaleur");
        } finally {
            reset(chef);
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ exemptions

    /** Créatif et spectateur sont exemptés (aucun passage, rien de consommé) ; survie et aventure ne le sont pas. */
    @GameTest(template = AREA)
    public static void creativeSpectatorExempt(GameTestHelper helper) {
        PlanetDefinition planet = planet(helper, "essai_exempt", true, true);
        ServerPlayer player = freshPlayer(helper, "sf_j5_exempt");
        try {
            player.getInventory().add(new ItemStack(ModItems.OXYGEN_BOTTLE.get(), 2));
            for (GameType exempt : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
                player.setGameMode(exempt);
                helper.assertTrue(Environment.exempt(player), exempt.getName() + " : exempté");
                helper.assertTrue(Environment.tick(player, planet, NOW) == null && state(player).equals(SpaceState.EMPTY)
                        && count(player, ModItems.OXYGEN_BOTTLE.get()) == 2 && count(player, ModItems.GAS_BOTTLE.get()) == 0,
                        exempt.getName() + " : aucun passage, bouteilles intactes, état vierge");
            }
            for (GameType subject : List.of(GameType.SURVIVAL, GameType.ADVENTURE)) {
                reset(player);
                player.getInventory().add(new ItemStack(ModItems.OXYGEN_BOTTLE.get(), 2));
                player.setGameMode(subject);
                helper.assertFalse(Environment.exempt(player), subject.getName() + " : non exempté");
                Environment.Outcome out = Environment.tick(player, planet, NOW);
                helper.assertTrue(out != null && out.takeOxygenBottle() && state(player).cold() == 1
                        && count(player, ModItems.OXYGEN_BOTTLE.get()) == 1, subject.getName() + " : le passage a lieu");
            }

            // Hors planète : le froid se dissipe pour un joueur en survie, pas pour un joueur exempté.
            reset(player);
            player.setGameMode(GameType.SURVIVAL);
            player.setData(ModAttachments.SPACE, SpaceState.EMPTY.withCold(10));
            helper.assertTrue(Environment.tick(player, null, NOW) == null && state(player).cold() == 8, "survie hors planète : froid − 2");
            player.setGameMode(GameType.CREATIVE);
            helper.assertTrue(Environment.tick(player, null, NOW) == null && state(player).cold() == 8, "Créatif hors planète : état figé");
        } finally {
            player.setGameMode(GameType.SURVIVAL);
            reset(player);
        }
        helper.succeed();
    }
}
