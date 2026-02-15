package ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy;

import javafx.application.Application;
import ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.Util.Prices;
import ton.dariushkmetsyak.Charts.Chart;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class ReversalPointsStrategyResearcher {

    public static Map<String, Double> startResearchingChart(Chart researchedChart) {

        if (researchedChart.getPrices().size()<3) return null;
        TradingChart.drawChart(researchedChart);
        TreeMap<Double, Double> prices = new TreeMap<>();
        for (double[] d : researchedChart.getPrices()) {
            prices.put(d[0], d[1]);
        }
        double minPercentageGap = 0.1;
        final Double[] currentMinPrice = {Double.MAX_VALUE};
        final Double[] currentMaxPrice = {0.0};
        final Double[] currentMaxPriceTimestamp = {0.0};
        final Double[] currentMinPriceTimestamp = {Double.MAX_VALUE};
        class Reversal {
            private final double[] data;
            private final String tag;

            Reversal(double[] data, String tag) {
                this.data = data;
                this.tag = tag;
            }
        }
        ArrayList<Reversal> reversalArrayList = new ArrayList<>();
        reversalArrayList.add(new Reversal(researchedChart.getPrices().get(0), "123"));               //убрать первый элемент в конце метода
        BiConsumer<Double, Double> findReversalPoints = (timestamp, price) -> {
          //  TradingChart.addSimplePoint(timestamp, price);
            Reversal previousRec = reversalArrayList.get(reversalArrayList.toArray().length - 1);
            if (price > currentMaxPrice[0]) {
                currentMaxPrice[0] = price;
                currentMaxPriceTimestamp[0] = timestamp;
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > minPercentageGap) {
                    if (!Objects.equals(previousRec.tag, "min")) {
                        Reversal r = new Reversal(new double[]{currentMinPriceTimestamp[0], currentMinPrice[0]}, "min");
                        reversalArrayList.add(r);
                        TradingChart.addSimplePriceMarker(currentMinPriceTimestamp[0], currentMinPrice[0]);
                    }
                    currentMinPrice[0] = price;
                }
            }
            if (price < currentMinPrice[0]) {
                currentMinPrice[0] = price;
                currentMinPriceTimestamp[0] = timestamp;
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > minPercentageGap) {
                    if (!Objects.equals(previousRec.tag, "max")) {
                        Reversal r = new Reversal(new double[]{currentMaxPriceTimestamp[0], currentMaxPrice[0]}, "max");
                        reversalArrayList.add(r);
                        TradingChart.addSimplePriceMarker(currentMaxPriceTimestamp[0], currentMaxPrice[0]);
                    }
                    currentMaxPrice[0] = price;

                }
            }
        };

        prices.forEach(findReversalPoints);

        Double[] allGaps = new Double[reversalArrayList.toArray().length - 2];
        ArrayList<Double> growthGaps = new ArrayList<>();
        ArrayList<Double> dropGaps = new ArrayList<>();
        reversalArrayList.remove(0);
        for (Reversal r : reversalArrayList) {
            if (reversalArrayList.indexOf(r) == 0) {
                continue;
            }


            double currentPrice = (double) r.data[1];
            double prevPrice = (double) (reversalArrayList.get((reversalArrayList.indexOf(r)) - 1)).data[1];
            double currentGap = ((Math.max(currentPrice, prevPrice) / Math.min(currentPrice, prevPrice)) * 100 - 100);
            if (r.tag.equals("min")) dropGaps.add(currentGap);
            if (r.tag.equals("max")) growthGaps.add(currentGap);
            allGaps[reversalArrayList.indexOf(r) - 1] = currentGap;
        }
        Arrays.sort(allGaps);
        dropGaps.sort(Comparator.naturalOrder());
        growthGaps.sort(Comparator.naturalOrder());

        Map<String, Double> result = new HashMap<>();
        result.put("GrowthGapsMedian", growthGaps.get(dropGaps.size() / 2));
        result.put("DropGapsMedian", dropGaps.get(dropGaps.size() / 2));
        result.put("AllGapsMedian", Prices.round(allGaps[allGaps.length / 2]));
        result.put("AllGapsCount", (double)allGaps.length);
        result.put("TotalVolume", researchedChart.getTotal_volumes().
                stream().
                mapToDouble(x-> x[1]).
                summaryStatistics()
                .getSum());
        System.out.println(Arrays.toString(allGaps));
        sendPhotoToTelegram(researchedChart.getCoinName(), result);
        return result;
    }

    public static Map<String, Double> startResearchingChartArray(Path researchedChartsPath){
        final String chartName = "Yearlychart.json";
        new Thread(new Runnable() {
            @Override
            public void run() {
                Application.launch(TradingChart.class);
            }
        }).start();
        ArrayList<Chart> charts = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(researchedChartsPath.toString()))) {
                for (Path entry : stream) {
                        charts.add(Chart.loadFromJson(new File(researchedChartsPath  +"/"+ entry.getFileName().toString()+ "/" +chartName)));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        return startResearchingChartArray(charts.toArray(new Chart[charts.size()]));
    }


    public static Map<String, Double> startResearchingChartArray(Chart[] researchedCharts){

        ArrayList<Double> allGrowthGaps = new ArrayList<>(),
                 allDropGaps  = new ArrayList<>(),
                 allGaps = new ArrayList<>(),
                 allGapsCount = new ArrayList<>(),
                 allTotalVolumes = new ArrayList<>();

        for (Chart chart : researchedCharts){
            System.out.println(chart.getCoinName());
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            Map<String, Double> researchedChartResult = startResearchingChart(chart);
            if (researchedChartResult==null)continue;
            allGrowthGaps.add(researchedChartResult.get("GrowthGapsMedian"));
            allDropGaps.add(researchedChartResult.get("DropGapsMedian"));
            allGaps.add(researchedChartResult.get("AllGapsMedian"));
            allGapsCount.add(researchedChartResult.get("AllGapsCount"));
            allTotalVolumes.add(researchedChartResult.get("TotalVolume"));
            clearChart();
        }
        allGrowthGaps.sort(Comparator.naturalOrder());
        allDropGaps.sort(Comparator.naturalOrder());
        allGaps.sort(Comparator.naturalOrder());
        allGapsCount.sort(Comparator.naturalOrder());
        allTotalVolumes.sort(Comparator.naturalOrder());
        Map<String,Double> result = new HashMap<>();
        result.put("GrowthGapsMedian", allGrowthGaps.get(allGrowthGaps.size() / 2));
        result.put("DropGapsMedian", allDropGaps.get(allDropGaps.size() / 2));
        result.put("AllGapsMedian", allGaps.get(allGaps.size() / 2));
        result.put("AllGapsCount", allGapsCount.get(allGapsCount.size() / 2));
        result.put("TotalVolume", allTotalVolumes.get(allTotalVolumes.size() / 2));
        ArrayList<double[]> chart= new ArrayList<>();
        for (double growthGap: allGrowthGaps){
            chart.add(new double[]{chart.size(),growthGap});
           }
        try {
            TradingChart.drawChart(chart, "All growth gaps median");
//            TradingChart.drawChart(Chart.getMonthlyChart_1hourInterval(Coin.createCoin("Ethereum"), YearMonth.of(2024,12)));

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        sendPhotoToTelegram("COINS", result);
        //clearChart();

        return result;
    }

    private static void clearChart (){
        TradingChart.clearChart();
    }

    private static void sendPhotoToTelegram (String researchedCoins, Map<String,Double> result){
        String telegramPicturePath = "";
        String currentPicturePath = telegramPicturePath + LocalDateTime.now();
        TradingChart.makeScreenShot(currentPicturePath);
        ImageAndMessageSender.sendPhoto(currentPicturePath,
                "Reversal ponts strategy research" + "\n\n" +
                         researchedCoins +
                        "\n" + "Grow gaps median" + ": " + Prices.round(result.get("GrowthGapsMedian")) +
                        "\n" + "Drop gaps median" + ": " + Prices.round(result.get("DropGapsMedian")) +
                        "\n" + "All gaps median" + ": " + Prices.round(result.get("AllGapsMedian"))+
                        "\n" + "All gaps count(growths approx equals drops)" + ": " + result.get("AllGapsCount").intValue() +
                        "\n" + "Total volume" + ": " + String.format(Locale.FRANCE, "%,d", result.get("TotalVolume").longValue())
        );
        try {
            Files.delete(Path.of(currentPicturePath));
        } catch (IOException e) {
            System.err.println("Error deleting file created for sending to Telegram: " + currentPicturePath);
            throw new RuntimeException(e);
        }

    }
}

