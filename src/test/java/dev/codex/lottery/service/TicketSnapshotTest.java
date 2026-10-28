package dev.codex.lottery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TicketSnapshotTest {

    @Test
    void optimizedSnapshotPreservesExactFairnessInputIncludingEmptyRound() {
        Map<UUID, Integer> tickets = new HashMap<>();
        assertEquals("", LotteryManager.createTicketSnapshot(tickets));
        for (int index = 0; index < 100; index++) {
            tickets.put(UUID.randomUUID(), index + 1);
        }
        String original = tickets.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + ":" + entry.getValue()).reduce("", (left, right) -> left + "|" + right);
        assertEquals(original, LotteryManager.createTicketSnapshot(tickets));
    }
}
