package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → serveur : le chef modifie une case de la matrice rôle × permission. */
public record SetRolePermissionC2S(int role, int permission, boolean granted) implements CustomPacketPayload {
    public static final Type<SetRolePermissionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "set_role_permission"));
    public static final StreamCodec<ByteBuf, SetRolePermissionC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetRolePermissionC2S::role,
            ByteBufCodecs.VAR_INT, SetRolePermissionC2S::permission,
            ByteBufCodecs.BOOL, SetRolePermissionC2S::granted,
            SetRolePermissionC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
