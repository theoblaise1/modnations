package io.github.theoblaise1.sovereignfrontier;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Configuration serveur (fichier {@code <monde>/serverconfig/sovereignfrontier-server.toml}).
 * Toutes les valeurs d'équilibrage sont ici pour pouvoir être ajustées sans recompiler.
 * <p>
 * La configuration SERVER n'est chargée qu'APRÈS le premier chargement des datapacks (et le premier
 * {@code TagsUpdatedEvent}) : toute validation de contenu au chargement utilise des constantes de code, jamais
 * ces valeurs, qui ne sont lues qu'à l'exécution.
 */
public final class SFConfig {
    public static final ModConfigSpec SPEC;

    // Économie
    public static final ModConfigSpec.LongValue STARTING_BALANCE;

    // Nation
    public static final ModConfigSpec.LongValue NATION_CREATION_COST;
    public static final ModConfigSpec.IntValue MAX_MEMBERS;
    public static final ModConfigSpec.IntValue INVITE_EXPIRY_MINUTES;

    // Territoire
    public static final ModConfigSpec.DoubleValue CLAIM_BASE_COST;
    public static final ModConfigSpec.DoubleValue CLAIM_COST_GROWTH;
    public static final ModConfigSpec.BooleanValue CLAIM_REQUIRE_CONTIGUOUS;
    public static final ModConfigSpec.DoubleValue UNCLAIM_REFUND_RATIO;
    public static final ModConfigSpec.IntValue SURFACE_SPAWN_Y_OFFSET;
    public static final ModConfigSpec.DoubleValue CROP_YIELD_BONUS;
    public static final ModConfigSpec.BooleanValue PROTECT_FROM_EXPLOSIONS;

    // Trésorerie et population
    public static final ModConfigSpec.IntValue TREASURY_REACH;
    public static final ModConfigSpec.IntValue INCOME_INTERVAL_TICKS;
    public static final ModConfigSpec.LongValue INCOME_PER_INHABITANT;
    public static final ModConfigSpec.IntValue MAX_BUILDING_VOLUME;
    public static final ModConfigSpec.IntValue FARM_MIN_FARMLAND;
    public static final ModConfigSpec.IntValue BUILDING_RECHECK_TICKS;

    // Recherche
    public static final ModConfigSpec.DoubleValue RESEARCH_CANCEL_REFUND;

    // Énergie et machines (J3)
    public static final ModConfigSpec.IntValue THERMAL_OUTPUT;
    public static final ModConfigSpec.IntValue SOLAR_OUTPUT;
    public static final ModConfigSpec.IntValue DRILL_ENERGY_PER_TICK;
    public static final ModConfigSpec.IntValue DRILL_LITERS_PER_CYCLE;
    public static final ModConfigSpec.LongValue GAS_PER_DIMENSION;
    public static final ModConfigSpec.IntValue REACTOR_OUTPUT_PER_CELL;
    public static final ModConfigSpec.IntValue REACTOR_HEAT_PER_CELL;
    public static final ModConfigSpec.IntValue REACTOR_DISSIPATION_PER_SINK;
    public static final ModConfigSpec.IntValue REACTOR_MAX_HEAT;
    public static final ModConfigSpec.IntValue REACTOR_FUEL_TICKS;
    public static final ModConfigSpec.DoubleValue REACTOR_EXPLOSION_POWER;

    // Terminal bancaire (J4)
    public static final ModConfigSpec.ConfigValue<List<? extends Number>> TERMINAL_HOURLY_RATES;
    public static final ModConfigSpec.IntValue TERMINAL_AFK_SECONDS;
    public static final ModConfigSpec.DoubleValue TERMINAL_LOOK_DEGREES;
    public static final ModConfigSpec.DoubleValue TERMINAL_WALK_BLOCKS;
    public static final ModConfigSpec.IntValue TERMINAL_WINDOW_HOURS;
    public static final ModConfigSpec.LongValue TERMINAL_MAX_PENDING;
    public static final ModConfigSpec.DoubleValue TERMINAL_SOUVERAIN_MULTIPLIER;

    // Récompenses : /daily et caisses (J4)
    public static final ModConfigSpec.IntValue DAILY_COOLDOWN_HOURS;
    public static final ModConfigSpec.IntValue DAILY_STREAK_RESET_HOURS;
    public static final ModConfigSpec.IntValue CRATE_PITY_THRESHOLD;
    public static final ModConfigSpec.IntValue CRATE_OPEN_TICKS;
    public static final ModConfigSpec.BooleanValue CRATE_ANNOUNCE_RARE;

