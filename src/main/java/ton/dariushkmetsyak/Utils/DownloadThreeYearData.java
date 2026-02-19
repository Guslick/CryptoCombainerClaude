package ton.dariushkmetsyak.Utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.GeckoApiService.GeckoRequests;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;

/**
 * Утилита для загрузки исторических данных за 3 года
 */
public class DownloadThreeYearData {
    
    public static void main(String[] args) {
        String coinId = "ethereum";
        int years = 3;
        
        System.out.println("📡 Загрузка данных " + coinId + " за " + years + " лет...");
        
        try {
            // Установка API ключа
            GeckoRequests.setApiKey("CG-cbW7FEWxc6mZiUAxSY3VDCd9");
            
            // Вычисление дат
            LocalDate toDate = LocalDate.now();
            LocalDate fromDate = toDate.minusYears(years);
            
            System.out.println("Период: " + fromDate + " -> " + toDate);
            
            // Загрузка данных
            String jsonData = GeckoRequests.getCoinDataWithRange(coinId, fromDate, toDate);
            
            // Сохранение в файл
            String outputPath = "YearlyCharts/" + capitalize(coinId) + "/ThreeYearChart.json";
            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();
            
            // Парсинг и пересохранение для форматирования
            ObjectMapper mapper = new ObjectMapper();
            Object json = mapper.readValue(jsonData, Object.class);
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, json);
            
            System.out.println("✅ Данные сохранены в: " + outputPath);
            
            // Статистика
            Chart chart = Chart.loadFromJson(outputFile);
            System.out.println("📊 Загружено точек: " + chart.getPrices().size());
            
        } catch (IOException e) {
            System.err.println("❌ Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
