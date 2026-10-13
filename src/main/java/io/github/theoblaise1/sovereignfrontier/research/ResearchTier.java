package io.github.theoblaise1.sovereignfrontier.research;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Un palier chargé : identifiant (chemin du fichier JSON) + définition. */
public record ResearchTier(ResourceLocation id, ResearchDefinition definition) {
    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchTier> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, ResearchTier::id,
            ByteBufCodecs.fromCodecWithRegistries(ResearchDefinition.CODEC), ResearchTier::definition,
            ResearchTier::new);

    public ResearchDomain domain() {
        return definition.domain();
    }

    public int tier() {
        return definition.tier();
    }

    /** Clé de traduction du nom : {@code sovereignfrontier.research.<chemin>} (et {@code .desc}). */
    public String translationKey() {
        return "research." + id.getPath();
    }

    public MutableComponent displayName() {
        return Msg.tr(translationKey());
    }

    public MutableComponent description() {
        return Msg.tr(translationKey() + ".desc");
    }
}
