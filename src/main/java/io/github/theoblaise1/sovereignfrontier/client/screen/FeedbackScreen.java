package io.github.theoblaise1.sovereignfrontier.client.screen;

import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Écran qui affiche le résultat d'une action serveur ({@code ActionFeedbackS2C}) dans sa barre d'état. Sans écran de
 * ce type ouvert, le message est déjà dans le chat.
 */
@OnlyIn(Dist.CLIENT)
public interface FeedbackScreen {
    void showFeedback(Component message, boolean success);
}
