package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → serveur : annuler la recherche en cours. */
public record CancelResearchC2S() implements CustomPacketPayload {
    public static final CancelResearchC2S INSTANCE = new CancelResearchC2S();
    public static final Type<CancelResearchC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "cancel_research"));
    public static final StreamCodec<ByteBuf, CancelResearchC2S> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
