package dev.codex.lottery.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RankingCacheTest {

    @Test
    void reusesSortedSnapshotForAllRanksUntilInvalidated() {
        RankingCache cache = new RankingCache();
        UUID alex = UUID.randomUUID();
        UUID steve = UUID.randomUUID();
        AtomicInteger builds = new AtomicInteger();
        var entries = List.of(new RankingCache.Entry(alex, "Alex", 10), new RankingCache.Entry(steve, "Steve", 20));
        for (int query = 0; query < 100; query++) {
            var index = cache.get("tickets", () -> { builds.incrementAndGet(); return entries; });
            assertEquals(2, index.rank(alex));
            assertEquals(1, index.rank(steve));
        }
        assertEquals(1, builds.get());
        cache.invalidate("tickets");
        assertEquals(1, cache.get("tickets", () -> List.of(new RankingCache.Entry(alex, "Alex", 50))).rank(alex));
    }
}
