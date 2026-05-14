package dev.codex.lottery.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record WinnerEntry(UUID playerId, String playerName, double amount, LocalDateTime wonAt, int ticketsBought) {
}
