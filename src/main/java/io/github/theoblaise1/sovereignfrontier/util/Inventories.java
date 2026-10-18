package io.github.theoblaise1.sovereignfrontier.util;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Opérations d'inventaire partagées par l'économie (marchands, contrats, caisses, /daily).
 * <ul>
 *   <li>Objet « intact » : aucun composant modifié par rapport à l'objet de base (ni renommé, ni enchanté,
 *       ni abîmé, ni contenu) ;</li>
 *   <li>comptage et retrait : inventaire principal (36 cases) puis main secondaire ; jamais l'armure portée ;</li>
 *   <li>remise : inventaire, sinon chute au sol réservée au joueur, comme {@code /give}.</li>
 * </ul>
 */
public final class Inventories {
    private Inventories() {
    }

    /** Vrai pour une pile non vide sans aucun {@code DataComponentPatch}. */
    public static boolean isPlain(ItemStack stack) {
        return !stack.isEmpty() && stack.getComponentsPatch().isEmpty();
    }

    public static boolean isPlain(ItemStack stack, Item item) {
        return stack.is(item) && isPlain(stack);
    }

    /** Nombre d'objets intacts de ce type dans l'inventaire principal et la main secondaire. */
    public static int countPlain(Player player, Item item) {
        Inventory inv = player.getInventory();
        return countPlain(inv.items, item) + countPlain(inv.offhand, item);
    }

    private static int countPlain(NonNullList<ItemStack> stacks, Item item) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (isPlain(stack, item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Retire au plus {@code amount} objets intacts de ce type (inventaire principal, puis main secondaire) ;
     * renvoie le nombre réellement retiré. L'appelant vérifie d'abord {@link #countPlain}.
     */
    public static int consumePlain(Player player, Item item, int amount) {
        Inventory inv = player.getInventory();
        int removed = consumePlain(inv.items, item, amount);
        removed += consumePlain(inv.offhand, item, amount - removed);
        if (removed > 0) {
            inv.setChanged();
        }
        return removed;
    }

    private static int consumePlain(NonNullList<ItemStack> stacks, Item item, int amount) {
        int removed = 0;
        for (int i = 0; i < stacks.size() && removed < amount; i++) {
            ItemStack stack = stacks.get(i);
            if (isPlain(stack, item)) {
                int take = Math.min(amount - removed, stack.getCount());
                stack.shrink(take);
                removed += take;
            }
        }
        return removed;
    }

    /** Vrai si la pile tient entièrement dans l'inventaire principal (estimation prudente : sans la main secondaire). */
    public static boolean canFit(Player player, ItemStack stack) {
        return canFit(player, List.of(stack));
    }

    /** Vrai si toutes les piles tiennent ensemble dans l'inventaire principal (simulation sur une copie). */
    public static boolean canFit(Player player, List<ItemStack> stacks) {
        List<ItemStack> slots = new ArrayList<>(player.getInventory().items.size());
        for (ItemStack s : player.getInventory().items) {
            slots.add(s.copy());
        }
        for (ItemStack wanted : stacks) {
            int remaining = wanted.getCount();
            int max = wanted.getMaxStackSize();
            for (ItemStack slot : slots) {
                if (remaining <= 0) {
                    break;
                }
                if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, wanted)) {
                    int add = Math.min(remaining, Math.max(0, Math.min(max, slot.getMaxStackSize()) - slot.getCount()));
                    slot.grow(add);
                    remaining -= add;
                }
            }
            for (int i = 0; i < slots.size() && remaining > 0; i++) {
                if (slots.get(i).isEmpty()) {
                    int add = Math.min(remaining, max);
                    slots.set(i, wanted.copyWithCount(add));
                    remaining -= add;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Donne la pile au joueur ; ce qui ne rentre pas est lâché à ses pieds, sans délai de ramassage et réservé à
     * lui seul (comme {@code /give}). La pile peut dépasser la taille maximale : elle est découpée.
     *
     * @return nombre d'objets lâchés au sol
     */
    public static int giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        int remaining = stack.getCount();
        int max = Math.max(1, stack.getMaxStackSize());
        while (remaining > 0) {
            ItemStack piece = stack.copyWithCount(Math.min(max, remaining));
            remaining -= piece.getCount();
            player.getInventory().add(piece);
            if (!piece.isEmpty()) {
                dropped += piece.getCount();
                drop(player, piece);
            }
        }
        return dropped;
    }

    private static void drop(ServerPlayer player, ItemStack stack) {
        ItemEntity entity = player.drop(stack, false);
        if (entity == null) {
            // Lâcher annulé par un tiers : l'objet est quand même posé, il ne doit pas disparaître.
            entity = new ItemEntity(player.level(), player.getX(), player.getEyeY() - 0.3D, player.getZ(), stack);
            player.level().addFreshEntity(entity);
        }
        entity.setNoPickUpDelay();
        entity.setTarget(player.getUUID());
    }
}
