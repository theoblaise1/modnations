package io.github.theoblaise1.sovereignfrontier.research;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cycle de vie des recherches d'une nation : une seule recherche active à la fois (tous domaines
 * confondus), objets consommés dans le coffre national, coût en trésorerie et en points, fin
 * déterminée par un horodatage epoch (la recherche avance même serveur éteint).
 */
public final class ResearchManager {
    /** Intervalle de vérification des fins de recherche (ticks). */
    public static final int CHECK_INTERVAL_TICKS = 20;

    public enum Status {
        COMPLETED,
        ACTIVE,
        AVAILABLE,
        LOCKED
    }

    private ResearchManager() {
    }

    public static Status status(Nation nation, ResearchRegistry registry, ResearchTier tier) {
        NationResearch research = nation.research();
        if (research.isCompleted(tier.id())) {
            return Status.COMPLETED;
        }
        NationResearch.Active active = research.active();
        if (active != null && active.id().equals(tier.id())) {
            return Status.ACTIVE;
        }
        for (ResourceLocation req : registry.prerequisites(tier)) {
            if (!research.isCompleted(req)) {
                return Status.LOCKED;
            }
        }
        return Status.AVAILABLE;
    }

    /** Durée effective (ms) d'un palier pour cette nation, bonus de vitesse inclus. */
    public static long durationMs(Nation nation, ResearchTier tier) {
        double speed = Math.max(0.0D, nation.research().bonus(ResearchStat.RESEARCH_SPEED));
        return Math.max(1000L, Math.round(tier.definition().baseDurationSeconds() * 1000.0D / (1.0D + speed)));
    }

    /** Raisons qui empêchent de lancer ce palier maintenant ; liste vide = lancement possible. */
    public static List<Component> blockers(Nation nation, ResearchRegistry registry, ResearchTier tier) {
        List<Component> reasons = new ArrayList<>();
        NationResearch research = nation.research();
        if (research.isCompleted(tier.id())) {
            reasons.add(Msg.err("research.error.completed", tier.displayName()));
            return reasons;
        }
        NationResearch.Active active = research.active();
        if (active != null) {
            ResearchTier running = registry.get(active.id());
            reasons.add(Msg.err("research.error.busy", running == null ? Component.literal(active.id().toString()) : running.displayName()));
        }
        for (ResourceLocation req : registry.prerequisites(tier)) {
            if (!research.isCompleted(req)) {
                ResearchTier missing = registry.get(req);
                reasons.add(Msg.err("research.error.prerequisite", missing == null ? Component.literal(req.toString()) : missing.displayName()));
            }
        }
        ResearchDefinition def = tier.definition();
        int population = nation.effectivePopulation();
        if (population < def.minPopulation()) {
            reasons.add(Msg.err("research.error.population", def.minPopulation(), population));
        }
        if (nation.treasury() < def.cost().treasury()) {
            reasons.add(Msg.err("research.error.treasury", Money.format(def.cost().treasury()), Money.format(nation.treasury())));
        }
        if (research.points() < def.cost().points()) {
            reasons.add(Msg.err("research.error.points", def.cost().points(), research.points()));
        }
        for (Map.Entry<Item, Integer> cost : itemTotals(def.cost()).entrySet()) {
            int have = count(nation.chest(), cost.getKey());
            if (have < cost.getValue()) {
                reasons.add(Msg.err("research.error.items", cost.getValue() - have, cost.getKey().getDescription()));
            }
        }
        return reasons;
    }

    /**
     * Quantité totale exigée par objet. Un datapack peut citer deux fois le même objet : vérifier et
     * consommer chaque entrée séparément ferait payer une seule fois le stock du coffre.
     */
    public static Map<Item, Integer> itemTotals(ResearchDefinition.Cost cost) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (ResearchDefinition.ItemCost item : cost.items()) {
            totals.merge(item.item(), item.count(), (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
        }
        return totals;
    }

    public static ActionResult start(ServerPlayer player, ResourceLocation id) {
        MinecraftServer server = player.server;
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(player.getUUID(), NationPermission.START_RESEARCH)) {
            return ActionResult.fail(Msg.err("nation.error.no_permission", NationPermission.START_RESEARCH.displayName()));
        }
        ResearchRegistry registry = ResearchRegistry.server();
        ResearchTier tier = registry.get(id);
        if (tier == null) {
            return ActionResult.fail(Msg.err("research.error.unknown", id.toString()));
        }
        List<Component> reasons = blockers(nation, registry, tier);
        if (!reasons.isEmpty()) {
            return ActionResult.fail(reasons.get(0));
        }
        ResearchDefinition def = tier.definition();
        for (Map.Entry<Item, Integer> cost : itemTotals(def.cost()).entrySet()) {
            consume(nation.chest(), cost.getKey(), cost.getValue());
        }
        if (def.cost().treasury() > 0) {
            NationManager.adjustTreasury(nation, -def.cost().treasury());
        }
        NationResearch research = nation.research();
        research.setPoints(research.points() - def.cost().points());
        long now = TimeUtil.now();
        long duration = durationMs(nation, tier);
        research.setActive(new NationResearch.Active(tier.id(), now, now + duration, def.cost().treasury()));
        nation.markDirty();
        String name = "@" + tier.translationKey();
        nation.log("research_start", player.getGameProfile().getName(), name, TimeUtil.duration(duration));
        NationManager.broadcast(server, nation, Msg.info("research.broadcast.started", player.getDisplayName(),
                tier.displayName(), TimeUtil.duration(duration)));
        NationSync.refreshNation(server, nation);
        return ActionResult.ok(Msg.ok("research.started", tier.displayName(), TimeUtil.duration(duration)));
    }

