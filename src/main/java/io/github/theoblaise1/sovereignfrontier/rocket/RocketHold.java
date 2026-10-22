// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Soute de la fusée (docs/conception/J5.md §4.1) : 9 cases, ouvertes dans un coffre vanilla d'une rangée. Le bloc-entité
 * ne l'expose jamais comme conteneur ni comme capacité d'objets : aucun entonnoir ne la vide ni ne la remplit.
 * <p>
 * Au décollage, la soute d'origine est <b>invalidée</b> ({@link #invalidate()}) : vidée, puis sourde à toute écriture
 * ({@code setItem}, {@code removeItem}, {@code addItem}…) et muette à la lecture. Un menu resté ouvert (ou un mod qui
 * garde la référence) ne peut donc rien dupliquer ni reprendre après le transfert.
 */
public final class RocketHold extends SimpleContainer {
    public static final int SIZE = 9;
    /** Distance maximale (blocs) d'un joueur à la fusée pour garder la soute ouverte. */
    public static final double RANGE = 8.0D;

    @Nullable
    private final RocketBlockEntity rocket;
    private boolean invalidated;
    private final Set<Player> viewers = new LinkedHashSet<>();

    RocketHold(@Nullable RocketBlockEntity rocket) {
        super(SIZE);
        this.rocket = rocket;
        addListener(c -> {
            if (this.rocket != null) {
                this.rocket.setChanged();
            }
        });
    }

    /** Soute autonome (GameTests, calculs de plan) : sans bloc, toujours valable. */
    public static RocketHold detached() {
        return new RocketHold(null);
    }

    public boolean invalidated() {
        return invalidated;
    }

    /** Vide la soute puis la rend inerte : plus aucune lecture ni écriture ne passe. */
    public void invalidate() {
        super.clearContent();
        invalidated = true;
        viewers.clear();
    }

    /** Joueurs qui ont la soute ouverte (les menus sont fermés avant le transfert). */
    public List<Player> viewers() {
        return new ArrayList<>(viewers);
    }

    @Override
    public void startOpen(Player player) {
        if (!invalidated) {
            viewers.add(player);
        }
    }

    @Override
    public void stopOpen(Player player) {
        viewers.remove(player);
    }

    @Override
    public boolean stillValid(Player player) {
        if (invalidated) {
            return false;
        }
        if (rocket == null) {
            return true;
        }
        if (rocket.isRemoved() || rocket.getLevel() == null || rocket.getLevel() != player.level()) {
            return false;
        }
        BlockState state = rocket.getBlockState();
        return state.is(ModBlocks.ROCKET.get()) && !state.getValue(RocketBlock.LAUNCHING)
                && player.distanceToSqr(rocket.getBlockPos().getCenter()) <= RANGE * RANGE;
    }

    // ------------------------------------------------------------------ comptage et prélèvement (objets intacts)

    /** Nombre d'objets intacts (sans composant modifié) de ce type dans la soute. */
    public int countPlain(Item item) {
        return countPlain(this, item);
    }

    public static int countPlain(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (Inventories.isPlain(stack, item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Vrai si la soute contient au moins un exemplaire de cet objet (propulseur, module : intact ou non). */
    public boolean contains(Item item) {
        return contains(this, item);
    }

    public static boolean contains(Container container, Item item) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retire exactement {@code amount} objets intacts de ce type ; sans effet (et {@code false}) s'il n'y en a pas assez.
     */
    public boolean consumePlain(Item item, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (countPlain(item) < amount) {
            return false;
        }
        int remaining = amount;
        for (int i = 0; i < getContainerSize() && remaining > 0; i++) {
            ItemStack stack = getItem(i);
            if (Inventories.isPlain(stack, item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) {
                    super.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        setChanged();
        return true;
    }

    /** Copie des piles non vides, dans l'ordre des cases. */
    public List<ItemStack> copyStacks() {
        List<ItemStack> copies = new ArrayList<>();
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack stack = getItem(i);
            if (!stack.isEmpty()) {
                copies.add(stack.copy());
            }
        }
        return copies;
    }

    // ------------------------------------------------------------------ soute invalidée : sourde et muette

    @Override
    public ItemStack getItem(int slot) {
        return invalidated ? ItemStack.EMPTY : super.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return invalidated ? ItemStack.EMPTY : super.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return invalidated ? ItemStack.EMPTY : super.removeItemNoUpdate(slot);
    }

    @Override
    public ItemStack removeItemType(Item item, int amount) {
        return invalidated ? ItemStack.EMPTY : super.removeItemType(item, amount);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!invalidated) {
            super.setItem(slot, stack);
        }
    }

    @Override
    public ItemStack addItem(ItemStack stack) {
        return invalidated ? stack : super.addItem(stack);
    }

    @Override
    public boolean canAddItem(ItemStack stack) {
        return !invalidated && super.canAddItem(stack);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return !invalidated;
    }

    @Override
    public boolean isEmpty() {
        return invalidated || super.isEmpty();
    }

    @Override
    public void clearContent() {
        if (!invalidated) {
            super.clearContent();
        }
    }
}
