package dev.codex.lottery.util;

import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TimeUtilTest {

    @Test
    void formatsDurationWithHours() {
        Assertions.assertEquals("2h 15m", TimeUtil.formatDurationCompact(Duration.ofMinutes(135)));
    }

    @Test
    void formatsNegativeDurationAsZero() {
        Assertions.assertEquals("0m", TimeUtil.formatDurationCompact(Duration.ofMinutes(-3)));
    }
}
