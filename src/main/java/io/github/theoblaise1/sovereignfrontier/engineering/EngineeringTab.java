package io.github.theoblaise1.sovereignfrontier.engineering;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.MutableComponent;

/** Onglets de la Station d'ingénierie ; chacun est débloqué par un palier de recherche. */
public enum EngineeringTab {
    MACHINES("machines", "station.machines"),
    ARMEMENT("armement", "station.armament"),
    AEROSPATIAL("aerospatial", "station.aerospace");

    private final String id;
    private final String unlock;

    EngineeringTab(String id, String unlock) {
        this.id = id;
        this.unlock = unlock;
    }

    public String id() {
        return id;
    }

    /** Clé de déblocage de recherche requise pour ouvrir l'onglet. */
    public String unlock() {
        return unlock;
    }

    public MutableComponent displayName() {
        return Msg.tr("engineering.tab." + id);
    }

    public static EngineeringTab byId(String id) {
        for (EngineeringTab t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }
}
