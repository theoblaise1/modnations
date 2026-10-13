package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → serveur : revendiquer ({@code claim}) ou abandonner un chunk de la dimension courante, depuis la carte. */
public record ClaimChunkC2S(int chunkX, int chunkZ, boolean claim) implements CustomPacketPayload {
    public static final Type<ClaimChunkC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "claim_chunk"));
    public static final StreamCodec<ByteBuf, ClaimChunkC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ClaimChunkC2S::chunkX,
            ByteBufCodecs.VAR_INT, ClaimChunkC2S::chunkZ,
            ByteBufCodecs.BOOL, ClaimChunkC2S::claim,
            ClaimChunkC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
