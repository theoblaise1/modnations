package io.github.theoblaise1.sovereignfrontier.research;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;
import java.util.Optional;

/**
 * Récompense d'un palier :
 * <ul>
 *   <li>{@code {"type": "unlock", "key": "station.machines"}} : drapeau de déblocage lu par les
 *       systèmes concernés (recettes, blocs, dimensions) ; voir {@code docs/recherche.md} ;</li>
 *   <li>{@code {"type": "bonus", "stat": "crop_yield", "amount": 0.02}} : bonus passif ({@link ResearchStat}).</li>
 * </ul>
 */
public record ResearchReward(Type type, String key, Optional<ResearchStat> stat, double amount) {

    public enum Type {
        UNLOCK,
        BONUS;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Codec<Type> TYPE_CODEC = Codec.STRING.comapFlatMap(
            s -> switch (s) {
                case "unlock" -> DataResult.success(Type.UNLOCK);
                case "bonus" -> DataResult.success(Type.BONUS);
                default -> DataResult.error(() -> "Type de récompense inconnu : " + s + " (attendu : unlock ou bonus)");
            },
            Type::id);

    private static final Codec<ResearchStat> STAT_CODEC = Codec.STRING.comapFlatMap(
            s -> ResearchStat.byId(s).map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Bonus inconnu : " + s)),
            ResearchStat::id);

    private static final Codec<ResearchReward> RAW_CODEC = RecordCodecBuilder.create(i -> i.group(
            TYPE_CODEC.fieldOf("type").forGetter(ResearchReward::type),
            Codec.STRING.optionalFieldOf("key", "").forGetter(ResearchReward::key),
            STAT_CODEC.optionalFieldOf("stat").forGetter(ResearchReward::stat),
            Codec.DOUBLE.optionalFieldOf("amount", 0.0D).forGetter(ResearchReward::amount)
    ).apply(i, ResearchReward::new));

    public static final Codec<ResearchReward> CODEC = RAW_CODEC.validate(r -> switch (r.type) {
        case UNLOCK -> r.key.isBlank()
                ? DataResult.error(() -> "Une récompense « unlock » exige une clé « key »")
                : DataResult.success(r);
        case BONUS -> r.stat.isEmpty() || r.amount == 0.0D
                ? DataResult.error(() -> "Une récompense « bonus » exige « stat » et un « amount » non nul")
                : DataResult.success(r);
    });

    /** Texte affiché : nom du déblocage ou « +5 % rendement des cultures ». */
    public MutableComponent describe() {
        if (type == Type.UNLOCK) {
            return Msg.tr("research.reward.unlock", Msg.tr("unlock." + key));
        }
        ResearchStat s = stat.orElseThrow();
        String value = s == ResearchStat.MAX_MEMBERS
                ? String.format(Locale.ROOT, "%+d", (int) Math.floor(amount))
                : String.format(Locale.ROOT, "%+.0f %%", amount * 100.0D);
        return Msg.tr("research.reward.bonus", Component.literal(value), s.displayName());
    }
}
