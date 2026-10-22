package io.github.theoblaise1.sovereignfrontier.space;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Clés des dimensions du J5 (définies en JSON de datapack : {@code data/sovereignfrontier/dimension/}) et
 * reconnaissance des planètes. Une dimension est une planète si une planète publiée la déclare ({@link PlanetRegistry}) :
 * une planète de test liée à une autre dimension (GameTests, §7) compte donc aussi.
 */
public final class SpaceDimensions {
    public static final ResourceKey<Level> LUNE = key("lune");
    public static final ResourceKey<Level> MARS = key("mars");
    public static final ResourceKey<Level> PLANETE_GLACEE = key("planete_glacee");
    /** Les trois dimensions livrées par le mod. */
    public static final List<ResourceKey<Level>> ALL = List.of(LUNE, MARS, PLANETE_GLACEE);

    private SpaceDimensions() {
    }

    private static ResourceKey<Level> key(String path) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path));
    }

    /** Vrai si une planète publiée est liée à cette dimension. */
    public static boolean isPlanet(ResourceKey<Level> dimension) {
        return PlanetRegistry.get().byDimension(dimension) != null;
    }

    public static boolean isPlanet(Level level) {
        return isPlanet(level.dimension());
    }

    /** Planète du niveau, ou {@code null} hors d'une planète. */
    @Nullable
    public static PlanetDefinition planet(Level level) {
        return PlanetRegistry.get().byDimension(level.dimension());
    }
}
