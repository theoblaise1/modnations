package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → serveur : affectation absolue de {@code workers} habitants au secteur d'ordinal {@code sector}.
 * Secteur inconnu ou nombre hors de 0..{@link #MAX_WORKERS} : paquet ignoré.
 */
public record EnterpriseAssignC2S(byte sector, int workers) implements CustomPacketPayload {
    public static final int MAX_WORKERS = 10_000;

    public static final Type<EnterpriseAssignC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "enterprise_assign"));
    public static final StreamCodec<ByteBuf, EnterpriseAssignC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, EnterpriseAssignC2S::sector,
            ByteBufCodecs.VAR_INT, EnterpriseAssignC2S::workers,
            EnterpriseAssignC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
