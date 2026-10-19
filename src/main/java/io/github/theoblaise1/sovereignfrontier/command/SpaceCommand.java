// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

/**
 * {@code /sfadmin space info|rescue <joueur>} (docs/conception/J5.md §3.4). Ébauche du socle : {@link #registerAdmin}
 * est appelée par {@link AdminCommand} et n'ajoute rien jusqu'au lot WP1.
 */
public final class SpaceCommand {
    private SpaceCommand() {
    }

    /** Greffe la sous-commande {@code space} sur l'arbre {@code /sfadmin}. */
    public static void registerAdmin(LiteralArgumentBuilder<CommandSourceStack> root) {
    }
}
