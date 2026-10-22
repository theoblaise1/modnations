// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.machine.MachineKind;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Raffinerie (docs/conception/J5.md §1.4) : 3 bouteilles de gaz pleines + 1 poudre de blaze + énergie (200 En/tick
 * pendant 200 ticks) donnent 1 carburant de fusée et rendent 3 bouteilles vides. Elle ne marche qu'en territoire d'une
 * nation qui a {@code rocket.fuel} (la machine elle-même se fabrique avec {@code refinery}) et s'arrête, sans rien
 * perdre, si une sortie ne peut plus recevoir.
 * <p>
 * Les ingrédients ne sont prélevés qu'<b>à la fin</b> du cycle : la progression ({@code progress}) n'est pas
 * persistée, et un redémarrage en plein cycle ne doit rien coûter que l'énergie déjà dépensée. Sans énergie, le cycle
 * se met en pause ; sans ingrédient, sans place en sortie, hors territoire ou sans la recherche, il retombe à zéro.
 * Aucune sortie n'est jamais un module cryogénique : la seule sortie de fusée est le carburant.
 * <p>
 * Appelée à chaque tick par {@link MachineBlockEntity} ; ne fait de recherche de territoire que si un cycle est
 * possible (ingrédients présents, sorties libres).
 */
public final class RefineryLogic {
    /** Bouteilles de gaz pleines consommées par cycle. */
    public static final int BOTTLES_IN = 3;
    /** Poudre de blaze consommée par cycle. */
    public static final int BLAZE_IN = 1;
    /** Carburant produit par cycle. */
    public static final int FUEL_OUT = 1;
    /** Bouteilles vides rendues par cycle. */
    public static final int BOTTLES_OUT = 3;

    private RefineryLogic() {
    }

    /** Un tick serveur de la Raffinerie en {@code pos} ; état et cases via les accesseurs de {@code machine}. */
    public static void tick(ServerLevel level, BlockPos pos, MachineBlockEntity machine) {
        SimpleContainer items = machine.items();
        if (!hasIngredients(items) || !hasRoom(items)) {
            idle(machine);
            return;
        }
        if (!mayRun(level, pos)) {
            idle(machine);
            return;
        }
        if (!machine.energyBuffer().consume(SFConfig.REFINERY_ENERGY_PER_TICK.get())) {
            // Sans énergie : le cycle est en pause (la progression est gardée), la machine n'est pas « active ».
            machine.setActive(false);
            return;
        }
        machine.setActive(true);
        machine.setProgress(machine.progress() + 1);
        if (machine.progress() >= SFConfig.REFINERY_CYCLE_TICKS.get()) {
            complete(items);
            machine.setProgress(0);
        }
    }

    private static void idle(MachineBlockEntity machine) {
        machine.setProgress(0);
        machine.setActive(false);
    }

    /** Vrai si le territoire de la machine appartient à une nation qui a {@code rocket.fuel} (une lecture d'index). */
    private static boolean mayRun(ServerLevel level, BlockPos pos) {
        Nation owner = NationData.get(level.getServer()).ownerAt(level.dimension(), pos);
        return owner != null && owner.research().hasUnlock(UnlockKeys.ROCKET_FUEL);
    }

    private static boolean hasIngredients(SimpleContainer items) {
        ItemStack gas = items.getItem(MachineKind.REFINERY_GAS);
        ItemStack blaze = items.getItem(MachineKind.REFINERY_BLAZE);
        return gas.is(ModItems.FULL_GAS_BOTTLE.get()) && gas.getCount() >= BOTTLES_IN
                && blaze.is(Items.BLAZE_POWDER) && blaze.getCount() >= BLAZE_IN;
    }

    /** Les deux sorties doivent pouvoir recevoir le produit du cycle (case vide, ou même objet avec de la place). */
    private static boolean hasRoom(SimpleContainer items) {
        return roomFor(items.getItem(MachineKind.REFINERY_FUEL_OUT), ModItems.ROCKET_FUEL.get(), FUEL_OUT)
                && roomFor(items.getItem(MachineKind.REFINERY_EMPTY_OUT), ModItems.GAS_BOTTLE.get(), BOTTLES_OUT);
    }

    private static boolean roomFor(ItemStack slot, Item item, int amount) {
        if (slot.isEmpty()) {
            return true;
        }
        return slot.is(item) && slot.getComponentsPatch().isEmpty() && slot.getCount() + amount <= slot.getMaxStackSize();
    }

    private static void complete(SimpleContainer items) {
        items.getItem(MachineKind.REFINERY_GAS).shrink(BOTTLES_IN);
        items.getItem(MachineKind.REFINERY_BLAZE).shrink(BLAZE_IN);
        add(items, MachineKind.REFINERY_FUEL_OUT, ModItems.ROCKET_FUEL.get(), FUEL_OUT);
        add(items, MachineKind.REFINERY_EMPTY_OUT, ModItems.GAS_BOTTLE.get(), BOTTLES_OUT);
        items.setChanged();
    }

    private static void add(SimpleContainer items, int slot, Item item, int amount) {
        ItemStack stack = items.getItem(slot);
        if (stack.isEmpty()) {
            items.setItem(slot, new ItemStack(item, amount));
        } else {
            stack.grow(amount);
        }
    }
}
