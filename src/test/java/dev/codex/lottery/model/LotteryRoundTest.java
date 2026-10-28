package dev.codex.lottery.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LotteryRoundTest {

    @Test
    void addTicketsTracksTicketsJackpotAndSpentPerPlayer() {
        LotteryRound round = new LotteryRound();
        UUID playerId = UUID.randomUUID();

        round.addTickets(playerId, 2, 100.0D, 250.0D);
        round.addTickets(playerId, 3, 150.0D, 375.0D);

        assertEquals(5, round.getTicketsFor(playerId));
        assertEquals(5, round.getTotalTickets());
        assertEquals(1, round.getUniquePlayers());
        assertEquals(250.0D, round.getJackpot());
        assertEquals(625.0D, round.getSpentFor(playerId));
    }

    @Test
    void resetClearsRoundDataAndKeepsCarryOver() {
        LotteryRound round = new LotteryRound();
        UUID playerId = UUID.randomUUID();
        LocalDateTime resetTime = LocalDateTime.of(2026, 5, 15, 20, 0);

        round.addTickets(playerId, 4, 500.0D, 1000.0D);
        round.reset(resetTime, 75.0D);

        assertEquals(0, round.getTotalTickets());
        assertEquals(0.0D, round.getSpentFor(playerId));
        assertEquals(75.0D, round.getJackpot());
        assertEquals(resetTime, round.getStartedAt());
        assertTrue(round.getTicketsByPlayer().isEmpty());
    }

    @Test
    void restoredTicketsUpdateCounterAndCannotBeMutatedOutsideRound() {
        LotteryRound round = new LotteryRound();
        UUID player = UUID.randomUUID();
        long before = round.getRevision();
        round.addTickets(player, 100, 0);
        round.setSpentFor(player, 25000);
        assertEquals(100, round.getTotalTickets());
        assertTrue(round.getRevision() > before);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
            () -> round.getTicketsByPlayer().put(player, 999));
        round.reset(LocalDateTime.now(), 0);
        round.addTickets(player, 1, 250);
        assertEquals(1, round.getTotalTickets());
    }
}
