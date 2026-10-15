package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → serveur : annuler la recherche {@code id} lancée à {@code startMs}. Le serveur refuse si une
 * autre recherche est en cours entre-temps, y compris un palier relancé (confirmation ouverte sur un écran périmé).
 */
public record CancelResearchC2S(ResourceLocation id, long startMs) implements CustomPacketPayload {
    public static final Type<CancelResearchC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "cancel_research"));
    public static final StreamCodec<ByteBuf, CancelResearchC2S> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, CancelResearchC2S::id,
            ByteBufCodecs.VAR_LONG, CancelResearchC2S::startMs,
            CancelResearchC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
