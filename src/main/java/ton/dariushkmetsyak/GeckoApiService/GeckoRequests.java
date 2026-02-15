package ton.dariushkmetsyak.GeckoApiService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;


public class GeckoRequests {
    private static String myApiKey = "CG-cbW7FEWxc6mZiUAxSY3VDCd9";
    private static String apiKey = "";


    public static void setApiKey(String apiKey) {
        GeckoRequests.apiKey = apiKey;
    }

    public static boolean testConnectionToGeckoServer() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.coingecko.com/api/v3/ping"))
                .header("accept", "application/json")
                .header("x-cg-demo-api-key", apiKey)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = null;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    public static String getCoinsList() {                         //returning JSON List of available coins on Gecko
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.coingecko.com/api/v3/coins/list"))
                .header("accept", "application/json")
                .header("x-cg-demo-api-key", apiKey)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = null;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return response.body();
    }


    public static String getCoinListWithMarketData (int page) { //выгружается постранично, 250 монет на страницу, в порядке убывания капитализации
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=250&page="+page))
                .header("accept", "application/json")
                .header("x-cg-demo-api-key", apiKey)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = null;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return response.body();
    }
    public static String getCoinDataWithRange(String coinId, LocalDate fromDate, LocalDate toDate) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.coingecko.com/api/v3/coins/" + coinId + "/market_chart/range?vs_currency=usd&from=" + fromDate.toEpochSecond(LocalTime.MIN, ZoneOffset.of("+05:00"))+ "&to=" + toDate.toEpochSecond(LocalTime.MIN, ZoneOffset.of("+05:00"))))
                .header("accept", "application/json")
                .header("x-cg-demo-api-key", apiKey)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = null;
        try {
            System.out.println(request.uri());
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return response.body();
    }

    public static String getCoinDailyData(String coinId) {   //график с пятиминутным интервалом на 1 сутки за период: текущий момент - 1 сутки
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.coingecko.com/api/v3/coins/"
                        + coinId + "/market_chart/range?vs_currency=usd&from="
                        + LocalDateTime.now().minusDays(1).toEpochSecond(ZoneOffset.of("+05:00"))
                        + "&to="
                        + LocalDateTime.now().toEpochSecond(ZoneOffset.of("+05:00"))))
                .header("accept", "application/json")
                .header("x-cg-demo-api-key", apiKey)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = null;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return response.body();
    }
public static String getCoinMonthlyData(String coinId, YearMonth yearMonth) {   //график с часовым интервалом на 1 месяц указанный в параметрах
  //  LocalDate requestedMonth = LocalDate.of(yea)
    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.coingecko.com/api/v3/coins/"
                    + coinId + "/market_chart/range?vs_currency=usd&from="
                    + yearMonth.atDay(1).toEpochSecond(LocalTime.MIDNIGHT,ZoneOffset.of("+05:00"))
                    + "&to="
                    + yearMonth.atEndOfMonth().toEpochSecond(LocalTime.of(23,59,59),ZoneOffset.of("+05:00"))))
            .header("accept", "application/json")
            .header("x-cg-demo-api-key", apiKey)
            .method("GET", HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> response = null;
    try {
        response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    } catch (IOException | InterruptedException e) {
        throw new RuntimeException(e);
    }
        return response.body();
    }
}



