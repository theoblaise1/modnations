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
}
