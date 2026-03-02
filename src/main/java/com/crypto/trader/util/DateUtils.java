package com.crypto.trader.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class DateUtils {

    public static Instant fromTimestamp(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    public static long toEpochMillis(Instant instant) {
        return instant.toEpochMilli();
    }

    public static String format(Instant instant, String pattern) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(DateTimeFormatter.ofPattern(pattern));
    }
}
