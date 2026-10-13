package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur → client : état de la nation. Si {@code openScreen}, le client ouvre l'écran national
 * sur l'onglet {@code tab} (ordinal de {@link NationTab}) ; sinon il rafraîchit l'écran s'il est ouvert.
 */
public record NationSnapshotS2C(NationSnapshot snapshot, boolean openScreen, int tab) implements CustomPacketPayload {
    public static final Type<NationSnapshotS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "nation_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, NationSnapshotS2C> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                p.snapshot().write(buf);
                buf.writeBoolean(p.openScreen());
                buf.writeVarInt(p.tab());
            },
            buf -> new NationSnapshotS2C(NationSnapshot.read(buf), buf.readBoolean(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
