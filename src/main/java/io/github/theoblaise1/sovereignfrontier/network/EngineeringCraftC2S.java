package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → serveur : fabriquer la recette d'ingénierie {@code id} à la station ouverte. */
public record EngineeringCraftC2S(ResourceLocation id) implements CustomPacketPayload {
    public static final Type<EngineeringCraftC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "engineering_craft"));
    public static final StreamCodec<ByteBuf, EngineeringCraftC2S> STREAM_CODEC =
            ResourceLocation.STREAM_CODEC.map(EngineeringCraftC2S::new, EngineeringCraftC2S::id);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
