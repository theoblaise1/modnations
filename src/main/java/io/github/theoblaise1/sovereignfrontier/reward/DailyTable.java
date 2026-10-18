package io.github.theoblaise1.sovereignfrontier.reward;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Table de {@code /daily} ({@code data/sovereignfrontier/daily/streak.json}, seul identifiant lu) : un jour par
 * valeur de série, le dernier se répète (plateau). Chaque jour verse {@code money} cr et 1 à 2 clés, chacune tirée
 * uniformément dans son {@code one_of}. Chargement en deux temps comme les caisses : candidat à l'analyse, publication
 * atomique au {@code TagsUpdatedEvent} (garde {@code #interdit_caisses} sur les clés).
 */
public record DailyTable(List<Day> days) {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "streak");
    public static final String DIRECTORY = "daily";
    public static final int MIN_KEYS = 1;
    public static final int MAX_KEYS = 2;
    public static final int MAX_DAYS = 31;
    public static final int MAX_MONEY = 1_000_000;
    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable
    private static volatile DailyTable published;
    @Nullable
    private static volatile DailyTable candidate;

    /** Une clé : un type tiré uniformément parmi {@code one_of} (sans doublon). */
    public record KeyChoice(List<CrateKind> oneOf) {
        public static final Codec<KeyChoice> CODEC = RecordCodecBuilder.<KeyChoice>create(i -> i.group(
                CrateKind.CODEC.listOf().fieldOf("one_of").forGetter(KeyChoice::oneOf)
        ).apply(i, KeyChoice::new)).validate(KeyChoice::validate);

        public KeyChoice {
            oneOf = List.copyOf(oneOf);
        }

        private static DataResult<KeyChoice> validate(KeyChoice c) {
            if (c.oneOf.isEmpty()) {
                return DataResult.error(() -> "« one_of » vide");
            }
            if (EnumSet.copyOf(c.oneOf).size() != c.oneOf.size()) {
                return DataResult.error(() -> "type de caisse en double dans « one_of » : " + c.oneOf);
            }
            return DataResult.success(c);
        }

        /** « Clé commune » ou « Clé industrielle ou Clé militaire ». */
        public MutableComponent describe() {
            MutableComponent c = Component.empty();
            for (int i = 0; i < oneOf.size(); i++) {
                boolean last = i == oneOf.size() - 1;
                if (i > 0) {
                    c.append(Msg.tr(last ? "reward.daily.or" : "reward.daily.comma"));
                }
                c.append(oneOf.get(i).keyItem().getDescription());
            }
            return c;
        }
    }

    /** Récompense d'un jour de série. */
    public record Day(int money, List<KeyChoice> keys) {
        public static final Codec<Day> CODEC = RecordCodecBuilder.<Day>create(i -> i.group(
                Codec.intRange(0, MAX_MONEY).fieldOf("money").forGetter(Day::money),
                KeyChoice.CODEC.listOf().fieldOf("keys").forGetter(Day::keys)
        ).apply(i, Day::new)).validate(d -> d.keys.size() >= MIN_KEYS && d.keys.size() <= MAX_KEYS ? DataResult.success(d)
                : DataResult.error(() -> d.keys.size() + " clé(s) par jour (de " + MIN_KEYS + " à " + MAX_KEYS + " attendues)"));

        public Day {
            keys = List.copyOf(keys);
        }

        /** « 250 cr + Clé commune + (Clé industrielle ou Clé militaire) ». */
        public MutableComponent describe() {
            MutableComponent c = Msg.money(money).copy();
            for (KeyChoice k : keys) {
                c.append(Component.literal(" + "));
                c.append(k.oneOf().size() > 1 ? Component.literal("(").append(k.describe()).append(")") : k.describe());
            }
            return c;
        }
    }

    public static final Codec<DailyTable> CODEC = RecordCodecBuilder.<DailyTable>create(i -> i.group(
            Day.CODEC.listOf().fieldOf("days").forGetter(DailyTable::days)
    ).apply(i, DailyTable::new)).validate(t -> !t.days.isEmpty() && t.days.size() <= MAX_DAYS ? DataResult.success(t)
            : DataResult.error(() -> t.days.size() + " jour(s) (de 1 à " + MAX_DAYS + " attendus)"));

    public DailyTable {
        days = List.copyOf(days);
    }

    /** Récompense du jour de série {@code streak} (≥ 1) : le dernier jour se répète. */
    public Day day(int streak) {
        return days.get(Math.min(Math.max(1, streak), days.size()) - 1);
    }

    /** Table publiée, ou {@code null} ({@code /daily} indisponible). */
    @Nullable
    public static DailyTable published() {
        return published;
    }

    public static DataResult<DailyTable> parse(JsonElement json) {
        return CODEC.parse(JsonOps.INSTANCE, json);
    }

    /** Types de clé interdits dans les sources automatiques (tags liés requis) ; vide dans le cas normal. */
    public Set<CrateKind> bannedKeys() {
        Set<CrateKind> out = EnumSet.noneOf(CrateKind.class);
        for (Day d : days) {
            for (KeyChoice k : d.keys) {
                for (CrateKind kind : k.oneOf) {
                    if (RestrictedItems.isBannedFromLoot(kind.keyItem())) {
                        out.add(kind);
                    }
                }
            }
        }
        return out;
    }

    /** Candidat à partir des fichiers chargés : seul {@link #ID} est lu, tout autre fichier est ignoré (avertissement). */
    @Nullable
    public static DailyTable build(Map<ResourceLocation, JsonElement> files) {
        DailyTable table = null;
        for (Map.Entry<ResourceLocation, JsonElement> e : files.entrySet()) {
            if (!ID.equals(e.getKey())) {
                LOGGER.warn("Table /daily {} ignorée : seul {} est lu", e.getKey(), ID);
                continue;
            }
            DataResult<DailyTable> result = parse(e.getValue());
            result.error().ifPresent(err -> LOGGER.error("Table /daily {} ignorée : {}", ID, err.message()));
            table = result.result().orElse(null);
        }
        return table;
    }

    /** Publication atomique du candidat (au {@code TagsUpdatedEvent} de chargement des données serveur). */
    public static void publish() {
        DailyTable table = candidate;
        if (table != null && !table.bannedKeys().isEmpty()) {
            LOGGER.error("Table /daily {} ignorée : clé(s) interdite(s) dans les sources automatiques : {}", ID, table.bannedKeys());
            table = null;
        }
        published = table;
        if (table == null) {
            LOGGER.error("/daily indisponible : aucune table valide ({})", ID);
        } else {
            LOGGER.info("Sovereign Frontier : table /daily publiée ({} jour(s))", table.days.size());
        }
    }

    /** Chargeur {@code data/<ns>/daily/*.json} : construit seulement le candidat. */
    public static final class ReloadListener extends SimpleJsonResourceReloadListener {
        public ReloadListener() {
            super(new GsonBuilder().create(), DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            candidate = build(files);
        }
    }
}
