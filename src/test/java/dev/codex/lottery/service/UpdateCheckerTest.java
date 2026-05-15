package dev.codex.lottery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

    @Test
    void compareVersionsDetectsNewerRelease() {
        assertTrue(UpdateChecker.compareVersions("1.0.0", "1.0.1") < 0);
        assertTrue(UpdateChecker.compareVersions("1.2.0", "1.10.0") < 0);
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.0.9") > 0);
    }

    @Test
    void compareVersionsIgnoresCommonVersionPrefixesAndSuffixes() {
        assertEquals(0, UpdateChecker.compareVersions("v1.0.0", "1.0.0"));
        assertEquals(0, UpdateChecker.compareVersions("1.0.0-SNAPSHOT", "v1.0.0"));
    }
}
