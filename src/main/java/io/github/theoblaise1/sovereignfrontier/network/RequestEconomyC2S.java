package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → serveur : demande une {@link EconomyViewS2C} (ouverture de l'onglet Économie ; débit limité par joueur). */
public record RequestEconomyC2S() implements CustomPacketPayload {
    public static final RequestEconomyC2S INSTANCE = new RequestEconomyC2S();
    public static final Type<RequestEconomyC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "request_economy"));
    public static final StreamCodec<ByteBuf, RequestEconomyC2S> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
