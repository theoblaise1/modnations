// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.enterprise;

import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.network.EconomyNetwork;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDomain;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchStat;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Entreprises nationales (docs/conception/J4.md §1.7) : affectation absolue des habitants aux secteurs et production
 * à chaque cycle de revenu.
 * <ul>
 *   <li>Les habitants affectés quittent le revenu passif ({@code NationManager.freeInhabitants}) et produisent à la
 *       place, dans le même {@code tickIncome} : production et revenu sont évalués au même instant ;</li>
 *   <li>multiplicateur de secteur : {@code 1 + bonus_per_tier × paliers terminés du domaine} ;</li>
 *   <li>argent : {@code round(w × money_per_worker × mult × (1 + income_multiplier))}, versé à la trésorerie ;</li>
 *   <li>objets, sans hasard : {@code a = w × per_worker × mult + frac[objet]}, {@code floor(a)} au coffre national,
 *       le reste attend le cycle suivant ; ce qui ne rentre pas est perdu et annoncé.</li>
 * </ul>
 * Aucun parcours de nations ici : {@link #produceCycle} est appelé par {@code NationManager.tickIncome} pour chaque
 * nation, une fois par cycle.
 */
public final class EnterpriseManager {
    /** Tolérance d'arrondi : 0,1 + 0,2 + … ne doit pas manquer une unité pour 1e-16. */
    private static final double EPSILON = 1e-9D;

    private EnterpriseManager() {
    }

    /** Bilan d'un cycle de production pour une nation. */
    public record CycleResult(long money, int items, int lost, int trimmed) {
        public static final CycleResult NONE = new CycleResult(0L, 0, 0, 0);
    }

    // ------------------------------------------------------------------ calculs

    /** Paliers terminés du domaine (connus du registre de recherche courant). */
    public static int domainTiers(Nation nation, ResearchDomain domain) {
        ResearchRegistry registry = ResearchRegistry.server();
        int n = 0;
        for (ResourceLocation id : nation.research().completed()) {
            ResearchTier tier = registry.get(id);
            if (tier != null && tier.domain() == domain) {
                n++;
            }
        }
        return n;
    }

    /** Multiplicateur de secteur : {@code 1 + bonus_per_tier × paliers terminés du domaine}. */
    public static double multiplier(Nation nation, SectorDefinition def) {
        return 1.0D + def.bonusPerTier() * domainTiers(nation, def.domain());
    }

    /** {@code 1 + income_multiplier} (bonus de recherche), comme le revenu passif. */
    public static double incomeMultiplier(Nation nation) {
        return 1.0D + Math.max(-1.0D, nation.research().bonus(ResearchStat.INCOME_MULTIPLIER));
    }

    /** Premier palier requis non terminé, ou {@code null} si tout est terminé. */
    @Nullable
    public static ResourceLocation firstMissing(Nation nation, List<ResourceLocation> requires) {
        for (ResourceLocation id : requires) {
            if (!nation.research().isCompleted(id)) {
                return id;
            }
        }
        return null;
    }

    /** Argent d'un cycle : {@code round(w × money_per_worker × mult × multiplicateur de revenu)}. */
    public static long money(SectorDefinition def, int workers, double mult, double incomeMult) {
        double raw = workers * (double) def.moneyPerWorker() * mult * incomeMult;
        return raw > 0.0D ? Math.round(raw) : 0L;
    }

    /** Habitants libres (ni affectés, ni en surplus) : borne d'une hausse d'affectation. */
    public static int freeInhabitants(Nation nation) {
        return NationManager.freeInhabitants(nation);
    }

    /** Affectation maximale autorisée pour ce secteur : actuels + libres (bornée). */
    public static int maxAssignable(Nation nation, Sector sector) {
        long max = (long) nation.enterprises().workers(sector) + freeInhabitants(nation);
        return (int) Math.min(NationEnterprises.MAX_WORKERS, max);
    }

    /** Nom affiché d'un palier (identifiant brut s'il est inconnu du registre). */
    public static Component researchName(ResourceLocation id) {
        ResearchTier tier = ResearchRegistry.server().get(id);
        return tier == null ? Component.literal(id.toString()) : tier.displayName();
    }

    /** Quantité lisible à la française, 3 décimales au plus : 1 → « 1 », 0,25 → « 0,25 », 1,08 → « 1,08 ». */
    public static String formatAmount(double value) {
        if (!Double.isFinite(value)) {
            return "?";
        }
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        if (rounded.signum() == 0) {
            return "0";
        }
        return rounded.toPlainString().replace('.', ',');
    }

    // ------------------------------------------------------------------ affectation

    /** Affectation absolue de {@code workers} habitants à {@code sector} (permission {@code economie}). */
    public static ActionResult assign(ServerPlayer player, Sector sector, int workers) {
        MinecraftServer server = player.server;
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(player.getUUID(), NationPermission.ECONOMY)) {
            return ActionResult.fail(Msg.err("nation.error.no_permission", NationPermission.ECONOMY.displayName()));
        }
        if (workers < 0 || workers > NationEnterprises.MAX_WORKERS) {
            return ActionResult.fail(Msg.err("enterprise.error.range", NationEnterprises.MAX_WORKERS));
        }
        NationEnterprises enterprises = nation.enterprises();
        int current = enterprises.workers(sector);
        if (workers == current) {
            return ActionResult.fail(Msg.warn("enterprise.error.unchanged", sector.displayName(), current));
        }
        // Une baisse est toujours permise (secteur verrouillé ou indisponible compris) : elle libère des habitants.
        if (workers > current) {
            SectorDefinition def = SectorRegistry.get().get(sector);
            if (def == null) {
                return ActionResult.fail(Msg.err("enterprise.error.unavailable", sector.displayName()));
            }
            ResourceLocation missing = firstMissing(nation, def.requires());
            if (missing != null) {
                return ActionResult.fail(Msg.err("enterprise.error.locked", sector.displayName(), researchName(missing)));
            }
            int free = freeInhabitants(nation);
            if (workers - current > free) {
                return ActionResult.fail(Msg.err("enterprise.error.population", workers - current, free));
            }
        }
        enterprises.setWorkers(sector, workers);
        nation.markDirty();
        nation.log("enterprise_assign", player.getGameProfile().getName(), String.valueOf(workers),
                "@enterprise.sector." + sector.id(), String.valueOf(current));
        // Revenu passif affiché (Aperçu, Trésor) et vue Économie des membres : envois groupés.
        NationSync.scheduleRefresh(nation);
        EconomyNetwork.markDirty(nation);
        return ActionResult.ok(Msg.ok("enterprise.assigned", sector.displayName(), workers, freeInhabitants(nation),
                nation.effectivePopulation()));
    }

    // ------------------------------------------------------------------ cycle de production

    /**
     * Production d'un cycle, appelée par {@code NationManager.tickIncome} pour chaque nation, juste AVANT le calcul
     * du revenu passif (habitants libres) : revenu et production sont évalués au même instant.
     */
    public static void produceCycle(MinecraftServer server, Nation nation) {
        runCycle(server, nation, SectorRegistry.get());
    }

    /**
     * Un cycle complet avec le registre donné (publié en jeu ; construit à la main par les GameTests) : ajustement à
     * la population, argent, objets, bilan, annonces.
     */
    public static CycleResult runCycle(MinecraftServer server, Nation nation, SectorRegistry registry) {
        NationEnterprises enterprises = nation.enterprises();
        if (enterprises.isIdle()) {
            return CycleResult.NONE;
        }
        int trimmed = trim(enterprises, nation.effectivePopulation());
        double incomeMult = incomeMultiplier(nation);
        long totalMoney = 0L;
        long totalItems = 0L;
        long totalLost = 0L;
        for (Sector sector : Sector.values()) {
            int w = enterprises.workers(sector);
            SectorDefinition def = registry.get(sector);
            long money = 0L;
            long items = 0L;
            long lost = 0L;
            // Secteur indisponible ou verrouillé (recherche retirée) : ses habitants ne produisent rien.
            if (w > 0 && def != null && firstMissing(nation, def.requires()) == null) {
                double mult = multiplier(nation, def);
                money = money(def, w, mult, incomeMult);
                for (SectorDefinition.Product product : def.products()) {
                    if (firstMissing(nation, product.requires()) != null) {
                        continue;
                    }
                    long[] result = produce(nation, sector, product, w, mult);
                    items += result[0];
                    lost += result[1];
                }
            }
            enterprises.setLast(sector, money, clamp(items), clamp(lost));
            totalMoney += money;
            totalItems += items;
            totalLost += lost;
        }
        enterprises.setLast(totalMoney, clamp(totalItems), clamp(totalLost));
        nation.markDirty();
        if (totalMoney > 0L) {
            NationManager.adjustTreasury(nation, totalMoney);
            NationSync.scheduleRefresh(nation);
        }
        if (trimmed > 0) {
            nation.log("enterprise_trim", String.valueOf(trimmed));
            NationManager.broadcast(server, nation, prefix(nation).append(Msg.warn("enterprise.trimmed", trimmed)));
        }
        if (totalMoney > 0L || totalItems > 0L) {
            NationManager.broadcast(server, nation, prefix(nation).append(Msg.info("enterprise.produced",
                    Money.format(totalMoney), totalItems)));
        }
        if (totalLost > 0L) {
            NationManager.broadcast(server, nation, prefix(nation).append(Msg.err("enterprise.chest_full", totalLost)));
        }
        EconomyNetwork.markDirty(nation);
        return new CycleResult(totalMoney, clamp(totalItems), clamp(totalLost), trimmed);
    }

    /**
     * Production déterministe d'un objet : {@code floor(w × per_worker × mult + frac)} au coffre national, le reste
     * gardé en fraction (partagée par les secteurs qui produisent le même objet).
     *
     * @return {déposés, perdus}
     */
    private static long[] produce(Nation nation, Sector sector, SectorDefinition.Product product, int workers, double mult) {
        NationEnterprises enterprises = nation.enterprises();
        String key = BuiltInRegistries.ITEM.getKey(product.item()).toString();
        double total = workers * product.perWorker() * mult + enterprises.fraction(key);
        long whole = (long) Math.floor(total + EPSILON);
        // Négatif d'un epsilon quand total vaut presque un entier : la fraction est alors effacée.
        enterprises.setFraction(key, total - whole);
        if (whole <= 0L) {
            return new long[]{0L, 0L};
        }
        if (!RestrictedItems.allowInLoot(new ItemStack(product.item()), "entreprise " + sector.id() + " de " + nation.name())) {
            return new long[]{0L, 0L};
        }
        long added = deposit(nation.chest(), product.item(), whole);
        return new long[]{added, whole - added};
    }

    /** Dépose {@code count} objets dans le coffre, pile par pile, jusqu'à ce qu'il refuse ; renvoie le nombre déposé. */
    static long deposit(SimpleContainer chest, Item item, long count) {
        int max = Math.max(1, item.getDefaultMaxStackSize());
        long remaining = count;
        while (remaining > 0L) {
            int portion = (int) Math.min(max, remaining);
            ItemStack rest = chest.addItem(new ItemStack(item, portion));
            remaining -= portion - rest.getCount();
            if (!rest.isEmpty()) {
                break;
            }
        }
        return count - remaining;
    }

    /**
     * Ajustement au début du cycle : tant que les affectés dépassent la population effective, retire un habitant au
     * secteur qui en a le plus (à égalité, l'ordinal le plus élevé).
     *
     * @return habitants retirés
     */
    public static int trim(NationEnterprises enterprises, int population) {
        int excess = enterprises.assignedTotal() - Math.max(0, population);
        int removed = 0;
        while (excess > 0) {
            Sector most = null;
            for (Sector s : Sector.values()) {
                if (most == null || enterprises.workers(s) >= enterprises.workers(most)) {
                    most = s;
                }
            }
            if (most == null || enterprises.workers(most) <= 0) {
                break;
            }
            enterprises.setWorkers(most, enterprises.workers(most) - 1);
            excess--;
            removed++;
        }
        return removed;
    }

    private static int clamp(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    private static MutableComponent prefix(Nation nation) {
        return Component.literal("[" + nation.name() + "] ").withStyle(ChatFormatting.AQUA);
    }

    // ------------------------------------------------------------------ vue

    /** Partie « entreprises » de la vue Économie de ce joueur (membre de {@code nation}). */
    public static EconomyView.EnterprisesView view(ServerPlayer player, Nation nation) {
        return view(player.server, nation, SectorRegistry.get());
    }

    /**
     * Vue des entreprises. Pour un secteur sans habitant affecté, l'argent et les quantités sont donnés pour UN
     * habitant (aperçu) : le client le sait car {@code workers == 0}.
     */
    public static EconomyView.EnterprisesView view(MinecraftServer server, Nation nation, SectorRegistry registry) {
        NationEnterprises enterprises = nation.enterprises();
        double incomeMult = incomeMultiplier(nation);
        List<EconomyView.SectorLine> lines = new ArrayList<>(Sector.values().length);
        for (Sector sector : Sector.values()) {
            byte ordinal = (byte) sector.ordinal();
            int w = enterprises.workers(sector);
            int basis = Math.max(1, w);
            long lastMoney = enterprises.lastMoney(sector);
            int lastItems = enterprises.lastItems(sector);
            int lastLost = enterprises.lastLost(sector);
            SectorDefinition def = registry.get(sector);
            if (def == null) {
                lines.add(new EconomyView.SectorLine(ordinal, false, Msg.tr("enterprise.unavailable"), w, 1.0F, 0L,
                        List.of(), lastMoney, lastItems, lastLost));
                continue;
            }
            ResourceLocation missing = firstMissing(nation, def.requires());
            Component reason = missing == null ? Component.empty() : Msg.tr("enterprise.locked_by", researchName(missing));
            double mult = multiplier(nation, def);
            List<EconomyView.OutputLine> outputs = new ArrayList<>(def.products().size());
            for (SectorDefinition.Product product : def.products()) {
                outputs.add(new EconomyView.OutputLine(new ItemStack(product.item()),
                        (float) (basis * product.perWorker() * mult), firstMissing(nation, product.requires()) != null));
            }
            lines.add(new EconomyView.SectorLine(ordinal, missing == null, reason, w, (float) mult,
                    money(def, basis, mult, incomeMult), outputs, lastMoney, lastItems, lastLost));
        }
        return new EconomyView.EnterprisesView(nation.effectivePopulation(), enterprises.assignedTotal(),
                NationManager.ticksToNextIncome(server), NationManager.incomePerCycle(nation), lines);
    }
}
