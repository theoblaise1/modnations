package io.github.theoblaise1.sovereignfrontier.reward;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Remise de clés de caisse, point d'entrée unique des sources de clés (/daily, contrats, puis boss de donjon et
 * victoires de guerre). Aucune clé ne se fabrique.
 */
public final class CrateKeys {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CrateKeys() {
    }

    public static ItemStack stack(CrateKind kind, int count) {
        return new ItemStack(kind.keyItem(), count);
    }

    /**
     * Donne {@code count} clés au joueur ; ce qui ne rentre pas tombe à ses pieds, réservé à lui (comme {@code /give}).
     *
     * @return nombre de clés lâchées au sol
     */
    public static int give(ServerPlayer player, CrateKind kind, int count) {
        if (count <= 0) {
            return 0;
        }
        return Inventories.giveOrDrop(player, stack(kind, count));
    }

    /**
     * Dépose {@code count} clés dans le coffre national ; s'il est plein, le reste va à {@code fallbackPlayer}
     * (inventaire, sinon au sol). Sans joueur de repli, le reste est perdu et journalisé.
     *
     * @return nombre de clés perdues (0 dans le cas normal)
     */
    public static int giveToNation(MinecraftServer server, Nation nation, CrateKind kind, int count,
                                   @Nullable ServerPlayer fallbackPlayer) {
        int remaining = Math.max(0, count);
        int max = kind.keyItem().getDefaultMaxStackSize();
        while (remaining > 0) {
            int portion = Math.min(max, remaining);
            ItemStack rest = nation.chest().addItem(stack(kind, portion));
            remaining -= portion - rest.getCount();
            if (!rest.isEmpty()) {
                break;
            }
        }
        if (remaining > 0 && fallbackPlayer != null) {
            give(fallbackPlayer, kind, remaining);
            remaining = 0;
        }
        if (remaining > 0) {
            LOGGER.warn("[Économie] {} clé(s) {} perdue(s) : coffre national de {} plein, aucun joueur de repli",
                    remaining, kind.id(), nation.name());
        }
        return remaining;
    }
}
