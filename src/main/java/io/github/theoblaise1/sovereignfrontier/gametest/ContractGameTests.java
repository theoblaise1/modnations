// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.command.ContractCommand;
import io.github.theoblaise1.sovereignfrontier.contract.ContractInstance;
import io.github.theoblaise1.sovereignfrontier.contract.ContractKind;
import io.github.theoblaise1.sovereignfrontier.contract.ContractManager;
import io.github.theoblaise1.sovereignfrontier.contract.ContractObjective;
import io.github.theoblaise1.sovereignfrontier.contract.ContractRegistry;
import io.github.theoblaise1.sovereignfrontier.contract.ContractTemplate;
import io.github.theoblaise1.sovereignfrontier.contract.NationContracts;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.network.EconomyNetwork;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.outpost.Outpost;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostData;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostManager;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GameTests des contrats nationaux (J4, WP3) : chargement strict des modèles, taille et génération du tableau,
 * acceptation, livraison, réussite et partage, chasse (types d'apparition), exploration (biome, point de passage,
 * avant-postes), échéances, rafraîchissements, persistance, points de recherche et sous-commandes d'administration.
 * <p>
 * Le temps est injecté ({@code now}, epoch) : la nation de test vient d'être fondée, son tableau s'ouvre à
 * {@code created + 12 h}. Les contrats « à la main » ne dépendent pas des modèles livrés. Tout test qui fonde une nation
 * a son propre lot ; les avant-postes enregistrés sont retirés dans un {@code finally}.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class ContractGameTests {
    private static final String AREA = J4TestUtil.AREA;
    private static final long HOUR = ContractManager.HOUR_MS;

    private ContractGameTests() {
    }

    // ------------------------------------------------------------------ outils

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path);
    }

    /** Première date où le tableau de la nation est ouvert (fondation + 13 h). */
    private static long t0(Nation nation) {
        return nation.createdEpochMs() + 13 * HOUR;
    }

    private static String key(ActionResult result) {
        return result.message().getContents() instanceof TranslatableContents t
                ? t.getKey().substring(SovereignFrontier.MODID.length() + 1) : "";
    }

    private static void expectKey(GameTestHelper helper, ActionResult result, String expected, String step) {
        helper.assertTrue(!result.success() && key(result).equals(expected),
                step + " : refus « " + expected + " » attendu, obtenu « " + result.message().getString() + " »");
    }

    /** Registre de test : fichiers {@code sovereignfrontier:<chemin>} analysés puis validés comme au chargement. */
    private static ContractRegistry registry(GameTestHelper helper, Map<String, String> jsonByPath) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        jsonByPath.forEach((path, json) -> files.put(id(path), JsonParser.parseString(json)));
        return ContractRegistry.parse(files).validated(helper.getLevel().registryAccess(), ResearchRegistry.server());
    }

    /** Contrat construit à la main (n° alloué par la nation), indépendant des modèles livrés. */
    private static ContractInstance manual(Nation nation, ContractKind kind, ContractInstance.Target target, int amount,
                                           long money, long points, int[] keys, long now) {
        return new ContractInstance(nation.contracts().allocateId(), id("test_" + kind.id()), kind, target, amount, money,
                points, keys, List.of(), 48, now);
    }

    /** Ajoute le contrat aux contrats en cours de la nation (échéance {@code now + 48 h}). */
    private static ContractInstance activate(Nation nation, ContractInstance c, ServerPlayer by, long now) {
        nation.contracts().addActive(c);
        c.accept(now, by.getUUID());
        nation.markDirty();
        return c;
    }

    private static boolean inHistory(Nation nation, int contractId, NationContracts.Status status) {
        return nation.contracts().history().stream().anyMatch(h -> h.id() == contractId && h.status() == status);
    }

    private static boolean logged(Nation nation, String logKey) {
        return nation.logEntries().stream().anyMatch(l -> l.key().equals(logKey));
    }

    private static Set<Integer> ids(List<ContractInstance> contracts) {
        Set<Integer> out = new HashSet<>();
        contracts.forEach(c -> out.add(c.id()));
        return out;
    }

    /**
     * Vrai joueur serveur (pas un {@code FakePlayer}), hors de la liste des joueurs connectés : ses messages ne partent
     * nulle part (aucune connexion).
     */
    private static ServerPlayer realPlayer(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault()) {
            @Override
            public void sendSystemMessage(Component message, boolean overlay) {
            }

            @Override
            public void displayClientMessage(Component message, boolean overlay) {
            }

            /** Sans connexion : déplacement sans {@code connection.resetPosition()}. */
            @Override
            public void moveTo(double x, double y, double z) {
                moveTo(x, y, z, getYRot(), getXRot());
            }
        };
        Vec3 at = helper.absoluteVec(new Vec3(4.5D, 2.0D, 4.5D));
        player.moveTo(at.x, at.y, at.z);
        return player;
    }

    /** Monstre de ce type, apparu avec ce type d'apparition ({@code null} : jamais finalisé). */
    private static Mob mob(GameTestHelper helper, EntityType<? extends Mob> type, MobSpawnType spawn) {
        ServerLevel level = helper.getLevel();
        Mob mob = type.create(level);
        helper.assertTrue(mob != null, "créature " + type + " créée");
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        mob.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        if (spawn != null) {
            EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(pos), spawn, null);
        }
        return mob;
    }

    private static void kill(GameTestHelper helper, Mob victim, ServerPlayer killer) {
        NeoForge.EVENT_BUS.post(new LivingDeathEvent(victim, helper.getLevel().damageSources().playerAttack(killer)));
    }

    // ------------------------------------------------------------------ chargement

    @GameTest(template = AREA)
    public static void templatesLoaded(GameTestHelper helper) {
        ContractRegistry live = ContractRegistry.get();
        helper.assertTrue(live.size() >= 20, "au moins 20 modèles publiés (" + live.size() + ")");
        Map<ContractKind, Integer> kinds = live.countByKind();
        helper.assertTrue(kinds.getOrDefault(ContractKind.DELIVER, 0) >= 9 && kinds.getOrDefault(ContractKind.KILL, 0) >= 6
                && kinds.getOrDefault(ContractKind.EXPLORE, 0) >= 5, "les 3 types de contrat : " + kinds);

        ContractTemplate fer = live.get(id("livraison_fer"));
        helper.assertTrue(fer != null && fer.kind() == ContractKind.DELIVER && fer.requires().equals(List.of(id("ressources_3")))
                && fer.durationHours() == 48 && fer.objective() instanceof ContractObjective.Deliver d && d.item() == Items.IRON_INGOT
                && d.amount().equals(new ContractObjective.Amount(16, 48, 8)) && fer.reward().money() == 150
                && Math.abs(fer.reward().moneyPerUnit() - 5.0D) < 1e-9 && fer.reward().keys().size() == 1
                && fer.reward().keys().get(0).crate() == CrateKind.INDUSTRIELLE, "livraison_fer conforme au tableau");
        ContractTemplate squelettes = live.get(id("chasse_squelettes"));
        helper.assertTrue(squelettes != null && squelettes.objective() instanceof ContractObjective.Kill k && k.entity().tag()
                && k.entity().id().equals(id("contrat/squelettes")), "chasse_squelettes : tag du mod (monstres seulement)");
        ContractTemplate blazes = live.get(id("chasse_blazes"));
        helper.assertTrue(blazes != null && blazes.reward().items().size() == 1
                && blazes.reward().items().get(0).item() == Items.EXPERIENCE_BOTTLE && blazes.reward().items().get(0).count() == 8,
                "chasse_blazes : 8 fioles d'expérience");
        ContractTemplate postes = live.get(id("exploration_avant_postes"));
        helper.assertTrue(postes != null && postes.objective() instanceof ContractObjective.Explore e
                && e.target() instanceof ContractObjective.OutpostsTarget o && o.min() == 2 && o.max() == 3
                && !e.scalesWithMembers(), "exploration_avant_postes : 2 à 3 avant-postes, non mis à l'échelle");
        helper.assertTrue(live.get(id("exploration_lointaine")) != null && live.get(id("exploration_nether")) != null
                && live.get(id("exploration_jungle")) != null, "explorations : point de passage, dimension, biome");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void invalidTemplatesRejected(GameTestHelper helper) {
        String valid = "{\"kind\": \"kill\", \"duration_hours\": 24, \"objective\": {\"entity\": \"minecraft:zombie\", "
                + "\"amount\": {\"min\": 4, \"max\": 8, \"step\": 2}}, \"reward\": {\"money\": 10}}";
        helper.assertTrue(registry(helper, Map.of("valide", valid)).get(id("valide")) != null, "modèle valide accepté");
        String explore = "{\"kind\": \"explore\", \"duration_hours\": 24, \"objective\": {\"waypoint\": {\"min\": 100, "
                + "\"max\": 200, \"radius\": 16}}, \"reward\": {\"points\": 2}}";
        helper.assertTrue(registry(helper, Map.of("point", explore)).get(id("point")) != null, "exploration valide acceptée");

        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("cible non MONSTER", "{\"kind\": \"kill\", \"duration_hours\": 24, \"objective\": {\"entity\": \"minecraft:cow\","
                + " \"amount\": {\"min\": 1, \"max\": 2}}, \"reward\": {\"money\": 10}}");
        invalid.put("tag avec une créature non MONSTER (cheval squelette)", "{\"kind\": \"kill\", \"duration_hours\": 24, "
                + "\"objective\": {\"entity\": \"#minecraft:skeletons\", \"amount\": {\"min\": 1, \"max\": 2}}, \"reward\": {\"money\": 10}}");
        invalid.put("tag d'entités inconnu", "{\"kind\": \"kill\", \"duration_hours\": 24, \"objective\": {\"entity\": "
                + "\"#sovereignfrontier:contrat/fantomes\", \"amount\": {\"min\": 1, \"max\": 2}}, \"reward\": {\"money\": 10}}");
        invalid.put("deux cibles d'exploration", "{\"kind\": \"explore\", \"duration_hours\": 24, \"objective\": {\"biome\": "
                + "\"#minecraft:is_jungle\", \"dimension\": \"minecraft:the_nether\"}, \"reward\": {\"money\": 10}}");
        invalid.put("aucune cible d'exploration", "{\"kind\": \"explore\", \"duration_hours\": 24, \"objective\": {}, "
                + "\"reward\": {\"money\": 10}}");
        invalid.put("champ manquant (duration_hours)", "{\"kind\": \"explore\", \"objective\": {\"dimension\": "
                + "\"minecraft:the_nether\"}, \"reward\": {\"money\": 10}}");
        invalid.put("champ manquant (kind)", "{\"duration_hours\": 24, \"objective\": {\"dimension\": \"minecraft:the_nether\"},"
                + " \"reward\": {\"money\": 10}}");
        invalid.put("champ manquant (amount)", "{\"kind\": \"deliver\", \"duration_hours\": 24, \"objective\": {\"item\": "
                + "\"minecraft:wheat\"}, \"reward\": {\"money\": 10}}");
        invalid.put("champ inconnu", valid.replace("\"duration_hours\"", "\"poids\": 3, \"duration_hours\""));
        invalid.put("objectif d'un autre type", "{\"kind\": \"deliver\", \"duration_hours\": 24, \"objective\": {\"entity\": "
                + "\"minecraft:zombie\", \"amount\": {\"min\": 1, \"max\": 2}}, \"reward\": {\"money\": 10}}");
        invalid.put("min > max", valid.replace("\"min\": 4, \"max\": 8", "\"min\": 8, \"max\": 4"));
        invalid.put("prérequis inconnu", valid.replace("\"duration_hours\"", "\"requires\": [\"sovereignfrontier:civil_99\"], \"duration_hours\""));
        invalid.put("biome inconnu", "{\"kind\": \"explore\", \"duration_hours\": 24, \"objective\": {\"biome\": "
                + "\"minecraft:biome_inexistant\"}, \"reward\": {\"money\": 10}}");
        invalid.put("tag de biome inconnu", "{\"kind\": \"explore\", \"duration_hours\": 24, \"objective\": {\"biome\": "
                + "\"#sovereignfrontier:inexistant\"}, \"reward\": {\"money\": 10}}");
        invalid.put("récompense interdite", valid.replace("\"reward\": {\"money\": 10}",
                "\"reward\": {\"items\": [{\"item\": \"minecraft:elytra\", \"count\": 1}]}"));
        invalid.put("clé dans les objets", valid.replace("\"reward\": {\"money\": 10}",
                "\"reward\": {\"items\": [{\"item\": \"sovereignfrontier:cle_commune\", \"count\": 1}]}"));
        invalid.put("chance nulle", valid.replace("\"reward\": {\"money\": 10}",
                "\"reward\": {\"keys\": [{\"crate\": \"commune\", \"chance\": 0}]}"));
        invalid.put("caisse inconnue", valid.replace("\"reward\": {\"money\": 10}",
                "\"reward\": {\"keys\": [{\"crate\": \"legendaire\"}]}"));
        invalid.put("récompense vide", valid.replace("\"reward\": {\"money\": 10}", "\"reward\": {}"));
        invalid.put("points au-delà de 1000", valid.replace("\"reward\": {\"money\": 10}",
                "\"reward\": {\"points\": 100, \"points_per_unit\": 50}"));
        invalid.put("part par unité au-delà du plafond", valid.replace("\"reward\": {\"money\": 10}",
                "\"reward\": {\"money_per_unit\": 5001}"));
        invalid.put("argent non entier", valid.replace("\"money\": 10", "\"money\": 10.5"));
        invalid.put("tableau au lieu d'un objet", "[]");
        invalid.forEach((label, json) -> helper.assertTrue(registry(helper, Map.of("faux", json)).get(id("faux")) == null,
                "modèle invalide rejeté en entier (" + label + ")"));

        ContractRegistry mixed = registry(helper, Map.of("valide", valid, "faux", invalid.get("cible non MONSTER")));
        helper.assertTrue(mixed.size() == 1 && mixed.get(id("valide")) != null, "seul le modèle fautif est rejeté");
        helper.succeed();
    }

    // ------------------------------------------------------------------ tableau

    @GameTest(template = AREA, batch = "sf_j4_contract_sizes")
    public static void boardSizesFollowMembers(GameTestHelper helper) {
        helper.assertTrue(ContractManager.offerSlots(1) == 4 && ContractManager.offerSlots(4) == 5
                && ContractManager.offerSlots(10) == 8 && ContractManager.offerSlots(30) == 8, "offres : min(8, 3 + ceil(m/2))");
        helper.assertTrue(ContractManager.maxActive(1) == 2 && ContractManager.maxActive(4) == 3
                && ContractManager.maxActive(7) == 4 && ContractManager.maxActive(30) == 4, "en cours : min(4, 1 + ceil(m/3))");
        helper.assertTrue(Math.abs(ContractManager.sizeFactor(1) - 1.0D) < 1e-9 && Math.abs(ContractManager.sizeFactor(4) - 1.6D) < 1e-9
                && Math.abs(ContractManager.sizeFactor(10) - 2.5D) < 1e-9 && Math.abs(ContractManager.sizeFactor(40) - 2.5D) < 1e-9,
                "taille : min(2,5 ; 1 + 0,2 × (m − 1))");
        ContractObjective.Amount zombies = new ContractObjective.Amount(15, 30, 5);
        helper.assertTrue(ContractManager.scale(zombies, 20, 1.6D) == 30 && ContractManager.scale(zombies, 15, 1.0D) == 15
                && ContractManager.scale(new ContractObjective.Amount(4, 12, 2), 12, 2.5D) == 30, "arrondi au pas");

        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_taille");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrTaille");
        try {
            long now = t0(nation);
            helper.assertTrue(ContractManager.maintain(helper.getLevel().getServer(), nation, now), "premier tableau généré");
            EconomyView.ContractsView view = ContractManager.view(helper.getLevel().getServer(), nation, chef.getUUID(), now);
            helper.assertTrue(nation.contracts().offers().size() == 4 && view.offers().size() == 4 && view.offerSlots() == 4
                    && view.maxActive() == 2, "1 membre : 4 offres, 2 en cours (obtenu " + view.offers().size() + " / "
                    + view.maxActive() + ")");
            Map<ContractKind, Integer> perKind = new LinkedHashMap<>();
            Set<ResourceLocation> templates = new HashSet<>();
            for (ContractInstance c : nation.contracts().offers()) {
                perKind.merge(c.kind(), 1, Integer::sum);
                templates.add(c.template());
                ContractTemplate t = ContractRegistry.get().get(c.template());
                helper.assertTrue(t != null && c.amount() >= t.objective().amount().min() && c.amount() <= t.objective().amount().max(),
                        "1 membre : quantité non mise à l'échelle (" + c + ")");
            }
            helper.assertTrue(perKind.values().stream().allMatch(n -> n <= 2) && templates.size() == 4,
                    "au plus 2 offres par type, modèles distincts : " + perKind);
            helper.assertTrue(view.refreshInMs() == 24 * HOUR, "prochain rafraîchissement dans 24 h");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_contract_age")
    public static void boardWaitsNationAge(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_age");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrAge");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            long created = nation.createdEpochMs();
            helper.assertFalse(ContractManager.maintain(server, nation, created + 11 * HOUR), "nation de 11 h : rien");
            helper.assertTrue(nation.contracts().offers().isEmpty() && nation.contracts().nextRefresh() == 0L,
                    "aucun tableau avant 12 h");
            expectKey(helper, ContractManager.accept(chef, 1, created + 11 * HOUR), "contract.error.too_young", "acceptation trop tôt");
            EconomyView.ContractsView early = ContractManager.view(server, nation, chef.getUUID(), created + 11 * HOUR);
            helper.assertTrue(early.offers().isEmpty() && early.refreshInMs() == HOUR, "vue : ouverture dans 1 h");
            helper.assertTrue(ContractManager.maintain(server, nation, created + 12 * HOUR), "12 h : tableau généré");
            helper.assertTrue(nation.contracts().offers().size() == 4
                    && nation.contracts().nextRefresh() == created + 36 * HOUR, "4 offres, rafraîchissement 24 h plus tard");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_contract_requires")
    public static void requirementsRespected(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_prereq");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrPrereq");
        try {
            J4TestUtil.addInhabitants(helper, nation, 5);
            for (String tier : List.of("ressources_3", "industrie_2", "militaire_3", "militaire_5", "ressources_8")) {
                nation.research().complete(id(tier));
            }
            ContractRegistry registry = ContractRegistry.get();
            long now = t0(nation);
            AtomicInteger ids = new AtomicInteger();
            Set<ResourceLocation> seen = new HashSet<>();
            for (int seed = 1; seed <= 200; seed++) {
                for (ContractInstance c : ContractManager.roll(nation, registry, 8, seed, now, ids::incrementAndGet)) {
                    seen.add(c.template());
                }
            }
            helper.assertFalse(seen.contains(id("livraison_circuits")), "200 générations sans technologie_1 : aucun circuit");
            helper.assertTrue(seen.contains(id("livraison_fer")) && seen.contains(id("chasse_endermen"))
                    && seen.contains(id("exploration_nether")), "modèles débloqués (recherche, population 5) proposés : " + seen);

            nation.research().complete(id("technologie_1"));
            boolean circuits = false;
            for (int seed = 1; seed <= 200 && !circuits; seed++) {
                for (ContractInstance c : ContractManager.roll(nation, registry, 8, seed, now, ids::incrementAndGet)) {
                    circuits |= c.template().equals(id("livraison_circuits"));
                }
            }
            helper.assertTrue(circuits, "technologie_1 terminée : les circuits apparaissent");
            nation.research().revoke(id("ressources_3"));
            helper.assertFalse(ContractManager.eligible(nation, registry).stream().anyMatch(t -> t.id().equals(id("livraison_fer"))),
                    "prérequis retiré : modèle inéligible");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ acceptation et livraison

    @GameTest(template = AREA, batch = "sf_j4_contract_accept")
    public static void acceptNeedsPermissionAndSlot(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_chef");
        ServerPlayer recruit = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_recrue");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrAccept");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            J4TestUtil.expect(helper, NationManager.invite(chef, recruit), true, "invitation de la recrue");
            J4TestUtil.expect(helper, NationManager.join(recruit, nation.name()), true, "adhésion de la recrue");
            long now = t0(nation);
            ContractManager.maintain(server, nation, now);
            List<ContractInstance> offers = new ArrayList<>(nation.contracts().offers());
            helper.assertTrue(offers.size() == 4 && ContractManager.maxActive(nation.memberCount()) == 2,
                    "2 membres : 4 offres, 2 contrats en cours");

            expectKey(helper, ContractManager.accept(recruit, offers.get(0).id(), now), "nation.error.no_permission",
                    "recrue sans la permission « economie »");
            J4TestUtil.expect(helper, ContractManager.accept(chef, offers.get(0).id(), now), true, "première acceptation");
            ContractInstance first = nation.contracts().activeContract(offers.get(0).id());
            helper.assertTrue(first != null && first.deadline() == now + first.durationMs() && chef.getUUID().equals(first.acceptedBy())
                    && nation.contracts().offer(first.id()) == null, "offre passée en cours, échéance = now + délai");
            helper.assertTrue(logged(nation, "contract_accept") && EconomyNetwork.isDirty(nation), "journal et vue Économie");
            expectKey(helper, ContractManager.accept(chef, first.id(), now), "contract.error.already_active", "déjà en cours");
            J4TestUtil.expect(helper, ContractManager.accept(chef, offers.get(1).id(), now), true, "deuxième acceptation");
            expectKey(helper, ContractManager.accept(chef, offers.get(2).id(), now), "contract.error.max_active", "emplacements pleins");
            expectKey(helper, ContractManager.accept(chef, 9999, now), "contract.error.unknown", "identifiant inconnu");
            helper.assertTrue(nation.contracts().active().size() == 2 && nation.contracts().offers().size() == 2,
                    "refus sans effet");

            expectKey(helper, ContractManager.abandon(recruit, first.id(), now), "nation.error.no_permission", "abandon par la recrue");
            J4TestUtil.expect(helper, ContractManager.abandon(chef, first.id(), now), true, "abandon par le chef");
            helper.assertTrue(inHistory(nation, first.id(), NationContracts.Status.ABANDONED), "historique : abandonné");
            J4TestUtil.expect(helper, ContractManager.accept(chef, offers.get(2).id(), now), true, "emplacement libéré");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_contract_deliver")
    public static void deliverConsumesPlainRemainingOnly(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_livreur");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrLivre");
        try {
            long now = t0(nation);
            ContractInstance wheat = activate(nation, manual(nation, ContractKind.DELIVER, ContractInstance.Target.item(Items.WHEAT),
                    20, 100L, 2L, new int[4], now), chef, now);
            ContractInstance hunt = activate(nation, manual(nation, ContractKind.KILL,
                    ContractInstance.Target.entity(id("inexistant"), false), 5, 10L, 0L, new int[4], now), chef, now);
            nation.contracts().setNextRefresh(now + 24 * HOUR);
            chef.getInventory().clearContent();
            ItemStack renamed = new ItemStack(Items.WHEAT, 5);
            renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Blé de concours"));
            chef.getInventory().items.set(0, new ItemStack(Items.WHEAT, 6));
            chef.getInventory().items.set(1, renamed);
            chef.getInventory().offhand.set(0, new ItemStack(Items.WHEAT, 8));

            J4TestUtil.expect(helper, ContractManager.deliver(chef, wheat.id(), Integer.MAX_VALUE, now), true, "livraison du maximum");
            helper.assertTrue(wheat.progress() == 14 && wheat.contribution(chef.getUUID()) == 14, "14 blés intacts livrés");
            helper.assertTrue(Inventories.countPlain(chef, Items.WHEAT) == 0 && renamed.getCount() == 5
                    && chef.getInventory().offhand.get(0).isEmpty(), "pile renommée conservée, main secondaire vidée");
            expectKey(helper, ContractManager.deliver(chef, wheat.id(), Integer.MAX_VALUE, now), "contract.error.no_items",
                    "plus aucun objet intact");

            chef.getInventory().items.set(2, new ItemStack(Items.WHEAT, 10));
            J4TestUtil.expect(helper, ContractManager.deliver(chef, wheat.id(), 3, now), true, "livraison de 3");
            helper.assertTrue(wheat.progress() == 17 && Inventories.countPlain(chef, Items.WHEAT) == 7, "quantité demandée respectée");
            J4TestUtil.expect(helper, ContractManager.deliver(chef, wheat.id(), Integer.MAX_VALUE, now), true, "livraison du reste");
            helper.assertTrue(wheat.progress() == 20 && Inventories.countPlain(chef, Items.WHEAT) == 4,
                    "seul le reste (3) est retiré, 4 blés conservés");
            helper.assertTrue(inHistory(nation, wheat.id(), NationContracts.Status.COMPLETED), "contrat rempli : réussi");
            expectKey(helper, ContractManager.deliver(chef, wheat.id(), Integer.MAX_VALUE, now), "contract.error.unknown",
                    "contrat terminé : plus au tableau");
            expectKey(helper, ContractManager.deliver(chef, hunt.id(), Integer.MAX_VALUE, now), "contract.error.not_deliver",
                    "contrat de chasse");
        } finally {
            chef.getInventory().clearContent();
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ réussite

    @GameTest(template = AREA, batch = "sf_j4_contract_reward")
    public static void completionPaysOnceAndSplits(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_gros");
        ServerPlayer member = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_petit");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrPaie");
        MinecraftServer server = helper.getLevel().getServer();
        EconomyData eco = EconomyData.get(server);
        try {
            J4TestUtil.expect(helper, NationManager.invite(chef, member), true, "invitation");
            J4TestUtil.expect(helper, NationManager.join(member, nation.name()), true, "adhésion");
            long now = t0(nation);
            nation.contracts().setNextRefresh(now + 24 * HOUR);
            int[] keys = new int[4];
            keys[CrateKind.MILITAIRE.ordinal()] = 1;
            ContractInstance c = activate(nation, manual(nation, ContractKind.DELIVER, ContractInstance.Target.item(Items.WHEAT),
                    30, 1000L, 7L, keys, now), chef, now);
            chef.getInventory().clearContent();
            member.getInventory().clearContent();
            member.getInventory().items.set(0, new ItemStack(Items.WHEAT, 10));
            chef.getInventory().items.set(0, new ItemStack(Items.WHEAT, 20));
            long chefBefore = eco.balance(chef.getUUID());
            long memberBefore = eco.balance(member.getUUID());
            long treasuryBefore = nation.treasury();
            long pointsBefore = nation.research().points();

            J4TestUtil.expect(helper, ContractManager.deliver(member, c.id(), Integer.MAX_VALUE, now), true, "10 livrés par le membre");
            helper.assertTrue(ContractManager.shareEstimate(nation, c, member.getUUID()) == 500L, "part estimée : seul contributeur");
            J4TestUtil.expect(helper, ContractManager.deliver(chef, c.id(), Integer.MAX_VALUE, now), true, "20 livrés par le chef");
            helper.assertTrue(inHistory(nation, c.id(), NationContracts.Status.COMPLETED), "contrat réussi");
            helper.assertTrue(eco.balance(chef.getUUID()) - chefBefore == 333L && eco.balance(member.getUUID()) - memberBefore == 166L,
                    "50 % au prorata : 333 et 166 cr (obtenu " + (eco.balance(chef.getUUID()) - chefBefore) + " / "
                            + (eco.balance(member.getUUID()) - memberBefore) + ")");
            helper.assertTrue(nation.treasury() - treasuryBefore == 501L, "le reste, arrondis compris, à la trésorerie : 501 cr");
            helper.assertTrue(nation.research().points() - pointsBefore == 7L, "+7 points de recherche");
            helper.assertTrue(Inventories.countPlain(chef, ModItems.KEY_MILITAIRE.get()) == 1
                    && Inventories.countPlain(member, ModItems.KEY_MILITAIRE.get()) == 0, "clé au plus gros contributeur");
            helper.assertTrue(logged(nation, "contract_done"), "réussite inscrite au journal");

            helper.assertFalse(ContractManager.complete(server, nation, c, chef, now), "second déclenchement refusé");
            helper.assertTrue(eco.balance(chef.getUUID()) - chefBefore == 333L && nation.treasury() - treasuryBefore == 501L
                    && nation.research().points() - pointsBefore == 7L
                    && Inventories.countPlain(chef, ModItems.KEY_MILITAIRE.get()) == 1, "second déclenchement sans effet");

            // Un contributeur parti ne touche rien : sa part revient à la trésorerie.
            ContractInstance second = activate(nation, manual(nation, ContractKind.DELIVER, ContractInstance.Target.item(Items.WHEAT),
                    20, 1000L, 0L, new int[4], now), chef, now);
            member.getInventory().items.set(0, new ItemStack(Items.WHEAT, 10));
            chef.getInventory().items.set(0, new ItemStack(Items.WHEAT, 10));
            J4TestUtil.expect(helper, ContractManager.deliver(member, second.id(), Integer.MAX_VALUE, now), true, "10 livrés");
            J4TestUtil.expect(helper, NationManager.leave(member), true, "le membre quitte la nation");
            long memberMid = eco.balance(member.getUUID());
            long chefMid = eco.balance(chef.getUUID());
            long treasuryMid = nation.treasury();
            J4TestUtil.expect(helper, ContractManager.deliver(chef, second.id(), Integer.MAX_VALUE, now), true, "10 livrés par le chef");
            helper.assertTrue(eco.balance(member.getUUID()) == memberMid && eco.balance(chef.getUUID()) - chefMid == 500L
                    && nation.treasury() - treasuryMid == 500L, "ancien membre exclu du partage");
        } finally {
            chef.getInventory().clearContent();
            member.getInventory().clearContent();
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ chasse et exploration

    @GameTest(template = AREA, batch = "sf_j4_contract_kill")
    public static void killCountsOnlyWhitelistedSpawns(GameTestHelper helper) {
        ServerPlayer hunter = realPlayer(helper, "sf_j4_ctr_chasse");
        ServerPlayer fake = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_faux");
        Nation nation = J4TestUtil.foundNation(helper, hunter, "SfCtrChasse");
        EconomyData eco = EconomyData.get(helper.getLevel().getServer());
        try {
            J4TestUtil.expect(helper, NationManager.invite(hunter, fake), true, "invitation du faux joueur");
            J4TestUtil.expect(helper, NationManager.join(fake, nation.name()), true, "adhésion du faux joueur");
            long now = System.currentTimeMillis();
            nation.contracts().setNextRefresh(now + 24 * HOUR);
            ContractInstance c = activate(nation, manual(nation, ContractKind.KILL,
                    ContractInstance.Target.entity(id("contrat/araignees"), true), 2, 100L, 1L, new int[4], now), hunter, now);
            long before = eco.balance(hunter.getUUID());

            kill(helper, mob(helper, EntityType.SPIDER, MobSpawnType.SPAWNER), hunter);
            helper.assertTrue(c.progress() == 0, "araignée de cage (SPAWNER) ignorée");
            kill(helper, mob(helper, EntityType.SPIDER, MobSpawnType.SPAWN_EGG), hunter);
            kill(helper, mob(helper, EntityType.SPIDER, MobSpawnType.BREEDING), hunter);
            kill(helper, mob(helper, EntityType.SPIDER, null), hunter);
            helper.assertTrue(c.progress() == 0, "œuf, reproduction et apparition inconnue ignorés");
            // CaveSpider#finalizeSpawn n'appelle pas Mob#finalizeSpawn : son type d'apparition reste inconnu. Les
            // araignées venimeuses ne naissent que des cages des puits de mine (SPAWNER) : ignorées dans tous les cas.
            kill(helper, mob(helper, EntityType.CAVE_SPIDER, MobSpawnType.NATURAL), hunter);
            helper.assertTrue(c.progress() == 0, "araignée venimeuse (type d'apparition jamais fixé) ignorée");
            kill(helper, mob(helper, EntityType.SPIDER, MobSpawnType.NATURAL), fake);
            helper.assertTrue(c.progress() == 0, "FakePlayer ignoré");
            kill(helper, mob(helper, EntityType.SKELETON, MobSpawnType.NATURAL), hunter);
            helper.assertTrue(c.progress() == 0, "mauvaise cible ignorée");

            kill(helper, mob(helper, EntityType.SPIDER, MobSpawnType.NATURAL), hunter);
            helper.assertTrue(c.progress() == 1 && c.contribution(hunter.getUUID()) == 1, "araignée NATURAL comptée");
            kill(helper, mob(helper, EntityType.SPIDER, MobSpawnType.STRUCTURE), hunter);
            helper.assertTrue(inHistory(nation, c.id(), NationContracts.Status.COMPLETED), "araignée STRUCTURE comptée : réussi");
            helper.assertTrue(eco.balance(hunter.getUUID()) - before == 50L, "part du chasseur : 50 cr");
        } finally {
            J4TestUtil.disband(hunter);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_contract_biome")
    public static void exploreBiomeNeedsDistance(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_biome");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrBiome");
        try {
            long now = t0(nation);
            nation.contracts().setNextRefresh(now + 24 * HOUR);
            Holder<Biome> here = helper.getLevel().getBiome(chef.blockPosition());
            ResourceLocation biome = here.unwrapKey().orElseThrow().location();
            ContractInstance.Spot atPlayer = new ContractInstance.Spot(Level.OVERWORLD, chef.getBlockX(), chef.getBlockZ());

            ContractInstance wrong = activate(nation, manual(nation, ContractKind.EXPLORE,
                    ContractInstance.Target.biome(ResourceLocation.withDefaultNamespace("is_nether"), true, 0), 1, 10L, 1L, new int[4], now), chef, now);
            wrong.setOrigin(atPlayer);
            ContractInstance right = activate(nation, manual(nation, ContractKind.EXPLORE,
                    ContractInstance.Target.biome(biome, false, 100), 1, 10L, 1L, new int[4], now), chef, now);
            right.setOrigin(atPlayer);

            helper.assertTrue(ContractManager.exploreStep(chef, now) == 0 && right.progress() == 0,
                    "bon biome à 0 bloc de l'origine : distance insuffisante");
            right.setOrigin(new ContractInstance.Spot(Level.OVERWORLD, chef.getBlockX() + 500, chef.getBlockZ()));
            helper.assertTrue(ContractManager.exploreStep(chef, now) == 1 && wrong.progress() == 0,
                    "bon biome à 500 blocs : réussi ; biome du Nether : rien");
            helper.assertTrue(inHistory(nation, right.id(), NationContracts.Status.COMPLETED), "exploration de biome réussie");
            helper.assertTrue(nation.contracts().activeExplore().size() == 1 && nation.contracts().activeExplore().get(0) == wrong,
                    "liste en cache mise à jour");
            helper.assertTrue(ContractManager.exploreStep(chef, wrong.deadline()) == 0, "échéance dépassée : plus de progression");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_contract_waypoint")
    public static void exploreWaypointRadius(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_point");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrPoint");
        try {
            long now = t0(nation);
            nation.contracts().setNextRefresh(now + 24 * HOUR);
            ContractInstance c = manual(nation, ContractKind.EXPLORE, ContractInstance.Target.waypoint(1000, 2000, 32), 1, 350L, 8L,
                    new int[4], now);
            nation.contracts().addOffer(c);
            int ox = chef.getBlockX();
            int oz = chef.getBlockZ();
            double y = chef.getY();
            J4TestUtil.expect(helper, ContractManager.accept(chef, c.id(), now), true, "acceptation du point de passage");
            ContractInstance.Spot origin = c.origin();
            ContractInstance.Spot wp = c.waypoint();
            helper.assertTrue(origin != null && origin.x() == ox && origin.z() == oz && origin.dimension() == Level.OVERWORLD,
                    "origine : position du joueur qui accepte");
            double dist = wp == null ? -1 : Math.sqrt(origin.distanceSqr(wp.x(), wp.z()));
            helper.assertTrue(wp != null && dist >= 999.0D && dist <= 2001.0D, "point tiré entre 1000 et 2000 blocs (" + dist + ")");

            chef.setPos(wp.x() + 40.0D, y, wp.z());
            helper.assertTrue(ContractManager.exploreStep(chef, now) == 0 && c.progress() == 0, "à 40 blocs : hors du rayon (32)");
            chef.setPos(wp.x() + 20.0D, y, wp.z() + 10.0D);
            helper.assertTrue(ContractManager.exploreStep(chef, now) == 1, "à 22 blocs : point atteint");
            helper.assertTrue(inHistory(nation, c.id(), NationContracts.Status.COMPLETED), "exploration lointaine réussie");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_contract_outposts")
    public static void exploreOutpostsDistinct(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_postes");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrPostes");
        List<Outpost> outposts = new ArrayList<>();
        try {
            for (BlockPos rel : List.of(new BlockPos(1, 2, 1), new BlockPos(7, 2, 7))) {
                Outpost o = OutpostManager.createAt(level, helper.absolutePos(rel), Direction.NORTH, OutpostManager.DEFAULT_POOL);
                helper.assertTrue(o != null, "avant-poste de test enregistré");
                outposts.add(o);
            }
            long now = t0(nation);
            nation.contracts().setNextRefresh(now + 24 * HOUR);
            ContractInstance c = activate(nation, manual(nation, ContractKind.EXPLORE, ContractInstance.Target.outposts(), 2, 340L,
                    8L, new int[4], now), chef, now);
            double y = chef.getY();
            BlockPos a = outposts.get(0).pos();
            BlockPos b = outposts.get(1).pos();

            chef.setPos(a.getX() + 100.5D, y, a.getZ() + 0.5D);
            helper.assertTrue(ContractManager.exploreStep(chef, now) == 0, "loin de tout comptoir : rien");
            chef.setPos(a.getX() + 0.5D, a.getY(), a.getZ() + 0.5D);
            helper.assertTrue(ContractManager.exploreStep(chef, now) == 1 && c.progress() == 1, "premier avant-poste visité");
            helper.assertTrue(ContractManager.exploreStep(chef, now) == 0 && c.progress() == 1, "même avant-poste : non compté deux fois");
            chef.setPos(b.getX() + 0.5D, b.getY(), b.getZ() + 0.5D);
            helper.assertTrue(ContractManager.exploreStep(chef, now) == 1, "second avant-poste distinct");
            helper.assertTrue(inHistory(nation, c.id(), NationContracts.Status.COMPLETED) && c.visited().size() == 2,
                    "tournée réussie, 2 avant-postes mémorisés");
        } finally {
            for (Outpost o : outposts) {
                if (OutpostData.get(server).get(o.id()) != null) {
                    OutpostManager.unregister(server, o);
                }
            }
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ échéances et rafraîchissements

    @GameTest(template = AREA, batch = "sf_j4_contract_deadline")
    public static void deadlineFails(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_echeance");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrEcheance");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            long now = t0(nation);
            ContractManager.maintain(server, nation, now);
            int offer = nation.contracts().offers().get(0).id();
            J4TestUtil.expect(helper, ContractManager.accept(chef, offer, now), true, "acceptation");
            ContractInstance c = nation.contracts().activeContract(offer);
            helper.assertTrue(c != null, "contrat en cours");
            ContractManager.maintain(server, nation, c.deadline() - 1L);
            helper.assertTrue(nation.contracts().activeContract(offer) != null, "1 ms avant l'échéance : toujours en cours");
            ContractManager.maintain(server, nation, c.deadline());
            helper.assertTrue(nation.contracts().activeContract(offer) == null && inHistory(nation, offer, NationContracts.Status.FAILED),
                    "échéance atteinte : échoué");
            helper.assertTrue(logged(nation, "contract_failed"), "échec inscrit au journal");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_contract_refresh")
    public static void refreshReplacesOnlyUnaccepted(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_rafraichi");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrRafraichi");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            long now = t0(nation);
            ContractManager.maintain(server, nation, now);
            ContractInstance accepted = nation.contracts().offers().get(0);
            J4TestUtil.expect(helper, ContractManager.accept(chef, accepted.id(), now), true, "acceptation");
            // Échéance repoussée : un contrat de 24 h échouerait au moment même du rafraîchissement.
            accepted.setDeadline(now + 72 * HOUR);
            accepted.advance(1);
            Set<Integer> remaining = ids(nation.contracts().offers());
            int serial = nation.contracts().serial();

            helper.assertFalse(ContractManager.maintain(server, nation, now + 23 * HOUR), "23 h : pas de rafraîchissement");
            helper.assertTrue(ids(nation.contracts().offers()).equals(remaining), "offres inchangées avant 24 h");
            helper.assertTrue(ContractManager.maintain(server, nation, now + 24 * HOUR), "24 h : rafraîchissement");
            List<ContractInstance> fresh = nation.contracts().offers();
            helper.assertTrue(fresh.size() == 4 && fresh.stream().noneMatch(c -> remaining.contains(c.id()) || c.id() == accepted.id()),
                    "4 nouvelles offres (identifiants neufs)");
            helper.assertTrue(fresh.stream().noneMatch(c -> c.template().equals(accepted.template())),
                    "le modèle en cours n'est pas reproposé");
            helper.assertTrue(nation.contracts().active().size() == 1 && nation.contracts().active().get(0) == accepted
                    && accepted.progress() == 1, "contrat accepté intact");
            helper.assertTrue(nation.contracts().serial() == serial + 1
                    && nation.contracts().nextRefresh() == now + 48 * HOUR, "nouvelle génération, prochaine dans 24 h");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_contract_abandon")
    public static void abandonedSlotNotRefilledBeforeRefresh(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_abandon");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrAbandon");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            long now = t0(nation);
            ContractManager.maintain(server, nation, now);
            int id = nation.contracts().offers().get(0).id();
            J4TestUtil.expect(helper, ContractManager.accept(chef, id, now), true, "acceptation");
            J4TestUtil.expect(helper, ContractManager.abandon(chef, id, now + HOUR), true, "abandon");
            helper.assertTrue(inHistory(nation, id, NationContracts.Status.ABANDONED) && logged(nation, "contract_abandon"),
                    "historique et journal");
            ContractManager.maintain(server, nation, now + 2 * HOUR);
            helper.assertTrue(nation.contracts().offers().size() == 3 && nation.contracts().active().isEmpty(),
                    "emplacement non regarni avant le rafraîchissement");
            ContractManager.maintain(server, nation, now + 24 * HOUR);
            helper.assertTrue(nation.contracts().offers().size() == 4, "rafraîchissement : 4 offres");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ persistance

    @GameTest(template = AREA, batch = "sf_j4_contract_persist")
    public static void contractsRoundTripAndSurviveReload(GameTestHelper helper) {
        UUID alice = UUID.randomUUID();
        UUID outpost = UUID.randomUUID();
        NationContracts board = new NationContracts();
        int[] keys = {1, 0, 2, 0};
        ContractInstance offer = new ContractInstance(board.allocateId(), id("livraison_ble"), ContractKind.DELIVER,
                ContractInstance.Target.item(Items.WHEAT), 64, 79L, 3L, keys, List.of(), 24, 1000L);
        ContractInstance tour = new ContractInstance(board.allocateId(), id("exploration_avant_postes"), ContractKind.EXPLORE,
                ContractInstance.Target.outposts(), 3, 460L, 12L, new int[4],
                List.of(new ContractTemplate.ItemReward(Items.EXPERIENCE_BOTTLE, 8)), 72, 2000L);
        ContractInstance far = new ContractInstance(board.allocateId(), id("exploration_lointaine"), ContractKind.EXPLORE,
                ContractInstance.Target.waypoint(1000, 2000, 32), 1, 350L, 8L, new int[4], List.of(), 48, 2000L);
        board.addOffer(offer);
        board.addOffer(tour);
        board.addOffer(far);
        board.activate(tour);
        board.activate(far);
        tour.accept(3000L, alice);
        tour.advance(1);
        tour.contribute(alice, 1);
        tour.visit(outpost);
        tour.setOrigin(new ContractInstance.Spot(Level.OVERWORLD, 10, -20));
        far.accept(3000L, alice);
        far.setWaypoint(new ContractInstance.Spot(Level.OVERWORLD, 1200, -800));
        board.setNextRefresh(86_400_000L);
        board.nextSerial();
        ContractInstance gone = new ContractInstance(board.allocateId(), id("chasse_zombies"), ContractKind.KILL,
                ContractInstance.Target.entity(ResourceLocation.withDefaultNamespace("zombie"), false), 15, 10L, 1L, new int[4],
                List.of(), 24, 0L);
        board.addActive(gone);
        board.finish(gone, NationContracts.Status.FAILED, 5000L, 10);

        CompoundTag saved = board.save();
        NationContracts back = NationContracts.load(saved);
        helper.assertTrue(back.save().equals(saved), "aller-retour NBT identique");
        ContractInstance tourBack = back.activeContract(tour.id());
        helper.assertTrue(back.offers().size() == 1 && back.active().size() == 2 && back.history().size() == 1
                && back.history().get(0).status() == NationContracts.Status.FAILED, "listes relues");
        helper.assertTrue(tourBack != null && tourBack.progress() == 1 && tourBack.contribution(alice) == 1
                && tourBack.visited().equals(List.of(outpost)) && tourBack.deadline() == 3000L + 72 * HOUR
                && tourBack.items().size() == 1 && tourBack.items().get(0).count() == 8
                && tourBack.origin() != null && tourBack.origin().x() == 10, "contrat en cours relu (contributions, visites, origine)");
        ContractInstance farBack = back.activeContract(far.id());
        helper.assertTrue(farBack != null && farBack.waypoint() != null && farBack.waypoint().x() == 1200
                && farBack.target().max() == 2000 && farBack.target().radius() == 32, "point de passage relu");
        ContractInstance offerBack = back.offer(offer.id());
        helper.assertTrue(offerBack != null && offerBack.keys(CrateKind.INDUSTRIELLE) == 2 && offerBack.money() == 79L
                && offerBack.item() == Items.WHEAT, "offre relue (clés tirées, argent)");
        helper.assertTrue(back.allocateId() == gone.id() + 1 && back.serial() == 1 && back.nextRefresh() == 86_400_000L,
                "compteurs relus");
        helper.assertTrue(NationContracts.load(new CompoundTag()).offers().isEmpty(), "NBT absent : tableau vide");

        // Après un /reload qui retire le modèle : le contrat en cours reste un instantané complet.
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_reload");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrReload");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            long now = t0(nation);
            ContractInstance orphan = new ContractInstance(nation.contracts().allocateId(), id("modele_retire"),
                    ContractKind.DELIVER, ContractInstance.Target.item(Items.COAL), 4, 40L, 2L, new int[4], List.of(), 24, now);
            activate(nation, orphan, chef, now);
            helper.assertTrue(ContractRegistry.get().get(orphan.template()) == null, "modèle absent du registre publié");
            ContractManager.refresh(server, nation, ContractRegistry.EMPTY, now);
            helper.assertTrue(nation.contracts().offers().isEmpty() && nation.contracts().activeContract(orphan.id()) == orphan,
                    "registre vide : aucune offre, contrat en cours intact");
            chef.getInventory().clearContent();
            chef.getInventory().items.set(0, new ItemStack(Items.COAL, 4));
            long points = nation.research().points();
            J4TestUtil.expect(helper, ContractManager.deliver(chef, orphan.id(), Integer.MAX_VALUE, now), true, "livraison");
            helper.assertTrue(inHistory(nation, orphan.id(), NationContracts.Status.COMPLETED)
                    && nation.research().points() - points == 2L, "réussite sans le modèle");
        } finally {
            chef.getInventory().clearContent();
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ points de recherche

    @GameTest(template = AREA, batch = "sf_j4_contract_points")
    public static void researchPointsCostApplied(GameTestHelper helper) {
        ResearchRegistry registry = ResearchRegistry.server();
        ResearchTier civil5 = registry.get(id("civil_5"));
        ResearchTier civil6 = registry.get(id("civil_6"));
        helper.assertTrue(civil5 != null && civil5.definition().cost().points() == 0L, "civil_5 : 0 point");
        helper.assertTrue(civil6 != null && civil6.definition().cost().points() == 5L, "civil_6 : 5 points");
        long total = 0L;
        for (ResearchTier tier : registry.all()) {
            long expected = tier.tier() >= 6 ? 5L * (tier.tier() - 5) : 0L;
            helper.assertTrue(tier.definition().cost().points() == expected, tier.id() + " : " + expected + " point(s)");
            total += tier.definition().cost().points();
        }
        helper.assertTrue(total == 1035L, "arbre complet : 1 035 points (" + total + ")");

        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_points");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrPoints");
        try {
            for (int i = 1; i <= 5; i++) {
                nation.research().complete(id("civil_" + i));
            }
            nation.research().setPoints(4L);
            String pointsKey = Msg.key("research.error.points");
            helper.assertTrue(ResearchManager.blockers(nation, registry, civil6).stream().anyMatch(c ->
                    c.getContents() instanceof TranslatableContents t && t.getKey().equals(pointsKey)), "4 points sur 5 : bloqué");
            nation.research().setPoints(5L);
            helper.assertFalse(ResearchManager.blockers(nation, registry, civil6).stream().anyMatch(c ->
                    c.getContents() instanceof TranslatableContents t && t.getKey().equals(pointsKey)), "5 points : plus de blocage");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ commandes

    @GameTest(template = AREA, batch = "sf_j4_contract_admin")
    public static void adminSubcommandsWork(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_admin");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrAdmin");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("sfadmin").requires(s -> s.hasPermission(2));
            ContractCommand.registerAdmin(root);
            dispatcher.register(root);
            ContractCommand.register(dispatcher);
            CommandSourceStack op = server.createCommandSourceStack().withSuppressedOutput();

            helper.assertTrue(dispatcher.execute("sfadmin contract " + nation.name() + " refresh", op) == 1
                    && nation.contracts().offers().size() == 4, "refresh : tableau généré sans condition d'âge");
            int id = nation.contracts().offers().get(0).id();
            ContractInstance c = nation.contracts().offer(id);
            nation.contracts().activate(c);
            c.accept(System.currentTimeMillis(), chef.getUUID());
            helper.assertTrue(dispatcher.execute("sfadmin contract " + nation.name() + " expire " + id, op) == 1
                    && inHistory(nation, id, NationContracts.Status.FAILED), "expire : échoué");
            int other = nation.contracts().offers().get(0).id();
            ContractInstance d = nation.contracts().offer(other);
            nation.contracts().activate(d);
            d.accept(System.currentTimeMillis(), chef.getUUID());
            long points = nation.research().points();
            helper.assertTrue(dispatcher.execute("sfadmin contract " + nation.name() + " complete " + other, op) == 1
                    && inHistory(nation, other, NationContracts.Status.COMPLETED)
                    && nation.research().points() - points == d.points(), "complete : réussi, points versés");
            helper.assertTrue(dispatcher.execute("sfadmin contract " + nation.name() + " complete 9999", op) == 0,
                    "contrat inconnu : échec");
            helper.assertTrue(logged(nation, "contract_admin"), "actions d'administration journalisées");
            helper.assertTrue(dispatcher.execute("contract", chef.createCommandSourceStack().withSuppressedOutput()) == 1,
                    "/contract : tableau affiché");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            helper.fail("commande refusée : " + e.getMessage());
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /** Graine stable : même nation, même génération → même tirage (rejouable). */
    @GameTest(template = AREA, batch = "sf_j4_contract_seed")
    public static void generationIsDeterministic(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ctr_graine");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCtrGraine");
        try {
            long seed = ContractManager.seed(nation.id(), 3);
            AtomicInteger a = new AtomicInteger();
            AtomicInteger b = new AtomicInteger();
            List<ContractInstance> first = ContractManager.roll(nation, ContractRegistry.get(), 4, seed, 0L, a::incrementAndGet);
            List<ContractInstance> again = ContractManager.roll(nation, ContractRegistry.get(), 4, seed, 0L, b::incrementAndGet);
            helper.assertTrue(first.size() == 4 && first.size() == again.size(), "4 offres tirées");
            for (int i = 0; i < first.size(); i++) {
                ContractInstance x = first.get(i);
                ContractInstance y = again.get(i);
                helper.assertTrue(x.template().equals(y.template()) && x.amount() == y.amount() && x.money() == y.money()
                        && java.util.Arrays.equals(x.keys(), y.keys()), "même graine, même tirage (" + x + ")");
            }
            ContractTemplate wheat = ContractRegistry.get().get(id("livraison_ble"));
            helper.assertTrue(wheat != null, "modèle livraison_ble");
            ContractInstance big = ContractManager.instantiate(wheat, 1, 4, 10, RandomSource.create(5L), 0L);
            helper.assertTrue(big.amount() % 16 == 0 && big.amount() >= 96 && big.amount() <= 256,
                    "4 membres : quantité × 1,6 arrondie au pas de 16 (" + big.amount() + ")");
            helper.assertTrue(big.money() == Math.round(60 * 1.2D) + Math.round(0.3D * big.amount())
                    && big.points() == 2L + (long) Math.floor(0.02D * big.amount() + 1e-9D),
                    "argent : base × (1 + 0,02 × 10 paliers) + 0,3 × quantité ; points : 2 + floor(0,02 × quantité)");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }
}
