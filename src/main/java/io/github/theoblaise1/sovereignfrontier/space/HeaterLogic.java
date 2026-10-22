// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.space;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.machine.MachineKind;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Chaudière à gaz et Réchauffeur nucléaire (docs/conception/J5.md §1.6) : brûlent leur combustible et maintiennent une
 * zone de chaleur ({@link ZoneIndex}) s'ils sont sur le territoire d'une nation qui a leur clé
 * ({@code boiler.gas}, {@code heater.nuclear}). Leur chaleur rayonne ensuite sans tenir compte des frontières.
 * <p>
 * Comme le diffuseur ({@link OxygenDiffuserLogic}), le travail se fait par fenêtres de
 * {@value OxygenDiffuserLogic#WINDOW} ticks, en O(1) : au premier tick de chaque fenêtre, la machine relit son
 * propriétaire, consomme une fenêtre de combustible et rafraîchit sa zone. Le combustible ne brûle que lorsque la
 * machine est autorisée (territoire et clé) ; sinon rien ne se consomme et la zone expire.
 * <ul>
 *   <li>Chaudière : une bouteille de gaz pleine brûle {@code boilerBottleTicks} ticks ; la bouteille vide est rendue
 *       dans la sortie à l'allumage, et la chaudière ne s'allume pas si la sortie est pleine (la combustion en cours
 *       va à son terme). Combustion restante : {@code burn} / {@code burnTotal}.</li>
 *   <li>Réchauffeur : un combustible enrichi brûle {@code nuclearHeaterFuelTicks} ticks. Combustible restant :
 *       {@code fuelTicks}. Il ne produit aucune énergie.</li>
 * </ul>
 */
public final class HeaterLogic {
    private HeaterLogic() {
    }

    /** Un tick serveur de la Chaudière à gaz en {@code pos}. */
    public static void tickBoiler(ServerLevel level, BlockPos pos, MachineBlockEntity machine) {
        if (!OxygenDiffuserLogic.windowStart(machine)) {
            return;
        }
        Nation owner = OxygenDiffuserLogic.ownerWith(level, pos, UnlockKeys.BOILER_GAS);
        boolean burning = false;
        if (owner != null) {
            int total = SFConfig.BOILER_BOTTLE_TICKS.get();
            if (machine.burn() <= 0 && ignite(machine)) {
                machine.setBurn(total, total);
            }
            if (machine.burn() > 0) {
                machine.setBurn(machine.burn() - OxygenDiffuserLogic.WINDOW, machine.burnTotal());
                burning = true;
            }
        }
        machine.setActive(burning);
        if (burning) {
            ZoneIndex.activate(level.dimension(), pos, ZoneIndex.Kind.HEAT, SFConfig.BOILER_RADIUS.get(),
                    SFConfig.BOILER_HALF_HEIGHT.get(), owner.id(), level.getGameTime());
        }
    }

    /**
     * Allume la chaudière : prend une bouteille pleine dans l'entrée et rend la bouteille vide dans la sortie. Refus
     * (rien n'est consommé) sans bouteille pleine ou si la sortie ne peut pas recevoir la bouteille vide.
     */
    private static boolean ignite(MachineBlockEntity machine) {
        ItemStack input = machine.items().getItem(MachineKind.BOILER_GAS);
        ItemStack output = machine.items().getItem(MachineKind.BOILER_EMPTY_OUT);
        if (!input.is(ModItems.FULL_GAS_BOTTLE.get())) {
            return false;
        }
        if (output.isEmpty()) {
            machine.items().setItem(MachineKind.BOILER_EMPTY_OUT, new ItemStack(ModItems.GAS_BOTTLE.get()));
        } else if (output.is(ModItems.GAS_BOTTLE.get()) && output.getCount() < output.getMaxStackSize()) {
            output.grow(1);
        } else {
            return false;
        }
        input.shrink(1);
        return true;
    }

    /** Un tick serveur du Réchauffeur nucléaire en {@code pos}. */
    public static void tickNuclear(ServerLevel level, BlockPos pos, MachineBlockEntity machine) {
        if (!OxygenDiffuserLogic.windowStart(machine)) {
            return;
        }
        Nation owner = OxygenDiffuserLogic.ownerWith(level, pos, UnlockKeys.HEATER_NUCLEAR);
        boolean heating = false;
        if (owner != null) {
            if (machine.fuelTicks() <= 0) {
                ItemStack fuel = machine.items().getItem(MachineKind.HEATER_FUEL);
                if (fuel.is(ModItems.ENRICHED_FUEL.get())) {
                    fuel.shrink(1);
                    machine.setFuelTicks(SFConfig.NUCLEAR_HEATER_FUEL_TICKS.get());
                }
            }
            if (machine.fuelTicks() > 0) {
                machine.setFuelTicks(machine.fuelTicks() - OxygenDiffuserLogic.WINDOW);
                heating = true;
            }
        }
        machine.setActive(heating);
        if (heating) {
            ZoneIndex.activate(level.dimension(), pos, ZoneIndex.Kind.HEAT, SFConfig.NUCLEAR_HEATER_RADIUS.get(),
                    SFConfig.NUCLEAR_HEATER_HALF_HEIGHT.get(), owner.id(), level.getGameTime());
        }
    }
}
