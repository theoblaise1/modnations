// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

/**
 * {@code /oxygen} et {@code /sfadmin oxygen|cold <joueur> <s>} (docs/conception/J5.md §5.1). Ébauche du socle :
 * {@link #registerAdmin} est appelée par {@link AdminCommand} et n'ajoute rien jusqu'au lot WP3.
 */
public final class EnvironmentCommand {
    private EnvironmentCommand() {
    }

    /** Greffe les sous-commandes {@code oxygen} et {@code cold} sur l'arbre {@code /sfadmin}. */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
    }
}
