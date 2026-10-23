package io.github.theoblaise1.sovereignfrontier.war;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * État d'une relation entre deux nations (J7, docs/conception/J7.md §1.1). {@link #PAIX} est l'état par défaut d'une
 * paire sans entrée. Les transitions de temps (préparation → guerre → trêve → paix) sont tenues par la boucle de guerre.
 */
public enum WarState {
    /** Pas de guerre : aucun combat entre joueurs des deux nations. */
    PAIX,
    /** Déclaration faite : préparation de {@code preparationHours}, annulable jusqu'à son terme. */
    PREPARATION,
    /** Guerre ouverte : combat autorisé entre les deux nations, score de guerre. */
    GUERRE,
    /** Trêve après une fin de guerre : pas de combat ni de nouvelle déclaration contre le même adversaire. */
    TREVE;

    /** Codec par nom de constante (chaîne dans le NBT). Un nom inconnu est une erreur de lecture, pas une exception. */
    public static final Codec<WarState> CODEC = Codec.STRING.comapFlatMap(WarState::fromName, WarState::name);

    private static DataResult<WarState> fromName(String name) {
        for (WarState state : values()) {
            if (state.name().equals(name)) {
                return DataResult.success(state);
            }
        }
        return DataResult.error(() -> "État de guerre inconnu : " + name);
    }
}
