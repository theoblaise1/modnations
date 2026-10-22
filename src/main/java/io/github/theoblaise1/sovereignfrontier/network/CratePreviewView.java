package io.github.theoblaise1.sovereignfrontier.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Aperçu du contenu d'une caisse (clic droit sans clé, {@code /crate preview}).
 *
 * @param kind      ordinal de {@code CrateKind}
 * @param rolls     tirages par ouverture
 * @param threshold seuil de pitié ({@code cratePityThreshold})
 * @param available faux si la table publiée manque (caisse indisponible)
 * @param entries   32 entrées au plus
 */
public record CratePreviewView(byte kind, int rolls, int threshold, boolean available, List<EntryLine> entries) {
    public static final int MAX_ENTRIES = 32;

    public static final StreamCodec<RegistryFriendlyByteBuf, CratePreviewView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, CratePreviewView::kind,
            ByteBufCodecs.VAR_INT, CratePreviewView::rolls,
            ByteBufCodecs.VAR_INT, CratePreviewView::threshold,
            ByteBufCodecs.BOOL, CratePreviewView::available,
            EntryLine.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), CratePreviewView::entries,
            CratePreviewView::new);

    /**
     * Une entrée de la table.
     *
     * @param icon        objet (lingot d'or pour l'argent)
     * @param min         quantité ou montant minimal
     * @param max         quantité ou montant maximal
     * @param money       vrai pour une entrée d'argent
     * @param weight      poids de l'entrée ; {@code weight / totalWeight} = chance par tirage
     * @param rarity      0 commun, 1 peu commun, 2 rare
     * @param myMiss      ouvertures sans cette entrée rare (-1 si elle n'est pas rare)
     * @param milestone   jalon d'une entrée provisoire (« J6 »), vide sinon
     * @param label       libellé d'une entrée provisoire ({@code reward.placeholder.<label>}), vide sinon
     */
    public record EntryLine(ItemStack icon, int min, int max, boolean money, int weight, int totalWeight, byte rarity,
                            int myMiss, String milestone, String label) {
        public static final byte COMMON = 0;
        public static final byte UNCOMMON = 1;
        public static final byte RARE = 2;

        public static final StreamCodec<RegistryFriendlyByteBuf, EntryLine> STREAM_CODEC =
                StreamCodec.ofMember(EntryLine::write, EntryLine::read);

        private void write(RegistryFriendlyByteBuf buf) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, icon);
            buf.writeVarInt(min);
            buf.writeVarInt(max);
            buf.writeBoolean(money);
            buf.writeVarInt(weight);
            buf.writeVarInt(totalWeight);
            buf.writeByte(rarity);
            buf.writeVarInt(myMiss);
            buf.writeUtf(milestone, 32);
            buf.writeUtf(label, 64);
        }

        private static EntryLine read(RegistryFriendlyByteBuf buf) {
            return new EntryLine(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readByte(), buf.readVarInt(),
                    buf.readUtf(32), buf.readUtf(64));
        }
    }
}
