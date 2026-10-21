// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.contract;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.network.ContractActionC2S;
import io.github.theoblaise1.sovereignfrontier.network.EconomyNetwork;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostRules;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKeys;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import io.github.theoblaise1.sovereignfrontier.util.WeightedPicker;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Contrats nationaux (docs/conception/J4.md §1.6) : génération du tableau, acceptation, livraison, progression (chasse,
 * exploration), réussite, échéances et rafraîchissements.
 * <ul>
 *   <li>tableau : {@code min(8, 3 + ceil(m/2))} offres et {@code min(4, 1 + ceil(m/3))} contrats en cours, généré
 *       seulement après 12 h d'existence de la nation ; offres non acceptées remplacées toutes les 24 h (epoch) ;</li>
 *   <li>progression sans aucun parcours par tick : livraison sur commande, chasse sur {@code LivingDeathEvent} (O(1)),
 *       exploration toutes les 40 ticks par joueur sur la liste en cache des contrats d'exploration ;</li>
 *   <li>réussite (transition unique d'abord) : 50 % de l'argent aux contributeurs encore membres, au prorata, le reste à
 *       la trésorerie ; points de recherche ; clés et objets au plus gros contributeur ou au coffre national ;</li>
 *   <li>entretien toutes les 1200 ticks (échéances, rafraîchissements), aussi recalculé à chaque lecture.</li>
 * </ul>
 * Les méthodes qui prennent {@code now} (epoch, ms) permettent d'injecter le temps (GameTests, administration).
 */
public final class ContractManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final long HOUR_MS = 3_600_000L;
    /** Au plus 2 offres du même type par génération. */
    public static final int MAX_OFFERS_PER_KIND = 2;
    /** Distance maximale à l'ancre d'un avant-poste pour le compter comme visité. */
    public static final int OUTPOST_VISIT_RADIUS = 16;
    /** Types d'apparition comptés par les contrats de chasse (cages, œufs, commandes, reproduction… exclus). */
    public static final Set<MobSpawnType> COUNTED_SPAWNS = EnumSet.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION,
            MobSpawnType.STRUCTURE, MobSpawnType.PATROL, MobSpawnType.EVENT, MobSpawnType.JOCKEY);
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private ContractManager() {
    }

    // ------------------------------------------------------------------ tailles

    /** Emplacements d'offres : {@code min(contractMaxOffers, contractBaseOffers + ceil(m/2))}. */
    public static int offerSlots(int members) {
        int m = Math.max(1, members);
        return Math.max(0, Math.min(SFConfig.CONTRACT_MAX_OFFERS.get(), SFConfig.CONTRACT_BASE_OFFERS.get() + ceilDiv(m, 2)));
    }

    /** Contrats en cours simultanés : {@code min(contractMaxActive, contractBaseActive + ceil(m/3))}. */
    public static int maxActive(int members) {
        int m = Math.max(1, members);
        return Math.max(0, Math.min(SFConfig.CONTRACT_MAX_ACTIVE.get(), SFConfig.CONTRACT_BASE_ACTIVE.get() + ceilDiv(m, 3)));
    }

    /** Taille des objectifs : {@code min(2,5 ; 1 + 0,2 × (m − 1))}. */
    public static double sizeFactor(int members) {
        return Math.min(SFConfig.CONTRACT_SIZE_CAP.get(),
                1.0D + SFConfig.CONTRACT_SIZE_PER_EXTRA_MEMBER.get() * (Math.max(1, members) - 1));
    }

    /** {@code arrondiAuPas(quantité × facteur)}, jamais sous 1. */
    public static int scale(ContractObjective.Amount amount, int sampled, double factor) {
        double scaled = sampled * factor;
        long steps = Math.round(scaled / amount.step());
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, steps * amount.step()));
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** La nation existe depuis {@code contractMinNationAgeHours} : son tableau peut être généré. */
    public static boolean isOldEnough(Nation nation, long now) {
        return now - nation.createdEpochMs() >= SFConfig.CONTRACT_MIN_NATION_AGE_HOURS.get() * HOUR_MS;
    }

    /** Temps restant avant l'ouverture du tableau d'une nation trop jeune (0 si elle est assez ancienne). */
    public static long timeUntilOpen(Nation nation, long now) {
        return Math.max(0L, nation.createdEpochMs() + SFConfig.CONTRACT_MIN_NATION_AGE_HOURS.get() * HOUR_MS - now);
    }

    private static int historySize() {
        return SFConfig.CONTRACT_HISTORY_SIZE.get();
    }

    // ------------------------------------------------------------------ génération

    /** Graine d'une génération : {@code nationId ^ serial · 0x9E37…}. */
    public static long seed(UUID nation, int serial) {
        return nation.getMostSignificantBits() ^ nation.getLeastSignificantBits() ^ (serial * GOLDEN);
    }

    /** Modèles éligibles : prérequis terminés, population suffisante, pas déjà offerts ni en cours. */
    public static List<ContractTemplate> eligible(Nation nation, ContractRegistry registry) {
        int population = nation.effectivePopulation();
        List<ContractTemplate> out = new ArrayList<>();
        for (ContractTemplate t : registry.all()) {
            if (t.minPopulation() > population || nation.contracts().usesTemplate(t.id())) {
                continue;
            }
            boolean ready = true;
            for (ResourceLocation req : t.requires()) {
                if (!nation.research().isCompleted(req)) {
                    ready = false;
                    break;
                }
            }
            if (ready) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Tirage pondéré de {@code slots} offres au plus (sans remise, 2 par type au plus), toutes instanciées : quantité,
     * argent, points et clés sont fixés ici et ne changeront plus.
     */
    public static List<ContractInstance> roll(Nation nation, ContractRegistry registry, int slots, long seed, long now,
                                              IntSupplier ids) {
        RandomSource random = RandomSource.create(seed);
        List<ContractTemplate> pool = new ArrayList<>(eligible(nation, registry));
        int members = nation.memberCount();
        int tiers = nation.research().completed().size();
        Map<ContractKind, Integer> perKind = new EnumMap<>(ContractKind.class);
        List<ContractInstance> out = new ArrayList<>(Math.max(0, slots));
        while (out.size() < slots) {
            List<ContractTemplate> allowed = new ArrayList<>(pool.size());
            for (ContractTemplate t : pool) {
                if (perKind.getOrDefault(t.kind(), 0) < MAX_OFFERS_PER_KIND) {
                    allowed.add(t);
                }
            }
            ContractTemplate picked = WeightedPicker.pick(allowed, ContractTemplate::weight, random);
            if (picked == null) {
                break;
            }
            pool.remove(picked);
            perKind.merge(picked.kind(), 1, Integer::sum);
            out.add(instantiate(picked, ids.getAsInt(), members, tiers, random, now));
        }
        return out;
    }

    /**
     * Instancie un modèle : {@code amount = arrondiAuPas(U(min..max, pas) × taille)} (exploration : non mise à
     * l'échelle), {@code M = round(money × (1 + 0,02 × paliers)) + round(money_per_unit × amount)},
     * {@code points + floor(points_per_unit × amount)}, chaque clé tirée selon sa chance.
     */
    public static ContractInstance instantiate(ContractTemplate t, int id, int members, int tiers, RandomSource random, long now) {
        ContractObjective.Amount range = t.objective().amount();
        int sampled = range.sample(random);
        int amount = t.objective().scalesWithMembers() ? scale(range, sampled, sizeFactor(members)) : sampled;
        ContractTemplate.Reward r = t.reward();
        long money = Math.round(r.money() * (1.0D + SFConfig.CONTRACT_MONEY_PER_TIER.get() * tiers))
                + Math.round(r.moneyPerUnit() * amount);
        long points = r.points() + (long) Math.floor(r.pointsPerUnit() * amount + 1e-9D);
        int[] keys = new int[ContractInstance.KEY_KINDS];
        for (ContractTemplate.KeyChance key : r.keys()) {
            if (key.chance() >= 1.0D || random.nextDouble() < key.chance()) {
                keys[key.crate().ordinal()] += key.count();
            }
        }
        return new ContractInstance(id, t.id(), t.kind(), ContractInstance.Target.of(t.objective()), amount, money, points,
                keys, r.items(), t.durationHours(), now);
    }

    // ------------------------------------------------------------------ entretien

    /** Appelé à chaque tick serveur ; ne travaille qu'une fois toutes les {@code contractMaintenanceTicks}. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % SFConfig.CONTRACT_MAINTENANCE_TICKS.get() != 0) {
            return;
        }
        long now = TimeUtil.now();
        for (Nation nation : NationData.get(server).nationsSnapshot()) {
            maintain(server, nation, now);
        }
    }

    /** Échéances dépassées puis rafraîchissement dû ; vrai si le tableau a changé. */
    public static boolean maintain(MinecraftServer server, Nation nation, long now) {
        boolean changed = expire(server, nation, now);
        return refreshIfDue(server, nation, ContractRegistry.get(), now) || changed;
    }

    /** Fait échouer les contrats en cours dont l'échéance est atteinte. */
    public static boolean expire(MinecraftServer server, Nation nation, long now) {
        boolean changed = false;
        for (ContractInstance c : List.copyOf(nation.contracts().active())) {
            if (now >= c.deadline()) {
                changed |= fail(server, nation, c, now);
            }
        }
        return changed;
    }

    /** Rafraîchit les offres si la nation est assez ancienne et que l'échéance de rafraîchissement est passée. */
    public static boolean refreshIfDue(MinecraftServer server, Nation nation, ContractRegistry registry, long now) {
        if (!isOldEnough(nation, now) || now < nation.contracts().nextRefresh()) {
            return false;
        }
        refresh(server, nation, registry, now);
        return true;
    }

    /**
     * Remplace toutes les offres non acceptées par un nouveau tirage (les contrats en cours restent), puis
     * {@code nextRefresh = now + 24 h}. Renvoie le nombre d'offres tirées.
     */
    public static int refresh(MinecraftServer server, Nation nation, ContractRegistry registry, long now) {
        NationContracts contracts = nation.contracts();
        int serial = contracts.nextSerial();
        // Les offres en place sont retirées avant le tirage : leurs modèles redeviennent éligibles.
        contracts.replaceOffers(List.of());
        List<ContractInstance> offers = roll(nation, registry, offerSlots(nation.memberCount()), seed(nation.id(), serial),
                now, contracts::allocateId);
        contracts.replaceOffers(offers);
        contracts.setNextRefresh(now + SFConfig.CONTRACT_REFRESH_HOURS.get() * HOUR_MS);
        nation.markDirty();
        EconomyNetwork.markDirty(nation);
        if (!offers.isEmpty()) {
            NationManager.broadcast(server, nation, prefix(nation).append(Msg.info("contract.broadcast.refreshed", offers.size())));
        }
        return offers.size();
    }

    /** Échec (échéance dépassée) ; faux si le contrat n'était plus en cours. */
    static boolean fail(MinecraftServer server, Nation nation, ContractInstance c, long now) {
        if (!nation.contracts().finish(c, NationContracts.Status.FAILED, now, historySize())) {
            return false;
        }
        nation.markDirty();
        nation.log("contract_failed", String.valueOf(c.id()), "@" + ContractTemplate.translationKey(c.template()));
        NationManager.broadcast(server, nation, prefix(nation).append(Msg.warn("contract.broadcast.failed", c.id(), title(c))));
        EconomyNetwork.markDirty(nation);
        return true;
    }

    // ------------------------------------------------------------------ actions des joueurs

    /**
     * Action reçue de l'écran ({@code ModPayloads}, thread principal) : {@code action} est l'une des constantes
     * de {@code ContractActionC2S} (déjà validée) ; la réponse est envoyée par l'appelant.
     */
    public static ActionResult handleAction(ServerPlayer player, byte action, int id) {
        return switch (action) {
            case ContractActionC2S.ACCEPT -> accept(player, id);
            case ContractActionC2S.ABANDON -> abandon(player, id);
            case ContractActionC2S.DELIVER -> deliver(player, id, Integer.MAX_VALUE);
            default -> ActionResult.fail(Msg.err("contract.error.unknown", id));
        };
    }

    public static ActionResult accept(ServerPlayer player, int id) {
        return accept(player, id, TimeUtil.now());
    }

    /** Accepte l'offre n°{@code id} (permission {@code economie}, emplacement libre) : échéance {@code now + délai}. */
    public static ActionResult accept(ServerPlayer player, int id, long now) {
        MinecraftServer server = player.server;
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(player.getUUID(), NationPermission.ECONOMY)) {
            return ActionResult.fail(Msg.err("nation.error.no_permission", NationPermission.ECONOMY.displayName()));
        }
        maintain(server, nation, now);
        NationContracts contracts = nation.contracts();
        ContractInstance offer = contracts.offer(id);
        if (offer == null) {
            if (contracts.activeContract(id) != null) {
                return ActionResult.fail(Msg.err("contract.error.already_active", id));
            }
            if (!isOldEnough(nation, now)) {
                return ActionResult.fail(Msg.err("contract.error.too_young",
                        TimeUtil.duration(SFConfig.CONTRACT_MIN_NATION_AGE_HOURS.get() * HOUR_MS)));
            }
            return ActionResult.fail(Msg.err("contract.error.unknown", id));
        }
        int max = maxActive(nation.memberCount());
        if (contracts.active().size() >= max) {
            return ActionResult.fail(Msg.err("contract.error.max_active", contracts.active().size(), max));
        }
        contracts.activate(offer);
        offer.accept(now, player.getUUID());
        if (offer.kind() == ContractKind.EXPLORE) {
            startExploration(server, offer, player);
        }
        nation.markDirty();
        String name = player.getGameProfile().getName();
        nation.log("contract_accept", name, String.valueOf(id), "@" + ContractTemplate.translationKey(offer.template()));
        NationManager.broadcast(server, nation, prefix(nation).append(Msg.info("contract.broadcast.accepted",
                player.getDisplayName(), id, title(offer))));
        EconomyNetwork.markDirty(nation);
        return ActionResult.ok(Msg.ok("contract.accepted", id, title(offer), TimeUtil.duration(offer.durationMs())));
    }

    /**
     * Origine de l'exploration : position du joueur qui accepte s'il est dans l'Overworld, sinon point d'apparition du
     * monde ; point de passage tiré à {@code U[min, max]} blocs de l'origine, dans une direction uniforme.
     */
    static void startExploration(MinecraftServer server, ContractInstance c, ServerPlayer player) {
        ContractInstance.Spot origin;
        if (player.level().dimension() == Level.OVERWORLD) {
            origin = new ContractInstance.Spot(Level.OVERWORLD, player.getBlockX(), player.getBlockZ());
        } else {
            BlockPos spawn = server.overworld().getSharedSpawnPos();
            origin = new ContractInstance.Spot(Level.OVERWORLD, spawn.getX(), spawn.getZ());
        }
        c.setOrigin(origin);
        ContractInstance.Target t = c.target();
        if (t.type() == ContractInstance.TargetType.WAYPOINT) {
            RandomSource random = player.getRandom();
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = t.min() >= t.max() ? t.min() : t.min() + random.nextInt(t.max() - t.min() + 1);
            c.setWaypoint(new ContractInstance.Spot(origin.dimension(),
                    origin.x() + (int) Math.round(Math.cos(angle) * distance),
                    origin.z() + (int) Math.round(Math.sin(angle) * distance)));
        }
    }

    public static ActionResult abandon(ServerPlayer player, int id) {
        return abandon(player, id, TimeUtil.now());
    }

    /** Abandonne le contrat en cours n°{@code id} (permission {@code economie}, sans pénalité ; emplacement non regarni). */
    public static ActionResult abandon(ServerPlayer player, int id, long now) {
        MinecraftServer server = player.server;
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(player.getUUID(), NationPermission.ECONOMY)) {
            return ActionResult.fail(Msg.err("nation.error.no_permission", NationPermission.ECONOMY.displayName()));
        }
        maintain(server, nation, now);
        ContractInstance c = nation.contracts().activeContract(id);
        if (c == null) {
            return ActionResult.fail(notActive(nation, id));
        }
        nation.contracts().finish(c, NationContracts.Status.ABANDONED, now, historySize());
        nation.markDirty();
        nation.log("contract_abandon", player.getGameProfile().getName(), String.valueOf(id),
                "@" + ContractTemplate.translationKey(c.template()));
        NationManager.broadcast(server, nation, prefix(nation).append(Msg.warn("contract.broadcast.abandoned",
                player.getDisplayName(), id, title(c))));
        EconomyNetwork.markDirty(nation);
        return ActionResult.ok(Msg.warn("contract.abandoned", id, title(c)));
    }

    public static ActionResult deliver(ServerPlayer player, int id, int max) {
        return deliver(player, id, max, TimeUtil.now());
    }

    /**
     * Livraison : retire {@code min(objets intacts possédés, reste, max)} de l'inventaire principal et de la main
     * secondaire AVANT d'ajouter la progression. Les objets sont détruits. Aucune permission : tout membre peut livrer.
     */
    public static ActionResult deliver(ServerPlayer player, int id, int max, long now) {
        MinecraftServer server = player.server;
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        maintain(server, nation, now);
        ContractInstance c = nation.contracts().activeContract(id);
        if (c == null) {
            return ActionResult.fail(notActive(nation, id));
        }
        if (c.kind() != ContractKind.DELIVER) {
            return ActionResult.fail(Msg.err("contract.error.not_deliver", id));
        }
        if (c.isFull()) {
            return ActionResult.fail(Msg.err("contract.error.already_full", id));
        }
        Item item = c.item();
        int want = Math.min(c.remaining(), max <= 0 ? 0 : max);
        int n = item == Items.AIR ? 0 : Math.min(Inventories.countPlain(player, item), want);
        if (n <= 0) {
            return ActionResult.fail(Msg.err("contract.error.no_items", item.getDescription()));
        }
        int removed = Inventories.consumePlain(player, item, n);
        int added = step(server, nation, c, player, removed, now);
        return ActionResult.ok(Msg.ok("contract.delivered", added, item.getDescription(), id, c.progress(), c.amount()));
    }

    private static Component notActive(Nation nation, int id) {
        return nation.contracts().offer(id) != null ? Msg.err("contract.error.not_active", id)
                : Msg.err("contract.error.unknown", id);
    }

    // ------------------------------------------------------------------ progression

    /**
     * {@code LivingDeathEvent} : le tueur direct doit être un vrai joueur (pas un {@code FakePlayer}) membre d'une
     * nation, la victime un {@code Mob} apparu naturellement ({@link #COUNTED_SPAWNS}) ; O(1) puis au plus les
     * contrats en cours de la nation.
     */
    public static void onKill(@Nullable Entity killer, LivingEntity victim) {
        if (!(killer instanceof ServerPlayer player) || player instanceof FakePlayer || player.isFakePlayer()
                || !(victim instanceof Mob mob)) {
            return;
        }
        MobSpawnType spawn = mob.getSpawnType();
        if (spawn == null || !COUNTED_SPAWNS.contains(spawn)) {
            return;
        }
        Nation nation = NationData.get(player.server).nationOf(player.getUUID());
        if (nation == null || nation.contracts().active().isEmpty()) {
            return;
        }
        long now = TimeUtil.now();
        EntityType<?> type = mob.getType();
        for (ContractInstance c : List.copyOf(nation.contracts().active())) {
            if (c.kind() == ContractKind.KILL && !c.isFull() && now < c.deadline() && c.matchesEntity(type)) {
                step(player.server, nation, c, player, 1, now);
            }
        }
    }

    /** {@code PlayerTickEvent.Post} : toutes les {@code contractExploreCheckTicks} ticks par joueur. */
    public static void exploreTick(ServerPlayer player) {
        if (player.tickCount % SFConfig.CONTRACT_EXPLORE_CHECK_TICKS.get() != 0 || player instanceof FakePlayer
                || player.isFakePlayer() || player.isSpectator()) {
            return;
        }
        exploreStep(player, TimeUtil.now());
    }

    /**
     * Un pas d'exploration pour ce joueur : {@code nationOf} puis la liste en cache des contrats d'exploration en cours
     * (sortie immédiate si elle est vide). Renvoie les unités gagnées.
     */
    public static int exploreStep(ServerPlayer player, long now) {
        Nation nation = NationData.get(player.server).nationOf(player.getUUID());
        if (nation == null) {
            return 0;
        }
        List<ContractInstance> explore = nation.contracts().activeExplore();
        if (explore.isEmpty()) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        int gained = 0;
        for (ContractInstance c : List.copyOf(explore)) {
            if (c.isFull() || now >= c.deadline()) {
                continue;
            }
            boolean reached = switch (c.target().type()) {
                case BIOME, BIOME_TAG -> biomeReached(level, player, pos, c);
                case WAYPOINT -> c.waypoint() != null && c.waypoint().dimension().equals(level.dimension())
                        && c.waypoint().distanceSqr(player.getX(), player.getZ()) <= (long) c.target().radius() * c.target().radius();
                case OUTPOSTS -> {
                    UUID outpost = OutpostRules.outpostAt(level, pos, OUTPOST_VISIT_RADIUS);
                    yield outpost != null && c.visit(outpost);
                }
                case DIMENSION -> level.dimension().location().equals(c.target().id());
                default -> false;
            };
            if (reached) {
                gained += step(player.server, nation, c, player, 1, now);
            }
        }
        return gained;
    }

    /**
     * Biome cible à la position du joueur, à {@code minDistance} blocs au moins (horizontalement) de l'origine. Hors de
     * la dimension de l'origine, la distance n'est pas mesurable : seule la condition de biome compte.
     */
    private static boolean biomeReached(ServerLevel level, ServerPlayer player, BlockPos pos, ContractInstance c) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) || !c.matchesBiome(level.getBiome(pos))) {
            return false;
        }
        ContractInstance.Spot origin = c.origin();
        if (origin == null) {
            BlockPos spawn = level.getServer().overworld().getSharedSpawnPos();
            origin = new ContractInstance.Spot(Level.OVERWORLD, spawn.getX(), spawn.getZ());
        }
        if (!origin.dimension().equals(level.dimension())) {
            return true;
        }
        long min = c.target().minDistance();
        return origin.distanceSqr(player.getX(), player.getZ()) >= min * min;
    }

    /**
     * Ajoute {@code n} unités (bornées au reste) au nom du joueur, affiche « Contrat n°3 : 12/30 » dans sa barre d'action
     * et termine le contrat s'il est rempli. Renvoie les unités ajoutées.
     */
    static int step(MinecraftServer server, Nation nation, ContractInstance c, ServerPlayer player, int n, long now) {
        int added = c.advance(n);
        if (added <= 0) {
            return 0;
        }
        c.contribute(player.getUUID(), added);
        nation.markDirty();
        player.displayClientMessage(Msg.info("contract.progress", c.id(), c.progress(), c.amount()), true);
        EconomyNetwork.markDirty(nation);
        if (c.isFull()) {
            complete(server, nation, c, player, now);
        }
        return added;
    }

    // ------------------------------------------------------------------ réussite

    /**
     * Réussite : la transition d'état d'abord (un second déclenchement est sans effet), puis argent (part des
     * contributeurs encore membres au prorata, le reste à la trésorerie), points de recherche, clés et objets (plus gros
     * contributeur en ligne, sinon coffre national, sinon {@code finisher}), journal, annonce et envois.
     *
     * @param finisher joueur dont l'action termine le contrat ({@code null} : administration depuis la console)
     * @return faux si le contrat n'était plus en cours
     */
    public static boolean complete(MinecraftServer server, Nation nation, ContractInstance c, @Nullable ServerPlayer finisher,
                                   long now) {
        if (!nation.contracts().finish(c, NationContracts.Status.COMPLETED, now, historySize())) {
            return false;
        }
        nation.markDirty();
        long total = c.money();
        long share = (long) Math.floor(total * SFConfig.CONTRACT_CONTRIBUTOR_SHARE.get());
        Map<UUID, Integer> eligible = eligibleContributors(nation, c);
        long units = 0L;
        for (int n : eligible.values()) {
            units += n;
        }
        long paid = 0L;
        if (share > 0L && units > 0L) {
            EconomyData eco = EconomyData.get(server);
            for (Map.Entry<UUID, Integer> e : eligible.entrySet()) {
                long part = share * e.getValue() / units;
                if (part <= 0L) {
                    continue;
                }
                eco.add(e.getKey(), part);
                paid += part;
                ServerPlayer online = online(server, e.getKey(), finisher);
                if (online != null) {
                    online.sendSystemMessage(prefix(nation).append(Msg.ok("contract.reward.share", c.id(), Money.format(part))));
                    NationSync.scheduleRefresh(online);
                }
            }
        }
        if (total - paid > 0L) {
            NationManager.adjustTreasury(nation, total - paid);
        }
        if (c.points() > 0L) {
            nation.research().setPoints(nation.research().points() + c.points());
        }
        UUID top = c.topContributor(nation::isMember);
        ServerPlayer recipient = top == null ? null : online(server, top, finisher);
        String source = "contrat n°" + c.id() + " (" + c.template() + ") de " + nation.name();
        for (CrateKind kind : CrateKind.values()) {
            int n = c.keys(kind);
            if (n <= 0) {
                continue;
            }
            if (recipient != null) {
                CrateKeys.give(recipient, kind, n);
            } else {
                CrateKeys.giveToNation(server, nation, kind, n, finisher);
            }
        }
        for (ContractTemplate.ItemReward item : c.items()) {
            ItemStack stack = new ItemStack(item.item(), item.count());
            if (!RestrictedItems.allowInContractReward(stack, source)) {
                continue;
            }
            if (recipient != null) {
                Inventories.giveOrDrop(recipient, stack);
            } else {
                giveToNation(nation, stack, finisher, source);
            }
        }
        nation.log("contract_done", String.valueOf(c.id()), "@" + ContractTemplate.translationKey(c.template()),
                Money.format(total), String.valueOf(c.points()));
        NationManager.broadcast(server, nation, prefix(nation).append(Msg.ok("contract.broadcast.completed", c.id(), title(c),
                Money.format(total), Money.format(paid), c.points())));
        NationSync.refreshNation(server, nation);
        EconomyNetwork.markDirty(nation);
        LOGGER.info("[Économie] Contrat n°{} ({}) réussi par {} : {} cr dont {} aux contributeurs, {} point(s)", c.id(),
                c.template(), nation.name(), total, paid, c.points());
        return true;
    }

    /** Contributions des joueurs encore membres (ordre d'arrivée). */
    private static Map<UUID, Integer> eligibleContributors(Nation nation, ContractInstance c) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> e : c.contributions().entrySet()) {
            if (e.getValue() > 0 && nation.isMember(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Part estimée du joueur si le contrat réussissait maintenant (0 sans contribution). */
    public static long shareEstimate(Nation nation, ContractInstance c, UUID player) {
        Map<UUID, Integer> eligible = eligibleContributors(nation, c);
        Integer mine = eligible.get(player);
        if (mine == null) {
            return 0L;
        }
        long units = 0L;
        for (int n : eligible.values()) {
            units += n;
        }
        long share = (long) Math.floor(c.money() * SFConfig.CONTRACT_CONTRIBUTOR_SHARE.get());
        return units <= 0L ? 0L : share * mine / units;
    }

    @Nullable
    private static ServerPlayer online(MinecraftServer server, UUID id, @Nullable ServerPlayer finisher) {
        if (finisher != null && finisher.getUUID().equals(id)) {
            return finisher;
        }
        return server.getPlayerList().getPlayer(id);
    }

    /** Objet de récompense au coffre national ; s'il est plein, à {@code fallback} (inventaire, sinon au sol). */
    private static void giveToNation(Nation nation, ItemStack stack, @Nullable ServerPlayer fallback, String source) {
        ItemStack rest = nation.chest().addItem(stack.copy());
        if (rest.isEmpty()) {
            return;
        }
        if (fallback != null) {
            Inventories.giveOrDrop(fallback, rest);
        } else {
            LOGGER.warn("[Économie] {} × {} perdu(s) ({}) : coffre national plein, aucun joueur de repli", rest.getCount(),
                    BuiltInRegistries.ITEM.getKey(rest.getItem()), source);
        }
    }

    // ------------------------------------------------------------------ administration

    /** {@code /sfadmin contract <nation> refresh} : nouveau tableau tout de suite (sans condition d'âge). */
    public static ActionResult adminRefresh(MinecraftServer server, Nation nation, long now) {
        expire(server, nation, now);
        int n = refresh(server, nation, ContractRegistry.get(), now);
        nation.log("contract_admin", "refresh");
        return ActionResult.ok(Msg.ok("contract.admin.refreshed", nation.name(), n));
    }

    /** {@code /sfadmin contract <nation> complete <n>} : réussite immédiate, contributions en l'état. */
    public static ActionResult adminComplete(MinecraftServer server, Nation nation, int id, @Nullable ServerPlayer source,
                                             long now) {
        ContractInstance c = nation.contracts().activeContract(id);
        if (c == null) {
            return ActionResult.fail(notActive(nation, id));
        }
        nation.log("contract_admin", "complete " + id);
        complete(server, nation, c, source, now);
        return ActionResult.ok(Msg.ok("contract.admin.completed", id, nation.name()));
    }

    /** {@code /sfadmin contract <nation> expire <n>} : échec immédiat (comme une échéance dépassée). */
    public static ActionResult adminExpire(MinecraftServer server, Nation nation, int id, long now) {
        ContractInstance c = nation.contracts().activeContract(id);
        if (c == null) {
            return ActionResult.fail(notActive(nation, id));
        }
        nation.log("contract_admin", "expire " + id);
        fail(server, nation, c, now);
        return ActionResult.ok(Msg.ok("contract.admin.expired", id, nation.name()));
    }

    // ------------------------------------------------------------------ textes et icônes

    private static MutableComponent prefix(Nation nation) {
        return Component.literal("[" + nation.name() + "] ").withStyle(ChatFormatting.AQUA);
    }

    /** Intitulé : nom traduit du modèle. */
    public static MutableComponent title(ContractInstance c) {
        return ContractTemplate.displayName(c.template());
    }

    /** Objectif lisible : « Livrer 64 × Blé », « Éliminer 12 × squelettes », « Explorer : jungles à 800 blocs »… */
    public static MutableComponent objective(ContractInstance c) {
        ContractInstance.Target t = c.target();
        return switch (t.type()) {
            case ITEM -> Msg.tr("contract.objective.deliver", c.amount(), c.item().getDescription());
            case ENTITY, ENTITY_TAG -> Msg.tr("contract.objective.kill", c.amount(), targetName(t));
            case BIOME, BIOME_TAG -> t.minDistance() > 0
                    ? Msg.tr("contract.objective.biome", targetName(t), t.minDistance())
                    : Msg.tr("contract.objective.biome_any", targetName(t));
            case WAYPOINT -> c.waypoint() == null
                    ? Msg.tr("contract.objective.waypoint_offer", t.min(), t.max(), t.radius())
                    : Msg.tr("contract.objective.waypoint", c.waypoint().x(), c.waypoint().z(), t.radius());
            case OUTPOSTS -> Msg.tr("contract.objective.outposts", c.amount(), OUTPOST_VISIT_RADIUS);
            case DIMENSION -> Msg.tr("contract.objective.dimension", targetName(t));
        };
    }

    /**
     * Fiche complète pour l'écran : objectif, puis, pour une offre, le délai accordé ; puis les objets de récompense
     * éventuels (absents de {@code ContractLine}).
     */
    public static MutableComponent describe(ContractInstance c, boolean offer) {
        MutableComponent text = objective(c);
        if (offer) {
            text.append(Msg.tr("contract.objective.delay", TimeUtil.duration(c.durationMs())));
        }
        if (!c.items().isEmpty()) {
            text.append(Msg.tr("contract.objective.items", itemsText(c)));
        }
        return text;
    }

    /** « 8 × Fiole d'expérience, 2 × … ». */
    public static MutableComponent itemsText(ContractInstance c) {
        MutableComponent list = Component.empty();
        for (int i = 0; i < c.items().size(); i++) {
            ContractTemplate.ItemReward item = c.items().get(i);
            if (i > 0) {
                list.append(", ");
            }
            list.append(item.count() + " × ").append(item.item().getDescription());
        }
        return list;
    }

    /** Nom de la cible : entité, tag (clé {@code contract.tag.<ns>.<chemin>}), biome ou dimension. */
    public static Component targetName(ContractInstance.Target t) {
        ResourceLocation id = t.id();
        if (id == null) {
            return Component.empty();
        }
        String dotted = id.getNamespace() + "." + id.getPath().replace('/', '.');
        return switch (t.type()) {
            case ENTITY -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).<Component>map(EntityType::getDescription)
                    .orElseGet(() -> Component.literal(id.toString()));
            case ENTITY_TAG, BIOME_TAG -> Component.translatableWithFallback(Msg.key("contract.tag." + dotted), "#" + id);
            case BIOME -> Component.translatableWithFallback("biome." + dotted, id.toString());
            case DIMENSION -> Component.translatableWithFallback(Msg.key("contract.dimension." + dotted), id.toString());
            case ITEM -> BuiltInRegistries.ITEM.getOptional(id).<Component>map(Item::getDescription)
                    .orElseGet(() -> Component.literal(id.toString()));
            default -> Component.literal(id.toString());
        };
    }

    /** Icône : objet livré, œuf d'apparition de la cible, carte, boussole, émeraude, bloc de la dimension. */
    public static ItemStack icon(ContractInstance c) {
        ContractInstance.Target t = c.target();
        return switch (t.type()) {
            case ITEM -> new ItemStack(c.item());
            case ENTITY -> egg(t.id() == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(t.id()).orElse(null));
            case ENTITY_TAG -> egg(t.id() == null ? null : BuiltInRegistries.ENTITY_TYPE
                    .getTag(TagKey.create(Registries.ENTITY_TYPE, t.id()))
                    .flatMap(set -> set.stream().findFirst()).map(Holder::value).orElse(null));
            case BIOME, BIOME_TAG -> new ItemStack(Items.MAP);
            case WAYPOINT -> new ItemStack(Items.COMPASS);
            case OUTPOSTS -> new ItemStack(Items.EMERALD);
            case DIMENSION -> new ItemStack(Level.NETHER.location().equals(t.id()) ? Items.NETHERRACK
                    : Level.END.location().equals(t.id()) ? Items.END_STONE : Items.OBSIDIAN);
        };
    }

    private static ItemStack egg(@Nullable EntityType<?> type) {
        SpawnEggItem egg = SpawnEggItem.byId(type);
        return new ItemStack(egg != null ? egg : Items.IRON_SWORD);
    }

    // ------------------------------------------------------------------ vue

    /** Partie « contrats » de la vue Économie de ce joueur (membre de {@code nation}) ; l'entretien est appliqué d'abord. */
    public static EconomyView.ContractsView view(ServerPlayer player, Nation nation) {
        long now = TimeUtil.now();
        maintain(player.server, nation, now);
        return view(player.server, nation, player.getUUID(), now);
    }

    /**
     * Vue du tableau pour {@code viewer} : offres, contrats en cours (part estimée, contribution, 3 meilleurs
     * contributeurs), historique du plus récent au plus ancien. Le temps avant rafraîchissement est, pour une nation trop
     * jeune, le temps avant l'ouverture du tableau.
     */
    public static EconomyView.ContractsView view(MinecraftServer server, Nation nation, UUID viewer, long now) {
        NationContracts contracts = nation.contracts();
        long refreshIn = isOldEnough(nation, now) ? Math.max(0L, contracts.nextRefresh() - now) : timeUntilOpen(nation, now);
        List<EconomyView.ContractLine> offers = new ArrayList<>();
        for (ContractInstance c : contracts.offers()) {
            if (offers.size() < EconomyView.ContractsView.MAX_OFFERS) {
                offers.add(line(server, nation, c, viewer, now, true));
            }
        }
        List<EconomyView.ContractLine> active = new ArrayList<>();
        for (ContractInstance c : contracts.active()) {
            if (active.size() < EconomyView.ContractsView.MAX_ACTIVE) {
                active.add(line(server, nation, c, viewer, now, false));
            }
        }
        List<NationContracts.History> history = contracts.history();
        List<EconomyView.HistoryLine> past = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && past.size() < EconomyView.ContractsView.MAX_HISTORY; i--) {
            NationContracts.History h = history.get(i);
            past.add(new EconomyView.HistoryLine(h.id(), ContractTemplate.displayName(h.template()), h.status().code(), h.epochMs()));
        }
        return new EconomyView.ContractsView(maxActive(nation.memberCount()), offerSlots(nation.memberCount()), refreshIn,
                offers, active, past);
    }

    private static EconomyView.ContractLine line(MinecraftServer server, Nation nation, ContractInstance c, UUID viewer,
                                                 long now, boolean offer) {
        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(c.contributions().entrySet());
        sorted.sort(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()));
        List<EconomyView.Contributor> top = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : sorted) {
            if (top.size() >= EconomyView.ContractLine.MAX_CONTRIBUTORS) {
                break;
            }
            top.add(new EconomyView.Contributor(contributorName(server, nation, e.getKey()), e.getValue()));
        }
        return new EconomyView.ContractLine(c.id(), (byte) c.kind().ordinal(), title(c), describe(c, offer), icon(c),
                c.progress(), c.amount(), c.money(), offer ? 0L : shareEstimate(nation, c, viewer), c.points(), c.keys(),
                offer ? 0L : Math.max(0L, c.deadline() - now), c.contribution(viewer), top);
    }

    /** Nom d'un contributeur (membre, sinon dernier nom connu du compte), borné à 16 caractères. */
    public static String contributorName(MinecraftServer server, Nation nation, UUID id) {
        String name = nation.isMember(id) ? nation.memberName(id) : EconomyData.get(server).nameOf(id);
        return name.length() > 16 ? name.substring(0, 16) : name;
    }
}
