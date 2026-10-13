package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → serveur : lancer le palier {@code id}. */
public record StartResearchC2S(ResourceLocation id) implements CustomPacketPayload {
    public static final Type<StartResearchC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "start_research"));
    public static final StreamCodec<ByteBuf, StartResearchC2S> STREAM_CODEC =
            ResourceLocation.STREAM_CODEC.map(StartResearchC2S::new, StartResearchC2S::id);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
