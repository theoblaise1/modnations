package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/** Matériaux d'armure (registre dynamique de 1.21). */
public final class ModArmorMaterials {
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, SovereignFrontier.MODID);

    /**
     * Plastron chauffant (J5) : défense de plastron 6 (comme le fer), réparable au lingot de titane. La couche
     * {@code textures/models/armor/plastron_chauffant_layer_1.png} appartient au lot graphique.
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> HEATED = ARMOR_MATERIALS.register("plastron_chauffant",
            () -> new ArmorMaterial(Util.make(new EnumMap<>(ArmorItem.Type.class), defense -> {
                defense.put(ArmorItem.Type.BOOTS, 2);
                defense.put(ArmorItem.Type.LEGGINGS, 5);
                defense.put(ArmorItem.Type.CHESTPLATE, 6);
                defense.put(ArmorItem.Type.HELMET, 2);
                defense.put(ArmorItem.Type.BODY, 5);
            }), 9, SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(ModItems.TITANIUM_INGOT.get()),
                    List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID,
                            "plastron_chauffant"))), 0.0F, 0.0F));

    private ModArmorMaterials() {
    }
}
