package ton.dariushkmetsyak.Charts;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ton.dariushkmetsyak.GeckoApiService.GeckoRequests;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;

import java.io.File;
import java.io.IOException;

 public class Chart1DayUntilNow_5MinuteInterval extends Chart{
    /*
    Five minute chart. 24 hours from yesterday until now.
     */

     private Chart1DayUntilNow_5MinuteInterval(){}

    @Override
    public void print() {
        System.out.println("                           Daily market chart for " + coin.toUpperCase());
        super.print();
    }

    Chart1DayUntilNow_5MinuteInterval(Coin coin){
         Chart1DayUntilNow_5MinuteInterval coinDailyMarketChart;
         String jsonResponse  = GeckoRequests.getCoinDailyData(coin.getId());

         ObjectMapper objectMapper = new ObjectMapper();
         try {
             coinDailyMarketChart = objectMapper.readValue(jsonResponse, new TypeReference<Chart1DayUntilNow_5MinuteInterval>(){});
             coinDailyMarketChart.coin=coin.getName();
             this.copy(coinDailyMarketChart);
         } catch (IOException e) {
             throw new RuntimeException(e);
         }

     }
}
