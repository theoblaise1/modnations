package io.github.theoblaise1.sovereignfrontier.enterprise;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.MutableComponent;

import javax.annotation.Nullable;

/**
 * Secteurs d'entreprise d'une nation. Le nom du fichier de définition
 * ({@code data/<ns>/enterprises/<id>.json}) est l'identifiant ; l'ordinal indexe les habitants
 * affectés ({@code NationEnterprises}) et les paquets : ne pas réordonner sans migration.
 */
public enum Sector {
    AGRICOLE("agricole"),
    MINIER("minier"),
    LOGISTIQUE("logistique"),
    INDUSTRIEL("industriel");

    private static final Sector[] VALUES = values();

    private final String id;

    Sector(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Nom affiché : {@code sovereignfrontier.enterprise.sector.<id>}. */
    public MutableComponent displayName() {
        return Msg.tr("enterprise.sector." + id);
    }

    @Nullable
    public static Sector byId(String id) {
        for (Sector s : VALUES) {
            if (s.id.equalsIgnoreCase(id)) {
                return s;
            }
        }
        return null;
    }

    @Nullable
    public static Sector byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }
}
