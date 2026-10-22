package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → serveur : action sur le contrat n°{@code id} du tableau de la nation ({@link #ACCEPT}, {@link #ABANDON}
 * ou {@link #DELIVER} — livraison du maximum possible). Une action inconnue est ignorée.
 */
public record ContractActionC2S(byte action, int id) implements CustomPacketPayload {
    public static final byte ACCEPT = 0;
    public static final byte ABANDON = 1;
    public static final byte DELIVER = 2;

    public static final Type<ContractActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "contract_action"));
    public static final StreamCodec<ByteBuf, ContractActionC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, ContractActionC2S::action,
            ByteBufCodecs.VAR_INT, ContractActionC2S::id,
            ContractActionC2S::new);

    public static boolean isValid(byte action) {
        return action >= ACCEPT && action <= DELIVER;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
