package dev.codex.lottery.util;

import java.time.Duration;
import java.time.LocalTime;

public final class TimeUtil {

    private TimeUtil() {
    }

    public static String formatDurationCompact(Duration duration) {
        Duration safeDuration = duration.isNegative() ? Duration.ZERO : duration;
        long totalSeconds = safeDuration.getSeconds();
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    public static String formatLocalTime(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}
