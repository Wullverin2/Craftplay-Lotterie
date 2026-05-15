package dev.codex.lottery.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerLotteryStatsTest {

    @Test
    void recordPurchaseAccumulatesTicketsAndMoney() {
        PlayerLotteryStats stats = new PlayerLotteryStats(UUID.randomUUID(), "Alex");
        LocalDateTime now = LocalDateTime.of(2026, 5, 15, 19, 30);

        stats.recordPurchase("Alex", 2, 500.0D, now);
        stats.recordPurchase("Alex", 3, 750.0D, now.plusMinutes(1));

        assertEquals(5, stats.getTicketsBought());
        assertEquals(1250.0D, stats.getMoneySpent());
        assertEquals(now.plusMinutes(1), stats.getLastPurchaseAt());
    }

    @Test
    void recordWinTracksTotalsAndHighestWin() {
        PlayerLotteryStats stats = new PlayerLotteryStats(UUID.randomUUID(), "Steve");
        LocalDateTime now = LocalDateTime.of(2026, 5, 15, 20, 0);

        stats.recordWin("Steve", 1000.0D, now);
        stats.recordWin("Steve", 750.0D, now.plusDays(1));

        assertEquals(2, stats.getWins());
        assertEquals(1750.0D, stats.getTotalWon());
        assertEquals(1000.0D, stats.getHighestWin());
        assertEquals(now.plusDays(1), stats.getLastWinAt());
    }
}
