package io.github.theoblaise1.sovereignfrontier.machine;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyBuffer;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyNetworks;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyNode;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyRole;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.rocket.RefineryLogic;
import io.github.theoblaise1.sovereignfrontier.space.HeaterLogic;
import io.github.theoblaise1.sovereignfrontier.space.OxygenDiffuserLogic;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.SpaceDimensions;
import io.github.theoblaise1.sovereignfrontier.space.ZoneIndex;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
 * Comportement de toutes les machines, selon leur {@link MachineKind}. Travail par tick
 * borné : quelques opérations arithmétiques et une distribution sur le réseau mis en cache ;
 * la structure du réacteur n'est revalidée que toutes les 40 ticks. Les machines du J5 délèguent leur tick à
 * {@link RefineryLogic}, {@link OxygenDiffuserLogic} et {@link HeaterLogic}, qui passent par les accesseurs ci-dessous
 * (aucun nouveau champ persisté : la Raffinerie utilise {@code progress}, la Chaudière {@code burn}/{@code burnTotal},
 * le Réchauffeur {@code fuelTicks}).
 */
public class MachineBlockEntity extends BlockEntity implements EnergyNode, MenuProvider {
    /**
     * Centre de l'explosion d'un réacteur en cours, sinon {@code null} : cette explosion-là (reconnue
     * à son centre) ignore la protection du territoire de la nation du réacteur, et d'elle seule.
     */
    @javax.annotation.Nullable
    public static net.minecraft.world.phys.Vec3 meltdownInProgress;

    public static final int DATA_SIZE = 15;
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
    /** Arrêté à la pose : le réacteur ne démarre qu'au bouton « Démarrer ». */
    private boolean running;
    private boolean valid;
    private int cells;
    private int sinks;
    private int counter;
    private long dimensionGas;
    /** Machines du J5 : état « actif » fixé par leur logique à chaque tick (non persisté). */
    private boolean active;

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

    // ------------------------------------------------------------------ accesseurs des logiques du J5

    /**
     * Compteur de ticks du bloc-entité depuis son chargement (non persisté) : les logiques du J5 s'en servent pour
     * leurs passages toutes les 20 ticks.
     */
    public int ticks() {
        return counter;
    }

    /** Progression du cycle (Raffinerie : ticks écoulés du cycle). */
    public int progress() {
        return progress;
    }

    public void setProgress(int value) {
        progress = Math.max(0, value);
    }

    /** Combustion restante (Chaudière : ticks de la bouteille en cours). */
    public int burn() {
        return burn;
    }

    public int burnTotal() {
        return burnTotal;
    }

    public void setBurn(int remaining, int total) {
        burn = Math.max(0, remaining);
        burnTotal = Math.max(0, total);
    }

    /** Combustible restant (réacteur, Réchauffeur nucléaire : ticks). */
    public int fuelTicks() {
        return fuelTicks;
    }

    public void setFuelTicks(int value) {
        fuelTicks = Math.max(0, value);
    }

    /** État « actif » d'une machine du J5 (zone maintenue, cycle en cours) : écran et diagnostic. */
    public boolean active() {
        return kind.isSpace() ? active : progress > 0 || burn > 0;
    }

