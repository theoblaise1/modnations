package io.github.theoblaise1.sovereignfrontier.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Raccourcis clavier du mod. */
@OnlyIn(Dist.CLIENT)
public final class ModKeyMappings {
    public static final String CATEGORY = "key.categories.sovereignfrontier";

    /** Ouvre l'écran national (N par défaut). */
    public static final KeyMapping OPEN_NATION = new KeyMapping(
            "key.sovereignfrontier.open_nation",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            CATEGORY);

    private ModKeyMappings() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_NATION);
    }
}
