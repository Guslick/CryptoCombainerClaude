package ton.dariushkmetsyak.TradingApi.ApiService;

import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfCoinException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfUsdtException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

public abstract class Account {
    protected   SpotTrader spotTrader;
    protected   Wallet wallet;
    public enum USD_TOKENS {
        USDT("USDT"),
        USDC("USDC");
        private String tokenName;
        USD_TOKENS(String tokenName) {
            this.tokenName = tokenName;}
        @Override
        public String toString() {return tokenName;}
    }

    protected Account() {
    }
    public SpotTrader trader(){return spotTrader;}
    public Wallet wallet(){return wallet;}

    protected abstract void initSpotTrader();
    protected abstract void initWallet();

    public abstract  class SpotTrader {
        protected SpotTrader(){}
        public abstract Boolean buy(Coin coin, double price, double quantity) throws NoSuchSymbolException, InsufficientAmountOfUsdtException;
        public abstract Boolean sell(Coin coin, double price, double quantity) throws NoSuchSymbolException, InsufficientAmountOfCoinException;
        public abstract Map<String,String> getOrder ();
        public abstract Boolean cancelOrder ();
        }

    public abstract  class Wallet {

        public abstract double getAmountOfCoin(Coin coin) ;
        public abstract double getCoinBalance(Coin coin) ;
        public abstract Map<Coin, Double> getAllAssets();
        public abstract double getBalance();
    }

    public static double getCurrentPrice (Coin coin) throws NoSuchSymbolException {
        boolean isCoinUsdToken = Arrays.stream(USD_TOKENS
                        .values())
                .map(USD_TOKENS::toString)
                .anyMatch(x -> {
                    try {
                        return x.equalsIgnoreCase(coin.getSymbol());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        if (isCoinUsdToken) {
            return 1;}
        final  String request = "https://api.binance.com/api/v3/ticker/price?symbol="+coin.getUsdtPair();
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request))
                .GET().build();
        Map<String,String> responseParams =new HashMap<>();
        try {
            HttpResponse<String> httpClient = HttpClient.newBuilder().build().send(httpRequest, HttpResponse.BodyHandlers.ofString());
            String response = httpClient.body()
                    .replaceAll("\"","")
                    .replaceAll("}","")
                    .replaceAll("\\{","");
            StringTokenizer tokenizer = new StringTokenizer(response,",");

            while (tokenizer.hasMoreElements()){
                String[] keyvalue = tokenizer.nextToken().split(":");
                responseParams.put(keyvalue[0],keyvalue[1]);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Ошибка при запросе цены на Binance. Вероятно пропало соединение с сервером");
            e.printStackTrace();
        }
        if(responseParams.containsKey("msg")&&responseParams.get("msg").equals("Invalid symbol.")) throw new NoSuchSymbolException(coin.getUsdtPair());
        return Double.parseDouble(responseParams.get("price"));
    }
}
