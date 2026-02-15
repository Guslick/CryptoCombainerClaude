package ton.dariushkmetsyak.Charts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.ArrayList;

public  class Chart {
    @JsonProperty("coinName")
    protected String coin;
    //protected String jsonResponse;

    protected Chart(){}

    protected ArrayList<double[]> prices;
    protected ArrayList<double[]> market_caps;
    protected ArrayList<double[]> total_volumes;

    public ArrayList<double[]> getPrices() {
        return prices;
    }



    public ArrayList<double[]> getTotal_volumes() {
        return total_volumes;
    }

    public ArrayList<double[]> getMarket_caps() {
        return market_caps;
    }

    public String getCoinName () {
        return coin;
    }

    //public String getJsonResponse() {return jsonResponse;}

    public static Chart get1DayUntilNowChart_5MinuteInterval(Coin coin) {return new Chart1DayUntilNow_5MinuteInterval(coin);}
    public static Chart getMonthlyChart_1hourInterval(Coin coin, YearMonth yearMonth) {return new ChartMonthly_1hourInterval(coin, yearMonth); }
    public static Chart getYearlyChart_1hourInterval(Coin coin) {
        return new ChartYearly_1hourInterval(coin);
    }


    public void saveToJson (File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(file, this);
    }
    public static Chart loadFromJson (File file) throws IOException {         ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(file, new TypeReference<Chart>(){});
    }

    public void roundChart () {
      this.getPrices().forEach(x->{
          System.out.println("Before" + x[1]);
          if (x[1]>=0.01&&x[1]<1) {x[1] = (double) Math.round(x[1] * 100000) /10000;} else
          if (x[1]>=1&&x[1]<10) {x[1] = (double) Math.round(x[1] * 1000) / 1000;} else
          if (x[1]>=10&&x[1]<100000) {x[1] = (double) Math.round(x[1] * 100) / 100;} else
          if (x[1]>=100000)x[1] = (double) Math.round(x[1]);
          System.out.println("After" + x[1]);





      });
    }
    public void print() {
        System.out.printf("\n %15s%20s%25s%18s    \n", "Time/date", "Price", "Trading volume", "Market cap");
        System.out.println("______________________________________________________________________________________");
        for (int i = 0; i < prices.size() - 1; i++) {
            System.out.printf("|  %tF %tT  |  %-15.10f  |  %-15.0f  |  %-15.0f  |\n",
                    new Timestamp((long) prices.get(i)[0]),
                    new Timestamp((long) prices.get(i)[0]),
                    prices.get(i)[1],
                    total_volumes.get(i)[1],
                    market_caps.get(i)[1]);
        }
        System.out.println("--------------------------------------------------------------------------------------");
    }


    protected void copy(Chart chart) {
        this.coin=chart.coin;
        this.market_caps=chart.market_caps;
        this.prices=chart.prices;
        this.total_volumes=chart.total_volumes;
    }
    protected void addCopy(Chart chart) {
        this.coin=chart.coin;
        this.market_caps.addAll(market_caps.size(),chart.market_caps);
        this.prices.addAll(prices.size(), chart.prices);
        this.total_volumes.addAll(total_volumes.size(),chart.total_volumes);
    }

}
