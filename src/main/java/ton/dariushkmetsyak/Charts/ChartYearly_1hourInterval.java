package ton.dariushkmetsyak.Charts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ton.dariushkmetsyak.GeckoApiService.GeckoRequests;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;

import java.io.File;
import java.io.IOException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

class ChartYearly_1hourInterval extends Chart {


   @Override
   public void print() {
       System.out.println("                           Monthly market chart for " + coin.toUpperCase());
       super.print();
   }

    private ChartYearly_1hourInterval(){
    }


    ChartYearly_1hourInterval(Coin coin){
       this.market_caps = new ArrayList<>();
       this.prices = new ArrayList<>();
       this.total_volumes=new ArrayList<>();
           ChartYearly_1hourInterval coinDailyMarketChart;
           String jsonResponse;
           for (YearMonth discoveredYM = YearMonth.now().minusMonths(1); discoveredYM.isAfter(YearMonth.now().minusMonths(12));discoveredYM=discoveredYM.minusMonths(1)){
               jsonResponse  = GeckoRequests.getCoinMonthlyData(coin.getId(), discoveredYM);

               ObjectMapper objectMapper = new ObjectMapper();
               try {
                   TimeUnit.SECONDS.sleep(45);
                   coinDailyMarketChart = objectMapper.readValue(jsonResponse, new TypeReference<ChartYearly_1hourInterval>(){});
                   coinDailyMarketChart.coin=coin.getName();
                   this.addCopy(coinDailyMarketChart);
                   System.out.println(discoveredYM + " added");
               } catch (IOException | InterruptedException e) {
                   System.out.println(jsonResponse);
                   throw new RuntimeException(e);
               }

           }


   }

}
