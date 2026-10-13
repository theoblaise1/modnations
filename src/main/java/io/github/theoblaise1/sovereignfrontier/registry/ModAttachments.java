package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.player.PlayerProfile;
import io.github.theoblaise1.sovereignfrontier.player.TerritoryTracker;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** DataAttachments portés par les joueurs. */
public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, SovereignFrontier.MODID);

    /** Profil persistant (sérialisé avec le joueur, conservé à la mort). */
    public static final Supplier<AttachmentType<PlayerProfile>> PROFILE = ATTACHMENTS.register("profile",
            () -> AttachmentType.builder(() -> PlayerProfile.EMPTY)
                    .serialize(PlayerProfile.CODEC)
                    .copyOnDeath()
                    .build());

    /** Dernier territoire traversé (transitoire, non sauvegardé) pour les messages d'entrée/sortie. */
    public static final Supplier<AttachmentType<TerritoryTracker>> TERRITORY_TRACKER = ATTACHMENTS.register(
            "territory_tracker", () -> AttachmentType.builder(() -> new TerritoryTracker()).build());

    private ModAttachments() {
    }
}
