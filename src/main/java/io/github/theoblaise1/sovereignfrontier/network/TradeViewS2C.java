package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Serveur → client : étal d'un marchand ; {@code open} = ouvrir l'écran de commerce, sinon le rafraîchir s'il est ouvert. */
public record TradeViewS2C(boolean open, TradeView view) implements CustomPacketPayload {
    public static final Type<TradeViewS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "trade_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TradeViewS2C> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, TradeViewS2C::open,
            TradeView.STREAM_CODEC, TradeViewS2C::view,
            TradeViewS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
