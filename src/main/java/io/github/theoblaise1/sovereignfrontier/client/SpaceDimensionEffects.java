package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;

/**
 * Effets de dimension des planètes (docs/conception/J5.md §3.1) : ciel vanilla {@code NORMAL} sans nuages
 * ({@code cloudLevel = NaN}), sol présent, pas de lumière forcée. Les couleurs du ciel et du brouillard viennent du
 * biome de chaque planète. Aucune texture ni aucun rendu dessiné : le ciel décoré appartient au lot graphique. Sans cet
 * enregistrement, le client retomberait sur les effets de l'Overworld (nuages compris).
 */
@OnlyIn(Dist.CLIENT)
public final class SpaceDimensionEffects extends DimensionSpecialEffects {
    /** Identifiants {@code effects} des types de dimension {@code data/sovereignfrontier/dimension_type/*.json}. */
    public static final String[] PLANETS = {"lune", "mars", "planete_glacee"};

    private SpaceDimensionEffects() {
        super(Float.NaN, true, SkyType.NORMAL, false, false);
    }

    public static void register(RegisterDimensionSpecialEffectsEvent event) {
        for (String planet : PLANETS) {
            event.register(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, planet), new SpaceDimensionEffects());
        }
    }

    /** Brouillard assombri la nuit, comme dans l'Overworld. */
    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        return fogColor.multiply(brightness * 0.94F + 0.06F, brightness * 0.94F + 0.06F, brightness * 0.91F + 0.09F);
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }
}