    // Marchands et avant-postes (J4)
    public static final ModConfigSpec.IntValue MERCHANT_ROTATION_TICKS;
    public static final ModConfigSpec.IntValue MERCHANT_PRESSURE_STEP;
    public static final ModConfigSpec.DoubleValue MERCHANT_SUPPLY_DROP;
    public static final ModConfigSpec.DoubleValue MERCHANT_DEMAND_RISE;
    public static final ModConfigSpec.DoubleValue MERCHANT_PAYOUT_FLOOR;
    public static final ModConfigSpec.DoubleValue MERCHANT_PRICE_CEILING;
    public static final ModConfigSpec.DoubleValue MERCHANT_PRESSURE_DECAY;
    public static final ModConfigSpec.IntValue MERCHANT_TRADE_REACH;
    public static final ModConfigSpec.BooleanValue PROTECT_OUTPOSTS;

    // Contrats (J4)
    public static final ModConfigSpec.IntValue CONTRACT_REFRESH_HOURS;
    public static final ModConfigSpec.IntValue CONTRACT_BASE_OFFERS;
    public static final ModConfigSpec.IntValue CONTRACT_MAX_OFFERS;
    public static final ModConfigSpec.IntValue CONTRACT_BASE_ACTIVE;
    public static final ModConfigSpec.IntValue CONTRACT_MAX_ACTIVE;
    public static final ModConfigSpec.DoubleValue CONTRACT_SIZE_PER_EXTRA_MEMBER;
    public static final ModConfigSpec.DoubleValue CONTRACT_SIZE_CAP;
    public static final ModConfigSpec.DoubleValue CONTRACT_MONEY_PER_TIER;
    public static final ModConfigSpec.DoubleValue CONTRACT_CONTRIBUTOR_SHARE;
    public static final ModConfigSpec.IntValue CONTRACT_MIN_NATION_AGE_HOURS;
    public static final ModConfigSpec.IntValue CONTRACT_MAINTENANCE_TICKS;
    public static final ModConfigSpec.IntValue CONTRACT_EXPLORE_CHECK_TICKS;
    public static final ModConfigSpec.IntValue CONTRACT_HISTORY_SIZE;

    /** Barème par défaut du terminal (cr par heure active ; la dernière valeur se répète). */
    private static final List<Double> DEFAULT_TERMINAL_RATES = List.of(240.0D, 180.0D, 135.0D, 100.0D, 75.0D, 55.0D, 40.0D,
            30.0D, 20.0D);

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("economy");
        STARTING_BALANCE = b.comment("Solde personnel accordé à la première connexion d'un joueur.")
                .defineInRange("startingBalance", 1000L, 0L, Long.MAX_VALUE);
        b.pop();

        b.push("nation");
        NATION_CREATION_COST = b.comment("Coût de fondation d'une nation, prélevé sur le solde personnel.")
                .defineInRange("creationCost", 0L, 0L, Long.MAX_VALUE);
        MAX_MEMBERS = b.comment("Nombre maximal de membres par nation.")
                .defineInRange("maxMembers", 10, 1, 100);
        INVITE_EXPIRY_MINUTES = b.comment("Durée de validité d'une invitation (minutes réelles).")
                .defineInRange("inviteExpiryMinutes", 1440, 1, 10080);
        b.pop();

        b.push("territory");
        CLAIM_BASE_COST = b.comment("Coût de base d'un chunk : coût = base * croissance^(chunks déjà revendiqués).")
                .defineInRange("claimBaseCost", 100.0D, 0.0D, 1.0E9D);
        CLAIM_COST_GROWTH = b.comment("Facteur de croissance du coût de revendication.")
                .defineInRange("claimCostGrowth", 1.08D, 1.0D, 10.0D);
        CLAIM_REQUIRE_CONTIGUOUS = b.comment("Exiger qu'un nouveau chunk touche (4-voisinage) un chunk déjà revendiqué dans la même dimension.")
                .define("requireContiguous", true);
        UNCLAIM_REFUND_RATIO = b.comment("Part du coût du dernier palier remboursée lors d'un abandon (0 = rien).")
                .defineInRange("unclaimRefundRatio", 0.0D, 0.0D, 1.0D);
        SURFACE_SPAWN_Y_OFFSET = b.comment("Les monstres ne peuvent pas apparaître naturellement en territoire revendiqué au-dessus de (niveau de la mer + décalage).")
                .defineInRange("surfaceSpawnYOffset", -8, -128, 128);
        CROP_YIELD_BONUS = b.comment("Bonus de rendement des cultures mûres récoltées en territoire revendiqué (0.05 = +5 %).")
                .defineInRange("cropYieldBonus", 0.05D, 0.0D, 10.0D);
        PROTECT_FROM_EXPLOSIONS = b.comment("Les explosions ne détruisent pas de blocs en territoire revendiqué.")
                .define("protectFromExplosions", true);
        b.pop();

