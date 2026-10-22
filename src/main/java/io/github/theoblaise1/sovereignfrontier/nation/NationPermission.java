package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.MutableComponent;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Permissions granulaires attribuables à un rôle. L'identifiant (français) sert dans les
 * commandes et dans la sauvegarde NBT : ne pas le renommer sans migration.
 */
public enum NationPermission {
    CLAIM("revendiquer"),
    UNCLAIM("abandonner"),
    WITHDRAW("retirer"),
    NATIONAL_CHEST("coffre"),
    BUILD("construire"),
    INVITE("inviter"),
    KICK("exclure"),
    START_RESEARCH("rechercher"),
    DECLARE_WAR("declarer_guerre"),
    SIGN_TREATY("signer_traite"),
    FIRE_MISSILE("tirer_missile"),
    /** J4 : contrats et entreprises (bit 11, ajoutée en dernier : les masques existants restent valables). */
    ECONOMY("economie");

    /**
     * Version du schéma des permissions sauvegardé dans le NBT de chaque nation ({@code permVersion}).
     * 0 (absente) : avant le J4 ; 1 : {@link #ECONOMY} existe.
     */
    public static final int CURRENT_VERSION = 1;

    private static final NationPermission[] VALUES = values();
    private final String id;

    NationPermission(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public MutableComponent displayName() {
        return Msg.tr("perm." + id);
    }

    public static NationPermission byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    public static Optional<NationPermission> byId(String id) {
        for (NationPermission p : VALUES) {
            if (p.id.equalsIgnoreCase(id)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public static int toMask(Set<NationPermission> perms) {
        int mask = 0;
        for (NationPermission p : perms) {
            mask |= p.bit();
        }
        return mask;
    }

    public static EnumSet<NationPermission> fromMask(int mask) {
        EnumSet<NationPermission> set = EnumSet.noneOf(NationPermission.class);
        for (NationPermission p : VALUES) {
            if ((mask & p.bit()) != 0) {
                set.add(p);
            }
        }
        return set;
    }

    /** Permissions par défaut d'une nation fraîchement fondée. */
    public static EnumSet<NationPermission> defaults(NationRole role) {
        return switch (role) {
            case CHEF -> EnumSet.allOf(NationPermission.class);
            case MINISTRE -> EnumSet.of(CLAIM, UNCLAIM, WITHDRAW, NATIONAL_CHEST, BUILD, INVITE, KICK, START_RESEARCH, SIGN_TREATY,
                    ECONOMY);
            case OFFICIER -> EnumSet.of(CLAIM, NATIONAL_CHEST, BUILD, INVITE, KICK, START_RESEARCH, ECONOMY);
            case CITOYEN -> EnumSet.of(NATIONAL_CHEST, BUILD);
            case RECRUE -> EnumSet.of(BUILD);
        };
    }

    /**
     * Migration (fonction pure) des permissions d'un rôle sauvegardées avec la version de schéma {@code fromVersion} :
     * une permission apparue depuis est accordée si les défauts du rôle la contiennent ; les choix du chef sur les
     * permissions existantes sont conservés.
     */
    public static EnumSet<NationPermission> migrate(NationRole role, Set<NationPermission> saved, int fromVersion) {
        EnumSet<NationPermission> out = EnumSet.noneOf(NationPermission.class);
        out.addAll(saved);
        if (fromVersion < 1 && defaults(role).contains(ECONOMY)) {
            out.add(ECONOMY);
        }
        return out;
    }
}
