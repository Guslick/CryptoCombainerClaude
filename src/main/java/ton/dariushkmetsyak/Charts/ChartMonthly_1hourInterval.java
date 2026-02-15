package ton.dariushkmetsyak.Charts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ton.dariushkmetsyak.GeckoApiService.GeckoRequests;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;

import java.io.File;
import java.io.IOException;
import java.time.YearMonth;

 class ChartMonthly_1hourInterval extends Chart {


    @Override
    public void print() {
        System.out.println("                           Monthly market chart for " + coin.toUpperCase());
        super.print();
    }

     private ChartMonthly_1hourInterval(){
     }


     ChartMonthly_1hourInterval (Coin coin, YearMonth yearMonth){
        ChartMonthly_1hourInterval coinDailyMarketChart;
        String jsonResponse  = GeckoRequests.getCoinMonthlyData(coin.getId(), yearMonth);

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            coinDailyMarketChart = objectMapper.readValue(jsonResponse, new TypeReference<ChartMonthly_1hourInterval>(){});
            coinDailyMarketChart.coin=coin.getName();
            this.copy(coinDailyMarketChart);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
