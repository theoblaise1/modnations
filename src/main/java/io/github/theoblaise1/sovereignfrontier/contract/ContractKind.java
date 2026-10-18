// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.contract;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringRepresentable;

import javax.annotation.Nullable;

/**
 * Les trois types de contrat. L'identifiant est le champ {@code kind} des modèles JSON et sert dans la sauvegarde
 * (NBT des contrats) : ne pas le renommer sans migration. L'ordinal est transmis à l'écran ({@code ContractLine}).
 */
public enum ContractKind implements StringRepresentable {
    DELIVER("deliver"),
    KILL("kill"),
    EXPLORE("explore");

    private static final ContractKind[] VALUES = values();

    private final String id;

    ContractKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /** Nom affiché du type (« Livraison ») : {@code sovereignfrontier.contract.kind.<id>}. */
    public MutableComponent displayName() {
        return Msg.tr("contract.kind." + id);
    }

    @Nullable
    public static ContractKind byId(String id) {
        for (ContractKind k : VALUES) {
            if (k.id.equals(id)) {
                return k;
            }
        }
        return null;
    }

    @Nullable
    public static ContractKind byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }
}
