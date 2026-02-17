package ton.dariushkmetsyak.GeckoApiService.geckoEntities;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigInteger;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Coin{

    private String id, symbol, name;
    private int market_cap_rank;
    private BigInteger market_cap, fully_diluted_valuation, total_volume, total_supply, max_supply, circulating_supply;
    public int getMarket_cap_rank() {return market_cap_rank;}
    public BigInteger getMarket_cap() {return market_cap;}
    public BigInteger getFully_diluted_valuation() {return fully_diluted_valuation;}
    public BigInteger getTotal_volume() {return total_volume;}
    public BigInteger getTotal_supply() {return total_supply;}
    public BigInteger getMax_supply() {return max_supply;}
    public BigInteger getCirculating_supply() {return circulating_supply;}
    public String getId() {return id;}
    public String getSymbol() {return symbol;}
    public String getName() {return name;}
    public String getUsdtPair() {return symbol.toUpperCase()+"USDT";}

    private Coin(){};

    public static Coin createCoin (String coinId) throws Exception {
            return CoinsList.getCoin(coinId);
    }



    @Override
    public String toString() {return symbol ;}

    @Override
    public int hashCode() {
        return id == null ? 0 : id.toLowerCase().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {

            return false;
        }
        Coin coin = (Coin) obj;
        return this.getId().equalsIgnoreCase(coin.getId());

    }
}