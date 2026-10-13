package io.github.theoblaise1.sovereignfrontier.energy;

/** Bloc-entité relié au réseau d'énergie. */
public interface EnergyNode {
    EnergyBuffer energyBuffer();

    EnergyRole energyRole();
}