        b.push("treasury");
        TREASURY_REACH = b.comment("Distance maximale (blocs) au Trésor national pour déposer/retirer.")
                .defineInRange("treasuryReach", 8, 2, 64);
        INCOME_INTERVAL_TICKS = b.comment("Intervalle de versement du revenu de population (ticks, compteur persistant).")
                .defineInRange("incomeIntervalTicks", 6000, 200, 1728000);
        INCOME_PER_INHABITANT = b.comment("Revenu versé à la trésorerie par habitant et par cycle.")
                .defineInRange("incomePerInhabitant", 5L, 0L, 1000000L);
        MAX_BUILDING_VOLUME = b.comment("Volume intérieur maximal (blocs d'air) d'un bâtiment civil clos.")
                .defineInRange("maxBuildingVolume", 512, 8, 4096);
        FARM_MIN_FARMLAND = b.comment("Nombre minimal de blocs de terre labourée dans un rayon de 4 blocs pour une ferme.")
                .defineInRange("farmMinFarmland", 16, 1, 81);
        BUILDING_RECHECK_TICKS = b.comment("Intervalle entre deux revalidations (une seule par passage, à tour de rôle).")
                .defineInRange("buildingRecheckTicks", 100, 20, 72000);
        b.pop();

        b.push("energy");
        THERMAL_OUTPUT = b.comment("Production du générateur thermique pendant la combustion (En/tick).")
                .defineInRange("thermalOutput", 20, 1, 100000);
        SOLAR_OUTPUT = b.comment("Production du panneau solaire de jour, ciel dégagé (En/tick).")
                .defineInRange("solarOutput", 8, 1, 100000);
        DRILL_ENERGY_PER_TICK = b.comment("Consommation de la foreuse à gaz (En/tick).")
                .defineInRange("drillEnergyPerTick", 40, 1, 100000);
        DRILL_LITERS_PER_CYCLE = b.comment("Gaz extrait par la foreuse toutes les 20 ticks (litres).")
                .defineInRange("drillLitersPerCycle", 10, 1, 100000);
        GAS_PER_DIMENSION = b.comment("Réserve de gaz FINIE de chaque dimension (litres), décomptée globalement.")
                .defineInRange("gasPerDimension", 200000L, 0L, Long.MAX_VALUE);
        REACTOR_OUTPUT_PER_CELL = b.comment("Production du réacteur par cellule de combustible (En/tick).")
                .defineInRange("reactorOutputPerCell", 120, 1, 1000000);
        REACTOR_HEAT_PER_CELL = b.comment("Chaleur produite par cellule de combustible et par tick.")
                .defineInRange("reactorHeatPerCell", 4, 0, 10000);
        REACTOR_DISSIPATION_PER_SINK = b.comment("Chaleur évacuée par dissipateur thermique et par tick.")
                .defineInRange("reactorDissipationPerSink", 3, 0, 10000);
        REACTOR_MAX_HEAT = b.comment("Chaleur à laquelle le réacteur explose.")
                .defineInRange("reactorMaxHeat", 10000, 100, 10000000);
        REACTOR_FUEL_TICKS = b.comment("Durée d'un combustible enrichi (ticks de fonctionnement du réacteur).")
                .defineInRange("reactorFuelTicks", 6000, 20, 10000000);
        REACTOR_EXPLOSION_POWER = b.comment("Puissance de l'explosion d'un réacteur en surchauffe (TNT = 4).")
                .defineInRange("reactorExplosionPower", 6.0D, 0.0D, 50.0D);
        b.pop();

        b.push("research");
        RESEARCH_CANCEL_REFUND = b.comment("Part du coût en trésorerie rendue quand une recherche est annulée (les objets sont perdus).")
                .defineInRange("researchCancelRefund", 0.5D, 0.0D, 1.0D);
        b.pop();

