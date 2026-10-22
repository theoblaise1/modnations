package io.github.theoblaise1.sovereignfrontier.reward;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Récompense quotidienne ({@code /daily}) et série de connexions. Délais en epoch persistés ({@link RewardData}) :
 * <ul>
 *   <li>refus si {@code last > 0} et {@code now < last + dailyCooldownHours} (un recul d'horloge allonge l'attente) ;</li>
 *   <li>{@code streak = (last > 0 && now − last ≤ dailyStreakResetHours) ? streak + 1 : 1} ; le jour de récompense
 *       vaut {@code min(streak, jours de la table)} (plateau) ;</li>
 *   <li>argent au portefeuille, clés à l'inventaire (sinon au sol, réservées au joueur, comme {@code /give}).</li>
 * </ul>
 */
public final class DailyManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long HOUR_MS = 3_600_000L;

    private DailyManager() {
    }

    public static long cooldownMs() {
        return SFConfig.DAILY_COOLDOWN_HOURS.get() * HOUR_MS;
    }

    public static long streakResetMs() {
        return SFConfig.effectiveDailyStreakResetHours() * HOUR_MS;
    }

    /** {@code /daily} avec la table publiée, à l'heure courante. */
    public static ActionResult claim(ServerPlayer player) {
        return claim(player, DailyTable.published(), TimeUtil.now(), player.getRandom());
    }

    /** Réclamation (public pour les GameTests, qui injectent la table, l'heure et le hasard). */
    public static ActionResult claim(ServerPlayer player, @Nullable DailyTable table, long now, RandomSource random) {
        if (table == null) {
            return ActionResult.fail(Msg.err("reward.daily.error.unavailable"));
        }
        MinecraftServer server = player.server;
        RewardData data = RewardData.get(server);
        RewardData.PlayerRewards r = data.of(player.getUUID());
        long cooldown = cooldownMs();
        if (r.dailyLast > 0L && now < r.dailyLast + cooldown) {
            return ActionResult.fail(Msg.err("reward.daily.error.cooldown", TimeUtil.duration(r.dailyLast + cooldown - now)));
        }
        int previous = r.streak;
        int streak = nextStreak(r, now);
        DailyTable.Day day = table.day(streak);
        // État écrit avant toute remise (même tick) : aucune double réclamation possible.
        r.dailyLast = now;
        r.streak = streak;
        data.setDirty();

        if (day.money() > 0) {
            EconomyData.get(server).add(player.getUUID(), day.money());
            NationSync.scheduleRefresh(player);
        }
        Map<CrateKind, Integer> keys = new EnumMap<>(CrateKind.class);
        for (DailyTable.KeyChoice choice : day.keys()) {
            CrateKind kind = choice.oneOf().get(random.nextInt(choice.oneOf().size()));
            keys.merge(kind, 1, Integer::sum);
        }
        int dropped = 0;
        MutableComponent keyText = Component.empty();
        boolean first = true;
        for (Map.Entry<CrateKind, Integer> e : keys.entrySet()) {
            if (!RestrictedItems.allowInLoot(CrateKeys.stack(e.getKey(), 1), "/daily")) {
                continue;
            }
            dropped += CrateKeys.give(player, e.getKey(), e.getValue());
            if (!first) {
                keyText.append(", ");
            }
            first = false;
            keyText.append(Component.literal(e.getValue() + " × ").append(e.getKey().keyItem().getDescription()));
        }
        LOGGER.info("[Économie] /daily : {} jour {} (série {}) : {} cr, clés {}", player.getGameProfile().getName(),
                Math.min(streak, table.days().size()), streak, day.money(), keys);

        MutableComponent message = Component.empty();
        if (streak == 1 && previous > 1) {
            message.append(Msg.warn("reward.daily.streak_reset", SFConfig.effectiveDailyStreakResetHours())).append("\n");
        }
        message.append(Msg.ok("reward.daily.claimed", Msg.hl(Math.min(streak, table.days().size())), Msg.hl(streak),
                Msg.money(day.money()), keyText.withStyle(ChatFormatting.WHITE)));
        if (dropped > 0) {
            message.append("\n").append(Msg.warn("reward.daily.keys_dropped", dropped));
        }
        return ActionResult.ok(message);
    }

    /** Série obtenue par une réclamation à l'instant {@code at}. */
    private static int nextStreak(RewardData.PlayerRewards r, long at) {
        return r.dailyLast > 0L && at - r.dailyLast <= streakResetMs() ? r.streak + 1 : 1;
    }

    /** {@code /daily info} : série, disponibilité, prochaine récompense et échéance de la série. */
    public static Component info(ServerPlayer player, long now) {
        DailyTable table = DailyTable.published();
        RewardData.PlayerRewards r = RewardData.get(player.server).find(player.getUUID());
        long last = r == null ? 0L : r.dailyLast;
        int streak = r == null ? 0 : r.streak;
        long resetMs = streakResetMs();
        boolean alive = last > 0L && now - last <= resetMs;
        MutableComponent c = Msg.tr("reward.daily.info.header").withStyle(ChatFormatting.AQUA);
        c.append("\n").append(Msg.info("reward.daily.info.streak", Msg.hl(alive ? streak : 0)));
        long readyAt = last > 0L ? last + cooldownMs() : now;
        c.append("\n").append(readyAt <= now ? Msg.ok("reward.daily.info.ready")
                : Msg.info("reward.daily.info.wait", Msg.hl(TimeUtil.duration(readyAt - now))));
        if (table == null) {
            c.append("\n").append(Msg.err("reward.daily.error.unavailable"));
        } else {
            long at = Math.max(now, readyAt);
            int next = last > 0L && at - last <= resetMs ? streak + 1 : 1;
            c.append("\n").append(Msg.info("reward.daily.info.next", Msg.hl(Math.min(next, table.days().size())),
                    table.day(next).describe().withStyle(ChatFormatting.WHITE)));
        }
        if (alive && streak > 0) {
            c.append("\n").append(Msg.info("reward.daily.info.deadline", Msg.hl(TimeUtil.shortStamp(last + resetMs)),
                    Msg.hl(TimeUtil.duration(last + resetMs - now))));
        }
        return c;
    }

    // ------------------------------------------------------------------ administration

    /** {@code /sfadmin daily reset} : réclamable tout de suite, série conservée ({@code last = now − délai}). */
    public static int resetCooldown(MinecraftServer server, UUID player, long now) {
        RewardData data = RewardData.get(server);
        RewardData.PlayerRewards r = data.of(player);
        r.dailyLast = Math.max(1L, now - cooldownMs());
        data.setDirty();
        return r.streak;
    }

    /** {@code /sfadmin daily streak} : fixe la série ; une série sans réclamation passée devient réclamable. */
    public static void setStreak(MinecraftServer server, UUID player, int streak, long now) {
        RewardData data = RewardData.get(server);
        RewardData.PlayerRewards r = data.of(player);
        r.streak = Math.max(0, streak);
        if (r.dailyLast == 0L && streak > 0) {
            r.dailyLast = Math.max(1L, now - cooldownMs());
        }
        data.setDirty();
    }
}
