package io.github.theoblaise1.sovereignfrontier.energy;

/** Rôle d'un nœud du réseau : les générateurs alimentent machines puis stockage ; le stockage n'alimente que les machines. */
public enum EnergyRole {
    GENERATOR,
    STORAGE,
    CONSUMER
}
