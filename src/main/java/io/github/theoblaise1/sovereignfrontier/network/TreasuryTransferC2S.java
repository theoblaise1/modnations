package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → serveur : dépôt ({@code deposit}) ou retrait d'un montant entre portefeuille et trésorerie. */
public record TreasuryTransferC2S(long amount, boolean deposit) implements CustomPacketPayload {
    public static final Type<TreasuryTransferC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "treasury_transfer"));
    public static final StreamCodec<ByteBuf, TreasuryTransferC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TreasuryTransferC2S::amount,
            ByteBufCodecs.BOOL, TreasuryTransferC2S::deposit,
            TreasuryTransferC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
