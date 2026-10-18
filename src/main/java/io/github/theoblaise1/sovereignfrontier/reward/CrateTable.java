package io.github.theoblaise1.sovereignfrontier.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Table validée d'un type de caisse : {@code rolls} tirages pondérés sans remise parmi les entrées non provisoires.
 * Constantes de validation dans le code (jamais lues dans la config, absente au premier chargement des datapacks) :
 * {@value #MIN_ROLLS}..{@value #MAX_ROLLS} tirages, {@value #MAX_ENTRIES} entrées au plus, {@value #MIN_RARES} à
 * {@value #MAX_RARES} entrées rares (avec {@code id}), au moins une entrée ni rare ni provisoire, identifiants uniques,
 * assez d'entrées tirables pour {@code rolls}.
 */
public record CrateTable(int rolls, List<CrateEntry> entries) {
    public static final int MIN_ROLLS = 1;
    public static final int MAX_ROLLS = 6;
    public static final int MAX_ENTRIES = 32;
    public static final int MIN_RARES = 1;
    public static final int MAX_RARES = 3;

    private static final Codec<CrateTable> RAW = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(MIN_ROLLS, MAX_ROLLS).fieldOf("rolls").forGetter(CrateTable::rolls),
            CrateEntry.CODEC.listOf().fieldOf("entries").forGetter(CrateTable::entries)
    ).apply(i, CrateTable::new));

    /** Analyse et validation structurelle (l'interdiction d'objets demande les tags : voir {@link #bannedItems()}). */
    public static final Codec<CrateTable> CODEC = RAW.validate(CrateTable::validate);

    public CrateTable {
        entries = List.copyOf(entries);
    }

    private static DataResult<CrateTable> validate(CrateTable t) {
        if (t.entries.size() > MAX_ENTRIES) {
            return DataResult.error(() -> t.entries.size() + " entrées (au plus " + MAX_ENTRIES + ")");
        }
        Set<String> ids = new HashSet<>();
        int rares = 0;
        int plain = 0;
        int eligible = 0;
        for (CrateEntry e : t.entries) {
            if (e.id().isPresent() && !ids.add(e.id().get())) {
                return DataResult.error(() -> "identifiant d'entrée « " + e.id().get() + " » en double");
            }
            if (e.isRare()) {
                rares++;
            }
            if (!e.isPlaceholder()) {
                eligible++;
                if (!e.isRare()) {
                    plain++;
                }
            }
        }
        int r = rares;
        if (rares < MIN_RARES || rares > MAX_RARES) {
            return DataResult.error(() -> r + " entrée(s) rare(s) (de " + MIN_RARES + " à " + MAX_RARES + " attendues)");
        }
        if (plain == 0) {
            return DataResult.error(() -> "aucune entrée ni rare ni provisoire");
        }
        int n = eligible;
        if (t.rolls > eligible) {
            return DataResult.error(() -> t.rolls + " tirages pour " + n + " entrée(s) tirable(s)");
        }
        return DataResult.success(t);
    }

    /** Entrées tirables (non provisoires), dans l'ordre du JSON. */
    public List<CrateEntry> eligible() {
        List<CrateEntry> out = new ArrayList<>();
        for (CrateEntry e : entries) {
            if (!e.isPlaceholder()) {
                out.add(e);
            }
        }
        return out;
    }

    /** Entrées rares (soumises à la pitié), dans l'ordre du JSON. */
    public List<CrateEntry> rares() {
        List<CrateEntry> out = new ArrayList<>();
        for (CrateEntry e : entries) {
            if (e.isRare()) {
                out.add(e);
            }
        }
        return out;
    }

    /** Somme des poids des entrées tirables (dénominateur de la « chance par tirage »). */
    public int totalWeight() {
        long total = 0L;
        for (CrateEntry e : entries) {
            if (!e.isPlaceholder()) {
                total += e.weight();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Objets de {@code #sovereignfrontier:interdit_caisses} présents dans la table (tags liés requis) : une table qui
     * en contient un est rejetée en entier au chargement.
     */
    public List<ResourceLocation> bannedItems() {
        List<ResourceLocation> out = new ArrayList<>();
        for (CrateEntry e : entries) {
            if (e.reward() instanceof CrateEntry.ItemReward it && RestrictedItems.isBannedFromLoot(it.stack(1))) {
                out.add(BuiltInRegistries.ITEM.getKey(it.item()));
            }
        }
        return out;
    }
}
