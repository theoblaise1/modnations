package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Serveur → client : ouvrir l'écran de la Station d'ingénierie située en {@code pos}. */
public record OpenEngineeringS2C(BlockPos pos) implements CustomPacketPayload {
    public static final Type<OpenEngineeringS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "open_engineering"));
    public static final StreamCodec<ByteBuf, OpenEngineeringS2C> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(OpenEngineeringS2C::new, OpenEngineeringS2C::pos);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