    public void setActive(boolean value) {
        active = value;
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
            case REFINERY -> RefineryLogic.tick(level, pos, this);
            case OXYGEN_DIFFUSER -> OxygenDiffuserLogic.tick(level, pos, this);
            case GAS_BOILER -> HeaterLogic.tickBoiler(level, pos, this);
            case NUCLEAR_HEATER -> HeaterLogic.tickNuclear(level, pos, this);
        }
        EnergyNetworks.push(level, pos, energy, kind.role());
        setChanged();
    }

    /**
     * Retrait du bloc-entité (bloc cassé, chunk déchargé) : la zone d'oxygène ou de chaleur de la machine disparaît
     * aussitôt de l'index.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide() && kind.isSpace()) {
            ZoneIndex.remove(level.dimension(), worldPosition);
        }
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
        long output = solarOutput(level.isDay(), level.isRaining(), level.canSeeSky(pos.above()),
                SpaceDimensions.planet(level), SFConfig.SOLAR_OUTPUT.get());
        if (output > 0) {
            energy.generate(output);
            progress = 100;
        } else {
            progress = 0;
        }
    }

    /**
     * Production d'un panneau solaire (En/tick) : de jour, ciel visible. Hors planète, rien sous la pluie ; sur une
     * planète (J5), la pluie terrestre (météo partagée de l'Overworld) est ignorée et la production est multipliée par
     * {@code solar_multiplier}, arrondie au plus proche.
     */
    public static long solarOutput(boolean day, boolean raining, boolean skyVisible, @javax.annotation.Nullable PlanetDefinition planet,
                                   int base) {
        if (!day || !skyVisible) {
            return 0L;
        }
        if (planet == null) {
            return raining ? 0L : base;
        }
        return Math.max(0L, Math.round(base * planet.solarMultiplier()));
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
        int before = heat;
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
        int warnAt = max * 3 / 4;
        // Avertissement au tick où le seuil est franchi (une montée rapide ne saute pas la fenêtre), puis toutes les 100 ticks.
        if (heat >= warnAt && (before < warnAt || counter % 100 == 0)) {
            Component warning = Msg.err("reactor.warning", Math.min(100, heat * 100 / max));
            for (ServerPlayer p : level.getPlayers(p -> p.distanceToSqr(pos.getCenter()) < 48 * 48)) {
                p.displayClientMessage(warning, true);
            }
        }
        if (heat >= max) {
            meltdown(level, pos);
        }
    }

    private void meltdown(ServerLevel level, BlockPos pos) {
        heat = 0;
        fuelTicks = 0;
        level.removeBlock(pos, false);
        net.minecraft.world.phys.Vec3 core = pos.getCenter();
        meltdownInProgress = core;
        try {
            level.explode(null, core.x, core.y, core.z,
                    SFConfig.REACTOR_EXPLOSION_POWER.get().floatValue(), Level.ExplosionInteraction.BLOCK);
        } finally {
            meltdownInProgress = null;
        }
    }

    // ------------------------------------------------------------------ menu et synchronisation

    /**
     * Valeurs synchronisées vers l'interface. Les valeurs 32 bits sont découpées en deux moitiés de 16 bits
     * (énergie 0-3, chaleur 4-5, chaleur maximale 8-9), la réserve de gaz 64 bits en quatre quarts (11-14).
     */
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> lo(energy.stored());
                case 1 -> hi(energy.stored());
                case 2 -> lo(energy.capacity());
                case 3 -> hi(energy.capacity());
                case 4 -> lo(heat);
                case 5 -> hi(heat);
                case 6 -> percent();
                case 7 -> kind == MachineKind.REACTOR ? cells * 100 + sinks : gasTank;
                case 8 -> lo(SFConfig.REACTOR_MAX_HEAT.get());
                case 9 -> hi(SFConfig.REACTOR_MAX_HEAT.get());
                case 10 -> (running ? FLAG_RUNNING : 0) | (valid ? FLAG_VALID : 0) | (active() ? FLAG_ACTIVE : 0);
                case 11 -> quarter(dimensionGas, 0);
                case 12 -> quarter(dimensionGas, 1);
                case 13 -> quarter(dimensionGas, 2);
                case 14 -> quarter(dimensionGas, 3);
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

    /**
     * Valeur d'écran n° 6 (0 à 100) : combustion (générateur thermique, chaudière), progression (foreuse, raffinerie),
     * combustible restant (réchauffeur nucléaire), production (panneau, réacteur).
     */
    private int percent() {
        return switch (kind) {
            case THERMAL, GAS_BOILER -> burnTotal == 0 ? 0 : burn * 100 / burnTotal;
            case GAS_DRILL -> progress * 5;
            case REFINERY -> Math.min(100, progress * 100 / Math.max(1, SFConfig.REFINERY_CYCLE_TICKS.get()));
            case NUCLEAR_HEATER -> Math.min(100, (int) ((long) fuelTicks * 100 / Math.max(1, SFConfig.NUCLEAR_HEATER_FUEL_TICKS.get())));
            case OXYGEN_DIFFUSER -> active ? 100 : 0;
            default -> progress;
        };
    }

    private static int lo(long v) {
        return (int) (Math.min(v, 0xFFFFFFFFL) & 0xFFFF);
    }

    private static int hi(long v) {
        return (int) ((Math.min(v, 0xFFFFFFFFL) >>> 16) & 0xFFFF);
    }

    /** Quart de 16 bits d'une valeur 64 bits, sans plafond : {@code part} vaut 0 (bits 0-15) à 3 (bits 48-63). */
    private static int quarter(long v, int part) {
        return (int) ((v >>> (16 * part)) & 0xFFFF);
    }

    @Override
    public Component getDisplayName() {
        return Msg.tr("machine." + kind.id());
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new MachineMenu(id, inventory, this, data);
    }

    /**
     * Données lues par le client à l'ouverture (voir {@link MachineMenu}). Écrites ici pour tous les chemins
     * d'ouverture, y compris le mode spectateur : sans elles, le client ne sait pas construire le menu.
     */
    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buffer) {
        buffer.writeBlockPos(worldPosition);
        buffer.writeVarInt(kind.ordinal());
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
        tag.putInt("progress", progress);
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
        progress = Math.max(0, tag.getInt("progress"));
        running = tag.getBoolean("running");
    }
}
