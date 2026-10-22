// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.space;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Diffuseur d'oxygène (docs/conception/J5.md §5.2 et §5.3) : consomme son énergie et maintient une zone d'oxygène
 * ({@link ZoneIndex}) sur les chunks de sa nation, si celle-ci a {@code oxygen.systems}.
 * <p>
 * Travail par fenêtres de {@value #WINDOW} ticks, en O(1) : au premier tick de chaque fenêtre, le diffuseur relit le
 * propriétaire de son chunk (une lecture d'index) et la clé de sa nation, paie d'avance la fenêtre
 * ({@code diffuserEnergyPerTick × 20}, tout ou rien) puis rafraîchit sa zone ({@link ZoneIndex#TTL_TICKS} = 40 ticks :
 * une fenêtre ratée est tolérée, deux ne le sont pas). Un diffuseur sans énergie, sans territoire ou sans la clé de sa
 * nation ne rafraîchit rien : sa zone expire d'elle-même. Aucun parcours de nations ni de chunks.
 */
public final class OxygenDiffuserLogic {
    /** Durée d'une fenêtre de travail (ticks) : la machine n'évalue son état qu'une fois par fenêtre. */
    public static final int WINDOW = 20;

    private OxygenDiffuserLogic() {
    }

    /** Vrai au premier tick de chaque fenêtre (le tout premier tick d'une machine chargée en est un). */
    static boolean windowStart(MachineBlockEntity machine) {
        return machine.ticks() % WINDOW == 1;
    }

    /**
     * Nation propriétaire du chunk de {@code pos} si elle possède le déblocage {@code unlockKey}, sinon {@code null}
     * (chunk libre ou nation sans la clé). Une seule lecture de l'index de territoire.
     */
    @Nullable
    static Nation ownerWith(ServerLevel level, BlockPos pos, String unlockKey) {
        Nation owner = NationData.get(level.getServer()).ownerAt(level.dimension(), pos);
        return owner != null && owner.research().hasUnlock(unlockKey) ? owner : null;
    }

    /** Un tick serveur du diffuseur en {@code pos} ; état via les accesseurs de {@code machine}. */
    public static void tick(ServerLevel level, BlockPos pos, MachineBlockEntity machine) {
        if (!windowStart(machine)) {
            return;
        }
        Nation owner = ownerWith(level, pos, UnlockKeys.OXYGEN_SYSTEMS);
        boolean running = owner != null && machine.energyBuffer().consume((long) SFConfig.DIFFUSER_ENERGY_PER_TICK.get() * WINDOW);
        machine.setActive(running);
        if (running) {
            ZoneIndex.activate(level.dimension(), pos, ZoneIndex.Kind.OXYGEN, SFConfig.DIFFUSER_RADIUS_CHUNKS.get(),
                    SFConfig.DIFFUSER_HALF_HEIGHT.get(), owner.id(), level.getGameTime());
        }
    }
}
