package io.github.theoblaise1.sovereignfrontier;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration serveur (fichier {@code <monde>/serverconfig/sovereignfrontier-server.toml}).
 * Toutes les valeurs d'équilibrage sont ici pour pouvoir être ajustées sans recompiler.
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

        b.push("research");
        RESEARCH_CANCEL_REFUND = b.comment("Part du coût en trésorerie rendue quand une recherche est annulée (les objets sont perdus).")
                .defineInRange("researchCancelRefund", 0.5D, 0.0D, 1.0D);
        b.pop();

        SPEC = b.build();
    }

    private SFConfig() {
    }
}