    public static ActionResult cancel(ServerPlayer player) {
        return cancel(player, null);
    }

    /**
     * Annule la recherche active. Avec {@code expected} (palier affiché par l'écran Science), refuse
     * si la recherche en cours n'est plus celle-là : la précédente a pu se terminer et un autre membre
     * en lancer une nouvelle entre l'affichage et le clic.
     */
    public static ActionResult cancel(ServerPlayer player, @Nullable ResourceLocation expected) {
        MinecraftServer server = player.server;
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(player.getUUID(), NationPermission.START_RESEARCH)) {
            return ActionResult.fail(Msg.err("nation.error.no_permission", NationPermission.START_RESEARCH.displayName()));
        }
        NationResearch research = nation.research();
        NationResearch.Active active = research.active();
        if (active == null) {
            return ActionResult.fail(Msg.err("research.error.no_active"));
        }
        ResearchRegistry registry = ResearchRegistry.server();
        if (expected != null && !active.id().equals(expected)) {
            ResearchTier shown = registry.get(expected);
            return ActionResult.fail(Msg.err("research.error.cancel_changed",
                    shown == null ? Component.literal(expected.toString()) : shown.displayName()));
        }
        ResearchTier tier = registry.get(active.id());
        // Remboursement calculé sur ce qui a été réellement payé : la définition courante a pu changer
        // ou disparaître depuis le lancement (datapack modifié puis /reload).
        long paid = active.paidTreasury() >= 0L ? active.paidTreasury()
                : tier == null ? 0L : tier.definition().cost().treasury();
        long refund = (long) Math.floor(paid * SFConfig.RESEARCH_CANCEL_REFUND.get());
        research.setActive(null);
        if (refund > 0) {
            NationManager.adjustTreasury(nation, refund);
        }
        nation.markDirty();
        Component name = tier == null ? Component.literal(active.id().toString()) : tier.displayName();
        nation.log("research_cancel", player.getGameProfile().getName(),
                tier == null ? active.id().toString() : "@" + tier.translationKey(), Money.format(refund));
        NationSync.refreshNation(server, nation);
        return ActionResult.ok(Msg.warn("research.cancelled", name, Money.format(refund)));
    }

    /** Appelé à chaque tick serveur ; ne travaille qu'une fois toutes les {@link #CHECK_INTERVAL_TICKS}. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_INTERVAL_TICKS == 0) {
            completeDue(server, TimeUtil.now());
        }
    }

    /** Termine toutes les recherches dont l'échéance epoch est passée (aussi celles échues serveur éteint). */
    public static void completeDue(MinecraftServer server, long now) {
        NationData data = NationData.get(server);
        for (Nation nation : data.nationsSnapshot()) {
            NationResearch.Active active = nation.research().active();
            if (active != null && now >= active.endEpochMs()) {
                finish(server, nation, active.id());
            }
        }
    }

    /** Termine la recherche active (fin normale ou commande d'administration). */
    public static void finish(MinecraftServer server, Nation nation, ResourceLocation id) {
        NationResearch research = nation.research();
        research.setActive(null);
        research.complete(id);
        nation.markDirty();
        ResearchTier tier = ResearchRegistry.server().get(id);
        Component name = tier == null ? Component.literal(id.toString()) : tier.displayName();
        nation.log("research_done", tier == null ? id.toString() : "@" + tier.translationKey());
        NationManager.broadcast(server, nation, Msg.ok("research.broadcast.completed", name).withStyle(ChatFormatting.GREEN));
        if (tier != null) {
            for (ResearchReward reward : tier.definition().rewards()) {
                NationManager.broadcast(server, nation, Component.literal("  • ").append(reward.describe()).withStyle(ChatFormatting.GRAY));
            }
        }
        NationSync.refreshNation(server, nation);
    }

    // ------------------------------------------------------------------ administration

    public static void adminComplete(MinecraftServer server, Nation nation, ResearchTier tier) {
        NationResearch.Active active = nation.research().active();
        if (active != null && active.id().equals(tier.id())) {
            nation.research().setActive(null);
        }
        nation.research().complete(tier.id());
        nation.markDirty();
        nation.log("admin_research", "@" + tier.translationKey());
        NationSync.refreshNation(server, nation);
    }

    public static void adminSetPoints(MinecraftServer server, Nation nation, long points) {
        nation.research().setPoints(points);
        nation.markDirty();
        NationSync.refreshNation(server, nation);
    }

    // ------------------------------------------------------------------ coffre national

    public static int count(SimpleContainer chest, Item item) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (isPlain(stack, item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void consume(SimpleContainer chest, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < chest.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = chest.getItem(i);
            if (isPlain(stack, item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        chest.setChanged();
    }

    private static boolean isPlain(ItemStack stack, Item item) {
        return stack.is(item) && isPlain(stack);
    }

    /**
     * Seuls les objets « bruts » comptent : ni endommagés, ni renommés, ni enchantés, ni porteurs d'un
     * contenu (inventaire d'un coffre, d'un sac). Les autres composants ne comptent pas : une fusée de
     * vol 2 ou 3 reste une fusée ordinaire. Règle partagée avec l'onglet Science ({@code NationSync}),
     * pour que l'écran compte exactement ce que le serveur consommera.
     */
    public static boolean isPlain(ItemStack stack) {
        return !stack.isEmpty()
                && !stack.isDamaged()
                && !stack.has(DataComponents.CUSTOM_NAME)
                && !stack.isEnchanted()
                && stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty()
                && !stack.has(DataComponents.BLOCK_ENTITY_DATA)
                && stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyStream().findAny().isEmpty()
                && stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).isEmpty();
    }
}
