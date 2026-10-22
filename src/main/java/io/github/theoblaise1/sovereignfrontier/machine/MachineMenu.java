package io.github.theoblaise1.sovereignfrontier.machine;

import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;

import javax.annotation.Nullable;

/** Menu commun à toutes les machines : emplacements propres au type, inventaire du joueur, données synchronisées. */
public class MachineMenu extends AbstractContainerMenu {
    public static final int BUTTON_TOGGLE = 0;

    private final MachineKind kind;
    private final ContainerData data;
    private final BlockPos pos;
    @Nullable
    private final MachineBlockEntity machine;

    /** Côté serveur. */
    public MachineMenu(int id, Inventory inventory, MachineBlockEntity machine, ContainerData data) {
        this(id, inventory, machine.kind(), machine.items(), data, machine.getBlockPos(), machine);
    }

    /** Côté client (données envoyées par {@code openMenu}). */
    public MachineMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(id, inventory, buf.readBlockPos(), MachineKind.byOrdinal(buf.readVarInt()));
    }

    private MachineMenu(int id, Inventory inventory, BlockPos pos, MachineKind kind) {
        this(id, inventory, kind, new SimpleContainer(kind.slotCount()), new SimpleContainerData(MachineBlockEntity.DATA_SIZE), pos, null);
    }

    private MachineMenu(int id, Inventory inventory, MachineKind kind, Container container, ContainerData data,
                        BlockPos pos, @Nullable MachineBlockEntity machine) {
        super(ModMenus.MACHINE.get(), id);
        this.kind = kind;
        this.data = data;
        this.pos = pos;
        this.machine = machine;
        for (int i = 0; i < kind.slotCount(); i++) {
            int[] xy = kind.slot(i);
            int index = i;
            addSlot(new Slot(container, i, xy[0], xy[1]) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return accepts(kind, index, stack);
                }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 142));
        }
        addDataSlots(data);
    }

    /** Règle de dépôt dans une case de machine (les cases de sortie n'acceptent rien). */
    public static boolean accepts(MachineKind kind, int slot, ItemStack stack) {
        return switch (kind) {
            case THERMAL -> stack.getBurnTime(RecipeType.SMELTING) > 0;
            case GAS_DRILL -> slot == 0 && stack.is(ModItems.GAS_BOTTLE.get());
            case REACTOR -> stack.is(ModItems.ENRICHED_FUEL.get());
            case REFINERY -> slot == MachineKind.REFINERY_GAS ? stack.is(ModItems.FULL_GAS_BOTTLE.get())
                    : slot == MachineKind.REFINERY_BLAZE && stack.is(Items.BLAZE_POWDER);
            case GAS_BOILER -> slot == MachineKind.BOILER_GAS && stack.is(ModItems.FULL_GAS_BOTTLE.get());
            case NUCLEAR_HEATER -> slot == MachineKind.HEATER_FUEL && stack.is(ModItems.ENRICHED_FUEL.get());
            default -> false;
        };
    }

    public MachineKind kind() {
        return kind;
    }

    public int value(int index) {
        return data.get(index);
    }

    /** Valeur 32 bits reconstituée à partir de deux moitiés synchronisées. */
    public long wide(int lowIndex) {
        return (data.get(lowIndex) & 0xFFFFL) | ((data.get(lowIndex + 1) & 0xFFFFL) << 16);
    }

    /** Valeur 64 bits reconstituée à partir de quatre quarts synchronisés (deux valeurs 32 bits consécutives). */
    public long wide64(int lowIndex) {
        return wide(lowIndex) | (wide(lowIndex + 2) << 32);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == BUTTON_TOGGLE && machine != null && kind == MachineKind.REACTOR) {
            machine.toggleRunning();
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int machineSlots = kind.slotCount();
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (machineSlots == 0 || !moveItemStackTo(stack, 0, machineSlots, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getCenter()) <= 64.0D
                && player.level().getBlockState(pos).getBlock() instanceof MachineBlock;
    }
}
