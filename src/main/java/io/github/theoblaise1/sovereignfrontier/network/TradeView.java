package io.github.theoblaise1.sovereignfrontier.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.UUID;

/**
 * Étal d'un marchand tel qu'affiché par l'écran de commerce. Les prix courants sont recalculés côté client à partir
 * des prix de base, de la pression ({@code b}, {@code s}) et des paramètres de tarification ; le serveur revalide
 * le total attendu à chaque échange.
 *
 * @param anchor          position du comptoir (l'écran se ferme si le joueur s'en éloigne)
 * @param variant         variante de l'avant-poste (plaines, désert, taïga)
 * @param rotation        numéro de rotation de l'étal (renvoyé dans {@link TradeActionC2S})
 * @param ticksToRotation ticks restants avant le réassort
 * @param wallet          portefeuille du joueur
 */
public record TradeView(UUID outpost, BlockPos anchor, Component title, String variant, long rotation,
                        long ticksToRotation, long wallet, PriceParams params, List<OfferLine> offers) {
    public static final int MAX_OFFERS = 24;

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeView> STREAM_CODEC =
            StreamCodec.ofMember(TradeView::write, TradeView::read);

    private void write(RegistryFriendlyByteBuf buf) {
        UUIDUtil.STREAM_CODEC.encode(buf, outpost);
        BlockPos.STREAM_CODEC.encode(buf, anchor);
        ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, title);
        buf.writeUtf(variant, 64);
        buf.writeVarLong(rotation);
        buf.writeVarLong(ticksToRotation);
        buf.writeVarLong(wallet);
        PriceParams.STREAM_CODEC.encode(buf, params);
        OfferLine.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_OFFERS)).encode(buf, offers);
    }

    private static TradeView read(RegistryFriendlyByteBuf buf) {
        return new TradeView(UUIDUtil.STREAM_CODEC.decode(buf), BlockPos.STREAM_CODEC.decode(buf),
                ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf), buf.readUtf(64), buf.readVarLong(),
                buf.readVarLong(), buf.readVarLong(), PriceParams.STREAM_CODEC.decode(buf),
                OfferLine.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_OFFERS)).decode(buf));
    }

    /**
     * Paramètres de tarification ({@code SFConfig [market]}) : prix {@code ceil(base × min(ceiling,
     * (1 + demandRise)^(B/step)))}, rachat {@code floor(base × max(floor, (1 − supplyDrop)^(S/step)))}.
     */
    public record PriceParams(int step, double supplyDrop, double demandRise, double floor, double ceiling) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PriceParams> STREAM_CODEC =
                StreamCodec.ofMember(PriceParams::write, PriceParams::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(step);
            buf.writeDouble(supplyDrop);
            buf.writeDouble(demandRise);
            buf.writeDouble(floor);
            buf.writeDouble(ceiling);
        }

        private static PriceParams read(RegistryFriendlyByteBuf buf) {
            PriceParams p = new PriceParams(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble());
            if (p.step <= 0 || !Double.isFinite(p.supplyDrop) || !Double.isFinite(p.demandRise)
                    || !Double.isFinite(p.floor) || !Double.isFinite(p.ceiling)) {
                throw new DecoderException("Paramètres de tarification invalides");
            }
            return p;
        }
    }

    /**
     * Une offre de l'étal.
     *
     * @param count      taille d'un lot
     * @param basePrice  prix de base d'un lot (0 = le marchand ne vend pas cet objet)
     * @param basePayout rachat de base d'un lot (0 = le marchand ne l'achète pas)
     * @param b          pression d'achat (lots achetés par les joueurs, amortie à chaque rotation)
     * @param s          pression de vente (lots vendus par les joueurs)
     * @param playerHas  objets intacts possédés par le joueur
     */
    public record OfferLine(Item item, int count, long basePrice, long basePayout, int stockLeft, int demandLeft,
                            double b, double s, int playerHas) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OfferLine> STREAM_CODEC =
                StreamCodec.ofMember(OfferLine::write, OfferLine::read);
        private static final StreamCodec<RegistryFriendlyByteBuf, Item> ITEM = ByteBufCodecs.registry(Registries.ITEM);

        private void write(RegistryFriendlyByteBuf buf) {
            ITEM.encode(buf, item);
            buf.writeVarInt(count);
            buf.writeVarLong(basePrice);
            buf.writeVarLong(basePayout);
            buf.writeVarInt(stockLeft);
            buf.writeVarInt(demandLeft);
            buf.writeDouble(b);
            buf.writeDouble(s);
            buf.writeVarInt(playerHas);
        }

        private static OfferLine read(RegistryFriendlyByteBuf buf) {
            OfferLine line = new OfferLine(ITEM.decode(buf), buf.readVarInt(), buf.readVarLong(), buf.readVarLong(),
                    buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readVarInt());
            if (!Double.isFinite(line.b) || !Double.isFinite(line.s)) {
                throw new DecoderException("Pression d'offre invalide");
            }
            return line;
        }
    }
}
