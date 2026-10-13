package io.github.theoblaise1.sovereignfrontier.block;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.UUID;

/**
 * Menu 9×6 vanilla (pas d'écran client à enregistrer) sur l'inventaire partagé de la nation.
 * Reste valide tant que le joueur est proche du coffre, membre et titulaire de la permission.
 */
public class NationalChestMenu extends ChestMenu {
    private final GlobalPos chestPos;
    private final UUID nationId;

    public NationalChestMenu(int containerId, Inventory playerInventory, Container container, GlobalPos chestPos, UUID nationId) {
        super(MenuType.GENERIC_9x6, containerId, playerInventory, container, 6);
        this.chestPos = chestPos;
        this.nationId = nationId;
    }

    @Override
    public boolean stillValid(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return true;
        }
        if (!sp.level().dimension().equals(chestPos.dimension())
                || sp.distanceToSqr(chestPos.pos().getCenter()) > 64.0D
                || !sp.level().getBlockState(chestPos.pos()).is(ModBlocks.NATIONAL_CHEST.get())) {
            return false;
        }
        Nation nation = NationData.get(sp.server).byId(nationId);
        return nation != null && nation.hasPermission(sp.getUUID(), NationPermission.NATIONAL_CHEST);
    }
}
