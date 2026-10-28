package dev.codex.lottery.model;

import java.time.LocalDateTime;
import java.util.UUID;

public final class PlayerLotteryStats {

    private final UUID playerId;
    private String playerName;
    private long revision;
    private Runnable changeListener = () -> { };
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

    public long getRevision() {
        return revision;
    }

    public void setRankingChangeListener(Runnable listener) {
        changeListener = listener;
    }

    private void changed() {
        revision++;
    }

    private void rankingChanged() {
        changed();
        changeListener.run();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        if (java.util.Objects.equals(this.playerName, playerName)) {
            return;
        }
        this.playerName = playerName;
        rankingChanged();
    }

    public int getTicketsBought() {
        return ticketsBought;
    }

    public void setTicketsBought(int ticketsBought) {
        this.ticketsBought = ticketsBought;
        rankingChanged();
    }

    public double getMoneySpent() {
        return moneySpent;
    }

    public void setMoneySpent(double moneySpent) {
        this.moneySpent = moneySpent;
        rankingChanged();
    }

    public int getWins() {
        return wins;
    }

    public void setWins(int wins) {
        this.wins = wins;
        rankingChanged();
    }

    public double getHighestWin() {
        return highestWin;
    }

    public void setHighestWin(double highestWin) {
        this.highestWin = highestWin;
        rankingChanged();
    }

    public double getTotalWon() {
        return totalWon;
    }

    public void setTotalWon(double totalWon) {
        this.totalWon = totalWon;
        rankingChanged();
    }

    public int getRoundsPlayed() {
        return roundsPlayed;
    }

    public void setRoundsPlayed(int roundsPlayed) {
        this.roundsPlayed = roundsPlayed;
        rankingChanged();
    }

    public int getLosingStreak() {
        return losingStreak;
    }

    public void setLosingStreak(int losingStreak) {
        this.losingStreak = Math.max(0, losingStreak);
        changed();
    }

    public int getBestLosingStreak() {
        return bestLosingStreak;
    }

    public void setBestLosingStreak(int bestLosingStreak) {
        this.bestLosingStreak = Math.max(0, bestLosingStreak);
        changed();
    }

    public int getConsolationRewards() {
        return consolationRewards;
    }

    public void setConsolationRewards(int consolationRewards) {
        this.consolationRewards = Math.max(0, consolationRewards);
        changed();
    }

    public int getPityRewards() {
        return pityRewards;
    }

    public void setPityRewards(int pityRewards) {
        this.pityRewards = Math.max(0, pityRewards);
        changed();
    }

    public int getLuckyNumberHits() {
        return luckyNumberHits;
    }

    public void setLuckyNumberHits(int luckyNumberHits) {
        this.luckyNumberHits = Math.max(0, luckyNumberHits);
        changed();
    }

    public int getGiftedTickets() {
        return giftedTickets;
    }

    public void setGiftedTickets(int giftedTickets) {
        this.giftedTickets = Math.max(0, giftedTickets);
        changed();
    }

    public double getGiftSpent() {
        return giftSpent;
    }

    public void setGiftSpent(double giftSpent) {
        this.giftSpent = Math.max(0.0D, giftSpent);
        changed();
    }

    public LocalDateTime getLastPurchaseAt() {
        return lastPurchaseAt;
    }

    public void setLastPurchaseAt(LocalDateTime lastPurchaseAt) {
        this.lastPurchaseAt = lastPurchaseAt;
        changed();
    }

    public LocalDateTime getLastWinAt() {
        return lastWinAt;
    }

    public void setLastWinAt(LocalDateTime lastWinAt) {
        this.lastWinAt = lastWinAt;
        changed();
    }

    public void recordPurchase(String name, int amount, double cost, LocalDateTime now) {
        playerName = name;
        ticketsBought += amount;
        moneySpent += cost;
        lastPurchaseAt = now;
        rankingChanged();
    }

    public void recordRoundPlayed(String name) {
        playerName = name;
        roundsPlayed++;
        rankingChanged();
    }

    public void recordLoss(String name) {
        playerName = name;
        losingStreak++;
        bestLosingStreak = Math.max(bestLosingStreak, losingStreak);
        changed();
    }

    public void recordWin(String name, double amount, LocalDateTime now) {
        playerName = name;
        wins++;
        totalWon += amount;
        highestWin = Math.max(highestWin, amount);
        lastWinAt = now;
        losingStreak = 0;
        rankingChanged();
    }

    public void recordConsolationReward(String name) {
        playerName = name;
        consolationRewards++;
        changed();
    }

    public void recordPityReward(String name, boolean resetStreak) {
        playerName = name;
        pityRewards++;
        if (resetStreak) {
            losingStreak = 0;
        }
        changed();
    }

    public void recordLuckyNumberHit(String name) {
        playerName = name;
        luckyNumberHits++;
        changed();
    }

    public void recordGiftPurchase(String name, int amount, double cost) {
        playerName = name;
        giftedTickets += amount;
        giftSpent += cost;
        changed();
    }
}
