package io.github.theoblaise1.sovereignfrontier.network;

/** Onglets de l'écran national (touche N). Partagé client/serveur : le serveur peut imposer un onglet. */
public enum NationTab {
    OVERVIEW("overview"),
    RESEARCH("research"),
    TREASURY("treasury"),
    TERRITORY("territory"),
    ROLES("roles"),
    JOURNAL("journal"),
    CONTRACTS("contracts");

    private static final NationTab[] VALUES = values();
    private final String id;

    NationTab(String id) {
        this.id = id;
    }

    /** Suffixe de clé de traduction : {@code sovereignfrontier.gui.tab.<id>}. */
    public String id() {
        return id;
    }

    public static NationTab byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : OVERVIEW;
    }
}
