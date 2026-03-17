package ton.dariushkmetsyak.Commission;

/**
 * Tracks per-session trading statistics: wins, losses, profits, commissions.
 */
public class TradeStatistics {
    private int winCount = 0;
    private int lossCount = 0;
    private double totalProfit = 0.0;    // sum of profitable trades (positive)
    private double totalLoss = 0.0;      // sum of losing trades (negative, stored as positive)
    private double totalCommission = 0.0; // estimated commission spent
    private double startBalance = 0.0;
    private double currentBalance = 0.0;

    public TradeStatistics() {}

    public TradeStatistics(double startBalance) {
        this.startBalance = startBalance;
        this.currentBalance = startBalance;
    }

    public void recordTrade(double buyPrice, double sellPrice, double quantity, double commission) {
        double pnl = (sellPrice - buyPrice) * quantity;
        if (pnl >= 0) {
            winCount++;
            totalProfit += pnl;
        } else {
            lossCount++;
            totalLoss += Math.abs(pnl);
        }
        totalCommission += commission;
    }

    public int getWinCount() { return winCount; }
    public int getLossCount() { return lossCount; }
    public int getTotalTrades() { return winCount + lossCount; }
    public double getTotalProfit() { return totalProfit; }
    public double getTotalLoss() { return totalLoss; }
    public double getNetPnl() { return totalProfit - totalLoss; }
    public double getNetPnlAfterCommission() { return totalProfit - totalLoss - totalCommission; }
    public double getTotalCommission() { return totalCommission; }
    public double getStartBalance() { return startBalance; }

    public void setStartBalance(double startBalance) { this.startBalance = startBalance; }
    public double getCurrentBalance() { return currentBalance; }
    public void setCurrentBalance(double currentBalance) { this.currentBalance = currentBalance; }

    // For restoring from saved state
    public void setWinCount(int winCount) { this.winCount = winCount; }
    public void setLossCount(int lossCount) { this.lossCount = lossCount; }
    public void setTotalProfit(double totalProfit) { this.totalProfit = totalProfit; }
    public void setTotalLoss(double totalLoss) { this.totalLoss = totalLoss; }
    public void setTotalCommission(double totalCommission) { this.totalCommission = totalCommission; }

    @Override
    public String toString() {
        return String.format(
            "Сделок: %d (✅ %d / ❌ %d)\n" +
            "Прибыль: +$%.2f\n" +
            "Убытки: -$%.2f\n" +
            "Итого: $%.2f\n" +
            "Комиссия: $%.2f\n" +
            "Итого с комиссией: $%.2f",
            getTotalTrades(), winCount, lossCount,
            totalProfit, totalLoss, getNetPnl(),
            totalCommission, getNetPnlAfterCommission()
        );
    }
}
