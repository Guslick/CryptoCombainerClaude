package ton.dariushkmetsyak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;

import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BinanceAccountTest {
    Account testerAccount;
    final static char[] TEST_Ed25519_PRIVATE_KEY = "/home/darik/Desktop//test-prv-key.pem".toCharArray();
    final static char[] TEST_Ed25519_API_KEY = "dLlBZX4SsOwXuDioeLWfOFCldwqgwGrIGhGEZdIUWtBCSKsTvqXyl0eYm6lepcAr".toCharArray();
    final static Coin USDT;
    static {
        try {
            USDT = Coin.createCoin("USDT");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Coin[] randomTestCoins;
    Map<Coin, Double> testWallet;


    @BeforeAll
    void init() {
        try {
            testerAccount = AccountBuilder.createNewBinance(TEST_Ed25519_API_KEY, TEST_Ed25519_PRIVATE_KEY, AccountBuilder.BINANCE_BASE_URL.TESTNET);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        testWallet = testerAccount.wallet().getAllAssets();
        randomTestCoins = new Coin[3];
        for (int i = 0; i < randomTestCoins.length; i++) {
            randomTestCoins[i] = testWallet
                    .keySet()
                    .toArray(new Coin[0])[new Random().nextInt(testWallet.keySet().size())];
        }
    }

    @Test
    void initCheckTest() {
        assertFalse(testWallet.isEmpty());
        for (Coin coin : randomTestCoins) {
            assertNotNull(coin);


        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0,1,2})
    void isBuyingWorkingCorrectTest(int position) throws Exception {
        Coin coin = randomTestCoins[position];
        DecimalFormat dcf = new DecimalFormat("##.####");
        double  buyingPrice = Account.getCurrentPrice(coin),
                notional = getMinNotional(coin),
                minStepSize = getMinStepSize(coin),
                quantity = Double.parseDouble(dcf.format(Math.ceil(notional/buyingPrice/minStepSize)*minStepSize)),
                startUsdtQty = testerAccount.wallet().getAmountOfCoin(USDT),
                startCoinQty = testerAccount.wallet().getAmountOfCoin(coin);
        System.out.println(coin.getUsdtPair());
        System.out.println("Buying quantity: "+ quantity);
        System.out.println("Price : "+ buyingPrice);
        System.out.println("You pay: " + buyingPrice*quantity);
        System.out.println("Amount in wallet: " + testerAccount.wallet().getAmountOfCoin(coin));
//        assertTrue(testerAccount.trader().buy(randomTestCoins[position],buyingPrice, quantity));
        while (!testerAccount.trader().getOrder().get("status").equalsIgnoreCase("FILLED")){
            System.out.println("Currently order status is not FILLED (ORDER IS NOD ENDED)" );
            TimeUnit.SECONDS.sleep(10);
        }
        double
            endUsdtQty=testerAccount.wallet().getAmountOfCoin(USDT),
            endCoinQty=testerAccount.wallet().getAmountOfCoin(coin);
        assertEquals(startCoinQty+quantity,endCoinQty,endCoinQty/100);
        assertEquals(startUsdtQty-quantity*buyingPrice,endUsdtQty,startUsdtQty/1000);
    }
    @ParameterizedTest
    @ValueSource(ints = {0,1,2})
    void isSellingWorkingCorrectTest(int position) throws Exception{
            Coin coin = randomTestCoins[position];
            DecimalFormat dcf = new DecimalFormat("##.####");
            double sellingPrice = Account.getCurrentPrice(coin),
                    notional = getMinNotional(coin),
                    minStepSize = getMinStepSize(coin),
                    quantity = Double.parseDouble(dcf.format(Math.ceil(notional / sellingPrice / minStepSize) * minStepSize)),
                    startUsdtQty = testerAccount.wallet().getAmountOfCoin(USDT),
                    startCoinQty = testerAccount.wallet().getAmountOfCoin(coin);
            System.out.println(coin.getUsdtPair());
            System.out.println("Selling quantity: " + quantity);
            System.out.println("Price : " + sellingPrice);
            System.out.println("You receive: " + sellingPrice * quantity);
            System.out.println("Amount in wallet: " + testerAccount.wallet().getAmountOfCoin(coin));
//            assertTrue(testerAccount.trader().sell(randomTestCoins[position], sellingPrice, quantity));
            while (!testerAccount.trader().getOrder().get("status").equalsIgnoreCase("FILLED")) {
                System.out.println("Currently order status is not FILLED");
                TimeUnit.SECONDS.sleep(10);
            }
            double
                    endUsdtQty = testerAccount.wallet().getAmountOfCoin(USDT),
                    endCoinQty = testerAccount.wallet().getAmountOfCoin(coin);
            assertEquals(startCoinQty - quantity, endCoinQty, endCoinQty / 100);
            assertEquals(startUsdtQty + quantity * sellingPrice, endUsdtQty, startUsdtQty / 1000);}


    double getMinOrderQuantity (Coin coin) {                           //парсинг и извлечение фильтра Binance: minQuantity
        double result=0;
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.binance.com/api/v3/exchangeInfo?symbol=" + coin.getUsdtPair()))
                .GET()
                .build();
        HttpResponse<String> response = null;
        JsonNode node;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ObjectMapper mapper = new ObjectMapper();
            node = mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        node = node.get("symbols");
        node = node.get(0);
        node = node.get("filters");
        for (JsonNode n : node){
            System.out.println(n);
            if(n.get("filterType").textValue().equalsIgnoreCase("LOT_SIZE")){
                System.out.println(n);
                result = Double.parseDouble(n.get("minQty").textValue());
            }
        }
        return result;
    }

    double getMinNotional (Coin coin) {                                     //парсинг и извлечение фильтра Binance: minNotional
        double result=0;
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.binance.com/api/v3/exchangeInfo?symbol=" + coin.getUsdtPair()))
                .GET()
                .build();
        HttpResponse<String> response = null;
        JsonNode node;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ObjectMapper mapper = new ObjectMapper();
            node = mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        node = node.get("symbols");
        node = node.get(0);
        node = node.get("filters");
        for (JsonNode n : node){
            if(n.get("filterType").textValue().equalsIgnoreCase("NOTIONAL")){
                System.out.println(n);
                result = Double.parseDouble(n.get("minNotional").textValue());
            }
        }
        return result;
    }
    double getMinStepSize (Coin coin) {                                //парсинг и извлечение фильтра Binance: minStepSize (минимальный шаг цены)
        double result=0;
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.binance.com/api/v3/exchangeInfo?symbol=" + coin.getUsdtPair()))
                .GET()
                .build();
        HttpResponse<String> response = null;
        JsonNode node;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ObjectMapper mapper = new ObjectMapper();
            node = mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        node = node.get("symbols");
        node = node.get(0);
        node = node.get("filters");
        for (JsonNode n : node){
            if(n.get("filterType").textValue().equalsIgnoreCase("LOT_SIZE")){
                System.out.println(n);
                result = Double.parseDouble(n.get("minQty").textValue());
            }
        }
        return result;
    }
}
