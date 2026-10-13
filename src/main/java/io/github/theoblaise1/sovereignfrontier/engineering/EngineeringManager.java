package io.github.theoblaise1.sovereignfrontier.engineering;

import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition.ItemCost;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Fabrication à la Station d'ingénierie : déblocages de la nation, proximité de la station, ingrédients de l'inventaire. */
public final class EngineeringManager {
    private static final Map<UUID, GlobalPos> SESSIONS = new HashMap<>();
    private static final double REACH_SQR = 8.0D * 8.0D;

    private EngineeringManager() {
    }

    public static void open(ServerPlayer player, GlobalPos station) {
        SESSIONS.put(player.getUUID(), station);
    }

    public static void close(UUID player) {
        SESSIONS.remove(player);
    }

    public static void clear() {
        SESSIONS.clear();
    }

    /** Vrai si la nation du joueur a débloqué l'onglet et la recette. */
    public static boolean unlocked(Nation nation, EngineeringRecipe recipe) {
        if (nation == null || !nation.research().hasUnlock(recipe.definition().tab().unlock())) {
            return false;
        }
        String key = recipe.definition().unlock();
        return key.isBlank() || nation.research().hasUnlock(key);
    }

    public static ActionResult craft(ServerPlayer player, ResourceLocation id) {
        GlobalPos session = SESSIONS.get(player.getUUID());
        if (session == null || !player.level().dimension().equals(session.dimension())
                || player.distanceToSqr(session.pos().getCenter()) > REACH_SQR
                || !player.level().getBlockState(session.pos()).is(ModBlocks.ENGINEERING_STATION.get())) {
            return ActionResult.fail(Msg.err("engineering.error.no_station"));
        }
        return craftUnchecked(player, id);
    }

    /** Fabrication proprement dite, sans contrôle de session (proximité déjà vérifiée, ou GameTests). */
    public static ActionResult craftUnchecked(ServerPlayer player, ResourceLocation id) {
        EngineeringRecipe recipe = EngineeringRegistry.server().get(id);
        if (recipe == null) {
            return ActionResult.fail(Msg.err("engineering.error.unknown", id.toString()));
        }
        Nation nation = NationData.get(player.server).nationOf(player.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!unlocked(nation, recipe)) {
            return ActionResult.fail(Msg.err("engineering.error.locked"));
        }
        Inventory inv = player.getInventory();
        for (ItemCost cost : recipe.definition().ingredients()) {
            int have = count(inv, cost.item());
            if (have < cost.count()) {
                return ActionResult.fail(Msg.err("engineering.error.missing", cost.count() - have, cost.item().getDescription()));
            }
        }
        for (ItemCost cost : recipe.definition().ingredients()) {
            consume(inv, cost.item(), cost.count());
        }
        ItemCost out = recipe.definition().result();
        ItemStack result = new ItemStack(out.item(), out.count());
        if (!inv.add(result)) {
            player.drop(result, false);
        }
        player.containerMenu.broadcastChanges();
        return ActionResult.ok(Msg.ok("engineering.crafted", out.count(), out.item().getDescription()));
    }

    public static int count(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item) && s.getComponentsPatch().isEmpty()) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static void consume(Inventory inv, Item item, int amount) {
        for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item) && s.getComponentsPatch().isEmpty()) {
                int take = Math.min(amount, s.getCount());
                s.shrink(take);
                amount -= take;
            }
        }
        inv.setChanged();
    }
}
