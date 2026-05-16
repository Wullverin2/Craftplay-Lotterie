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
    private int losingStreak;
    private int bestLosingStreak;
    private int consolationRewards;
    private int pityRewards;
    private int luckyNumberHits;
    private int giftedTickets;
    private double giftSpent;
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

    public int getLosingStreak() {
        return losingStreak;
    }

    public void setLosingStreak(int losingStreak) {
        this.losingStreak = Math.max(0, losingStreak);
    }

    public int getBestLosingStreak() {
        return bestLosingStreak;
    }

    public void setBestLosingStreak(int bestLosingStreak) {
        this.bestLosingStreak = Math.max(0, bestLosingStreak);
    }

    public int getConsolationRewards() {
        return consolationRewards;
    }

    public void setConsolationRewards(int consolationRewards) {
        this.consolationRewards = Math.max(0, consolationRewards);
    }

    public int getPityRewards() {
        return pityRewards;
    }

    public void setPityRewards(int pityRewards) {
        this.pityRewards = Math.max(0, pityRewards);
    }

    public int getLuckyNumberHits() {
        return luckyNumberHits;
    }

    public void setLuckyNumberHits(int luckyNumberHits) {
        this.luckyNumberHits = Math.max(0, luckyNumberHits);
    }

    public int getGiftedTickets() {
        return giftedTickets;
    }

    public void setGiftedTickets(int giftedTickets) {
        this.giftedTickets = Math.max(0, giftedTickets);
    }

    public double getGiftSpent() {
        return giftSpent;
    }

    public void setGiftSpent(double giftSpent) {
        this.giftSpent = Math.max(0.0D, giftSpent);
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

    public void recordLoss(String name) {
        playerName = name;
        losingStreak++;
        bestLosingStreak = Math.max(bestLosingStreak, losingStreak);
    }

    public void recordWin(String name, double amount, LocalDateTime now) {
        playerName = name;
        wins++;
        totalWon += amount;
        highestWin = Math.max(highestWin, amount);
        lastWinAt = now;
        losingStreak = 0;
    }

    public void recordConsolationReward(String name) {
        playerName = name;
        consolationRewards++;
    }

    public void recordPityReward(String name, boolean resetStreak) {
        playerName = name;
        pityRewards++;
        if (resetStreak) {
            losingStreak = 0;
        }
    }

    public void recordLuckyNumberHit(String name) {
        playerName = name;
        luckyNumberHits++;
    }

    public void recordGiftPurchase(String name, int amount, double cost) {
        playerName = name;
        giftedTickets += amount;
        giftSpent += cost;
    }
}