        b.push("terminal");
        TERMINAL_HOURLY_RATES = b.comment("Gain du Terminal bancaire (cr) par heure ACTIVE dans la fenêtre : 1re heure, 2e heure…",
                        "La dernière valeur s'applique à toutes les heures suivantes (1 à 24 valeurs, chacune de 0 à 1 000 000).")
                .defineList(List.of("terminalHourlyRates"), () -> DEFAULT_TERMINAL_RATES, () -> 20.0D,
                        o -> o instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 0.0D
                                && n.doubleValue() <= 1.0E6D,
                        ModConfigSpec.Range.of(1, 24));
        TERMINAL_AFK_SECONDS = b.comment("Secondes sans signal d'activité (regard, marche, casse/pose, chat, commande, conteneur, artisanat) avant la pause des gains.")
                .defineInRange("terminalAfkSeconds", 180, 30, 3600);
        TERMINAL_LOOK_DEGREES = b.comment("Rotation du regard (degrés, lacet + tangage) comptée comme activité entre deux échantillons (1 s).")
                .defineInRange("terminalLookDegrees", 10.0D, 1.0D, 180.0D);
        TERMINAL_WALK_BLOCKS = b.comment("Déplacement horizontal au sol (blocs) compté comme activité entre deux échantillons (1 s).")
                .defineInRange("terminalWalkBlocks", 1.0D, 0.2D, 16.0D);
        TERMINAL_WINDOW_HOURS = b.comment("Durée de la fenêtre du barème dégressif (heures réelles) ; le barème repart à la 1re heure ensuite.")
                .defineInRange("terminalWindowHours", 24, 1, 168);
        TERMINAL_MAX_PENDING = b.comment("Épargne maximale non encaissée (cr) ; au-delà, l'accumulation s'arrête.")
                .defineInRange("terminalMaxPending", 3000L, 0L, 1_000_000_000L);
        TERMINAL_SOUVERAIN_MULTIPLIER = b.comment("Multiplicateur du Terminal pour le grade Souverain (lu à partir du jalon J8).")
                .defineInRange("terminalSouverainMultiplier", 1.25D, 1.0D, 10.0D);
        b.pop();

        b.push("rewards");
        DAILY_COOLDOWN_HOURS = b.comment("Délai entre deux /daily (heures réelles).")
                .defineInRange("dailyCooldownHours", 24, 1, 168);
        DAILY_STREAK_RESET_HOURS = b.comment("Au-delà de ce délai depuis le dernier /daily, la série repart au jour 1 (au moins délai + 1 h).")
                .defineInRange("dailyStreakResetHours", 48, 2, 336);
        CRATE_PITY_THRESHOLD = b.comment("Ouvertures sans une récompense rare donnée après lesquelles elle est garantie à l'ouverture suivante.")
                .defineInRange("cratePityThreshold", 20, 1, 1000);
        CRATE_OPEN_TICKS = b.comment("Durée de l'animation d'ouverture d'une caisse (ticks) ; la caisse est indisponible pendant ce temps.")
                .defineInRange("crateOpenTicks", 40, 10, 200);
        CRATE_ANNOUNCE_RARE = b.comment("Annoncer à tout le serveur les gains rares tirés des caisses.")
                .define("crateAnnounceRare", true);
        b.pop();

        b.push("market");
        MERCHANT_ROTATION_TICKS = b.comment("Durée d'une rotation de l'étal des marchands (ticks de jeu, compteur persistant).")
                .defineInRange("merchantRotationTicks", 24000, 1200, 1728000);
        MERCHANT_PRESSURE_STEP = b.comment("Nombre de lots qui applique une fois la hausse de prix ou la baisse de rachat.")
                .defineInRange("merchantPressureStep", 20, 1, 1000);
        MERCHANT_SUPPLY_DROP = b.comment("Baisse du rachat par pas de lots vendus (0.30 = ×0,70).")
                .defineInRange("merchantSupplyDrop", 0.30D, 0.0D, 0.95D);
        MERCHANT_DEMAND_RISE = b.comment("Hausse du prix par pas de lots achetés (0.30 = ×1,30).")
                .defineInRange("merchantDemandRise", 0.30D, 0.0D, 5.0D);
        MERCHANT_PAYOUT_FLOOR = b.comment("Rachat minimal, en part du rachat de base.")
                .defineInRange("merchantPayoutFloor", 0.25D, 0.0D, 1.0D);
        MERCHANT_PRICE_CEILING = b.comment("Prix maximal, en multiple du prix de base.")
                .defineInRange("merchantPriceCeiling", 2.0D, 1.0D, 10.0D);
        MERCHANT_PRESSURE_DECAY = b.comment("Facteur appliqué à la pression d'achat et de vente à chaque rotation.")
                .defineInRange("merchantPressureDecay", 0.5D, 0.0D, 1.0D);
        MERCHANT_TRADE_REACH = b.comment("Distance maximale (blocs) au comptoir pour commercer.")
                .defineInRange("merchantTradeReach", 8, 2, 32);
        PROTECT_OUTPOSTS = b.comment("Protéger les avant-postes : casse et pose interdites aux joueurs en survie, explosions sans dégâts.")
                .define("protectOutposts", true);
        b.pop();

