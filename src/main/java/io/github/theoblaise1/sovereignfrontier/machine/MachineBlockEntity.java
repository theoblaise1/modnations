package io.github.theoblaise1.sovereignfrontier.machine;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyBuffer;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyNetworks;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyNode;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyRole;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Comportement de toutes les machines du J3, selon leur {@link MachineKind}. Travail par tick
 * borné : quelques opérations arithmétiques et une distribution sur le réseau mis en cache ;
 * la structure du réacteur n'est revalidée que toutes les 40 ticks.
 */
public class MachineBlockEntity extends BlockEntity implements EnergyNode, MenuProvider {
    /** Vrai pendant l'explosion d'un réacteur : la protection du territoire ne s'applique pas à sa propre nation. */
    public static boolean meltdownInProgress;

    public static final int DATA_SIZE = 11;
    public static final int FLAG_RUNNING = 1;
    public static final int FLAG_VALID = 2;
    public static final int FLAG_ACTIVE = 4;
    public static final int GAS_TANK = 1_000;
    public static final int BOTTLE_LITERS = 100;

    private final MachineKind kind;
    private final EnergyBuffer energy;
    private final SimpleContainer items;
    private int burn;
    private int burnTotal;
    private int heat;
    private int progress;
    private int gasTank;
    private int fuelTicks;
    private boolean running = true;
    private boolean valid;
    private int cells;
    private int sinks;
    private int counter;
    private long dimensionGas;

