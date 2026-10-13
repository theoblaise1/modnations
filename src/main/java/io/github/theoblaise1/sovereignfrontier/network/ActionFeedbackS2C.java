package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Serveur → client : résultat d'une action lancée depuis l'écran national, affiché dans sa barre d'état. */
public record ActionFeedbackS2C(Component message, boolean success) implements CustomPacketPayload {
    public static final Type<ActionFeedbackS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "action_feedback"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ActionFeedbackS2C> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.TRUSTED_STREAM_CODEC, ActionFeedbackS2C::message,
            ByteBufCodecs.BOOL, ActionFeedbackS2C::success,
            ActionFeedbackS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
