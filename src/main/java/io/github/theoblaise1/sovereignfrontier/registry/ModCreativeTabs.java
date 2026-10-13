package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Onglet créatif unique du mod. */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SovereignFrontier.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Msg.tr("creative_tab"))
                    .icon(() -> ModItems.TREASURY.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(ModItems.TREASURY.get());
                        output.accept(ModItems.NATIONAL_CHEST.get());
                        output.accept(ModItems.ARMORED_PLATING.get());
                        output.accept(ModItems.CIVIL_REGISTRY.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
