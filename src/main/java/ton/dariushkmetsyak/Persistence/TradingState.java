package ton.dariushkmetsyak.Persistence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Полное состояние торговой сессии для восстановления после сбоя.
 */
public class TradingState {

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("timestamp")
    private long timestamp;

    @JsonProperty("coinName")
    private String coinName;

    @JsonProperty("isTrading")
    private boolean isTrading;

    @JsonProperty("buyPrice")
    private Double buyPrice;

    @JsonProperty("boughtFor")
    private Double boughtFor;

    @JsonProperty("soldFor")
    private Double soldFor;

    @JsonProperty("currentMinPrice")
    private Double currentMinPrice;

    @JsonProperty("currentMaxPrice")
    private Double currentMaxPrice;

    @JsonProperty("currentMinPriceTimestamp")
    private Double currentMinPriceTimestamp;

    @JsonProperty("currentMaxPriceTimestamp")
    private Double currentMaxPriceTimestamp;

    @JsonProperty("priceHistory")
    private TreeMap<Double, Double> priceHistory;

    @JsonProperty("reversals")
    private List<ReversalPoint> reversals;

    @JsonProperty("tradingSum")
    private double tradingSum;

    @JsonProperty("buyGap")
    private double buyGap;

    @JsonProperty("sellWithProfitGap")
    private double sellWithProfitGap;

    @JsonProperty("sellWithLossGap")
    private double sellWithLossGap;

    @JsonProperty("updateTimeout")
    private int updateTimeout;

    @JsonProperty("chatId")
    private Long chatId;

    @JsonProperty("accountType")
    private String accountType;

    /** coinSymbol -> amount, wallet snapshot at save time */
    @JsonProperty("walletAssets")
    private TreeMap<String, Double> walletAssets;

    // Trade statistics — persisted for restore after restart
    @JsonProperty("winCount")
    private int winCount;

    @JsonProperty("lossCount")
    private int lossCount;

    @JsonProperty("totalProfit")
    private double totalProfit;

    @JsonProperty("totalLoss")
    private double totalLoss;

    @JsonProperty("totalCommission")
    private double totalCommission;

    @JsonProperty("startBalance")
    private double startBalance;

    public TradingState() {
        this.timestamp = System.currentTimeMillis();
        this.priceHistory = new TreeMap<>();
        this.reversals = new ArrayList<>();
        this.walletAssets = new TreeMap<>();
    }

    public static class ReversalPoint {
        @JsonProperty("timestamp") private double timestamp;
        @JsonProperty("price")     private double price;
        @JsonProperty("tag")       private String tag;

        public ReversalPoint() {}
        public ReversalPoint(double ts, double price, String tag) {
            this.timestamp = ts; this.price = price; this.tag = tag;
        }
        public double getTimestamp() { return timestamp; }
        public void setTimestamp(double v) { this.timestamp = v; }
        public double getPrice() { return price; }
        public void setPrice(double v) { this.price = v; }
        public String getTag() { return tag; }
        public void setTag(String v) { this.tag = v; }
    }

    public void saveToFile(String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        this.timestamp = System.currentTimeMillis();
        mapper.writeValue(file, this);
    }

    public static TradingState loadFromFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) throw new IOException("State file not found: " + filePath);
        return new ObjectMapper().readValue(file, TradingState.class);
    }

    public static boolean stateFileExists(String filePath) {
        return new File(filePath).exists();
    }

    // --- Getters / Setters ---
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long v) { this.timestamp = v; }
    public String getCoinName() { return coinName; }
    public void setCoinName(String v) { this.coinName = v; }
    public boolean isTrading() { return isTrading; }
    public void setTrading(boolean v) { isTrading = v; }
    public Double getBuyPrice() { return buyPrice; }
    public void setBuyPrice(Double v) { this.buyPrice = v; }
    public Double getBoughtFor() { return boughtFor; }
    public void setBoughtFor(Double v) { this.boughtFor = v; }
    public Double getSoldFor() { return soldFor; }
    public void setSoldFor(Double v) { this.soldFor = v; }
    public Double getCurrentMinPrice() { return currentMinPrice; }
    public void setCurrentMinPrice(Double v) { this.currentMinPrice = v; }
    public Double getCurrentMaxPrice() { return currentMaxPrice; }
    public void setCurrentMaxPrice(Double v) { this.currentMaxPrice = v; }
    public Double getCurrentMinPriceTimestamp() { return currentMinPriceTimestamp; }
    public void setCurrentMinPriceTimestamp(Double v) { this.currentMinPriceTimestamp = v; }
    public Double getCurrentMaxPriceTimestamp() { return currentMaxPriceTimestamp; }
    public void setCurrentMaxPriceTimestamp(Double v) { this.currentMaxPriceTimestamp = v; }
    public TreeMap<Double, Double> getPriceHistory() { return priceHistory; }
    public void setPriceHistory(TreeMap<Double, Double> v) { this.priceHistory = v; }
    public List<ReversalPoint> getReversals() { return reversals; }
    public void setReversals(List<ReversalPoint> v) { this.reversals = v; }
    public double getTradingSum() { return tradingSum; }
    public void setTradingSum(double v) { this.tradingSum = v; }
    public double getBuyGap() { return buyGap; }
    public void setBuyGap(double v) { this.buyGap = v; }
    public double getSellWithProfitGap() { return sellWithProfitGap; }
    public void setSellWithProfitGap(double v) { this.sellWithProfitGap = v; }
    public double getSellWithLossGap() { return sellWithLossGap; }
    public void setSellWithLossGap(double v) { this.sellWithLossGap = v; }
    public int getUpdateTimeout() { return updateTimeout; }
    public void setUpdateTimeout(int v) { this.updateTimeout = v; }
    public Long getChatId() { return chatId; }
    public void setChatId(Long v) { this.chatId = v; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String v) { this.accountType = v; }
    public TreeMap<String, Double> getWalletAssets() { return walletAssets; }
    public void setWalletAssets(TreeMap<String, Double> v) { this.walletAssets = v; }

    public int getWinCount() { return winCount; }
    public void setWinCount(int v) { this.winCount = v; }
    public int getLossCount() { return lossCount; }
    public void setLossCount(int v) { this.lossCount = v; }
    public double getTotalProfit() { return totalProfit; }
    public void setTotalProfit(double v) { this.totalProfit = v; }
    public double getTotalLoss() { return totalLoss; }
    public void setTotalLoss(double v) { this.totalLoss = v; }
    public double getTotalCommission() { return totalCommission; }
    public void setTotalCommission(double v) { this.totalCommission = v; }
    public double getStartBalance() { return startBalance; }
    public void setStartBalance(double v) { this.startBalance = v; }

    @Override
    public String toString() {
        return "TradingState{sessionId='" + sessionId + "', coin='" + coinName +
               "', isTrading=" + isTrading + ", type='" + accountType + "'}";
    }
}
