package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.economy.ActivityTracker;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.economy.RankHooks;
import io.github.theoblaise1.sovereignfrontier.economy.TerminalManager;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.network.CratePreviewView;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.reward.CrateBlock;
import io.github.theoblaise1.sovereignfrontier.reward.CrateEntry;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.reward.CrateManager;
import io.github.theoblaise1.sovereignfrontier.reward.CrateRegistry;
import io.github.theoblaise1.sovereignfrontier.reward.CrateRoller;
import io.github.theoblaise1.sovereignfrontier.reward.CrateTable;
import io.github.theoblaise1.sovereignfrontier.reward.DailyManager;
import io.github.theoblaise1.sovereignfrontier.reward.DailyTable;
import io.github.theoblaise1.sovereignfrontier.reward.RewardData;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GameTests des récompenses joueur (WP2) : Terminal bancaire (barème, activité, encaissement, fenêtre, plafond),
 * {@code /daily} (délai epoch, série, plateau, clés), persistance et caisses (chargement, interdiction, provisoires,
 * pitié, ouverture, mauvaise clé, verrou d'animation, inventaire plein). Le temps et le hasard sont injectés.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class RewardGameTests {
    private static final String AREA = J4TestUtil.AREA;
    /** Barème de la spécification (cr par heure active). */
    private static final double[] SPEC_RATES = {240, 180, 135, 100, 75, 55, 40, 30, 20};
    private static final long HOUR = 3_600_000L;
    private static final long WINDOW = 24L * HOUR;
    private static final long T0 = 1_700_000_000_000L;
    private static final int THRESHOLD = 20;

    private RewardGameTests() {
    }

    // ------------------------------------------------------------------ outils

    private static HolderLookup.Provider registries(GameTestHelper helper) {
        return helper.getLevel().registryAccess();
    }

    private static CrateTable table(GameTestHelper helper, String json) {
        return CrateRegistry.parse(JsonParser.parseString(json), registries(helper)).getOrThrow();
    }

    private static boolean rejected(GameTestHelper helper, String json) {
        return CrateRegistry.parse(JsonParser.parseString(json), registries(helper)).error().isPresent();
    }

    private static RewardData rewards(GameTestHelper helper) {
        return RewardData.get(helper.getLevel().getServer());
    }

    /** Faux joueur placé dans la zone du test, inventaire vide, sans fiche de récompenses ni suivi d'activité. */
    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer p = J4TestUtil.fakePlayer(helper, name);
        BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
        p.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        p.getInventory().clearContent();
        rewards(helper).remove(p.getUUID());
        ActivityTracker.forget(p.getUUID());
        return p;
    }

    private static void cleanup(GameTestHelper helper, ServerPlayer p) {
        p.getInventory().clearContent();
        rewards(helper).remove(p.getUUID());
        ActivityTracker.forget(p.getUUID());
    }

    private static int keys(ServerPlayer p) {
        int n = 0;
        for (CrateKind kind : CrateKind.values()) {
            n += Inventories.countPlain(p, kind.keyItem());
        }
        return n;
    }

    /** Message traduisible {@code sovereignfrontier.<key>} dans le composant ou ses enfants (indépendant de la langue chargée). */
    @Nullable
    private static TranslatableContents translatable(Component root, String key) {
        if (root.getContents() instanceof TranslatableContents tc && tc.getKey().equals(Msg.key(key))) {
            return tc;
        }
        for (Component sibling : root.getSiblings()) {
            TranslatableContents found = translatable(sibling, key);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static TerminalManager.Accrual accrue(RewardData.PlayerRewards r, long now) {
        return TerminalManager.accrueSecond(r, now, SPEC_RATES, 1.0D, 1_000_000L, WINDOW);
    }

    private static BlockPos crate(GameTestHelper helper, CrateKind kind) {
        BlockPos rel = new BlockPos(2, 1, 2);
        helper.setBlock(rel, kind.block().defaultBlockState());
        return helper.absolutePos(rel);
    }

    private static boolean isOpen(GameTestHelper helper, BlockPos abs) {
        return helper.getLevel().getBlockState(abs).getValue(CrateBlock.OPEN);
    }

    /** Table d'objets seulement (aucun argent) : tout gain est un objet à remettre. */
    private static final String ITEMS_ONLY = """
            {"rolls": 2, "entries": [
              {"item": "minecraft:bread", "count": 5, "weight": 1},
              {"item": "minecraft:coal", "count": 3, "weight": 1},
              {"id": "r", "item": "minecraft:diamond", "count": 1, "weight": 1, "rarity": "rare"}
            ]}""";

    // ------------------------------------------------------------------ Terminal bancaire

    @GameTest(template = AREA)
    public static void terminalRateTable(GameTestHelper helper) {
        helper.assertTrue(TerminalManager.rateAt(SPEC_RATES, 0) == 240 && TerminalManager.rateAt(SPEC_RATES, 3599) == 240
                && TerminalManager.rateAt(SPEC_RATES, 3600) == 180 && TerminalManager.rateAt(SPEC_RATES, 8 * 3600) == 20
                && TerminalManager.rateAt(SPEC_RATES, 500 * 3600) == 20, "barème par heure active, dernière valeur répétée");
        double[] config = SFConfig.terminalHourlyRates();
        helper.assertTrue(Arrays.equals(config, SPEC_RATES), "barème par défaut de la config : " + Arrays.toString(config));

        long[] cumulative = {240, 420, 555, 655, 730, 785, 825, 855, 875};
        RewardData.PlayerRewards r = new RewardData.PlayerRewards();
        r.window = T0;
        for (int hour = 0; hour < cumulative.length; hour++) {
            for (int s = 0; s < 3600; s++) {
                helper.assertTrue(accrue(r, T0 + 1000L) == TerminalManager.Accrual.ACCRUED, "seconde active comptée");
            }
            helper.assertTrue(r.units == cumulative[hour] * TerminalManager.UNITS_PER_CREDIT,
                    "cumul après " + (hour + 1) + " h : " + cumulative[hour] + " cr (obtenu " + r.units / 3600.0 + ")");
        }
        helper.assertTrue(r.activeSec == 9 * 3600 && r.lifetime == 9 * 3600L, "temps actif compté");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void terminalAccruesOnlyWhileActive(GameTestHelper helper) {
        ServerPlayer p = player(helper, "sf_j4_actif");
        try {
            UUID id = p.getUUID();
            long afkSec = TerminalManager.afkMs() / 1000L;
            long perSecond = Math.round(TerminalManager.rateAt(SFConfig.terminalHourlyRates(), 0));
            RewardData.PlayerRewards r = rewards(helper).of(id);
            long t0 = TimeUtil.now();
            r.window = t0 - 1000L;

            TerminalManager.tick(p, t0);
            helper.assertTrue(r.units == 0L && !ActivityTracker.isActive(id, t0, TerminalManager.afkMs()),
                    "connexion sans geste : inactif, rien d'accumulé");
            float look = SFConfig.TERMINAL_LOOK_DEGREES.get().floatValue() + 1.0F;
            p.setYRot(p.getYRot() + look);
            long signal = t0 + 1000L;
            TerminalManager.tick(p, signal);
            helper.assertTrue(r.units == perSecond, "regard ≥ seuil : actif, une seconde comptée (" + r.units + ")");
            for (long s = 1; s <= afkSec; s++) {
                TerminalManager.tick(p, signal + s * 1000L);
            }
            helper.assertTrue(r.units == (afkSec + 1) * perSecond, "+" + afkSec + " s après le dernier signal : encore actif ("
                    + r.units + ")");
            TerminalManager.tick(p, signal + (afkSec + 1) * 1000L);
            helper.assertTrue(r.units == (afkSec + 1) * perSecond, "+" + (afkSec + 1) + " s : en pause, rien d'ajouté");
            p.setYRot(p.getYRot() - look);
            TerminalManager.tick(p, signal + (afkSec + 2) * 1000L);
            helper.assertTrue(r.units == (afkSec + 2) * perSecond, "reprise après un mouvement de regard");
            // Regard de 1° : pas un signal ; seules les secondes encore couvertes par le dernier signal comptent.
            p.setXRot(p.getXRot() + 1.0F);
            long units = r.units;
            for (long s = afkSec + 3; s < 2 * afkSec + 10; s++) {
                TerminalManager.tick(p, signal + s * 1000L);
            }
            helper.assertTrue(r.units - units == afkSec * perSecond, "regard de 1° : pas un signal (" + (r.units - units) + ")");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void terminalPassiveMovementIgnored(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        ActivityTracker.forget(id);
        try {
            double look = 10.0D;
            double walk = 1.0D;
            helper.assertFalse(ActivityTracker.sample(id, new ActivityTracker.Motion(0, 0, 0, 0, true, false), 1000L, look, walk),
                    "premier échantillon : référence seulement");
            helper.assertTrue(ActivityTracker.sample(id, new ActivityTracker.Motion(0, 0, 2, 0, true, false), 2000L, look, walk),
                    "marche de 2 blocs au sol : signal");
            helper.assertFalse(ActivityTracker.sample(id, new ActivityTracker.Motion(0, 0, 6, 0, true, true), 3000L, look, walk),
                    "déplacement dans l'eau, en monture ou en vol plané : ignoré");
            helper.assertFalse(ActivityTracker.sample(id, new ActivityTracker.Motion(0, 0, 10, 0, false, false), 4000L, look, walk),
                    "chute ou poussée sans toucher le sol : ignorée");
            helper.assertFalse(ActivityTracker.sample(id, new ActivityTracker.Motion(0, 0, 10.5, 0, true, false), 5000L, look, walk),
                    "pas de 0,5 bloc : sous le seuil");
            helper.assertTrue(ActivityTracker.lastSignal(id) == 2000L, "seule la marche au sol a compté");
            helper.assertTrue(ActivityTracker.sample(id, new ActivityTracker.Motion(350, 0, 10.5, 0, true, true), 6000L, look, walk),
                    "le regard compte même en monture (350° = −10°, angle replié)");
        } finally {
            ActivityTracker.forget(id);
        }

        // Drapeau lu sur une vraie entité (vol plané). Un FakePlayer NeoForge ne monte jamais (startRiding refusé) :
        // le cas « passager » est couvert par l'instantané ci-dessus.
        ServerPlayer p = player(helper, "sf_j4_passif");
        try {
            helper.assertFalse(ActivityTracker.Motion.of(p).passive(), "à pied : mouvement non passif");
            p.startFallFlying();
            helper.assertTrue(ActivityTracker.Motion.of(p).passive(), "vol plané : passif");
            p.stopFallFlying();
            helper.assertFalse(ActivityTracker.Motion.of(p).passive(), "fin du vol plané");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void terminalRepeatedBreakIgnored(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        ActivityTracker.forget(id);
        try {
            BlockPos a = new BlockPos(1, 64, 1);
            List<BlockPos> others = List.of(new BlockPos(2, 64, 1), new BlockPos(3, 64, 1), new BlockPos(4, 64, 1));
            BlockPos e = new BlockPos(5, 64, 1);
            helper.assertTrue(ActivityTracker.signalAt(id, a, 1000L) && ActivityTracker.lastSignal(id) == 1000L, "première casse");
            helper.assertFalse(ActivityTracker.signalAt(id, a.immutable(), 2000L), "générateur de pierre : même position ignorée");
            helper.assertTrue(ActivityTracker.lastSignal(id) == 1000L, "signal inchangé");
            long t = 3000L;
            for (BlockPos pos : others) {
                helper.assertTrue(ActivityTracker.signalAt(id, pos, t), "nouvelle position comptée");
                t += 1000L;
            }
            helper.assertFalse(ActivityTracker.signalAt(id, a, t), "position parmi les 4 dernières : ignorée");
            helper.assertTrue(ActivityTracker.signalAt(id, e, t + 1000L), "cinquième position comptée");
            helper.assertTrue(ActivityTracker.signalAt(id, a, t + 2000L), "position sortie de l'anneau de 4 : de nouveau comptée");
            long afk = 180_000L;
            long last = ActivityTracker.lastSignal(id);
            helper.assertTrue(ActivityTracker.isActive(id, last + afk, afk) && !ActivityTracker.isActive(id, last + afk + 1L, afk),
                    "actif jusqu'à 180 s après le dernier signal");
        } finally {
            ActivityTracker.forget(id);
        }
        helper.assertFalse(ActivityTracker.isActive(id, 1000L, 180_000L), "joueur oublié : inactif");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void terminalCashOutExact(GameTestHelper helper) {
        ServerPlayer p = player(helper, "sf_j4_terminal");
        try {
            EconomyData eco = EconomyData.get(helper.getLevel().getServer());
            RewardData.PlayerRewards r = rewards(helper).of(p.getUUID());
            r.window = T0;
            for (int s = 0; s < 3600 + 10; s++) {
                accrue(r, T0 + 1000L);
            }
            helper.assertTrue(r.units == 240L * 3600L + 10L * 180L, "3600 s à 240 cr/h puis 10 s à 180 cr/h");
            long before = eco.balance(p.getUUID());
            ActionResult first = TerminalManager.cashOut(p);
            helper.assertTrue(first.success() && eco.balance(p.getUUID()) == before + 240L, "240 cr versés (obtenu "
                    + (eco.balance(p.getUUID()) - before) + ")");
            helper.assertTrue(r.units == 1800L, "reste conservé : 0,5 cr (" + r.units + " unités)");
            ActionResult second = TerminalManager.cashOut(p);
            helper.assertFalse(second.success(), "second encaissement : rien à encaisser");
            helper.assertTrue(eco.balance(p.getUUID()) == before + 240L && r.units == 1800L, "second encaissement sans effet");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void terminalWindowReset24h(GameTestHelper helper) {
        RewardData.PlayerRewards r = new RewardData.PlayerRewards();
        helper.assertTrue(accrue(r, T0) == TerminalManager.Accrual.WINDOW_RESET && r.window == T0 && r.units == 0L,
                "aucune fenêtre : elle s'ouvre, sans gain pour cette seconde");
        r.activeSec = 2 * 3600;
        helper.assertTrue(accrue(r, T0 + WINDOW - 1L) == TerminalManager.Accrual.ACCRUED && r.units == 135L,
                "3e heure de la fenêtre : 135 cr/h");
        helper.assertTrue(accrue(r, T0 + WINDOW) == TerminalManager.Accrual.WINDOW_RESET && r.window == T0 + WINDOW
                && r.activeSec == 0 && r.units == 135L, "24 h après son début : la fenêtre repart, épargne conservée");
        helper.assertTrue(accrue(r, T0 + WINDOW + 1000L) == TerminalManager.Accrual.ACCRUED && r.units == 135L + 240L,
                "barème repris à la 1re heure");
        RewardData.PlayerRewards back = new RewardData.PlayerRewards();
        back.window = T0;
        back.activeSec = 100;
        helper.assertTrue(accrue(back, T0 - HOUR) == TerminalManager.Accrual.ACCRUED && back.window == T0,
                "recul d'horloge : la fenêtre s'allonge au lieu de repartir");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void terminalPendingCap(GameTestHelper helper) {
        long max = 3000L;
        long cap = TerminalManager.capUnits(max);
        RewardData.PlayerRewards r = new RewardData.PlayerRewards();
        r.window = T0;
        r.units = cap - 100L;
        helper.assertTrue(TerminalManager.accrueSecond(r, T0 + 1000L, SPEC_RATES, 1.0D, max, WINDOW)
                == TerminalManager.Accrual.ACCRUED && r.units == cap, "épargne bornée au plafond de 3000 cr");
        int active = r.activeSec;
        helper.assertTrue(TerminalManager.accrueSecond(r, T0 + 2000L, SPEC_RATES, 1.0D, max, WINDOW)
                == TerminalManager.Accrual.FULL && r.units == cap && r.activeSec == active,
                "épargne pleine : l'accumulation s'arrête, le barème n'avance pas");
        RewardData.PlayerRewards zero = new RewardData.PlayerRewards();
        zero.window = T0;
        helper.assertTrue(TerminalManager.accrueSecond(zero, T0 + 1000L, SPEC_RATES, 1.0D, 0L, WINDOW)
                == TerminalManager.Accrual.FULL && zero.units == 0L, "plafond nul : aucune accumulation");
        helper.assertTrue(TerminalManager.capUnits(SFConfig.TERMINAL_MAX_PENDING.get()) == 3000L * 3600L,
                "plafond par défaut : 3000 cr");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void rankHookDefaultsToOne(GameTestHelper helper) {
        ServerPlayer p = player(helper, "sf_j4_grade");
        try {
            helper.assertTrue(RankHooks.terminalMultiplier(p) == 1.0D, "aucun grade avant le J8 : multiplicateur 1");
            helper.assertFalse(TerminalManager.eligible(p), "faux joueur : jamais échantillonné");
            RewardData.PlayerRewards r = new RewardData.PlayerRewards();
            r.window = T0;
            TerminalManager.accrueSecond(r, T0 + 1000L, SPEC_RATES, 1.25D, 3000L, WINDOW);
            helper.assertTrue(r.units == 300L, "crochet prêt pour le Souverain : 240 × 1,25 = 300 unités");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ /daily

    private static DailyTable dailyTable(GameTestHelper helper) {
        DailyTable table = DailyTable.published();
        helper.assertTrue(table != null, "table /daily publiée");
        return table;
    }

    @GameTest(template = AREA)
    public static void dailyCooldownEpoch(GameTestHelper helper) {
        DailyTable table = dailyTable(helper);
        ServerPlayer p = player(helper, "sf_j4_daily");
        try {
            long cd = DailyManager.cooldownMs();
            RandomSource random = RandomSource.create(1L);
            RewardData.PlayerRewards r = rewards(helper).of(p.getUUID());
            helper.assertTrue(DailyManager.claim(p, table, T0, random).success() && r.dailyLast == T0 && r.streak == 1,
                    "première réclamation : série 1");
            ActionResult early = DailyManager.claim(p, table, T0 + cd - 60_000L, random);
            helper.assertFalse(early.success(), "t0 + 23 h 59 : refusé");
            helper.assertTrue(r.dailyLast == T0 && r.streak == 1, "un refus ne change rien");
            helper.assertTrue(DailyManager.claim(p, table, T0 + cd, random).success() && r.streak == 2,
                    "t0 + 24 h : accepté, série 2");
            helper.assertFalse(DailyManager.claim(p, table, T0, random).success(), "recul d'horloge : l'attente s'allonge");
            helper.assertFalse(DailyManager.claim(p, null, T0 + 3 * cd, random).success(), "table absente : /daily indisponible");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void dailyStreakPlateauAndReset(GameTestHelper helper) {
        DailyTable table = dailyTable(helper);
        helper.assertTrue(table.days().size() == 7 && table.day(1).money() == 100 && table.day(7).money() == 500,
                "table livrée : 7 jours, 100 à 500 cr");
        ServerPlayer p = player(helper, "sf_j4_serie");
        try {
            EconomyData eco = EconomyData.get(helper.getLevel().getServer());
            RewardData.PlayerRewards r = rewards(helper).of(p.getUUID());
            long cd = DailyManager.cooldownMs();
            RandomSource random = RandomSource.create(2L);
            long t = T0;
            for (int day = 1; day <= 9; day++) {
                long before = eco.balance(p.getUUID());
                helper.assertTrue(DailyManager.claim(p, table, t, random).success(), "réclamation du jour " + day);
                helper.assertTrue(r.streak == day, "série " + day + " (obtenu " + r.streak + ")");
                long expected = table.day(Math.min(day, 7)).money();
                helper.assertTrue(eco.balance(p.getUUID()) - before == expected, "jour " + day + " : " + expected + " cr");
                t += cd;
            }
            helper.assertTrue(table.day(8).equals(table.day(7)) && table.day(30).equals(table.day(7)), "plateau au jour 7");
            long last = r.dailyLast;
            long before = eco.balance(p.getUUID());
            helper.assertTrue(DailyManager.claim(p, table, last + DailyManager.streakResetMs() + 1L, random).success()
                    && r.streak == 1, "plus de 48 h sans /daily : série ramenée à 1");
            helper.assertTrue(eco.balance(p.getUUID()) - before == table.day(1).money(), "récompense du jour 1");
            r.dailyLast = T0;
            r.streak = 3;
            helper.assertTrue(DailyManager.claim(p, table, T0 + DailyManager.streakResetMs(), random).success() && r.streak == 4,
                    "exactement 48 h : série conservée");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void dailyOneOrTwoKeys(GameTestHelper helper) {
        DailyTable table = dailyTable(helper);
        helper.assertTrue(table.bannedKeys().isEmpty(), "aucune clé interdite");
        ServerPlayer p = player(helper, "sf_j4_cles_jour");
        try {
            RewardData.PlayerRewards r = rewards(helper).of(p.getUUID());
            long cd = DailyManager.cooldownMs();
            for (long seed = 0; seed < 12; seed++) {
                for (int day = 1; day <= 8; day++) {
                    DailyTable.Day reward = table.day(day);
                    helper.assertTrue(reward.keys().size() >= 1 && reward.keys().size() <= 2, "1 à 2 clés au jour " + day);
                    p.getInventory().clearContent();
                    r.streak = day - 1;
                    r.dailyLast = day == 1 ? 0L : T0 - cd;
                    helper.assertTrue(DailyManager.claim(p, table, T0, RandomSource.create(seed * 31 + day)).success(),
                            "réclamation (graine " + seed + ", jour " + day + ")");
                    helper.assertTrue(keys(p) == reward.keys().size(), "jour " + day + " : " + reward.keys().size()
                            + " clé(s) remise(s) (obtenu " + keys(p) + ")");
                    Set<CrateKind> allowed = new HashSet<>();
                    reward.keys().forEach(k -> allowed.addAll(k.oneOf()));
                    for (CrateKind kind : CrateKind.values()) {
                        if (!allowed.contains(kind)) {
                            helper.assertTrue(Inventories.countPlain(p, kind.keyItem()) == 0, "clé " + kind.id()
                                    + " hors des choix du jour " + day);
                        }
                    }
                }
            }
            helper.assertTrue(DailyTable.parse(JsonParser.parseString("""
                    {"days": [{"money": 10, "keys": [{"one_of": ["commune"]}, {"one_of": ["commune"]}, {"one_of": ["commune"]}]}]}
                    """)).error().isPresent(), "3 clés par jour refusées");
            helper.assertTrue(DailyTable.parse(JsonParser.parseString("{\"days\": [{\"money\": 10, \"keys\": []}]}"))
                    .error().isPresent(), "aucune clé refusée");
            helper.assertTrue(DailyTable.parse(JsonParser.parseString(
                    "{\"days\": [{\"money\": 10, \"keys\": [{\"one_of\": [\"militaire\", \"militaire\"]}]}]}")).error().isPresent(),
                    "type en double refusé");
            helper.assertTrue(DailyTable.parse(JsonParser.parseString("{\"days\": []}")).error().isPresent(), "aucun jour refusé");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void rewardDataRoundTrip(GameTestHelper helper) {
        RewardData data = new RewardData();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RewardData.PlayerRewards ra = data.of(a);
        ra.window = T0;
        ra.activeSec = 4321;
        ra.units = 987_654L;
        ra.lifetime = 123_456L;
        ra.dailyLast = T0 + 5L;
        ra.streak = 9;
        Map<String, Integer> pity = new LinkedHashMap<>();
        pity.put("diamants", 3);
        pity.put("pactole", 20);
        ra.setPity(CrateKind.COMMUNE, pity);
        ra.setPity(CrateKind.SPATIALE, Map.of("gaz_massif", 7));
        ra.setPity(CrateKind.MILITAIRE, Map.of());
        data.of(b);

        CompoundTag tag = data.save(new CompoundTag(), registries(helper));
        RewardData back = RewardData.load(tag, registries(helper));
        RewardData.PlayerRewards rb = back.find(a);
        helper.assertTrue(rb != null && back.find(b) == null, "fiche vide non sauvegardée");
        helper.assertTrue(rb.window == T0 && rb.activeSec == 4321 && rb.units == 987_654L && rb.lifetime == 123_456L,
                "terminal relu");
        helper.assertTrue(rb.dailyLast == T0 + 5L && rb.streak == 9, "série /daily relue");
        helper.assertTrue(rb.pity(CrateKind.COMMUNE).equals(pity) && rb.pity(CrateKind.SPATIALE).equals(Map.of("gaz_massif", 7))
                && rb.pity(CrateKind.MILITAIRE).isEmpty() && rb.pity(CrateKind.INDUSTRIELLE).isEmpty(), "pitié relue");
        helper.assertTrue(back.save(new CompoundTag(), registries(helper)).equals(tag), "aller-retour NBT stable");
        helper.assertTrue(RewardData.load(new CompoundTag(), registries(helper)).size() == 0, "NBT absent : aucune fiche");
        helper.succeed();
    }

    // ------------------------------------------------------------------ caisses

    @GameTest(template = AREA)
    public static void cratesLoadedClean(GameTestHelper helper) {
        for (CrateKind kind : CrateKind.values()) {
            CrateTable t = CrateRegistry.get(kind);
            helper.assertTrue(t != null, "table publiée : " + kind.id());
            helper.assertTrue(t.rares().size() >= 1 && t.rares().size() <= 3, kind.id() + " : 1 à 3 entrées rares");
            helper.assertTrue(t.bannedItems().isEmpty(), kind.id() + " : aucun objet interdit");
            helper.assertTrue(t.rolls() >= 1 && t.rolls() <= 6 && t.totalWeight() > 0, kind.id() + " : tirages et poids");
            for (CrateEntry e : t.rares()) {
                helper.assertTrue(e.id().isPresent() && !e.isPlaceholder(), kind.id() + " : rare identifiée et tirable");
            }
            CratePreviewView view = CrateManager.preview(kind, t, Map.of(), THRESHOLD);
            helper.assertTrue(view.available() && view.entries().size() == t.entries().size() && view.rolls() == t.rolls(),
                    kind.id() + " : aperçu complet");
            for (int i = 0; i < t.entries().size(); i++) {
                CrateEntry e = t.entries().get(i);
                CratePreviewView.EntryLine line = view.entries().get(i);
                helper.assertTrue(line.myMiss() == (e.isRare() ? 0 : -1) && line.label().isEmpty() != e.isPlaceholder()
                        && line.totalWeight() == t.totalWeight() && line.rarity() == e.rarity().code(),
                        kind.id() + " : ligne d'aperçu " + i);
            }
        }
        helper.assertTrue(CrateRegistry.get(CrateKind.MILITAIRE).entries().stream().filter(CrateEntry::isPlaceholder).count() == 0
                && CrateRegistry.get(CrateKind.SPATIALE).entries().stream().filter(CrateEntry::isPlaceholder).count() == 0,
                "plus aucune entrée provisoire (militaire remplacée au J6, spatiale au J5)");
        helper.assertFalse(CrateManager.preview(CrateKind.COMMUNE, null, Map.of(), THRESHOLD).available(),
                "sans table : aperçu « indisponible »");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void bannedEntryRejectsWholeTable(GameTestHelper helper) {
        CrateTable banned = table(helper, """
                {"rolls": 1, "entries": [
                  {"item": "minecraft:bread", "weight": 5},
                  {"id": "x", "item": "minecraft:elytra", "rarity": "rare"}
                ]}""");
        helper.assertTrue(banned.bannedItems().equals(List.of(ResourceLocation.withDefaultNamespace("elytra"))),
                "élytres détectées : " + banned.bannedItems());
        CrateTable machine = table(helper, """
                {"rolls": 1, "entries": [
                  {"item": "minecraft:bread"},
                  {"id": "x", "item": "sovereignfrontier:generateur_thermique", "rarity": "rare"}
                ]}""");
        helper.assertFalse(machine.bannedItems().isEmpty(), "machine soumise à recherche détectée");
        Map<CrateKind, CrateTable> candidate = new EnumMap<>(CrateKind.class);
        candidate.put(CrateKind.COMMUNE, banned);
        candidate.put(CrateKind.INDUSTRIELLE, machine);
        candidate.put(CrateKind.MILITAIRE, CrateRegistry.get(CrateKind.MILITAIRE));
        Map<CrateKind, CrateTable> kept = CrateRegistry.rejectBanned(candidate);
        helper.assertTrue(kept.keySet().equals(Set.of(CrateKind.MILITAIRE)), "tables fautives rejetées en entier : " + kept.keySet());

        String valid = "{\"rolls\": 1, \"entries\": [{\"item\": \"minecraft:bread\"}, {\"id\": \"r\", \"money\": 5, \"rarity\": \"rare\"}]}";
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "commune"), JsonParser.parseString(valid));
        files.put(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "bonus"), JsonParser.parseString(valid));
        files.put(ResourceLocation.fromNamespaceAndPath("autre", "spatiale"), JsonParser.parseString(valid));
        files.put(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "militaire"),
                JsonParser.parseString("{\"rolls\": 1, \"entries\": [{\"item\": \"minecraft:bread\"}, {\"money\": 5, \"rarity\": \"rare\"}]}"));
        helper.assertTrue(CrateRegistry.build(files, registries(helper)).keySet().equals(Set.of(CrateKind.COMMUNE)),
                "fichiers mal nommés ou invalides ignorés");

        helper.assertTrue(rejected(helper, "{\"rolls\": 1, \"entries\": [{\"item\": \"minecraft:bread\", \"money\": 5}, "
                + "{\"id\": \"r\", \"money\": 5, \"rarity\": \"rare\"}]}"), "deux contenus dans une entrée refusés");
        helper.assertTrue(rejected(helper, "{\"rolls\": 7, \"entries\": [{\"item\": \"minecraft:bread\"}, "
                + "{\"id\": \"r\", \"money\": 5, \"rarity\": \"rare\"}]}"), "7 tirages refusés");
        helper.assertTrue(rejected(helper, "{\"rolls\": 3, \"entries\": [{\"item\": \"minecraft:bread\"}, "
                + "{\"id\": \"r\", \"money\": 5, \"rarity\": \"rare\"}]}"), "plus de tirages que d'entrées tirables refusés");
        helper.assertTrue(rejected(helper, "{\"rolls\": 1, \"entries\": [{\"item\": \"minecraft:bread\"}]}"), "aucune rare refusée");
        helper.assertTrue(rejected(helper, "{\"rolls\": 1, \"entries\": [{\"id\": \"r\", \"money\": 5, \"rarity\": \"rare\"}]}"),
                "aucune entrée courante refusée");
        helper.assertTrue(rejected(helper, "{\"rolls\": 1, \"entries\": [{\"item\": \"minecraft:bread\"}, "
                + "{\"id\": \"p\", \"placeholder\": {\"milestone\": \"J6\", \"label\": \"x\"}, \"rarity\": \"rare\"}]}"),
                "rare provisoire refusée");
        helper.assertTrue(rejected(helper, "{\"rolls\": 1, \"entries\": [{\"item\": \"minecraft:inconnu\"}, "
                + "{\"id\": \"r\", \"money\": 5, \"rarity\": \"rare\"}]}"), "objet inconnu refusé");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void placeholdersNeverRolled(GameTestHelper helper) {
        for (CrateKind kind : List.of(CrateKind.MILITAIRE, CrateKind.SPATIALE)) {
            CrateTable t = CrateRegistry.get(kind);
            helper.assertTrue(t != null, "table publiée : " + kind.id());
            RandomSource random = RandomSource.create(kind.ordinal() * 7919L + 13L);
            Map<String, Integer> miss = Map.of();
            for (int i = 0; i < 10_000; i++) {
                CrateRoller.Roll roll = CrateRoller.roll(t, miss, THRESHOLD, random);
                helper.assertTrue(roll.drawn().size() == t.rolls() && new HashSet<>(roll.drawn()).size() == t.rolls(),
                        "tirages sans remise");
                for (CrateEntry e : roll.drawn()) {
                    helper.assertFalse(e.isPlaceholder(), kind.id() + " : entrée provisoire tirée");
                }
                for (CrateRoller.Gain g : CrateRoller.realize(roll.drawn(), random)) {
                    helper.assertTrue(g.entry().range().contains(g.isMoney() ? (int) g.money() : g.stack().getCount()),
                            "quantité dans la plage");
                }
                miss = roll.miss();
            }
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void pityGuarantees21st(GameTestHelper helper) {
        CrateTable t = table(helper, """
                {"rolls": 1, "entries": [
                  {"id": "rare", "item": "minecraft:diamond", "weight": 1, "rarity": "rare"},
                  {"item": "minecraft:dirt", "weight": 1000000}
                ]}""");
        CrateEntry rare = t.rares().get(0);
        RandomSource random = RandomSource.create(21L);
        Map<String, Integer> miss = Map.of();
        for (int opening = 1; opening <= THRESHOLD; opening++) {
            CrateRoller.Roll roll = CrateRoller.roll(t, miss, THRESHOLD, random);
            helper.assertTrue(roll.forced() == null && !roll.drawn().contains(rare), "ouverture " + opening + " : pas de rare");
            miss = roll.miss();
        }
        helper.assertTrue(miss.get("rare") == THRESHOLD, "20 ouvertures sans la rare");
        CrateRoller.Roll guaranteed = CrateRoller.roll(t, miss, THRESHOLD, random);
        helper.assertTrue(guaranteed.forced() == rare && guaranteed.drawn().equals(List.of(rare)), "21e ouverture : rare garantie");
        helper.assertTrue(guaranteed.miss().get("rare") == 0, "compteur remis à zéro");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void pityTwoRaresStagger(GameTestHelper helper) {
        CrateTable t = table(helper, """
                {"rolls": 1, "entries": [
                  {"id": "a", "item": "minecraft:diamond", "weight": 1, "rarity": "rare"},
                  {"id": "b", "item": "minecraft:emerald", "weight": 1, "rarity": "rare"},
                  {"item": "minecraft:dirt", "weight": 1000000}
                ]}""");
        CrateEntry a = t.rares().get(0);
        CrateEntry b = t.rares().get(1);
        RandomSource random = RandomSource.create(22L);
        Map<String, Integer> miss = Map.of();
        for (int opening = 1; opening <= THRESHOLD; opening++) {
            miss = CrateRoller.roll(t, miss, THRESHOLD, random).miss();
        }
        helper.assertTrue(miss.get("a") == THRESHOLD && miss.get("b") == THRESHOLD, "deux rares au seuil ensemble : " + miss);
        CrateRoller.Roll r21 = CrateRoller.roll(t, miss, THRESHOLD, random);
        helper.assertTrue(r21.forced() == a && r21.drawn().equals(List.of(a)), "21e ouverture : A (ordre du JSON)");
        helper.assertTrue(r21.miss().get("a") == 0 && r21.miss().get("b") == THRESHOLD + 1, "B attend : " + r21.miss());
        CrateRoller.Roll r22 = CrateRoller.roll(t, r21.miss(), THRESHOLD, random);
        helper.assertTrue(r22.forced() == b && r22.drawn().equals(List.of(b)), "22e ouverture : B");
        helper.assertTrue(r22.miss().get("a") == 1 && r22.miss().get("b") == 0, "compteurs après la 22e : " + r22.miss());
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void pityResetsOnNaturalDrop(GameTestHelper helper) {
        CrateTable t = table(helper, """
                {"rolls": 1, "entries": [
                  {"id": "a", "item": "minecraft:diamond", "weight": 1000000, "rarity": "rare"},
                  {"id": "b", "item": "minecraft:emerald", "weight": 1, "rarity": "rare"},
                  {"item": "minecraft:dirt", "weight": 1}
                ]}""");
        Map<String, Integer> miss = new LinkedHashMap<>();
        miss.put("a", 10);
        miss.put("b", 5);
        miss.put("ancienne", 7);
        CrateRoller.Roll roll = CrateRoller.roll(t, miss, THRESHOLD, RandomSource.create(3L));
        helper.assertTrue(roll.forced() == null && roll.drawn().equals(List.of(t.rares().get(0))), "A tirée naturellement");
        helper.assertTrue(roll.miss().equals(Map.of("a", 0, "b", 6)), "A remise à zéro, B +1, identifiant inconnu purgé : "
                + roll.miss());
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void openConsumesOneKeyAndGrants(GameTestHelper helper) {
        ServerPlayer p = player(helper, "sf_j4_caisse");
        try {
            BlockPos pos = crate(helper, CrateKind.COMMUNE);
            CrateTable t = table(helper, ITEMS_ONLY);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.KEY_COMMUNE.get(), 3));
            ServerLevel level = helper.getLevel();
            ActionResult result = CrateManager.open(p, level, pos, p.getMainHandItem(), k -> t, RandomSource.create(5L));
            helper.assertTrue(result.success(), "ouverture : " + result.message().getString());
            helper.assertTrue(p.getMainHandItem().is(ModItems.KEY_COMMUNE.get()) && p.getMainHandItem().getCount() == 2,
                    "une seule clé consommée");
            int bread = Inventories.countPlain(p, Items.BREAD);
            int coal = Inventories.countPlain(p, Items.COAL);
            int diamond = Inventories.countPlain(p, Items.DIAMOND);
            int granted = (bread == 5 ? 1 : 0) + (coal == 3 ? 1 : 0) + (diamond == 1 ? 1 : 0);
            helper.assertTrue(granted == 2 && bread + coal + diamond > 0, "2 tirages remis (pain " + bread + ", charbon " + coal
                    + ", diamant " + diamond + ")");
            helper.assertTrue(isOpen(helper, pos), "caisse ouverte pendant l'animation");
            Map<String, Integer> pity = CrateManager.pityOf(level.getServer(), p.getUUID(), CrateKind.COMMUNE);
            helper.assertTrue(pity.equals(Map.of("r", diamond == 1 ? 0 : 1)), "pitié mise à jour : " + pity);
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void wrongKeyRefusedKeyKept(GameTestHelper helper) {
        ServerPlayer p = player(helper, "sf_j4_mauvaise");
        try {
            BlockPos pos = crate(helper, CrateKind.COMMUNE);
            CrateTable t = table(helper, ITEMS_ONLY);
            ServerLevel level = helper.getLevel();
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.KEY_MILITAIRE.get(), 2));
            ActionResult wrong = CrateManager.open(p, level, pos, p.getMainHandItem(), k -> t, RandomSource.create(1L));
            helper.assertFalse(wrong.success(), "clé militaire sur une caisse commune : refusée");
            TranslatableContents why = translatable(wrong.message(), "reward.crate.error.wrong_key");
            helper.assertTrue(why != null && why.getArgs().length == 1
                            && why.getArgs()[0] instanceof Component named
                            && named.getContents() instanceof TranslatableContents item
                            && item.getKey().equals(ModItems.KEY_COMMUNE.get().getDescriptionId()),
                    "le message nomme la bonne clé : " + wrong.message().getString());
            helper.assertTrue(p.getMainHandItem().getCount() == 2 && !isOpen(helper, pos), "clé conservée, caisse fermée");
            helper.assertFalse(CrateManager.open(p, level, pos, ItemStack.EMPTY, k -> t, RandomSource.create(1L)).success(),
                    "sans clé : refus");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.KEY_COMMUNE.get(), 2));
            helper.assertFalse(CrateManager.open(p, level, pos, p.getMainHandItem(), k -> null, RandomSource.create(1L)).success()
                    || p.getMainHandItem().getCount() != 2, "table absente : caisse indisponible, clé conservée");
            helper.assertFalse(CrateManager.use(p, level, pos, p.getMainHandItem()).success(), "faux joueur (automatisation) refusé");
            helper.assertTrue(p.getMainHandItem().getCount() == 2 && !isOpen(helper, pos), "aucune clé consommée par les refus");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void crateBusyThenCloses(GameTestHelper helper) {
        ServerPlayer p = player(helper, "sf_j4_verrou");
        BlockPos pos = crate(helper, CrateKind.COMMUNE);
        CrateTable t = table(helper, ITEMS_ONLY);
        ServerLevel level = helper.getLevel();
        try {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.KEY_COMMUNE.get(), 3));
            helper.assertTrue(CrateManager.open(p, level, pos, p.getMainHandItem(), k -> t, RandomSource.create(9L)).success(),
                    "première ouverture");
            ActionResult busy = CrateManager.open(p, level, pos, p.getMainHandItem(), k -> t, RandomSource.create(9L));
            helper.assertFalse(busy.success(), "caisse en cours d'ouverture : refus");
            helper.assertTrue(p.getMainHandItem().getCount() == 2 && isOpen(helper, pos), "clé conservée pendant l'animation");
        } catch (RuntimeException e) {
            cleanup(helper, p);
            throw e;
        }
        helper.runAtTickTime(SFConfig.CRATE_OPEN_TICKS.get() + 5, () -> {
            try {
                helper.assertFalse(isOpen(helper, pos), "caisse refermée après l'animation");
                helper.assertTrue(CrateManager.open(p, level, pos, p.getMainHandItem(), k -> t, RandomSource.create(10L)).success()
                        && p.getMainHandItem().getCount() == 1, "de nouveau disponible");
            } finally {
                cleanup(helper, p);
            }
            helper.succeed();
        });
    }

    @GameTest(template = AREA)
    public static void fullInventoryDropsOwned(GameTestHelper helper) {
        ServerPlayer p = player(helper, "sf_j4_plein");
        List<ItemEntity> drops = List.of();
        try {
            BlockPos pos = crate(helper, CrateKind.COMMUNE);
            CrateTable t = table(helper, ITEMS_ONLY);
            var inv = p.getInventory();
            for (int i = 0; i < inv.items.size(); i++) {
                inv.items.set(i, new ItemStack(Items.STONE, 64));
            }
            inv.items.set(inv.selected, new ItemStack(ModItems.KEY_COMMUNE.get(), 2));
            ActionResult result = CrateManager.open(p, helper.getLevel(), pos, p.getMainHandItem(), k -> t, RandomSource.create(4L));
            helper.assertTrue(result.success() && p.getMainHandItem().getCount() == 1, "ouverture, une clé consommée");
            drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(4.0D),
                    e -> e.getItem().is(Items.BREAD) || e.getItem().is(Items.COAL) || e.getItem().is(Items.DIAMOND));
            int total = drops.stream().mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(drops.size() == 2, "deux gains au sol (" + drops.size() + ")");
            for (ItemEntity e : drops) {
                helper.assertTrue(p.getUUID().equals(e.getTarget()), "gain au sol réservé au joueur");
            }
            TranslatableContents dropped = translatable(result.message(), "reward.crate.dropped");
            helper.assertTrue(total >= 2 && dropped != null && Integer.valueOf(total).equals(dropped.getArgs()[0]),
                    "le résumé signale les " + total + " objets au sol : " + result.message().getString());
            helper.assertTrue(Inventories.countPlain(p, Items.STONE) == 35 * 64, "inventaire intact");
        } finally {
            drops.forEach(Entity::discard);
            cleanup(helper, p);
        }
        helper.succeed();
    }
}
