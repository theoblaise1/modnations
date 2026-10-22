package io.github.theoblaise1.sovereignfrontier.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Onglet « Économie » de l'écran national : contrats (WP3) et entreprises (WP4) de la nation du joueur.
 * Construit par {@code EconomyNetwork} (vue personnalisée : permission, part estimée, contribution).
 * Toutes les listes sont bornées à la lecture comme à l'écriture.
 *
 * @param canManage vrai si le joueur a la permission {@code economie} (boutons d'action actifs)
 */
public record EconomyView(boolean canManage, ContractsView contracts, EnterprisesView enterprises) {
    public static final EconomyView EMPTY = new EconomyView(false, ContractsView.EMPTY, EnterprisesView.EMPTY);

    public static final StreamCodec<RegistryFriendlyByteBuf, EconomyView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, EconomyView::canManage,
            ContractsView.STREAM_CODEC, EconomyView::contracts,
            EnterprisesView.STREAM_CODEC, EconomyView::enterprises,
            EconomyView::new);

    // ------------------------------------------------------------------ contrats

    /**
     * Tableau des contrats. Bornes des listes = maxima de la configuration ({@code contractMaxOffers} 12,
     * {@code contractMaxActive} 10, {@code contractHistorySize} 50).
     *
     * @param maxActive   contrats actifs simultanés autorisés
     * @param offerSlots  emplacements d'offres du tableau
     * @param refreshInMs temps restant avant le prochain rafraîchissement des offres
     */
    public record ContractsView(int maxActive, int offerSlots, long refreshInMs, List<ContractLine> offers,
                                List<ContractLine> active, List<HistoryLine> history) {
        public static final int MAX_OFFERS = 12;
        public static final int MAX_ACTIVE = 10;
        public static final int MAX_HISTORY = 50;
        public static final ContractsView EMPTY = new ContractsView(0, 0, 0L, List.of(), List.of(), List.of());

        public static final StreamCodec<RegistryFriendlyByteBuf, ContractsView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ContractsView::maxActive,
                ByteBufCodecs.VAR_INT, ContractsView::offerSlots,
                ByteBufCodecs.VAR_LONG, ContractsView::refreshInMs,
                ContractLine.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_OFFERS)), ContractsView::offers,
                ContractLine.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ACTIVE)), ContractsView::active,
                HistoryLine.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_HISTORY)), ContractsView::history,
                ContractsView::new);
    }

    /**
     * Un contrat du tableau (offre ou en cours).
     *
     * @param kind             ordinal du type de contrat (livraison, chasse, exploration)
     * @param icon             objet représentatif (vide si aucun)
     * @param myShareEstimate  part personnelle estimée du joueur si le contrat réussissait maintenant
     * @param keys             clés déjà tirées à la génération, par ordinal de {@code CrateKind} (4 valeurs)
     * @param timeLeftMs       temps restant avant l'échéance (contrat en cours), 0 pour une offre
     * @param topContributors  3 meilleurs contributeurs au plus, du plus gros au plus petit
     */
    public record ContractLine(int id, byte kind, Component title, Component objective, ItemStack icon, int progress,
                               int required, long money, long myShareEstimate, long points, int[] keys,
                               long timeLeftMs, int myContribution, List<Contributor> topContributors) {
        public static final int KEY_KINDS = 4;
        public static final int MAX_CONTRIBUTORS = 3;

        public static final StreamCodec<RegistryFriendlyByteBuf, ContractLine> STREAM_CODEC =
                StreamCodec.ofMember(ContractLine::write, ContractLine::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(id);
            buf.writeByte(kind);
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, title);
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, objective);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, icon);
            buf.writeVarInt(progress);
            buf.writeVarInt(required);
            buf.writeVarLong(money);
            buf.writeVarLong(myShareEstimate);
            buf.writeVarLong(points);
            for (int i = 0; i < KEY_KINDS; i++) {
                buf.writeVarInt(i < keys.length ? keys[i] : 0);
            }
            buf.writeVarLong(timeLeftMs);
            buf.writeVarInt(myContribution);
            Contributor.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CONTRIBUTORS)).encode(buf, topContributors);
        }

        private static ContractLine read(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            byte kind = buf.readByte();
            Component title = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
            Component objective = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
            ItemStack icon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            int progress = buf.readVarInt();
            int required = buf.readVarInt();
            long money = buf.readVarLong();
            long share = buf.readVarLong();
            long points = buf.readVarLong();
            int[] keys = new int[KEY_KINDS];
            for (int i = 0; i < KEY_KINDS; i++) {
                keys[i] = buf.readVarInt();
            }
            long timeLeft = buf.readVarLong();
            int mine = buf.readVarInt();
            List<Contributor> top = Contributor.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CONTRIBUTORS)).decode(buf);
            return new ContractLine(id, kind, title, objective, icon, progress, required, money, share, points, keys,
                    timeLeft, mine, top);
        }
    }

    /** Contributeur d'un contrat : nom et unités apportées. */
    public record Contributor(String name, int amount) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Contributor> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), Contributor::name,
                ByteBufCodecs.VAR_INT, Contributor::amount,
                Contributor::new);
    }

    /**
     * Contrat terminé (historique).
     *
     * @param status  0 = réussi, 1 = échoué (échéance), 2 = abandonné
     * @param epochMs date de fin
     */
    public record HistoryLine(int id, Component title, byte status, long epochMs) {
        public static final byte COMPLETED = 0;
        public static final byte FAILED = 1;
        public static final byte ABANDONED = 2;

        public static final StreamCodec<RegistryFriendlyByteBuf, HistoryLine> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HistoryLine::id,
                ComponentSerialization.TRUSTED_STREAM_CODEC, HistoryLine::title,
                ByteBufCodecs.BYTE, HistoryLine::status,
                ByteBufCodecs.VAR_LONG, HistoryLine::epochMs,
                HistoryLine::new);
    }

    // ------------------------------------------------------------------ entreprises

    /**
     * Entreprises de la nation.
     *
     * @param population    habitants effectifs
     * @param assigned      habitants affectés aux secteurs
     * @param ticksToCycle  ticks restants avant le prochain cycle de revenu et de production
     * @param passiveIncome revenu passif par cycle des habitants libres (cr)
     * @param sectors       une ligne par secteur (ordre des ordinaux de {@code Sector})
     */
    public record EnterprisesView(int population, int assigned, int ticksToCycle, long passiveIncome,
                                  List<SectorLine> sectors) {
        public static final int MAX_SECTORS = 4;
        public static final EnterprisesView EMPTY = new EnterprisesView(0, 0, 0, 0L, List.of());

        public static final StreamCodec<RegistryFriendlyByteBuf, EnterprisesView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, EnterprisesView::population,
                ByteBufCodecs.VAR_INT, EnterprisesView::assigned,
                ByteBufCodecs.VAR_INT, EnterprisesView::ticksToCycle,
                ByteBufCodecs.VAR_LONG, EnterprisesView::passiveIncome,
                SectorLine.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SECTORS)), EnterprisesView::sectors,
                EnterprisesView::new);
    }

    /**
     * Un secteur d'entreprise.
     *
     * @param sector     ordinal de {@code Sector}
     * @param lockReason motif du verrou (vide si débloqué)
     * @param mult       multiplicateur de secteur (paliers du domaine)
     * @param outputs    8 produits au plus
     * @param lastMoney  argent versé au dernier cycle
     * @param lastItems  objets déposés au dernier cycle
     * @param lastLost   objets perdus au dernier cycle (coffre national plein)
     */
    public record SectorLine(byte sector, boolean unlocked, Component lockReason, int workers, float mult,
                             long moneyPerCycle, List<OutputLine> outputs, long lastMoney, int lastItems, int lastLost) {
        public static final int MAX_OUTPUTS = 8;

        public static final StreamCodec<RegistryFriendlyByteBuf, SectorLine> STREAM_CODEC =
                StreamCodec.ofMember(SectorLine::write, SectorLine::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeByte(sector);
            buf.writeBoolean(unlocked);
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, lockReason);
            buf.writeVarInt(workers);
            buf.writeFloat(mult);
            buf.writeVarLong(moneyPerCycle);
            OutputLine.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_OUTPUTS)).encode(buf, outputs);
            buf.writeVarLong(lastMoney);
            buf.writeVarInt(lastItems);
            buf.writeVarInt(lastLost);
        }

        private static SectorLine read(RegistryFriendlyByteBuf buf) {
            byte sector = buf.readByte();
            boolean unlocked = buf.readBoolean();
            Component reason = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
            int workers = buf.readVarInt();
            float mult = buf.readFloat();
            if (!Float.isFinite(mult)) {
                throw new DecoderException("Multiplicateur de secteur invalide");
            }
            long money = buf.readVarLong();
            List<OutputLine> outputs = OutputLine.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_OUTPUTS)).decode(buf);
            return new SectorLine(sector, unlocked, reason, workers, mult, money, outputs, buf.readVarLong(),
                    buf.readVarInt(), buf.readVarInt());
        }
    }

    /** Produit d'un secteur : icône, quantité par cycle (fractions comprises), verrouillé par la recherche. */
    public record OutputLine(ItemStack icon, float perCycle, boolean locked) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputLine> STREAM_CODEC = StreamCodec.composite(
                ItemStack.OPTIONAL_STREAM_CODEC, OutputLine::icon,
                ByteBufCodecs.FLOAT, OutputLine::perCycle,
                ByteBufCodecs.BOOL, OutputLine::locked,
                OutputLine::new);
    }
}
