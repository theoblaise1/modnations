// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.contract;

import net.minecraft.nbt.CompoundTag;

/**
 * Tableau de contrats d'une nation (offres, contrats en cours, historique), persisté dans le NBT de
 * {@code Nation} sous {@code "contracts"}. Ébauche du socle : le NBT est conservé tel quel.
 * Appeler {@code Nation#markDirty()} après toute mutation.
 */
public final class NationContracts {
    private CompoundTag raw = new CompoundTag();

    public CompoundTag save() {
        return raw.copy();
    }

    public static NationContracts load(CompoundTag tag) {
        NationContracts contracts = new NationContracts();
        contracts.raw = tag.copy();
        return contracts;
    }
}
