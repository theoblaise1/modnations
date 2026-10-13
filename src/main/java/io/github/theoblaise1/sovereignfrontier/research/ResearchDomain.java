package io.github.theoblaise1.sovereignfrontier.research;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.MutableComponent;

import java.util.Optional;

/** Les 5 domaines de recherche et leur nombre de paliers (séquentiels dans chaque domaine). */
public enum ResearchDomain {
    CIVIL("civil", 15),
    MILITAIRE("militaire", 16),
    RESSOURCES("ressources", 15),
    INDUSTRIE("industrie", 12),
    TECHNOLOGIE("technologie", 7);

    private final String id;
    private final int tierCount;

    ResearchDomain(String id, int tierCount) {
        this.id = id;
        this.tierCount = tierCount;
    }

    public String id() {
        return id;
    }

    public int tierCount() {
        return tierCount;
    }

    public MutableComponent displayName() {
        return Msg.tr("research.domain." + id);
    }

    public static Optional<ResearchDomain> byId(String id) {
        for (ResearchDomain d : values()) {
            if (d.id.equalsIgnoreCase(id)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }
}
