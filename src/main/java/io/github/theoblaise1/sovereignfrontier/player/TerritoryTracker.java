package io.github.theoblaise1.sovereignfrontier.player;

import javax.annotation.Nullable;
import java.util.UUID;

/** État transitoire : dernier chunk et dernière nation vus par le joueur. */
public final class TerritoryTracker {
    public long lastChunk = Long.MIN_VALUE;
    @Nullable
    public UUID lastOwner;
    public boolean initialized;
}
