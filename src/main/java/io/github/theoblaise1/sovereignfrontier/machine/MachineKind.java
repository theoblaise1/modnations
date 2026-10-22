package io.github.theoblaise1.sovereignfrontier.machine;

import com.mojang.serialization.Codec;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyRole;
import net.minecraft.util.StringRepresentable;

/**
 * Types de machines : rôle sur le réseau, réserve, débits et emplacements d'objets
 * (coordonnées dans l'interface 176×166). Les types du J5 sont ajoutés en fin d'énumération : les ordinaux
 * (envoyés au client à l'ouverture du menu) des types existants ne changent pas. Une machine « inerte » (chaudière,
 * réchauffeur) est un consommateur de capacité nulle : le réseau ne lui donne jamais rien.
 */
public enum MachineKind implements StringRepresentable {
    THERMAL("generateur_thermique", EnergyRole.GENERATOR, 20_000, 0, 200, new int[][]{{80, 56}}),
    SOLAR("panneau_solaire", EnergyRole.GENERATOR, 4_000, 0, 64, new int[0][]),
    BATTERY("cellule_energie", EnergyRole.STORAGE, 200_000, 400, 400, new int[0][]),
    GAS_DRILL("foreuse_gaz", EnergyRole.CONSUMER, 20_000, 200, 0, new int[][]{{62, 56}, {116, 56}}),
    REACTOR("controleur_reacteur", EnergyRole.GENERATOR, 500_000, 0, 2_000, new int[][]{{80, 56}}),
    /** J5 : gaz plein et poudre de blaze en entrée ; carburant de fusée et bouteilles vides en sortie. */
    REFINERY("raffinerie", EnergyRole.CONSUMER, 50_000, 400, 0, new int[][]{{44, 56}, {62, 56}, {116, 56}, {134, 56}}),
    /** J5 : zone oxygénée autour de lui, sans emplacement. */
    OXYGEN_DIFFUSER("diffuseur_oxygene", EnergyRole.CONSUMER, 20_000, 200, 0, new int[0][]),
    /** J5 : inerte ; gaz plein en entrée, bouteilles vides en sortie. */
    GAS_BOILER("chaudiere_gaz", EnergyRole.CONSUMER, 0, 0, 0, new int[][]{{62, 56}, {116, 56}}),
    /** J5 : inerte ; combustible enrichi en entrée. Ne produit pas d'énergie. */
    NUCLEAR_HEATER("rechauffeur_nucleaire", EnergyRole.CONSUMER, 0, 0, 0, new int[][]{{80, 56}});

    public static final Codec<MachineKind> CODEC = StringRepresentable.fromEnum(MachineKind::values);

    /** Emplacements de la Raffinerie. */
    public static final int REFINERY_GAS = 0;
    public static final int REFINERY_BLAZE = 1;
    public static final int REFINERY_FUEL_OUT = 2;
    public static final int REFINERY_EMPTY_OUT = 3;
    /** Emplacements de la Chaudière à gaz. */
    public static final int BOILER_GAS = 0;
    public static final int BOILER_EMPTY_OUT = 1;
    /** Emplacement du Réchauffeur nucléaire. */
    public static final int HEATER_FUEL = 0;

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

    /** Vrai pour les machines du J5 (programme spatial) : leur état « actif » est fixé par leur logique. */
    public boolean isSpace() {
        return ordinal() >= REFINERY.ordinal();
    }

    public static MachineKind byOrdinal(int ordinal) {
        MachineKind[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : THERMAL;
    }
}
