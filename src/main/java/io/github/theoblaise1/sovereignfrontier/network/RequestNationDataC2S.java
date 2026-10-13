package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → serveur : demande un {@link NationSnapshotS2C} ; {@code open} = ouvrir l'écran sur {@code tab}. */
public record RequestNationDataC2S(boolean open, int tab) implements CustomPacketPayload {
    public static final Type<RequestNationDataC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "request_nation_data"));
    public static final StreamCodec<ByteBuf, RequestNationDataC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, RequestNationDataC2S::open,
            ByteBufCodecs.VAR_INT, RequestNationDataC2S::tab,
            RequestNationDataC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
