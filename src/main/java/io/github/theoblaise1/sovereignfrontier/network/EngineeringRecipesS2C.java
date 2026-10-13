package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRecipe;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Serveur → client : recettes de la Station d'ingénierie (connexion et /reload). */
public record EngineeringRecipesS2C(List<EngineeringRecipe> recipes) implements CustomPacketPayload {
    public static final Type<EngineeringRecipesS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "engineering_recipes"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EngineeringRecipesS2C> STREAM_CODEC =
            EngineeringRecipe.STREAM_CODEC.apply(ByteBufCodecs.list(1024)).map(EngineeringRecipesS2C::new, EngineeringRecipesS2C::recipes);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
