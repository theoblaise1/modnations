package io.github.theoblaise1.sovereignfrontier.economy;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.reward.RewardData;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * Terminal bancaire : le temps de jeu ACTIF ({@link ActivityTracker}) remplit une épargne personnelle, encaissée par
 * clic droit sur un bloc {@code terminal_bancaire}. Aucune nation n'est requise.
 * <ul>
 *   <li>Unité : 1/3600 cr ; chaque seconde active ajoute {@code round(taux × multiplicateur)} unités, où le taux
 *       (cr/h) dépend de l'heure active dans la fenêtre de {@code terminalWindowHours} (barème dégressif, la
 *       dernière valeur se répète) ;</li>
 *   <li>la fenêtre est un horodatage epoch persisté : elle repart à la première seconde active après son terme ;</li>
 *   <li>l'accumulation s'arrête à {@code terminalMaxPending} cr d'épargne non encaissée.</li>
 * </ul>
 */
public final class TerminalManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Unités d'épargne par crédit (une seconde au taux de 1 cr/h vaut une unité). */
    public static final long UNITS_PER_CREDIT = 3600L;
    /** Intervalle minimal entre deux avis « épargne pleine » dans la barre d'action. */
    public static final long FULL_NOTICE_INTERVAL_MS = 5L * 60_000L;
    private static final long HOUR_MS = 3_600_000L;

    private TerminalManager() {
    }

    /** Résultat d'une seconde active. */
    public enum Accrual {
        /** Épargne augmentée. */
        ACCRUED,
        /** Nouvelle fenêtre ouverte : cette seconde ne rapporte rien. */
        WINDOW_RESET,
        /** Épargne pleine : rien n'est ajouté, le temps actif de la fenêtre n'avance pas. */
        FULL
    }

    /** Joueur dont l'activité est échantillonnée : ni faux joueur ni spectateur. */
    public static boolean eligible(ServerPlayer player) {
        return !(player instanceof FakePlayer) && !player.isSpectator();
    }

    public static long windowMs() {
        return SFConfig.TERMINAL_WINDOW_HOURS.get() * HOUR_MS;
    }

    public static long afkMs() {
        return SFConfig.TERMINAL_AFK_SECONDS.get() * 1000L;
    }

    /** Taux (cr/h) de la seconde {@code activeSec} de la fenêtre : {@code rates[min(activeSec / 3600, n − 1)]}. */
    public static double rateAt(double[] rates, int activeSec) {
        if (rates.length == 0) {
            return 0.0D;
        }
        int hour = Math.max(0, activeSec) / 3600;
        return rates[Math.min(hour, rates.length - 1)];
    }

    /** Plafond de l'épargne en unités. */
    public static long capUnits(long maxPendingCredits) {
        return Math.max(0L, maxPendingCredits) * UNITS_PER_CREDIT;
    }

    /**
     * Une seconde active (fonction pure sur la fiche, temps injecté).
     * <ul>
     *   <li>fenêtre absente ou échue ({@code now − window ≥ windowMs}) : nouvelle fenêtre, {@code activeSec = 0} ;</li>
     *   <li>sinon, si l'épargne est pleine : rien ;</li>
     *   <li>sinon : {@code units += round(rateAt(activeSec) × multiplier)} (borné au plafond), puis
     *       {@code activeSec++} et {@code lifetime++}.</li>
     * </ul>
     */
    public static Accrual accrueSecond(RewardData.PlayerRewards r, long now, double[] rates, double multiplier,
                                       long maxPendingCredits, long windowMs) {
        if (r.window == 0L || now - r.window >= windowMs) {
            r.window = now;
            r.activeSec = 0;
            return Accrual.WINDOW_RESET;
        }
        long cap = capUnits(maxPendingCredits);
        if (r.units >= cap) {
            return Accrual.FULL;
        }
        long add = Math.max(0L, Math.round(rateAt(rates, r.activeSec) * multiplier));
        r.units = Math.min(cap, r.units + add);
        r.activeSec++;
        r.lifetime++;
        return Accrual.ACCRUED;
    }

    /**
     * Échantillon d'un joueur (toutes les 20 ticks) : signaux de regard et de marche, annonce de pause ou de reprise,
     * puis une seconde d'épargne s'il est actif. L'appelant écarte les faux joueurs et les spectateurs ({@link #eligible}).
     */
    public static void tick(ServerPlayer player, long now) {
        UUID id = player.getUUID();
        ActivityTracker.sample(id, ActivityTracker.Motion.of(player), now, SFConfig.TERMINAL_LOOK_DEGREES.get(),
                SFConfig.TERMINAL_WALK_BLOCKS.get());
        boolean active = ActivityTracker.isActive(id, now, afkMs());
        switch (ActivityTracker.updatePause(id, active)) {
            case PAUSED -> player.displayClientMessage(Msg.warn("terminal.paused"), true);
            case RESUMED -> player.displayClientMessage(Msg.ok("terminal.resumed"), true);
            default -> {
            }
        }
        if (!active) {
            return;
        }
        RewardData data = RewardData.get(player.server);
        long maxPending = SFConfig.TERMINAL_MAX_PENDING.get();
        Accrual result = accrueSecond(data.of(id), now, SFConfig.terminalHourlyRates(), RankHooks.terminalMultiplier(player),
                maxPending, windowMs());
        if (result != Accrual.FULL) {
            data.setDirty();
        } else if (ActivityTracker.shouldNotifyFull(id, now, FULL_NOTICE_INTERVAL_MS)) {
            player.displayClientMessage(Msg.warn("terminal.full", Msg.money(maxPending)), true);
        }
    }

    /**
     * Retire de l'épargne les crédits entiers ({@code floor(units / 3600)}) ; le reste demeure.
     *
     * @return crédits retirés (0 s'il n'y a rien à encaisser)
     */
    public static long withdraw(MinecraftServer server, UUID player) {
        RewardData data = RewardData.get(server);
        RewardData.PlayerRewards r = data.find(player);
        long credits = r == null ? 0L : r.units / UNITS_PER_CREDIT;
        if (credits <= 0L) {
            return 0L;
        }
        r.units -= credits * UNITS_PER_CREDIT;
        data.setDirty();
        return credits;
    }

    /** Clic droit sur un terminal : verse l'épargne entière au portefeuille. */
    public static ActionResult cashOut(ServerPlayer player) {
        long credits = withdraw(player.server, player.getUUID());
        if (credits <= 0L) {
            return ActionResult.fail(Msg.warn("terminal.nothing"));
        }
        EconomyData eco = EconomyData.get(player.server);
        eco.add(player.getUUID(), credits);
        long balance = eco.balance(player.getUUID());
        NationSync.scheduleRefresh(player);
        LOGGER.info("[Économie] Terminal bancaire : {} encaisse {} cr (solde : {} cr)",
                player.getGameProfile().getName(), credits, balance);
        return ActionResult.ok(Msg.ok("terminal.cashed", Msg.money(credits), Msg.money(balance)));
    }

    /** État du terminal ({@code /terminal}, clic accroupi) : temps actif, taux, épargne, fenêtre, activité. */
    public static Component status(ServerPlayer player, long now) {
        UUID id = player.getUUID();
        RewardData.PlayerRewards r = RewardData.get(player.server).find(id);
        double[] rates = SFConfig.terminalHourlyRates();
        double mult = RankHooks.terminalMultiplier(player);
        long windowMs = windowMs();
        boolean live = r != null && r.window != 0L && now - r.window < windowMs;
        int activeSec = live ? r.activeSec : 0;
        int hour = activeSec / 3600;
        long maxPending = SFConfig.TERMINAL_MAX_PENDING.get();
        long units = r == null ? 0L : r.units;

        MutableComponent c = Msg.tr("terminal.status.header").withStyle(ChatFormatting.AQUA);
        c.append("\n").append(Msg.info("terminal.status.active_time", Msg.hl(TimeUtil.duration(activeSec * 1000L)), Msg.hl(hour + 1)));
        Component current = rate(rateAt(rates, activeSec) * mult);
        if (hour >= rates.length - 1) {
            c.append("\n").append(Msg.info("terminal.status.rate_last", current));
        } else {
            long untilNext = (3600L - activeSec % 3600) * 1000L;
            c.append("\n").append(Msg.info("terminal.status.rate", current, rate(rateAt(rates, (hour + 1) * 3600) * mult),
                    Msg.hl(TimeUtil.duration(untilNext))));
        }
        c.append("\n").append(Msg.info(units >= capUnits(maxPending) ? "terminal.status.pending_full" : "terminal.status.pending",
                Msg.money(units / UNITS_PER_CREDIT), Msg.money(maxPending)));
        if (live) {
            c.append("\n").append(Msg.info("terminal.status.window", Msg.hl(TimeUtil.duration(r.window + windowMs - now))));
        } else {
            c.append("\n").append(Msg.info("terminal.status.window_none"));
        }
        c.append("\n").append(ActivityTracker.isActive(id, now, afkMs())
                ? Msg.ok("terminal.status.active")
                : Msg.warn("terminal.status.paused", Msg.hl(SFConfig.TERMINAL_AFK_SECONDS.get())));
        return c;
    }

    private static Component rate(double crPerHour) {
        String text = crPerHour == Math.rint(crPerHour) ? Long.toString(Math.round(crPerHour))
                : String.format(Locale.FRENCH, "%.1f", crPerHour);
        return Component.literal(text + " cr/h").withStyle(ChatFormatting.YELLOW);
    }

    // ------------------------------------------------------------------ administration

    /** Remet l'épargne et la fenêtre à zéro (le temps actif cumulé est conservé). */
    public static void reset(MinecraftServer server, UUID player) {
        RewardData data = RewardData.get(server);
        RewardData.PlayerRewards r = data.find(player);
        if (r != null) {
            r.window = 0L;
            r.activeSec = 0;
            r.units = 0L;
            data.setDirty();
        }
    }

    /**
     * Crédite {@code minutes} minutes d'activité au barème courant (comme si le joueur avait joué), à l'instant
     * {@code now}, avec le multiplicateur du joueur s'il est en ligne.
     *
     * @return épargne résultante en crédits entiers
     */
    public static long give(MinecraftServer server, UUID player, int minutes, long now, @Nullable ServerPlayer online) {
        RewardData data = RewardData.get(server);
        RewardData.PlayerRewards r = data.of(player);
        double[] rates = SFConfig.terminalHourlyRates();
        double mult = online == null ? 1.0D : RankHooks.terminalMultiplier(online);
        long maxPending = SFConfig.TERMINAL_MAX_PENDING.get();
        long windowMs = windowMs();
        for (long s = 0, n = minutes * 60L; s < n; s++) {
            if (accrueSecond(r, now, rates, mult, maxPending, windowMs) == Accrual.FULL) {
                break;
            }
        }
        data.setDirty();
        return r.units / UNITS_PER_CREDIT;
    }
}
