package dev.codex.lottery.model;

import java.time.LocalDateTime;
import java.util.UUID;

public final class PlayerLotteryStats {

    private final UUID playerId;
    private String playerName;
    private int ticketsBought;
    private double moneySpent;
    private int wins;
    private double highestWin;
    private double totalWon;
    private int roundsPlayed;
    private LocalDateTime lastPurchaseAt;
    private LocalDateTime lastWinAt;

    public PlayerLotteryStats(UUID playerId, String playerName) {
        this.playerId = playerId;
        this.playerName = playerName;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public int getTicketsBought() {
        return ticketsBought;
    }

    public void setTicketsBought(int ticketsBought) {
        this.ticketsBought = ticketsBought;
    }

    public double getMoneySpent() {
        return moneySpent;
    }

    public void setMoneySpent(double moneySpent) {
        this.moneySpent = moneySpent;
    }

    public int getWins() {
        return wins;
    }

    public void setWins(int wins) {
        this.wins = wins;
    }

    public double getHighestWin() {
        return highestWin;
    }

    public void setHighestWin(double highestWin) {
        this.highestWin = highestWin;
    }

    public double getTotalWon() {
        return totalWon;
    }

    public void setTotalWon(double totalWon) {
        this.totalWon = totalWon;
    }

    public int getRoundsPlayed() {
        return roundsPlayed;
    }

    public void setRoundsPlayed(int roundsPlayed) {
        this.roundsPlayed = roundsPlayed;
    }

    public LocalDateTime getLastPurchaseAt() {
        return lastPurchaseAt;
    }

    public void setLastPurchaseAt(LocalDateTime lastPurchaseAt) {
        this.lastPurchaseAt = lastPurchaseAt;
    }

    public LocalDateTime getLastWinAt() {
        return lastWinAt;
    }

    public void setLastWinAt(LocalDateTime lastWinAt) {
        this.lastWinAt = lastWinAt;
    }

    public void recordPurchase(String name, int amount, double cost, LocalDateTime now) {
        playerName = name;
        ticketsBought += amount;
        moneySpent += cost;
        lastPurchaseAt = now;
    }

    public void recordRoundPlayed(String name) {
        playerName = name;
        roundsPlayed++;
    }

    public void recordWin(String name, double amount, LocalDateTime now) {
        playerName = name;
        wins++;
        totalWon += amount;
        highestWin = Math.max(highestWin, amount);
        lastWinAt = now;
    }
}
