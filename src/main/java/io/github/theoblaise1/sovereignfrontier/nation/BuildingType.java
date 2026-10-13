package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/** Bâtiments civils : chacun validé ajoute un habitant à la nation. */
public enum BuildingType {
    HOUSE,
    FARM,
    WORKSHOP;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public MutableComponent displayName() {
        return Msg.tr("building." + id());
    }

    public static BuildingType byId(String id) {
        for (BuildingType t : values()) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return HOUSE;
    }
}
