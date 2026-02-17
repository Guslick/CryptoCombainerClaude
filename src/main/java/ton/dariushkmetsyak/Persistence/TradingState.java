package ton.dariushkmetsyak.Persistence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Класс для сериализации и десериализации состояния торговли
 * Позволяет восстановить работу после сбоя
 */
public class TradingState {
    
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
    private String accountType; // "TESTER", "BINANCE_TEST", "BINANCE_MAIN"
    
    @JsonProperty("walletAssets")
    private TreeMap<String, Double> walletAssets;
    
    // Конструкторы
    public TradingState() {
        this.timestamp = System.currentTimeMillis();
        this.priceHistory = new TreeMap<>();
        this.reversals = new ArrayList<>();
        this.walletAssets = new TreeMap<>();
    }
    
    // Вложенный класс для точек разворота
    public static class ReversalPoint {
        @JsonProperty("timestamp")
        private double timestamp;
        
        @JsonProperty("price")
        private double price;
        
        @JsonProperty("tag")
        private String tag;
        
        public ReversalPoint() {}
        
        public ReversalPoint(double timestamp, double price, String tag) {
            this.timestamp = timestamp;
            this.price = price;
            this.tag = tag;
        }
        
        // Getters and Setters
        public double getTimestamp() { return timestamp; }
        public void setTimestamp(double timestamp) { this.timestamp = timestamp; }
        public double getPrice() { return price; }
        public void setPrice(double price) { this.price = price; }
        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }
    }
    
    /**
     * Сохранить состояние в файл
     */
    public void saveToFile(String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        mapper.writeValue(file, this);
    }
    
    /**
     * Загрузить состояние из файла
     */
    public static TradingState loadFromFile(String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        File file = new File(filePath);
        
        if (!file.exists()) {
            throw new IOException("State file not found: " + filePath);
        }
        
        return mapper.readValue(file, TradingState.class);
    }
    
    /**
     * Проверить существование файла состояния
     */
    public static boolean stateFileExists(String filePath) {
        return new File(filePath).exists();
    }
    
    /**
     * Создать бэкап текущего состояния
     */
    public void createBackup(String filePath) throws IOException {
        String backupPath = filePath + ".backup." + System.currentTimeMillis();
        saveToFile(backupPath);
    }
    
    // Getters and Setters
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    
    public String getCoinName() { return coinName; }
    public void setCoinName(String coinName) { this.coinName = coinName; }
    
    public boolean isTrading() { return isTrading; }
    public void setTrading(boolean trading) { isTrading = trading; }
    
    public Double getBuyPrice() { return buyPrice; }
    public void setBuyPrice(Double buyPrice) { this.buyPrice = buyPrice; }
    
    public Double getBoughtFor() { return boughtFor; }
    public void setBoughtFor(Double boughtFor) { this.boughtFor = boughtFor; }
    
    public Double getSoldFor() { return soldFor; }
    public void setSoldFor(Double soldFor) { this.soldFor = soldFor; }
    
    public Double getCurrentMinPrice() { return currentMinPrice; }
    public void setCurrentMinPrice(Double currentMinPrice) { this.currentMinPrice = currentMinPrice; }
    
    public Double getCurrentMaxPrice() { return currentMaxPrice; }
    public void setCurrentMaxPrice(Double currentMaxPrice) { this.currentMaxPrice = currentMaxPrice; }
    
    public Double getCurrentMinPriceTimestamp() { return currentMinPriceTimestamp; }
    public void setCurrentMinPriceTimestamp(Double currentMinPriceTimestamp) { 
        this.currentMinPriceTimestamp = currentMinPriceTimestamp; 
    }
    
    public Double getCurrentMaxPriceTimestamp() { return currentMaxPriceTimestamp; }
    public void setCurrentMaxPriceTimestamp(Double currentMaxPriceTimestamp) { 
        this.currentMaxPriceTimestamp = currentMaxPriceTimestamp; 
    }
    
    public TreeMap<Double, Double> getPriceHistory() { return priceHistory; }
    public void setPriceHistory(TreeMap<Double, Double> priceHistory) { 
        this.priceHistory = priceHistory; 
    }
    
    public List<ReversalPoint> getReversals() { return reversals; }
    public void setReversals(List<ReversalPoint> reversals) { this.reversals = reversals; }
    
    public double getTradingSum() { return tradingSum; }
    public void setTradingSum(double tradingSum) { this.tradingSum = tradingSum; }
    
    public double getBuyGap() { return buyGap; }
    public void setBuyGap(double buyGap) { this.buyGap = buyGap; }
    
    public double getSellWithProfitGap() { return sellWithProfitGap; }
    public void setSellWithProfitGap(double sellWithProfitGap) { 
        this.sellWithProfitGap = sellWithProfitGap; 
    }
    
    public double getSellWithLossGap() { return sellWithLossGap; }
    public void setSellWithLossGap(double sellWithLossGap) { 
        this.sellWithLossGap = sellWithLossGap; 
    }
    
    public int getUpdateTimeout() { return updateTimeout; }
    public void setUpdateTimeout(int updateTimeout) { this.updateTimeout = updateTimeout; }
    
    public Long getChatId() { return chatId; }
    public void setChatId(Long chatId) { this.chatId = chatId; }
    
    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }
    
    public TreeMap<String, Double> getWalletAssets() { return walletAssets; }
    public void setWalletAssets(TreeMap<String, Double> walletAssets) { 
        this.walletAssets = walletAssets; 
    }
    
    @Override
    public String toString() {
        return "TradingState{" +
                "timestamp=" + timestamp +
                ", coinName='" + coinName + '\'' +
                ", isTrading=" + isTrading +
                ", buyPrice=" + buyPrice +
                ", accountType='" + accountType + '\'' +
                '}';
    }
}
