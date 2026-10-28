package dev.codex.lottery.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public final class LotteryRound {

    private final Map<UUID, Integer> ticketsByPlayer = new HashMap<>();
    private final Map<UUID, Double> spentByPlayer = new HashMap<>();
    private double jackpot;
    private int totalTickets;
    private long revision;
    private LocalDateTime startedAt = LocalDateTime.now();

    public Map<UUID, Integer> getTicketsByPlayer() {
        return Collections.unmodifiableMap(ticketsByPlayer);
    }

    public Map<UUID, Double> getSpentByPlayer() {
        return Collections.unmodifiableMap(spentByPlayer);
    }

    public int getTicketsFor(UUID playerId) {
        return ticketsByPlayer.getOrDefault(playerId, 0);
    }

    public int getTotalTickets() {
        return totalTickets;
    }

    public long getRevision() {
        return revision;
    }

    public void setSpentFor(UUID playerId, double amount) {
        spentByPlayer.put(playerId, amount);
        revision++;
    }

    public int getUniquePlayers() {
        return ticketsByPlayer.size();
    }

    public void addTickets(UUID playerId, int amount, double jackpotIncrease) {
        ticketsByPlayer.merge(playerId, amount, Integer::sum);
        totalTickets += amount;
        revision++;
        jackpot += jackpotIncrease;
    }

    public void addTickets(UUID playerId, int amount, double jackpotIncrease, double spent) {
        addTickets(playerId, amount, jackpotIncrease);
        spentByPlayer.merge(playerId, spent, Double::sum);
    }

    public double getSpentFor(UUID playerId) {
        return spentByPlayer.getOrDefault(playerId, 0.0D);
    }

    public double getJackpot() {
        return jackpot;
    }

    public void setJackpot(double jackpot) {
        this.jackpot = Math.max(0.0D, jackpot);
        revision++;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
        revision++;
    }

    public void reset(LocalDateTime now, double jackpotCarryOver) {
        ticketsByPlayer.clear();
        spentByPlayer.clear();
        totalTickets = 0;
        revision++;
        jackpot = Math.max(0.0D, jackpotCarryOver);
        startedAt = now;
    }
}