    public MachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MACHINE.get(), pos, state);
        this.kind = state.getBlock() instanceof MachineBlock mb ? mb.kind() : MachineKind.THERMAL;
        this.energy = new EnergyBuffer(kind.capacity(), kind.maxIn(), kind.maxOut());
        this.items = new SimpleContainer(kind.slotCount());
        items.addListener(c -> setChanged());
    }

    public MachineKind kind() {
        return kind;
    }

    public SimpleContainer items() {
        return items;
    }

    @Override
    public EnergyBuffer energyBuffer() {
        return energy;
    }

    @Override
    public EnergyRole energyRole() {
        return kind.role();
    }

    public int heat() {
        return heat;
    }

    public int gasTank() {
        return gasTank;
    }

    public boolean structureValid() {
        return valid;
    }

    public int cells() {
        return cells;
    }

    public int sinks() {
        return sinks;
    }

    public void toggleRunning() {
        running = !running;
        setChanged();
    }

    // ------------------------------------------------------------------ tick

    public static void serverTick(Level level, BlockPos pos, BlockState state, MachineBlockEntity be) {
        be.tick((ServerLevel) level, pos, state);
    }

    public void tick(ServerLevel level, BlockPos pos, BlockState state) {
        counter++;
        switch (kind) {
            case THERMAL -> tickThermal();
            case SOLAR -> tickSolar(level, pos);
            case GAS_DRILL -> tickDrill(level);
            case REACTOR -> tickReactor(level, pos, state);
            case BATTERY -> {
            }
        }
        EnergyNetworks.push(level, pos, energy, kind.role());
        setChanged();
    }

    private void tickThermal() {
        if (burn <= 0 && energy.space() > 0) {
            ItemStack fuel = items.getItem(0);
            int time = fuel.isEmpty() ? 0 : fuel.getBurnTime(RecipeType.SMELTING);
            if (time > 0) {
                ItemStack remainder = fuel.getCraftingRemainingItem();
                fuel.shrink(1);
                if (fuel.isEmpty() && !remainder.isEmpty()) {
                    items.setItem(0, remainder);
                }
                burn = burnTotal = time;
            }
        }
        if (burn > 0) {
            burn--;
            energy.generate(SFConfig.THERMAL_OUTPUT.get());
        }
    }

    private void tickSolar(ServerLevel level, BlockPos pos) {
        if (level.isDay() && !level.isRaining() && level.canSeeSky(pos.above())) {
            energy.generate(SFConfig.SOLAR_OUTPUT.get());
            progress = 100;
        } else {
            progress = 0;
        }
    }

    private void tickDrill(ServerLevel level) {
        GasData gas = GasData.get(level);
        dimensionGas = gas.remaining();
        if (gasTank < GAS_TANK && dimensionGas > 0 && energy.consume(SFConfig.DRILL_ENERGY_PER_TICK.get())) {
            if (++progress >= 20) {
                progress = 0;
                long want = Math.min(SFConfig.DRILL_LITERS_PER_CYCLE.get(), GAS_TANK - gasTank);
                gasTank += (int) gas.extract(want);
                dimensionGas = gas.remaining();
            }
        }
        ItemStack input = items.getItem(0);
        ItemStack output = items.getItem(1);
        if (gasTank >= BOTTLE_LITERS && input.is(ModItems.GAS_BOTTLE.get())
                && (output.isEmpty() || (output.is(ModItems.FULL_GAS_BOTTLE.get()) && output.getCount() < output.getMaxStackSize()))) {
            input.shrink(1);
            if (output.isEmpty()) {
                items.setItem(1, new ItemStack(ModItems.FULL_GAS_BOTTLE.get()));
            } else {
                output.grow(1);
            }
            gasTank -= BOTTLE_LITERS;
        }
    }

    private void tickReactor(ServerLevel level, BlockPos pos, BlockState state) {
        if (counter % 40 == 1) {
            ReactorStructure.Result r = ReactorStructure.validate(level, pos, state.getValue(MachineBlock.FACING));
            valid = r.valid();
            cells = r.cells();
            sinks = r.sinks();
        }
        boolean active = false;
        if (valid && running) {
            if (fuelTicks <= 0) {
                ItemStack fuel = items.getItem(0);
                if (fuel.is(ModItems.ENRICHED_FUEL.get())) {
                    fuel.shrink(1);
                    fuelTicks = SFConfig.REACTOR_FUEL_TICKS.get();
                }
            }
            if (fuelTicks > 0) {
                fuelTicks--;
                energy.generate((long) cells * SFConfig.REACTOR_OUTPUT_PER_CELL.get());
                heat += cells * SFConfig.REACTOR_HEAT_PER_CELL.get();
                active = true;
            }
        }
        progress = active ? 100 : 0;
        heat = Math.max(0, heat - 1 - (valid ? sinks * SFConfig.REACTOR_DISSIPATION_PER_SINK.get() : 0));
        int max = SFConfig.REACTOR_MAX_HEAT.get();
        if (heat >= max) {
            meltdown(level, pos);
        } else if (heat >= max * 3 / 4 && counter % 100 == 0) {
            Component warning = Msg.err("reactor.warning", heat * 100 / max);
            for (ServerPlayer p : level.getPlayers(p -> p.distanceToSqr(pos.getCenter()) < 48 * 48)) {
                p.displayClientMessage(warning, true);
            }
        }
    }

    private void meltdown(ServerLevel level, BlockPos pos) {
        heat = 0;
        fuelTicks = 0;
        level.removeBlock(pos, false);
        meltdownInProgress = true;
        try {
            level.explode(null, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                    SFConfig.REACTOR_EXPLOSION_POWER.get().floatValue(), Level.ExplosionInteraction.BLOCK);
        } finally {
            meltdownInProgress = false;
        }
    }

    // ------------------------------------------------------------------ menu et synchronisation

    /** Valeurs synchronisées vers l'interface ; les longs sont découpés en deux moitiés de 16 bits. */
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> lo(energy.stored());
                case 1 -> hi(energy.stored());
                case 2 -> lo(energy.capacity());
                case 3 -> hi(energy.capacity());
                case 4 -> Math.min(heat, 0xFFFF);
                case 5 -> Math.min(SFConfig.REACTOR_MAX_HEAT.get(), 0xFFFF);
                case 6 -> kind == MachineKind.THERMAL ? (burnTotal == 0 ? 0 : burn * 100 / burnTotal)
                        : kind == MachineKind.GAS_DRILL ? progress * 5 : progress;
                case 7 -> kind == MachineKind.REACTOR ? cells * 100 + sinks : gasTank;
                case 8 -> lo(dimensionGas);
                case 9 -> hi(dimensionGas);
                case 10 -> (running ? FLAG_RUNNING : 0) | (valid ? FLAG_VALID : 0) | (progress > 0 || burn > 0 ? FLAG_ACTIVE : 0);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    private static int lo(long v) {
        return (int) (Math.min(v, 0xFFFFFFFFL) & 0xFFFF);
    }

    private static int hi(long v) {
        return (int) ((Math.min(v, 0xFFFFFFFFL) >>> 16) & 0xFFFF);
    }

    @Override
    public Component getDisplayName() {
        return Msg.tr("machine." + kind.id());
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new MachineMenu(id, inventory, this, data);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        energy.save(tag);
        ContainerHelper.saveAllItems(tag, items.getItems(), registries);
        tag.putInt("burn", burn);
        tag.putInt("burnTotal", burnTotal);
        tag.putInt("heat", heat);
        tag.putInt("gasTank", gasTank);
        tag.putInt("fuelTicks", fuelTicks);
        tag.putBoolean("running", running);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.load(tag);
        ContainerHelper.loadAllItems(tag, items.getItems(), registries);
        burn = tag.getInt("burn");
        burnTotal = tag.getInt("burnTotal");
        heat = tag.getInt("heat");
        gasTank = tag.getInt("gasTank");
        fuelTicks = tag.getInt("fuelTicks");
        running = !tag.contains("running") || tag.getBoolean("running");
    }
}
