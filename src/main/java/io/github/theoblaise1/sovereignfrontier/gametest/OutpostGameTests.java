package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.network.TradeView.PriceParams;
import io.github.theoblaise1.sovereignfrontier.outpost.ComptoirBlock;
import io.github.theoblaise1.sovereignfrontier.outpost.MarketManager;
import io.github.theoblaise1.sovereignfrontier.outpost.MarketRules;
import io.github.theoblaise1.sovereignfrontier.outpost.MerchantEntity;
import io.github.theoblaise1.sovereignfrontier.outpost.MerchantOffer;
import io.github.theoblaise1.sovereignfrontier.outpost.MerchantPool;
import io.github.theoblaise1.sovereignfrontier.outpost.MerchantRegistry;
import io.github.theoblaise1.sovereignfrontier.outpost.Outpost;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostData;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostManager;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostRules;
import io.github.theoblaise1.sovereignfrontier.outpost.PriceMath;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModEntityTypes;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GameTests des avant-postes et des marchands (WP1) : pools et règle d'écart, tarification, rotation paresseuse,
 * liaison du gabarit réel, marchand (doublons, invulnérabilité), échanges atomiques, distance, garde de
 * revendication, boîte protégée, griefs, génération du monde et persistance.
 * <p>
 * Tout test qui enregistre un avant-poste est dans un lot {@code sf_j4_outpost_*} : la zone non revendicable de
 * 3 × 3 chunks déborderait sur les tests voisins du lot par défaut qui fondent des nations.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class OutpostGameTests {
    private static final String AREA = J4TestUtil.AREA;
    private static final String TRADE_BATCH = "sf_j4_outpost_trade";
    private static final ResourceLocation GENERAL = id("general");
    private static final ResourceLocation PROSPECTEUR = id("prospecteur");
    private static final ResourceLocation TRAPPEUR = id("trappeur");
    private static final List<String> VARIANTS = List.of("plaines", "desert", "taiga");

    private OutpostGameTests() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path);
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static MerchantPool pool(GameTestHelper helper, ResourceLocation id) {
        MerchantPool pool = MerchantRegistry.get().pool(id);
        helper.assertTrue(pool != null, "pool " + id + " publié");
        return pool;
    }

    /**
     * Comptoir lié au pool général, posé sur le sol de la zone de test (le gabarit est placé un cran au-dessus du bloc
     * de structure : la case (x, y, z) du gabarit est {@code absolutePos(x, y + 1, z)}), tourné au nord.
     */
    private static Outpost outpost(GameTestHelper helper) {
        Outpost outpost = OutpostManager.createAt(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), Direction.NORTH, GENERAL);
        helper.assertTrue(outpost != null, "avant-poste de test créé et lié");
        return outpost;
    }

    private static void cleanup(MinecraftServer server, Outpost outpost) {
        if (outpost != null && OutpostData.get(server).get(outpost.id()) != null) {
            OutpostManager.unregister(server, outpost);
        }
    }

    /** Client au nord du comptoir, à 2 blocs, avec un compte à {@code wallet} cr et un inventaire vide. */
    private static ServerPlayer customer(GameTestHelper helper, Outpost outpost, String name, long wallet) {
        ServerPlayer player = J4TestUtil.fakePlayer(helper, J4TestUtil.unique(helper, name));
        Vec3 at = Vec3.atBottomCenterOf(outpost.pos().north(2));
        player.moveTo(at.x, at.y, at.z);
        player.getInventory().clearContent();
        EconomyData eco = EconomyData.get(helper.getLevel().getServer());
        eco.ensureAccount(player.getUUID(), player.getGameProfile().getName(), 0L);
        eco.set(player.getUUID(), wallet);
        return player;
    }

    // ------------------------------------------------------------------ pools

    @GameTest(template = AREA)
    public static void poolsLoadedAndValid(GameTestHelper helper) {
        MerchantRegistry registry = MerchantRegistry.get();
        helper.assertTrue(registry.ids().containsAll(Set.of(GENERAL, PROSPECTEUR, TRAPPEUR)),
                "3 pools publiés : " + registry.ids());
        Map<ResourceLocation, String> variants = Map.of(GENERAL, "general", PROSPECTEUR, "prospecteur", TRAPPEUR, "trappeur");
        for (Map.Entry<ResourceLocation, String> e : variants.entrySet()) {
            MerchantPool pool = pool(helper, e.getKey());
            helper.assertTrue(pool.variant().equals(e.getValue()), "variante de " + e.getKey());
            helper.assertTrue(pool.problem() == null, "pool " + e.getKey() + " valide : " + pool.problem());
            helper.assertTrue(pool.rolls() == 6 && pool.offers().size() >= 10, "étal de 6 offres tirées parmi " + pool.offers().size());
            long guaranteed = pool.offers().stream().filter(MerchantOffer::guaranteed).count();
            helper.assertTrue(guaranteed == 2, "2 offres garanties (vente et rachat) dans " + e.getKey());
            for (MerchantOffer offer : pool.offers()) {
                helper.assertFalse(io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems.isBannedFromLoot(offer.item()),
                        "aucun objet interdit dans " + e.getKey());
            }
        }
        helper.assertTrue(pool(helper, GENERAL).offer(Items.BREAD).price() == 18, "pain du marchand général à 18 cr le lot");
        helper.assertTrue(MarketRules.spreadViolations(registry.all()).isEmpty(),
                "écart global respecté : " + MarketRules.spreadViolations(registry.all()));
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void invalidPoolsRejected(GameTestHelper helper) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(id("valide"), json("{\"variant\":\"general\",\"rolls\":1,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"price\":18,\"guaranteed\":true},"
                + "{\"item\":\"minecraft:wheat\",\"count\":16,\"payout\":12}]}"));
        files.put(id("doublon"), json("{\"variant\":\"general\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"price\":18,\"guaranteed\":true},"
                + "{\"item\":\"minecraft:bread\",\"count\":3,\"payout\":2}]}"));
        files.put(id("sans_prix"), json("{\"variant\":\"general\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"guaranteed\":true}]}"));
        files.put(id("inconnu"), json("{\"variant\":\"general\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:objet_inexistant\",\"count\":1,\"price\":5,\"guaranteed\":true}]}"));
        files.put(id("pile"), json("{\"variant\":\"general\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:saddle\",\"count\":2,\"price\":5,\"guaranteed\":true}]}"));
        files.put(id("tirages"), json("{\"variant\":\"general\",\"rolls\":3,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"price\":18}]}"));
        files.put(id("prix_nul"), json("{\"variant\":\"general\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"price\":0,\"guaranteed\":true}]}"));
        files.put(id("partiel"), json("{\"variant\":\"general\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"price\":18,\"guaranteed\":true},"
                + "{\"item\":\"minecraft:wheat\",\"count\":0,\"payout\":12}]}"));
        files.put(id("variante"), json("{\"variant\":\"Général !\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"price\":18,\"guaranteed\":true}]}"));
        files.put(id("vide"), json("{\"variant\":\"general\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"price\":18}]}"));
        Map<ResourceLocation, MerchantPool> parsed = MerchantRegistry.parse(files, JsonOps.INSTANCE);
        helper.assertTrue(parsed.keySet().equals(Set.of(id("valide"))),
                "seul le pool valide est retenu (doublon, ni prix ni rachat, objet inconnu, pile, tirages, prix nul, "
                        + "offre invalide, variante, étal vide rejetés) : " + parsed.keySet());

        // Objet interdit : rejeté à la publication (les tags sont liés), le reste est publié.
        files.clear();
        files.put(id("valide"), json("{\"variant\":\"general\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"price\":18,\"guaranteed\":true}]}"));
        files.put(id("interdit"), json("{\"variant\":\"general\",\"rolls\":0,\"offers\":["
                + "{\"item\":\"minecraft:bread\",\"count\":6,\"price\":18,\"guaranteed\":true},"
                + "{\"item\":\"minecraft:elytra\",\"count\":1,\"payout\":500,\"guaranteed\":true}]}"));
        MerchantRegistry validated = MerchantRegistry.validate(MerchantRegistry.parse(files, JsonOps.INSTANCE));
        helper.assertTrue(validated.ids().equals(Set.of(id("valide"))), "pool avec élytres rejeté en entier : " + validated.ids());
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void globalSpreadEnforced(GameTestHelper helper) {
        Map<ResourceLocation, MerchantPool> pools = new LinkedHashMap<>();
        pools.put(id("vendeur"), new MerchantPool(id("vendeur"), "general", 0,
                List.of(new MerchantOffer(Items.IRON_INGOT, 4, 60, 0, 8, 0, 1, true))));
        // 12 cr/u = 0,8 × 15 cr/u : à la limite, accepté.
        pools.put(id("juste"), new MerchantPool(id("juste"), "general", 0,
                List.of(new MerchantOffer(Items.IRON_INGOT, 4, 0, 48, 0, 8, 1, true))));
        // 13 cr/u > 12 cr/u : rejeté en entier (avec son offre de pain).
        pools.put(id("gourmand"), new MerchantPool(id("gourmand"), "general", 0,
                List.of(new MerchantOffer(Items.BREAD, 6, 18, 0, 8, 0, 1, true),
                        new MerchantOffer(Items.IRON_INGOT, 1, 0, 13, 0, 8, 1, true))));
        // Le même avant-poste ne peut pas racheter au-delà de 0,8 × son propre prix.
        pools.put(id("aller_retour"), new MerchantPool(id("aller_retour"), "general", 0,
                List.of(new MerchantOffer(Items.COAL, 8, 24, 20, 8, 8, 1, true))));
        helper.assertTrue(MarketRules.spreadViolations(pools.values()).size() == 2, "deux pools en infraction détectés");
        List<String> log = new ArrayList<>();
        Map<ResourceLocation, MerchantPool> kept = MarketRules.enforceSpread(pools, log::add);
        helper.assertTrue(kept.keySet().equals(Set.of(id("vendeur"), id("juste"))), "pools en infraction rejetés : " + kept.keySet());
        helper.assertTrue(log.size() == 2 && log.get(0).contains("gourmand"), "rejet journalisé : " + log);
        helper.assertTrue(MarketRules.spreadViolations(MerchantRegistry.get().all()).isEmpty(), "pools publiés conformes");
        helper.succeed();
    }

    // ------------------------------------------------------------------ tarification

    @GameTest(template = AREA)
    public static void payoutDrops30PercentAfter20Lots(GameTestHelper helper) {
        PriceParams p = MarketRules.DEFAULT_PARAMS;
        helper.assertTrue(PriceMath.payout(100, 0, p) == 100, "rachat de base");
        helper.assertTrue(PriceMath.payout(100, 20, p) == 70, "20 lots vendus : ×0,70 (" + PriceMath.payout(100, 20, p) + ")");
        helper.assertTrue(PriceMath.payout(100, 40, p) == 49, "40 lots vendus : ×0,49 (" + PriceMath.payout(100, 40, p) + ")");
        helper.assertTrue(PriceMath.payout(100, 60, p) == 34, "60 lots vendus : ×0,343");
        helper.assertTrue(PriceMath.payout(100, 78, p) == 25 && PriceMath.payout(100, 500, p) == 25, "plancher ×0,25 atteint vers 78 lots");
        for (int s = 0; s < 300; s++) {
            helper.assertTrue(PriceMath.payout(100, s + 1, p) <= PriceMath.payout(100, s, p), "rachat décroissant");
        }
        helper.assertTrue(PriceMath.sellTotal(100, 0, 0, 0, 3, p)
                        == PriceMath.payout(100, 0, p) + PriceMath.payout(100, 1, p) + PriceMath.payout(100, 2, p),
                "vente de 3 lots : somme exacte lot par lot");
        helper.assertTrue(PriceMath.payout(100, 0, 100, 0, p) == 80, "rachat plafonné à 0,8 × le prix du même avant-poste");
        helper.assertTrue(PriceMath.maxSellLots(3, 0, 0, 0, 64, 64, p) < 64, "rachat de 3 cr : tombe à 0 avant 64 lots");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void priceRisesAndCaps(GameTestHelper helper) {
        PriceParams p = MarketRules.DEFAULT_PARAMS;
        helper.assertTrue(PriceMath.price(100, 0, p) == 100, "prix de base");
        helper.assertTrue(PriceMath.price(10, 20, p) == 13, "10 × 1,3 = 13 (arrondi tolérant)");
        helper.assertTrue(PriceMath.price(100, 20, p) == 130 && PriceMath.price(100, 40, p) == 169, "×1,3 par pas de 20 lots");
        helper.assertTrue(PriceMath.price(18, 20, p) == 24, "18 × 1,3 = 23,4 → 24");
        helper.assertTrue(PriceMath.price(100, 60, p) == 200 && PriceMath.price(100, 1000, p) == 200, "plafond ×2");
        for (int b = 0; b < 300; b++) {
            helper.assertTrue(PriceMath.price(37, b + 1, p) >= PriceMath.price(37, b, p), "prix croissant");
        }
        helper.assertTrue(PriceMath.buyTotal(18, 0, 3, p) == PriceMath.price(18, 0, p) + PriceMath.price(18, 1, p)
                + PriceMath.price(18, 2, p), "achat de 3 lots : somme exacte");
        helper.assertTrue(PriceMath.maxBuyLots(18, 0, 16, 40, p) == 2, "fonds pour 2 lots seulement");
        helper.assertTrue(PriceMath.maxBuyLots(1, 0, 200, 1_000_000, p) == MarketRules.MAX_LOTS, "64 lots au plus par échange");
        helper.succeed();
    }

    /** Aucun aller-retour gagnant, quelles que soient les pressions B et S (0 à 200) et la configuration. */
    @GameTest(template = AREA)
    public static void noArbitrageAtAnyPressure(GameTestHelper helper) {
        List<PriceParams> configs = List.of(MarketRules.DEFAULT_PARAMS, new PriceParams(1, 0.0D, 0.0D, 1.0D, 1.0D),
                new PriceParams(1000, 0.95D, 5.0D, 0.0D, 10.0D), new PriceParams(7, 0.5D, 0.01D, 0.9D, 1.0D));
        Map<Item, List<MerchantOffer>> sellers = new HashMap<>();
        Map<Item, List<MerchantOffer>> buyers = new HashMap<>();
        for (MerchantPool pool : MerchantRegistry.get().all()) {
            for (MerchantOffer offer : pool.offers()) {
                if (offer.sells()) {
                    sellers.computeIfAbsent(offer.item(), i -> new ArrayList<>()).add(offer);
                }
                if (offer.buys()) {
                    buyers.computeIfAbsent(offer.item(), i -> new ArrayList<>()).add(offer);
                }
            }
        }
        int pairs = 0;
        for (PriceParams p : configs) {
            for (Map.Entry<Item, List<MerchantOffer>> e : buyers.entrySet()) {
                for (MerchantOffer buyer : e.getValue()) {
                    double maxPayout = 0.0D;
                    for (int s = 0; s <= 200; s++) {
                        for (int b = 0; b <= 200; b += 10) {
                            maxPayout = Math.max(maxPayout, (double) PriceMath.payout(buyer.payout(), s, buyer.price(), b, p) / buyer.count());
                        }
                    }
                    for (MerchantOffer seller : sellers.getOrDefault(e.getKey(), List.of())) {
                        pairs++;
                        for (int b = 0; b <= 200; b++) {
                            double unitPrice = (double) PriceMath.price(seller.price(), b, p) / seller.count();
                            helper.assertTrue(maxPayout <= unitPrice, "arbitrage possible sur " + e.getKey() + " : rachat "
                                    + maxPayout + " cr/u > prix " + unitPrice + " cr/u (B = " + b + ")");
                        }
                    }
                }
            }
        }
        helper.assertTrue(pairs >= 4, "paires vente/rachat contrôlées : " + pairs);
        // Aller-retour au même avant-poste : acheter n lots puis les revendre perd toujours de l'argent.
        MerchantOffer coal = pool(helper, GENERAL).offer(Items.COAL);
        for (int n = 1; n <= 64; n *= 2) {
            long paid = PriceMath.buyTotal(coal.price(), 0, n, MarketRules.DEFAULT_PARAMS);
            long back = PriceMath.sellTotal(coal.payout(), 0, coal.price(), n, n, MarketRules.DEFAULT_PARAMS);
            helper.assertTrue(back < paid, "aller-retour de " + n + " lot(s) de charbon : " + back + " < " + paid);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ rotation

    @GameTest(template = AREA)
    public static void stockRotatesAfter24000Ticks(GameTestHelper helper) {
        OutpostData clock = new OutpostData();
        clock.advance(23999);
        helper.assertTrue(clock.rotationIndex(24000) == 0 && clock.ticksToRotation(24000) == 1, "rotation 0 jusqu'au tick 23 999");
        clock.advance(1);
        helper.assertTrue(clock.rotationIndex(24000) == 1 && clock.ticksToRotation(24000) == 24000, "rotation 1 au tick 24 000");

        MerchantPool general = pool(helper, GENERAL);
        UUID id = new UUID(0x1234L, 0x5678L);
        Outpost outpost = new Outpost(id, Level.OVERWORLD, BlockPos.ZERO, Direction.NORTH, GENERAL, "general", 0L);
        helper.assertTrue(OutpostManager.refresh(outpost, 0, 0.5D, general), "premier étal tiré");
        helper.assertTrue(outpost.offers().size() == 8, "2 offres garanties + 6 tirées : " + outpost.offers().size());
        helper.assertTrue(outpost.slot(Items.BREAD) != null && outpost.slot(Items.WHEAT) != null, "offres garanties présentes");
        Outpost.Slot bread = outpost.slot(Items.BREAD);
        helper.assertTrue(bread.stockLeft() == 16 && bread.price() == 18, "instantané : stock plein, prix de base");
        bread.setStockLeft(4);
        outpost.addPressure(Items.BREAD, true, 12);
        outpost.addPressure(Items.WHEAT, false, 40);
        helper.assertFalse(OutpostManager.refresh(outpost, 0, 0.5D, general), "même rotation : rien ne change");
        helper.assertTrue(outpost.slot(Items.BREAD).stockLeft() == 4, "stock entamé conservé pendant la rotation");

        helper.assertTrue(OutpostManager.refresh(outpost, 1, 0.5D, general), "rotation suivante");
        helper.assertTrue(outpost.slot(Items.BREAD).stockLeft() == 16, "réassort : stock plein");
        helper.assertTrue(outpost.buyPressure(Items.BREAD) == 6.0D && outpost.sellPressure(Items.WHEAT) == 20.0D,
                "pression divisée par deux");
        Outpost twin = new Outpost(id, Level.OVERWORLD, BlockPos.ZERO, Direction.NORTH, GENERAL, "general", 0L);
        OutpostManager.refresh(twin, 1, 0.5D, general);
        helper.assertTrue(items(twin).equals(items(outpost)), "tirage déterministe (même avant-poste, même rotation)");
        boolean differs = false;
        for (long r = 2; r < 12 && !differs; r++) {
            Outpost other = new Outpost(id, Level.OVERWORLD, BlockPos.ZERO, Direction.NORTH, GENERAL, "general", 0L);
            OutpostManager.refresh(other, r, 0.5D, general);
            differs = !items(other).equals(items(outpost));
        }
        helper.assertTrue(differs, "l'étal change d'une rotation à l'autre");

        OutpostManager.refresh(outpost, 5, 0.5D, general);
        helper.assertTrue(outpost.buyPressure(Items.BREAD) == 6.0D / 16.0D, "4 rotations d'absence : × 0,5⁴");
        OutpostManager.refresh(outpost, 1000, 0.5D, general);
        helper.assertTrue(outpost.pressure().isEmpty(), "longue absence : × 0,5¹⁶ puis entrées sous 0,01 oubliées");
        helper.succeed();
    }

    private static List<Item> items(Outpost outpost) {
        List<Item> out = new ArrayList<>();
        for (Outpost.Slot slot : outpost.offers()) {
            out.add(slot.item());
        }
        return out;
    }

    @GameTest(template = AREA)
    public static void reloadKeepsCurrentRotation(GameTestHelper helper) {
        MerchantPool general = pool(helper, GENERAL);
        MerchantPool trappeur = pool(helper, TRAPPEUR);
        Outpost outpost = new Outpost(UUID.randomUUID(), Level.OVERWORLD, BlockPos.ZERO, Direction.NORTH, GENERAL, "general", 0L);
        OutpostManager.refresh(outpost, 3, 0.5D, general);
        List<Item> before = items(outpost);
        // Le pool publié change (/reload) : l'étal de la rotation en cours est un instantané.
        helper.assertFalse(OutpostManager.refresh(outpost, 3, 0.5D, trappeur), "même rotation après un rechargement : étal inchangé");
        helper.assertTrue(items(outpost).equals(before), "offres inchangées");
        OutpostManager.refresh(outpost, 4, 0.5D, trappeur);
        helper.assertTrue(outpost.slot(Items.COOKED_BEEF) != null && outpost.slot(Items.BREAD) == null,
                "rotation suivante : tirage depuis le nouveau pool");

        // Registre publié remplacé pour de bon, puis restauré : l'étal courant d'un avant-poste ne bouge pas.
        MinecraftServer server = helper.getLevel().getServer();
        Outpost live = new Outpost(UUID.randomUUID(), Level.OVERWORLD, BlockPos.ZERO, Direction.NORTH, GENERAL, "general", 0L);
        OutpostManager.refresh(server, live);
        List<Item> stall = items(live);
        MerchantRegistry previous = MerchantRegistry.swap(new MerchantRegistry(Map.of()));
        try {
            OutpostManager.refresh(server, live);
            helper.assertTrue(items(live).equals(stall) && !stall.isEmpty(), "registre vidé : étal en cours conservé");
        } finally {
            MerchantRegistry.swap(previous);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ gabarit, liaison et marchand

    /** Case (x, y, z) du gabarit d'avant-poste dans le monde (gabarit posé un cran au-dessus du bloc de structure). */
    private static BlockPos templatePos(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y + 1, z));
    }

    @GameTest(template = "avant_poste/plaines", batch = "sf_j4_outpost_a", timeoutTicks = 200)
    public static void templateBindsAndSpawnsOneMerchant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = templatePos(helper, 5, 1, 4);
        // Filet de sécurité : même en cas d'échec, l'avant-poste ne doit pas rester enregistré (sa zone de 3 × 3
        // chunks bloquerait les fondations de nation des tests suivants).
        helper.runAtTickTime(195, () -> {
            Outpost leftover = OutpostData.get(level.getServer()).atAnchor(level.dimension(), anchor);
            cleanup(level.getServer(), leftover);
        });
        helper.succeedWhen(() -> {
            BlockState state = level.getBlockState(anchor);
            helper.assertTrue(state.is(ModBlocks.OUTPOST_COUNTER.get()) && state.getValue(ComptoirBlock.BOUND), "comptoir lié (BOUND)");
            Outpost outpost = OutpostData.get(level.getServer()).atAnchor(level.dimension(), anchor);
            helper.assertTrue(outpost != null, "avant-poste enregistré à l'ancre");
            helper.assertTrue(outpost.pool().equals(GENERAL) && outpost.variant().equals("general"), "pool général du gabarit");
            List<MerchantEntity> merchants = level.getEntitiesOfClass(MerchantEntity.class, new AABB(anchor).inflate(3.0D));
            helper.assertTrue(merchants.size() == 1, "exactement un marchand à 3 blocs au plus : " + merchants.size());
            MerchantEntity merchant = merchants.get(0);
            helper.assertTrue(merchant.getUUID().equals(outpost.merchant()) && outpost.id().equals(merchant.outpostId()),
                    "marchand enregistré");
            helper.assertTrue(merchant.blockPosition().equals(templatePos(helper, 5, 1, 5)), "marchand à son poste");
            helper.assertTrue(level.getBlockEntity(templatePos(helper, 2, 1, 8)) instanceof BarrelBlockEntity barrel
                    && barrel.getLootTable() != null, "tonneau à butin");
            helper.assertTrue(level.getBlockState(templatePos(helper, 8, 1, 8)).is(ModBlocks.CRATE_COMMUNE.get()),
                    "caisse commune publique");
            helper.assertTrue(level.getBlockState(templatePos(helper, 2, 4, 2)).is(Blocks.LANTERN)
                    && level.getBlockState(templatePos(helper, 5, 5, 5)).is(Blocks.OAK_SLAB), "lanternes et toit");
            helper.assertTrue(outpost.id().equals(OutpostRules.outpostAt(level, anchor.north(10), 16)), "outpostAt à 16 blocs");
            helper.assertTrue(OutpostRules.outpostAt(level, anchor.north(20), 16) == null, "outpostAt : trop loin");
            OutpostManager.unregister(level.getServer(), outpost);
        });
    }

    @GameTest(template = AREA, batch = "sf_j4_outpost_c", timeoutTicks = 120)
    public static void duplicateMerchantDiscards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Outpost outpost = outpost(helper);
        Entity registered = level.getEntity(outpost.merchant());
        helper.assertTrue(registered instanceof MerchantEntity, "marchand enregistré présent");
        // Doublon qui se réclame du même avant-poste, et marchand invoqué sans avant-poste (/summon).
        MerchantEntity duplicate = ModEntityTypes.MERCHANT.get().create(level);
        duplicate.setup(outpost.id(), outpost.variant(), outpost.post());
        Vec3 at = Vec3.atBottomCenterOf(outpost.post());
        duplicate.moveTo(at.x, at.y, at.z);
        level.addFreshEntity(duplicate);
        MerchantEntity summoned = helper.spawn(ModEntityTypes.MERCHANT.get(), new BlockPos(1, 2, 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(duplicate.isRemoved() && summoned.isRemoved(), "doublon et marchand invoqué retirés");
            helper.assertFalse(registered.isRemoved(), "le marchand enregistré reste");
            cleanup(level.getServer(), outpost);
        });
    }

    @GameTest(template = AREA, batch = "sf_j4_outpost_c")
    public static void merchantInvulnerableExceptKill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Outpost outpost = outpost(helper);
        MerchantEntity merchant = (MerchantEntity) level.getEntity(outpost.merchant());
        try {
            helper.assertTrue(merchant != null && merchant.getCustomName() != null, "marchand nommé");
            ServerPlayer attacker = J4TestUtil.fakePlayer(helper, "sf_j4_brute");
            helper.assertFalse(merchant.hurt(level.damageSources().playerAttack(attacker), 50.0F), "coup d'un joueur sans effet");
            helper.assertFalse(merchant.hurt(level.damageSources().lava(), 50.0F), "lave sans effet");
            helper.assertFalse(merchant.hurt(level.damageSources().explosion(null, null), 50.0F), "explosion sans effet");
            helper.assertTrue(merchant.getHealth() == merchant.getMaxHealth(), "aucun dégât");
            Vec3 before = merchant.getDeltaMovement();
            merchant.push(3.0D, 0.0D, 3.0D);
            helper.assertTrue(merchant.getDeltaMovement().equals(before) && !merchant.isPushable(), "aucune poussée");
            helper.assertFalse(merchant.canBeLeashed(), "pas de laisse");
            Boat boat = new Boat(EntityType.BOAT, level);
            boat.moveTo(merchant.getX(), merchant.getY(), merchant.getZ());
            helper.assertFalse(merchant.startRiding(boat, true), "pas de monture");
            merchant.kill();
            helper.assertTrue(merchant.isDeadOrDying(), "/kill tue le marchand (#bypasses_invulnerability)");
            helper.assertTrue(outpost.merchant() == null, "sa mort libère le poste");
            helper.assertTrue(OutpostManager.ensureMerchant(level, outpost) && outpost.merchant() != null
                    && !outpost.merchant().equals(merchant.getUUID()), "un nouveau marchand réapparaît");
        } finally {
            if (merchant != null) {
                merchant.discard();
            }
            cleanup(level.getServer(), outpost);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ échanges

    @GameTest(template = AREA, batch = TRADE_BATCH)
    public static void buyIsAtomic(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Outpost outpost = outpost(helper);
        ServerPlayer player = customer(helper, outpost, "sf_j4_acheteur", 10L);
        EconomyData eco = EconomyData.get(server);
        try {
            OutpostManager.refresh(server, outpost);
            Outpost.Slot bread = outpost.slot(Items.BREAD);
            helper.assertTrue(bread != null && bread.stockLeft() == 16, "pain en vente");
            PriceParams p = MarketRules.params();
            long expected = PriceMath.buyTotal(bread.price(), 0.0D, 2, p);
            long rotation = outpost.rotation();
            Runnable unchanged = () -> {
                helper.assertTrue(bread.stockLeft() == 16 && outpost.buyPressure(Items.BREAD) == 0.0D, "stock et pression intacts");
                helper.assertTrue(player.getInventory().countItem(Items.BREAD) == 0, "aucun pain remis");
            };

            refused(helper, MarketManager.trade(player, outpost.id(), rotation, Items.BREAD, true, 2, expected), "fonds insuffisants");
            unchanged.run();
            helper.assertTrue(eco.balance(player.getUUID()) == 10L, "portefeuille intact");

            eco.set(player.getUUID(), 1000L);
            refused(helper, MarketManager.trade(player, outpost.id(), rotation, Items.BREAD, true, 2, expected - 1), "total attendu périmé");
            refused(helper, MarketManager.trade(player, outpost.id(), rotation + 1, Items.BREAD, true, 2, expected), "rotation périmée");
            refused(helper, MarketManager.trade(player, outpost.id(), rotation, Items.BREAD, true, 17,
                    PriceMath.buyTotal(bread.price(), 0.0D, 17, p)), "plus de lots que le stock");
            refused(helper, MarketManager.trade(player, outpost.id(), rotation, Items.WHEAT, true, 1, 0L), "objet non vendu");
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                player.getInventory().items.set(i, new ItemStack(Items.STONE, 64));
            }
            refused(helper, MarketManager.trade(player, outpost.id(), rotation, Items.BREAD, true, 2, expected), "inventaire plein");
            player.getInventory().clearContent();
            unchanged.run();
            helper.assertTrue(eco.balance(player.getUUID()) == 1000L, "aucun débit sur refus");

            ActionResult ok = MarketManager.trade(player, outpost.id(), rotation, Items.BREAD, true, 2, expected);
            helper.assertTrue(ok.success(), "achat valide : " + ok.message().getString());
            helper.assertTrue(eco.balance(player.getUUID()) == 1000L - expected, "débit exact");
            helper.assertTrue(player.getInventory().countItem(Items.BREAD) == 12, "2 lots de 6 pains remis");
            helper.assertTrue(bread.stockLeft() == 14 && outpost.buyPressure(Items.BREAD) == 2.0D, "stock −2, B +2");
            long next = PriceMath.buyTotal(bread.price(), 2.0D, 1, p);
            helper.assertTrue(next >= bread.price(), "le prix suivant ne baisse jamais");
        } finally {
            player.getInventory().clearContent();
            cleanup(server, outpost);
        }
        helper.succeed();
    }

    private static void refused(GameTestHelper helper, ActionResult result, String step) {
        helper.assertFalse(result.success(), step + " : refus attendu, obtenu « " + result.message().getString() + " »");
    }

    @GameTest(template = AREA, batch = TRADE_BATCH)
    public static void sellPlainWithinDemand(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Outpost outpost = outpost(helper);
        ServerPlayer player = customer(helper, outpost, "sf_j4_vendeur", 0L);
        EconomyData eco = EconomyData.get(server);
        try {
            OutpostManager.refresh(server, outpost);
            Outpost.Slot wheat = outpost.slot(Items.WHEAT);
            helper.assertTrue(wheat != null && wheat.payout() == 12 && wheat.count() == 16 && wheat.demandLeft() == 32,
                    "blé racheté 12 cr les 16");
            PriceParams p = MarketRules.params();
            ItemStack renamed = new ItemStack(Items.WHEAT, 16);
            renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Blé doré"));
            player.getInventory().items.set(0, renamed);
            player.getInventory().items.set(1, new ItemStack(Items.WHEAT, 32));
            long three = PriceMath.sellTotal(wheat.payout(), 0.0D, wheat.price(), 0.0D, 3, p);
            refused(helper, MarketManager.trade(player, outpost.id(), outpost.rotation(), Items.WHEAT, false, 3, three),
                    "3 lots demandés, 2 lots intacts seulement");
            helper.assertTrue(eco.balance(player.getUUID()) == 0L && wheat.demandLeft() == 32, "rien n'a bougé");

            long two = PriceMath.sellTotal(wheat.payout(), 0.0D, wheat.price(), 0.0D, 2, p);
            helper.assertTrue(two == 12 + PriceMath.payout(12, 1.0D, p), "deuxième lot déjà moins payé");
            ActionResult ok = MarketManager.trade(player, outpost.id(), outpost.rotation(), Items.WHEAT, false, 2, two);
            helper.assertTrue(ok.success(), "vente valide : " + ok.message().getString());
            helper.assertTrue(eco.balance(player.getUUID()) == two, "crédit exact");
            helper.assertTrue(player.getInventory().items.get(1).isEmpty() && renamed.getCount() == 16,
                    "blé intact retiré, blé renommé conservé");
            helper.assertTrue(wheat.demandLeft() == 30 && outpost.sellPressure(Items.WHEAT) == 2.0D, "demande −2, S +2");

            player.getInventory().items.set(1, new ItemStack(Items.WHEAT, 64));
            wheat.setDemandLeft(1);
            refused(helper, MarketManager.trade(player, outpost.id(), outpost.rotation(), Items.WHEAT, false, 2,
                    PriceMath.sellTotal(wheat.payout(), 2.0D, wheat.price(), 0.0D, 2, p)), "au-delà de la demande");
            refused(helper, MarketManager.trade(player, outpost.id(), outpost.rotation(), Items.BREAD, false, 1, 0L), "objet non racheté");
            helper.assertTrue(player.getInventory().items.get(1).getCount() == 64, "objets conservés sur refus");
        } finally {
            player.getInventory().clearContent();
            cleanup(server, outpost);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = TRADE_BATCH)
    public static void tradeRefusedOutOfReach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Outpost outpost = outpost(helper);
        ServerPlayer player = customer(helper, outpost, "sf_j4_lointain", 1000L);
        try {
            OutpostManager.refresh(server, outpost);
            long expected = PriceMath.buyTotal(18, 0.0D, 1, MarketRules.params());
            Vec3 far = Vec3.atBottomCenterOf(outpost.pos().north(MarketRules.reach() + 3));
            player.moveTo(far.x, far.y, far.z);
            refused(helper, MarketManager.trade(player, outpost.id(), outpost.rotation(), Items.BREAD, true, 1, expected),
                    "hors de portée");
            helper.assertTrue(outpost.slot(Items.BREAD).stockLeft() == 16 && EconomyData.get(server).balance(player.getUUID()) == 1000L,
                    "rien n'a bougé");
            Vec3 near = Vec3.atBottomCenterOf(outpost.pos().north(2));
            player.moveTo(near.x, near.y, near.z);
            helper.assertTrue(MarketManager.reachProblem(player, outpost) == null, "à portée");
            // Le comptoir disparaît : l'avant-poste aussi, tout échange est refusé.
            level.setBlock(outpost.pos(), Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(OutpostData.get(server).get(outpost.id()) == null, "comptoir retiré : avant-poste désenregistré");
            refused(helper, MarketManager.trade(player, outpost.id(), outpost.rotation(), Items.BREAD, true, 1, expected),
                    "avant-poste disparu");
        } finally {
            player.getInventory().clearContent();
            cleanup(server, outpost);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ revendication, boîte, griefs

    @GameTest(template = AREA, batch = "sf_j4_outpost_b")
    public static void claimRefusedNearOutpost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Outpost outpost = outpost(helper);
        ChunkPos origin = new ChunkPos(outpost.pos());
        ServerPlayer founder = J4TestUtil.fakePlayer(helper, "sf_j4_capitale");
        ServerPlayer claimer = J4TestUtil.fakePlayer(helper, "sf_j4_revendique");
        try {
            J4TestUtil.moveTo(helper, founder, origin);
            ActionResult refused = NationManager.create(founder, J4TestUtil.unique(helper, "SfCap"));
            refused(helper, refused, "capitale sur le chunk du comptoir");
            helper.assertTrue(refused.message().getContents() instanceof TranslatableContents contents
                    && contents.getKey().equals(Msg.key("outpost.claim.refused")), "motif : avant-poste marchand");

            J4TestUtil.moveTo(helper, claimer, new ChunkPos(origin.x + 2, origin.z));
            J4TestUtil.expect(helper, NationManager.create(claimer, J4TestUtil.unique(helper, "SfRev")), true,
                    "capitale hors de la zone de 3 × 3 chunks");
            Nation nation = NationData.get(server).nationOf(claimer.getUUID());
            NationManager.adjustTreasury(nation, 1_000_000L);
            ChunkPos edge = new ChunkPos(origin.x + 1, origin.z);
            refused(helper, NationManager.claim(claimer, level.dimension(), edge), "chunk voisin du comptoir");

            OutpostManager.unregister(server, outpost);
            J4TestUtil.expect(helper, NationManager.claim(claimer, level.dimension(), edge), true, "revendication après désenregistrement");
            J4TestUtil.expect(helper, NationManager.create(founder, J4TestUtil.unique(helper, "SfCap")), true,
                    "capitale acceptée après désenregistrement");
        } finally {
            cleanup(server, outpost);
            J4TestUtil.disband(founder);
            J4TestUtil.disband(claimer);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = TRADE_BATCH)
    public static void boxProtectedFromSurvival(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Outpost outpost = outpost(helper);
        ServerPlayer survivor = J4TestUtil.fakePlayer(helper, "sf_j4_survie");
        try {
            BlockPos inside = outpost.pos().offset(3, 0, 3);
            level.setBlock(inside, Blocks.DIRT.defaultBlockState(), 3);
            helper.assertTrue(breakCanceled(level, inside, survivor), "casse refusée dans la boîte");
            helper.assertTrue(placeCanceled(level, inside.above(), survivor), "pose refusée dans la boîte");
            helper.assertTrue(breakCanceled(level, outpost.pos().offset(0, 7, 0), survivor), "boîte jusqu'à +7 en Y");
            helper.assertFalse(breakCanceled(level, outpost.pos().offset(8, 0, 0), survivor), "hors de la boîte (8 blocs) : permis");
            helper.assertFalse(breakCanceled(level, outpost.pos().offset(0, -3, 0), survivor), "sous la boîte (−3) : permis");

            level.explode(null, inside.getX() + 0.5D, inside.getY() + 0.5D, inside.getZ() + 1.5D, 2.0F, Level.ExplosionInteraction.BLOCK);
            helper.assertTrue(level.getBlockState(inside).is(Blocks.DIRT), "explosion sans dégât dans la boîte");

            survivor.setGameMode(GameType.CREATIVE);
            helper.assertFalse(breakCanceled(level, inside, survivor), "Créatif : casse permise");
            survivor.setGameMode(GameType.SURVIVAL);

            OutpostManager.unregister(server, outpost);
            helper.assertFalse(breakCanceled(level, inside, survivor), "témoin : avant-poste retiré, casse permise");
            level.explode(null, inside.getX() + 0.5D, inside.getY() + 0.5D, inside.getZ() + 1.5D, 2.0F, Level.ExplosionInteraction.BLOCK);
            helper.assertTrue(level.getBlockState(inside).isAir(), "témoin : l'explosion détruit hors avant-poste");
        } finally {
            cleanup(server, outpost);
        }
        helper.succeed();
    }

    private static boolean breakCanceled(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    private static boolean placeCanceled(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, pos),
                level.getBlockState(pos.below()), player);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    @GameTest(template = AREA, batch = "sf_j4_outpost_grief")
    public static void lootingRecordsGrievance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_pillard");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfPille");
        Outpost outpost = outpost(helper);
        try {
            BlockPos looted = outpost.pos().offset(2, 0, 2);
            BlockPos plain = outpost.pos().offset(-2, 0, 2);
            level.setBlock(looted, Blocks.BARREL.defaultBlockState(), 3);
            level.setBlock(plain, Blocks.BARREL.defaultBlockState(), 3);
            helper.assertTrue(level.getBlockEntity(looted) instanceof BarrelBlockEntity, "tonneau posé");
            ((BarrelBlockEntity) level.getBlockEntity(looted)).setLootTable(
                    ResourceKey.create(Registries.LOOT_TABLE, id("chests/avant_poste_plaines")), 7L);
            chef.moveTo(looted.getX() + 0.5D, looted.getY() + 1.0D, looted.getZ() - 1.5D);
            long before = System.currentTimeMillis();
            rightClick(chef, looted);
            Outpost.Grievance grievance = outpost.grievances().get(nation.id());
            helper.assertTrue(grievance != null && grievance.count() == 1 && grievance.last() >= before,
                    "pillage d'un conteneur non pillé : grief +1 daté");
            rightClick(chef, plain);
            helper.assertTrue(outpost.grievances().get(nation.id()).count() == 1, "conteneur sans table de butin : rien");
            ServerPlayer stranger = J4TestUtil.fakePlayer(helper, "sf_j4_errant");
            stranger.moveTo(looted.getX() + 0.5D, looted.getY() + 1.0D, looted.getZ() - 1.5D);
            rightClick(stranger, looted);
            helper.assertTrue(outpost.grievances().size() == 1 && outpost.grievances().get(nation.id()).count() == 1,
                    "joueur sans nation : aucun grief");
        } finally {
            cleanup(server, outpost);
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    private static void rightClick(ServerPlayer player, BlockPos pos) {
        CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    // ------------------------------------------------------------------ génération du monde et persistance

    @GameTest(template = AREA)
    public static void worldgenEntriesPresent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryAccess access = level.registryAccess();
        helper.assertTrue(access.registryOrThrow(Registries.STRUCTURE_SET).containsKey(id("avant_postes")), "ensemble de structures");
        helper.assertTrue(access.registryOrThrow(Registries.STRUCTURE).getTag(OutpostRules.OUTPOST_STRUCTURES)
                .map(HolderSet::size).orElse(0) == 3, "tag #avant_postes : 3 structures");
        for (String v : VARIANTS) {
            helper.assertTrue(access.registryOrThrow(Registries.STRUCTURE).containsKey(id("avant_poste_" + v)), "structure " + v);
            helper.assertTrue(access.registryOrThrow(Registries.TEMPLATE_POOL).containsKey(id("avant_poste/" + v)), "pool de gabarits " + v);
            StructureTemplate template = level.getStructureManager().get(id("avant_poste/" + v)).orElse(null);
            helper.assertTrue(template != null && template.getSize().getX() == 11 && template.getSize().getY() == 7
                    && template.getSize().getZ() == 11, "gabarit " + v + " de 11 × 7 × 11");
            TagKey<Biome> biomes = TagKey.create(Registries.BIOME, id("has_structure/avant_poste_" + v));
            helper.assertTrue(access.registryOrThrow(Registries.BIOME).getTag(biomes).map(HolderSet::size).orElse(0) > 0,
                    "biomes de la variante " + v);
            LootTable loot = level.getServer().reloadableRegistries()
                    .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id("chests/avant_poste_" + v)));
            helper.assertTrue(loot != LootTable.EMPTY, "butin du tonneau " + v);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void outpostDataRoundTrip(GameTestHelper helper) {
        RegistryAccess access = helper.getLevel().registryAccess();
        UUID id = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        UUID nation = UUID.randomUUID();
        BlockPos pos = new BlockPos(1000, 70, -2000);
        Outpost outpost = new Outpost(id, Level.OVERWORLD, pos, Direction.EAST, PROSPECTEUR, "prospecteur", 42L);
        OutpostManager.refresh(outpost, 3, 0.5D, pool(helper, PROSPECTEUR));
        outpost.setMerchant(merchant);
        outpost.addPressure(Items.TORCH, true, 3.5D);
        outpost.addPressure(Items.RAW_IRON, false, 7.0D);
        outpost.recordGrievance(nation, 777L);
        outpost.recordGrievance(nation, 999L);
        outpost.slot(Items.TORCH).setStockLeft(5);

        CompoundTag tag = new CompoundTag();
        tag.putLong("ticks", 12345L);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(outpost.save());
        tag.put("outposts", list);
        OutpostData back = OutpostData.load(tag, access);
        Outpost copy = back.get(id);
        helper.assertTrue(copy != null && back.ticks() == 12345L, "avant-poste et compteur relus");
        helper.assertTrue(copy.pos().equals(pos) && copy.facing() == Direction.EAST && copy.pool().equals(PROSPECTEUR)
                && copy.variant().equals("prospecteur") && copy.faction().equals(Outpost.NEUTRAL_FACTION)
                && merchant.equals(copy.merchant()) && copy.created() == 42L && copy.rotation() == 3L, "champs relus");
        helper.assertTrue(items(copy).equals(items(outpost)) && copy.slot(Items.TORCH).stockLeft() == 5, "étal relu");
        helper.assertTrue(copy.buyPressure(Items.TORCH) == 3.5D && copy.sellPressure(Items.RAW_IRON) == 7.0D, "pression relue");
        helper.assertTrue(copy.grievances().get(nation).equals(new Outpost.Grievance(2, 999L)), "griefs relus");
        ChunkPos chunk = new ChunkPos(pos);
        helper.assertTrue(back.atAnchor(Level.OVERWORLD, pos) == copy, "index : ancre");
        helper.assertTrue(back.claimBlocked(Level.OVERWORLD, ChunkPos.asLong(chunk.x + 1, chunk.z - 1))
                && !back.claimBlocked(Level.OVERWORLD, ChunkPos.asLong(chunk.x + 2, chunk.z))
                && !back.claimBlocked(Level.NETHER, chunk.toLong()), "index : 3 × 3 chunks, par dimension");
        helper.assertTrue(back.boxAt(Level.OVERWORLD, pos.offset(7, 7, -7)) == copy
                && back.boxAt(Level.OVERWORLD, pos.offset(0, -3, 0)) == null, "boîte protégée relue");
        helper.assertTrue(back.save(new CompoundTag(), access).equals(tag), "écriture identique après relecture");
        helper.succeed();
    }
}
