package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → serveur : annuler la recherche {@code id}. Le serveur refuse si une autre recherche est
 * en cours entre-temps (confirmation ouverte sur un écran périmé).
 */
public record CancelResearchC2S(ResourceLocation id) implements CustomPacketPayload {
    public static final Type<CancelResearchC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "cancel_research"));
    public static final StreamCodec<io.netty.buffer.ByteBuf, CancelResearchC2S> STREAM_CODEC =
            ResourceLocation.STREAM_CODEC.map(CancelResearchC2S::new, CancelResearchC2S::id);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
