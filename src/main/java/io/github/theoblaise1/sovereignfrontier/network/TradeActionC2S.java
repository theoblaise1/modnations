package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.UUID;

/**
 * Client → serveur : acheter ({@code buy}) ou vendre {@code lots} lots d'un objet à un marchand. Le serveur revalide
 * tout (distance à l'ancre, rotation, stock ou demande, {@code expectedTotal}, fonds, place) ; {@code lots} hors de
 * 1..{@link #MAX_LOTS} est ignoré.
 */
public record TradeActionC2S(UUID outpost, long rotation, Item item, boolean buy, int lots, long expectedTotal)
        implements CustomPacketPayload {
    public static final int MAX_LOTS = 64;
    public static final Type<TradeActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "trade_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TradeActionC2S> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, TradeActionC2S::outpost,
            ByteBufCodecs.VAR_LONG, TradeActionC2S::rotation,
            ByteBufCodecs.registry(Registries.ITEM), TradeActionC2S::item,
            ByteBufCodecs.BOOL, TradeActionC2S::buy,
            ByteBufCodecs.VAR_INT, TradeActionC2S::lots,
            ByteBufCodecs.VAR_LONG, TradeActionC2S::expectedTotal,
            TradeActionC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
