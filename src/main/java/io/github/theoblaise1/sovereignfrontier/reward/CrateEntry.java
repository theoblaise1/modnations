package io.github.theoblaise1.sovereignfrontier.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.theoblaise1.sovereignfrontier.util.IntRange;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Une entrée d'une table de caisse ({@code data/<ns>/crates/<type>.json}). Elle contient exactement un des champs
 * {@code item} (avec {@code count} et éventuellement {@code components}), {@code money} ou
 * {@code placeholder: {milestone, label}} ; une entrée provisoire n'est jamais tirée.
 *
 * @param id     identifiant stable (obligatoire pour une entrée rare : clé des compteurs de pitié)
 * @param rarity {@code commun} (défaut), {@code peu_commun} ou {@code rare}
 * @param weight poids du tirage pondéré (1 par défaut)
 */
public record CrateEntry(Optional<String> id, Rarity rarity, int weight, Reward reward) {
    public static final int MAX_WEIGHT = 1_000_000;
    public static final int MAX_COUNT = 256;
    public static final int MAX_MONEY = 1_000_000;
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9_]{1,48}");
    private static final Pattern MILESTONE = Pattern.compile("J[0-9]{1,2}");

    /** Rareté d'une entrée ; {@link #code()} est l'octet de {@code CratePreviewView.EntryLine#rarity}. */
    public enum Rarity implements StringRepresentable {
        COMMUN("commun", ChatFormatting.WHITE),
        PEU_COMMUN("peu_commun", ChatFormatting.YELLOW),
        RARE("rare", ChatFormatting.AQUA);

        public static final Codec<Rarity> CODEC = StringRepresentable.fromEnum(Rarity::values);

        private final String id;
        private final ChatFormatting color;

        Rarity(String id, ChatFormatting color) {
            this.id = id;
            this.color = color;
        }

        public String id() {
            return id;
        }

        public byte code() {
            return (byte) ordinal();
        }

        public ChatFormatting color() {
            return color;
        }

        public MutableComponent displayName() {
            return Msg.tr("reward.rarity." + id).withStyle(color);
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    /** Contenu d'une entrée. */
    public sealed interface Reward permits ItemReward, MoneyReward, Placeholder {
    }

    /** Objets : {@code count} (n ou [min, max]) exemplaires, avec les composants donnés. */
    public record ItemReward(Item item, IntRange count, DataComponentPatch components) implements Reward {
        public ItemStack stack(int n) {
            return new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item), n, components);
        }
    }

    /** Crédits versés au portefeuille. */
    public record MoneyReward(IntRange amount) implements Reward {
    }

    /** Entrée provisoire, affichée grisée « À venir (J6) : … » ({@code reward.placeholder.<label>}), jamais tirée. */
    public record Placeholder(String milestone, String label) implements Reward {
        static final Codec<Placeholder> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.validate(s -> MILESTONE.matcher(s).matches() ? DataResult.success(s)
                        : DataResult.error(() -> "jalon « " + s + " » invalide (J1 à J99 attendu)")).fieldOf("milestone")
                        .forGetter(Placeholder::milestone),
                Codec.STRING.validate(s -> LABEL.matcher(s).matches() ? DataResult.success(s)
                        : DataResult.error(() -> "libellé « " + s + " » invalide ([a-z0-9_], 48 caractères au plus)"))
                        .fieldOf("label").forGetter(Placeholder::label)
        ).apply(i, Placeholder::new));

        public MutableComponent displayName() {
            return Msg.tr("reward.placeholder." + label);
        }
    }

    private static final Codec<Item> ITEM = BuiltInRegistries.ITEM.byNameCodec().validate(item -> item == Items.AIR
            ? DataResult.error(() -> "objet « minecraft:air » interdit") : DataResult.success(item));

    private record Raw(Optional<String> id, Optional<Item> item, Optional<IntRange> count,
                       Optional<DataComponentPatch> components, Optional<IntRange> money, Optional<Placeholder> placeholder,
                       int weight, Rarity rarity) {
    }

    private static final Codec<Raw> RAW = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.validate(s -> ID.matcher(s).matches() ? DataResult.success(s)
                    : DataResult.error(() -> "identifiant « " + s + " » invalide ([a-z0-9_], 32 caractères au plus)"))
                    .optionalFieldOf("id").forGetter(Raw::id),
            ITEM.optionalFieldOf("item").forGetter(Raw::item),
            IntRange.codec(1, MAX_COUNT).optionalFieldOf("count").forGetter(Raw::count),
            DataComponentPatch.CODEC.optionalFieldOf("components").forGetter(Raw::components),
            IntRange.codec(1, MAX_MONEY).optionalFieldOf("money").forGetter(Raw::money),
            Placeholder.CODEC.optionalFieldOf("placeholder").forGetter(Raw::placeholder),
            Codec.intRange(1, MAX_WEIGHT).optionalFieldOf("weight", 1).forGetter(Raw::weight),
            Rarity.CODEC.optionalFieldOf("rarity", Rarity.COMMUN).forGetter(Raw::rarity)
    ).apply(i, Raw::new));

    /** Analyse stricte d'une entrée (un seul contenu, {@code id} obligatoire pour une rare, provisoire jamais rare). */
    public static final Codec<CrateEntry> CODEC = RAW.flatXmap(CrateEntry::fromRaw, e -> DataResult.success(e.toRaw()));

    private static DataResult<CrateEntry> fromRaw(Raw raw) {
        int kinds = (raw.item.isPresent() ? 1 : 0) + (raw.money.isPresent() ? 1 : 0) + (raw.placeholder.isPresent() ? 1 : 0);
        if (kinds != 1) {
            return DataResult.error(() -> "une entrée contient exactement un champ parmi « item », « money » et « placeholder »"
                    + raw.item.map(it -> " (objet " + BuiltInRegistries.ITEM.getKey(it) + ")").orElse(""));
        }
        if (raw.item.isEmpty() && (raw.count.isPresent() || raw.components.isPresent())) {
            return DataResult.error(() -> "« count » et « components » n'existent que pour une entrée « item »");
        }
        if (raw.rarity == Rarity.RARE && raw.id.isEmpty()) {
            return DataResult.error(() -> "une entrée rare doit avoir un « id » (compteur de pitié)");
        }
        if (raw.rarity == Rarity.RARE && raw.placeholder.isPresent()) {
            return DataResult.error(() -> "une entrée provisoire ne peut pas être rare (elle n'est jamais tirée)");
        }
        Reward reward;
        if (raw.item.isPresent()) {
            reward = new ItemReward(raw.item.get(), raw.count.orElse(IntRange.of(1)), raw.components.orElse(DataComponentPatch.EMPTY));
        } else if (raw.money.isPresent()) {
            reward = new MoneyReward(raw.money.get());
        } else {
            reward = raw.placeholder.get();
        }
        return DataResult.success(new CrateEntry(raw.id, raw.rarity, raw.weight, reward));
    }

    private Raw toRaw() {
        return switch (reward) {
            case ItemReward r -> new Raw(id, Optional.of(r.item()), Optional.of(r.count()),
                    r.components().isEmpty() ? Optional.empty() : Optional.of(r.components()), Optional.empty(), Optional.empty(),
                    weight, rarity);
            case MoneyReward m -> new Raw(id, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(m.amount()),
                    Optional.empty(), weight, rarity);
            case Placeholder p -> new Raw(id, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(p), weight, rarity);
        };
    }

    public boolean isPlaceholder() {
        return reward instanceof Placeholder;
    }

    public boolean isRare() {
        return rarity == Rarity.RARE;
    }

    /** Identifiant de pitié ({@code ""} si l'entrée n'en a pas). */
    public String key() {
        return id.orElse("");
    }

    /** Icône : l'objet (avec ses composants), un lingot d'or pour l'argent, rien pour une entrée provisoire. */
    public ItemStack icon() {
        return switch (reward) {
            case ItemReward r -> r.stack(1);
            case MoneyReward m -> new ItemStack(Items.GOLD_INGOT);
            case Placeholder p -> ItemStack.EMPTY;
        };
    }

    /** Plage de quantité (objets) ou de montant (argent) ; [0, 0] pour une entrée provisoire. */
    public IntRange range() {
        return switch (reward) {
            case ItemReward r -> r.count();
            case MoneyReward m -> m.amount();
            case Placeholder p -> IntRange.of(0);
        };
    }

    /** Description de l'entrée dans la table : « 3 à 8 × Lingot de fer », « 50 à 150 cr », « À venir (J6) : … ». */
    public MutableComponent describe() {
        IntRange r = range();
        return switch (reward) {
            case ItemReward it -> Component.literal(rangeText(r) + " × ").append(it.stack(1).getHoverName());
            case MoneyReward m -> r.isConstant() ? Component.literal(Money.format(r.min()))
                    : Msg.tr("reward.money_range", Money.format(r.min()), Money.format(r.max()));
            case Placeholder p -> Msg.tr("reward.placeholder", p.milestone(), p.displayName());
        };
    }

    private static String rangeText(IntRange r) {
        return r.isConstant() ? Integer.toString(r.min()) : r.min() + "–" + r.max();
    }
}
