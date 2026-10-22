// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.contract;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tableau de contrats d'une nation, persisté dans le NBT de {@code Nation} sous {@code "contracts"} : compteur
 * d'identifiants, numéro de génération (graine), prochain rafraîchissement (epoch), offres, contrats en cours et
 * historique des derniers contrats terminés. Les règles (génération, acceptation, échéances) sont dans
 * {@link ContractManager}. Appeler {@code Nation#markDirty()} après toute mutation.
 */
public final class NationContracts {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Borne de l'historique relu (le maximum de la configuration {@code contractHistorySize}). */
    public static final int MAX_HISTORY = EconomyView.ContractsView.MAX_HISTORY;
    /** Bornes des listes relues (maxima de la configuration). */
    public static final int MAX_OFFERS = EconomyView.ContractsView.MAX_OFFERS;
    public static final int MAX_ACTIVE = EconomyView.ContractsView.MAX_ACTIVE;

    /** Issue d'un contrat terminé ; l'identifiant est écrit dans la sauvegarde. */
    public enum Status {
        COMPLETED("completed", EconomyView.HistoryLine.COMPLETED),
        FAILED("failed", EconomyView.HistoryLine.FAILED),
        ABANDONED("abandoned", EconomyView.HistoryLine.ABANDONED);

        private final String id;
        private final byte code;

        Status(String id, byte code) {
            this.id = id;
            this.code = code;
        }

        public String id() {
            return id;
        }

        /** Code transmis à l'écran ({@code HistoryLine.status}). */
        public byte code() {
            return code;
        }

        @Nullable
        static Status byId(String id) {
            for (Status s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            return null;
        }
    }

    /** Contrat terminé (historique). */
    public record History(int id, ResourceLocation template, Status status, long epochMs) {
    }

    private int nextId = 1;
    private int serial;
    private long nextRefresh;
    private final List<ContractInstance> offers = new ArrayList<>();
    private final List<ContractInstance> active = new ArrayList<>();
    private final ArrayDeque<History> history = new ArrayDeque<>();
    /** Contrats d'exploration en cours (dérivé, reconstruit après chaque changement de {@link #active}). */
    @Nullable
    private List<ContractInstance> exploreCache;

    // ------------------------------------------------------------------ compteurs

    /** Identifiant suivant (n° affiché du contrat), jamais réutilisé. */
    public int allocateId() {
        int id = nextId;
        nextId = nextId >= Integer.MAX_VALUE - 1 ? 1 : nextId + 1;
        return id;
    }

    public int serial() {
        return serial;
    }

    /** Numéro de la génération suivante (graine du tirage). */
    public int nextSerial() {
        return ++serial;
    }

    /** Prochain rafraîchissement des offres (epoch, ms) ; 0 = tableau jamais généré. */
    public long nextRefresh() {
        return nextRefresh;
    }

    public void setNextRefresh(long epochMs) {
        this.nextRefresh = epochMs;
    }

    // ------------------------------------------------------------------ listes

    public List<ContractInstance> offers() {
        return Collections.unmodifiableList(offers);
    }

    public List<ContractInstance> active() {
        return Collections.unmodifiableList(active);
    }

    /** Contrats d'exploration en cours (liste en cache : vide dans le cas courant, sans allocation). */
    public List<ContractInstance> activeExplore() {
        List<ContractInstance> cache = exploreCache;
        if (cache == null) {
            List<ContractInstance> found = new ArrayList<>(0);
            for (ContractInstance c : active) {
                if (c.kind() == ContractKind.EXPLORE) {
                    found.add(c);
                }
            }
            cache = found.isEmpty() ? List.of() : Collections.unmodifiableList(found);
            exploreCache = cache;
        }
        return cache;
    }

    public List<History> history() {
        return List.copyOf(history);
    }

    @Nullable
    public ContractInstance offer(int id) {
        for (ContractInstance c : offers) {
            if (c.id() == id) {
                return c;
            }
        }
        return null;
    }

    @Nullable
    public ContractInstance activeContract(int id) {
        for (ContractInstance c : active) {
            if (c.id() == id) {
                return c;
            }
        }
        return null;
    }

    /** Vrai si un contrat issu de ce modèle est déjà offert ou en cours. */
    public boolean usesTemplate(ResourceLocation template) {
        for (ContractInstance c : offers) {
            if (c.template().equals(template)) {
                return true;
            }
        }
        for (ContractInstance c : active) {
            if (c.template().equals(template)) {
                return true;
            }
        }
        return false;
    }

    /** Remplace toutes les offres (rafraîchissement) ; les contrats en cours ne sont pas touchés. */
    public void replaceOffers(List<ContractInstance> next) {
        offers.clear();
        offers.addAll(next.subList(0, Math.min(next.size(), MAX_OFFERS)));
    }

    public void addOffer(ContractInstance contract) {
        if (offers.size() < MAX_OFFERS) {
            offers.add(contract);
        }
    }

    /** L'offre passe « en cours » (l'appelant fixe l'échéance) ; faux si elle n'est plus au tableau. */
    public boolean activate(ContractInstance contract) {
        if (!offers.remove(contract)) {
            return false;
        }
        addActive(contract);
        return true;
    }

    /** Ajoute directement un contrat en cours (activation, administration, GameTests). */
    public void addActive(ContractInstance contract) {
        if (active.size() < MAX_ACTIVE && !active.contains(contract)) {
            active.add(contract);
            exploreCache = null;
        }
    }

    /**
     * Transition unique hors de « en cours » : retire le contrat et l'inscrit à l'historique. Faux s'il n'était plus en
     * cours (déjà terminé) : l'appelant ne fait alors rien d'autre.
     */
    public boolean finish(ContractInstance contract, Status status, long now, int historySize) {
        if (!active.remove(contract)) {
            return false;
        }
        exploreCache = null;
        history.addLast(new History(contract.id(), contract.template(), status, now));
        trimHistory(historySize);
        return true;
    }

    private void trimHistory(int size) {
        int max = Math.max(0, Math.min(MAX_HISTORY, size));
        while (history.size() > max) {
            history.removeFirst();
        }
    }

    // ------------------------------------------------------------------ NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("nextId", nextId);
        tag.putInt("serial", serial);
        tag.putLong("nextRefresh", nextRefresh);
        tag.put("offers", saveList(offers));
        tag.put("active", saveList(active));
        ListTag hist = new ListTag();
        for (History h : history) {
            CompoundTag e = new CompoundTag();
            e.putInt("id", h.id());
            e.putString("tpl", h.template().toString());
            e.putString("status", h.status().id());
            e.putLong("t", h.epochMs());
            hist.add(e);
        }
        tag.put("history", hist);
        return tag;
    }

    private static ListTag saveList(List<ContractInstance> contracts) {
        ListTag list = new ListTag();
        for (ContractInstance c : contracts) {
            list.add(c.save());
        }
        return list;
    }

    public static NationContracts load(CompoundTag tag) {
        NationContracts contracts = new NationContracts();
        contracts.nextId = Math.max(1, tag.getInt("nextId"));
        contracts.serial = Math.max(0, tag.getInt("serial"));
        contracts.nextRefresh = Math.max(0L, tag.getLong("nextRefresh"));
        loadList(tag.getList("offers", Tag.TAG_COMPOUND), contracts.offers, MAX_OFFERS);
        loadList(tag.getList("active", Tag.TAG_COMPOUND), contracts.active, MAX_ACTIVE);
        ListTag hist = tag.getList("history", Tag.TAG_COMPOUND);
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag e = hist.getCompound(i);
            ResourceLocation tpl = ResourceLocation.tryParse(e.getString("tpl"));
            Status status = Status.byId(e.getString("status"));
            if (tpl != null && status != null) {
                contracts.history.addLast(new History(e.getInt("id"), tpl, status, e.getLong("t")));
            }
        }
        contracts.trimHistory(MAX_HISTORY);
        // Identifiants jamais réutilisés, même après une sauvegarde incohérente.
        for (ContractInstance c : contracts.offers) {
            contracts.nextId = Math.max(contracts.nextId, c.id() + 1);
        }
        for (ContractInstance c : contracts.active) {
            contracts.nextId = Math.max(contracts.nextId, c.id() + 1);
        }
        return contracts;
    }

    private static void loadList(ListTag list, List<ContractInstance> into, int max) {
        for (int i = 0; i < list.size() && into.size() < max; i++) {
            ContractInstance c = ContractInstance.load(list.getCompound(i));
            if (c == null) {
                LOGGER.warn("Contrat illisible ignoré : {}", list.getCompound(i));
            } else {
                into.add(c);
            }
        }
    }
}