        b.push("contracts");
        CONTRACT_REFRESH_HOURS = b.comment("Intervalle de rafraîchissement des offres non acceptées du tableau (heures réelles).")
                .defineInRange("contractRefreshHours", 24, 1, 168);
        CONTRACT_BASE_OFFERS = b.comment("Offres du tableau : base + ceil(membres / 2), au plus contractMaxOffers.")
                .defineInRange("contractBaseOffers", 3, 0, 10);
        CONTRACT_MAX_OFFERS = b.comment("Nombre maximal d'offres du tableau.")
                .defineInRange("contractMaxOffers", 8, 1, 12);
        CONTRACT_BASE_ACTIVE = b.comment("Contrats en cours simultanés : base + ceil(membres / 3), au plus contractMaxActive.")
                .defineInRange("contractBaseActive", 1, 0, 10);
        CONTRACT_MAX_ACTIVE = b.comment("Nombre maximal de contrats en cours simultanés.")
                .defineInRange("contractMaxActive", 4, 1, 10);
        CONTRACT_SIZE_PER_EXTRA_MEMBER = b.comment("Taille des objectifs : × (1 + valeur × (membres - 1)), plafonnée par contractSizeCap.")
                .defineInRange("contractSizePerExtraMember", 0.2D, 0.0D, 2.0D);
        CONTRACT_SIZE_CAP = b.comment("Multiplicateur maximal de la taille des objectifs.")
                .defineInRange("contractSizeCap", 2.5D, 1.0D, 10.0D);
        CONTRACT_MONEY_PER_TIER = b.comment("Bonus de la récompense de base par palier de recherche terminé (0.02 = +2 %).")
                .defineInRange("contractMoneyPerTier", 0.02D, 0.0D, 0.2D);
        CONTRACT_CONTRIBUTOR_SHARE = b.comment("Part de la récompense en argent répartie entre les contributeurs (le reste va à la trésorerie).")
                .defineInRange("contractContributorShare", 0.5D, 0.0D, 1.0D);
        CONTRACT_MIN_NATION_AGE_HOURS = b.comment("Âge minimal d'une nation (heures réelles) avant que son tableau de contrats soit généré.")
                .defineInRange("contractMinNationAgeHours", 12, 0, 168);
        CONTRACT_MAINTENANCE_TICKS = b.comment("Intervalle de l'entretien des contrats : échéances et rafraîchissements (ticks).")
                .defineInRange("contractMaintenanceTicks", 1200, 20, 72000);
        CONTRACT_EXPLORE_CHECK_TICKS = b.comment("Intervalle de vérification des contrats d'exploration par joueur (ticks).")
                .defineInRange("contractExploreCheckTicks", 40, 10, 1200);
        CONTRACT_HISTORY_SIZE = b.comment("Nombre de contrats terminés conservés dans l'historique de chaque nation.")
                .defineInRange("contractHistorySize", 10, 0, 50);
        b.pop();

        SPEC = b.build();
    }

    private SFConfig() {
    }

    /** Barème du terminal (cr par heure active), toujours au moins une valeur, chacune finie et positive. */
    public static double[] terminalHourlyRates() {
        List<? extends Number> values = TERMINAL_HOURLY_RATES.get();
        if (values.isEmpty()) {
            return DEFAULT_TERMINAL_RATES.stream().mapToDouble(Double::doubleValue).toArray();
        }
        double[] rates = new double[values.size()];
        for (int i = 0; i < rates.length; i++) {
            double v = values.get(i).doubleValue();
            rates[i] = Double.isFinite(v) ? Math.max(0.0D, v) : 0.0D;
        }
        return rates;
    }

    /** Délai de réinitialisation de la série /daily réellement appliqué : au moins le délai entre deux /daily + 1 h. */
    public static int effectiveDailyStreakResetHours() {
        return Math.max(DAILY_STREAK_RESET_HOURS.get(), DAILY_COOLDOWN_HOURS.get() + 1);
    }
}
