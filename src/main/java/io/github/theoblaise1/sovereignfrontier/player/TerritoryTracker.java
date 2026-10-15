package io.github.theoblaise1.sovereignfrontier.player;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/** État transitoire : dernière dimension et dernière nation vues par le joueur (messages d'entrée/sortie). */
public final class TerritoryTracker {
    @Nullable
    public ResourceKey<Level> lastDimension;
    @Nullable
    public UUID lastOwner;
    public boolean initialized;
}
