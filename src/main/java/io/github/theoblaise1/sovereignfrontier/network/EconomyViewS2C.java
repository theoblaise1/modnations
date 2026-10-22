package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Serveur → client : vue « Économie » (contrats et entreprises) de la nation du joueur. */
public record EconomyViewS2C(EconomyView view) implements CustomPacketPayload {
    public static final Type<EconomyViewS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "economy_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EconomyViewS2C> STREAM_CODEC =
            EconomyView.STREAM_CODEC.map(EconomyViewS2C::new, EconomyViewS2C::view);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
