// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

/**
 * {@code /rocket} et {@code /sfadmin rocket} (docs/conception/J5.md §4.7). Ébauche du socle : {@link #registerAdmin}
 * est appelée par {@link AdminCommand} et n'ajoute rien jusqu'au lot WP2.
 */
public final class RocketCommand {
    private RocketCommand() {
    }

    /** Greffe la sous-commande {@code rocket} sur l'arbre {@code /sfadmin}. */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
    }
}
