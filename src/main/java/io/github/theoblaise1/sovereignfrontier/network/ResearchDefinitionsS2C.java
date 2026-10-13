package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Serveur → client : définitions des paliers de recherche (à la connexion et après /reload). */
public record ResearchDefinitionsS2C(List<ResearchTier> tiers) implements CustomPacketPayload {
    public static final Type<ResearchDefinitionsS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "research_definitions"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchDefinitionsS2C> STREAM_CODEC =
            ResearchTier.STREAM_CODEC.apply(ByteBufCodecs.list(512)).map(ResearchDefinitionsS2C::new, ResearchDefinitionsS2C::tiers);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
