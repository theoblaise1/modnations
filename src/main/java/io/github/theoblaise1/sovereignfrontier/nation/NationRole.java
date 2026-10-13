package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;
import java.util.Optional;

/** Rôles hiérarchiques, du plus élevé (CHEF) au plus bas (RECRUE). */
public enum NationRole {
    CHEF,
    MINISTRE,
    OFFICIER,
    CITOYEN,
    RECRUE;

    private static final NationRole[] VALUES = values();

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public MutableComponent displayName() {
        return Msg.tr("role." + id());
    }

    /** Vrai si ce rôle est strictement au-dessus de {@code other} dans la hiérarchie. */
    public boolean outranks(NationRole other) {
        return ordinal() < other.ordinal();
    }

    public static NationRole byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : RECRUE;
    }

    public static Optional<NationRole> byId(String id) {
        for (NationRole r : VALUES) {
            if (r.id().equalsIgnoreCase(id)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
