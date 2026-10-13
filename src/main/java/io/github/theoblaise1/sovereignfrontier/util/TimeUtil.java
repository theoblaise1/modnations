package io.github.theoblaise1.sovereignfrontier.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Horodatages epoch (millisecondes) : survivent aux redémarrages, contrairement aux ticks. */
public final class TimeUtil {
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault());

    private TimeUtil() {
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    public static String shortStamp(long epochMs) {
        return SHORT.format(Instant.ofEpochMilli(epochMs));
    }

    public static String date(long epochMs) {
        return DATE.format(Instant.ofEpochMilli(epochMs));
    }

    /** Durée lisible : « 1 h 05 min », « 12 min 30 s », « 45 s ». */
    public static String duration(long ms) {
        long totalSeconds = Math.max(0L, (ms + 999L) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format("%d h %02d min", hours, minutes);
        }
        if (minutes > 0) {
            return String.format("%d min %02d s", minutes, seconds);
        }
        return seconds + " s";
    }
}
