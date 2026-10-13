package io.github.theoblaise1.sovereignfrontier.machine;

import com.mojang.serialization.Codec;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyRole;
import net.minecraft.util.StringRepresentable;

/**
 * Types de machines : rôle sur le réseau, réserve, débits et emplacements d'objets
 * (coordonnées dans l'interface 176×166).
 */
public enum MachineKind implements StringRepresentable {
    THERMAL("generateur_thermique", EnergyRole.GENERATOR, 20_000, 0, 200, new int[][]{{80, 56}}),
    SOLAR("panneau_solaire", EnergyRole.GENERATOR, 4_000, 0, 64, new int[0][]),
    BATTERY("cellule_energie", EnergyRole.STORAGE, 200_000, 400, 400, new int[0][]),
    GAS_DRILL("foreuse_gaz", EnergyRole.CONSUMER, 20_000, 200, 0, new int[][]{{62, 56}, {116, 56}}),
    REACTOR("controleur_reacteur", EnergyRole.GENERATOR, 500_000, 0, 2_000, new int[][]{{80, 56}});

    public static final Codec<MachineKind> CODEC = StringRepresentable.fromEnum(MachineKind::values);

    private final String id;
    private final EnergyRole role;
    private final long capacity;
    private final long maxIn;
    private final long maxOut;
    private final int[][] slots;

    MachineKind(String id, EnergyRole role, long capacity, long maxIn, long maxOut, int[][] slots) {
        this.id = id;
        this.role = role;
        this.capacity = capacity;
        this.maxIn = maxIn;
        this.maxOut = maxOut;
        this.slots = slots;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String id() {
        return id;
    }

    public EnergyRole role() {
        return role;
    }

    public long capacity() {
        return capacity;
    }

    public long maxIn() {
        return maxIn;
    }

    public long maxOut() {
        return maxOut;
    }

    public int slotCount() {
        return slots.length;
    }

    public int[] slot(int index) {
        return slots[index];
    }

    public static MachineKind byOrdinal(int ordinal) {
        MachineKind[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : THERMAL;
    }
}
