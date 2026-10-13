package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registre centralisé des objets (pour l'instant : les objets-blocs du jalon J1). */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SovereignFrontier.MODID);

    public static final DeferredItem<BlockItem> TREASURY = ITEMS.registerSimpleBlockItem(ModBlocks.TREASURY);
    public static final DeferredItem<BlockItem> NATIONAL_CHEST = ITEMS.registerSimpleBlockItem(ModBlocks.NATIONAL_CHEST);
    public static final DeferredItem<BlockItem> ARMORED_PLATING = ITEMS.registerSimpleBlockItem(ModBlocks.ARMORED_PLATING);
    public static final DeferredItem<BlockItem> CIVIL_REGISTRY = ITEMS.registerSimpleBlockItem(ModBlocks.CIVIL_REGISTRY);

    private ModItems() {
    }
}
