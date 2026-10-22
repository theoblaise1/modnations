package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Serveur → client : aperçu d'une caisse (ouvre l'écran d'aperçu). */
public record CratePreviewS2C(CratePreviewView view) implements CustomPacketPayload {
    public static final Type<CratePreviewS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "crate_preview"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CratePreviewS2C> STREAM_CODEC =
            CratePreviewView.STREAM_CODEC.map(CratePreviewS2C::new, CratePreviewS2C::view);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
