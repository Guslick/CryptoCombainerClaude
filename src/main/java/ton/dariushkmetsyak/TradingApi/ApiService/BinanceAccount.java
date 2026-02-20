package ton.dariushkmetsyak.TradingApi.ApiService;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.exceptions.BinanceClientException;
import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import com.binance.connector.client.utils.signaturegenerator.Ed25519SignatureGenerator;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Util.Prices;

import java.io.IOException;
import java.sql.Time;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class BinanceAccount extends Account {
    private static final Logger log = LoggerFactory.getLogger(BinanceAccount.class);
    char[] api_key;
    char[] private_key;
    AccountBuilder.BINANCE_BASE_URL baseUrl;

    char[] getApi_key() {return api_key;}
    char[] getPrivate_key() {return private_key;}

    public BinanceAccount(char[] api_key, char[] private_key, AccountBuilder.BINANCE_BASE_URL baseUrl) {

        this.api_key = api_key;
        this.private_key = private_key;
        this.baseUrl=baseUrl;

    }

    @Override
    public void initSpotTrader() {
        super.spotTrader = new BinanceSpotTrader(this);
    }

    @Override
    public void initWallet() throws IOException {
        super.wallet = new BinanceWallet(this);
    }

     class BinanceSpotTrader extends SpotTrader {
       private SpotClient client;
       private Map<String,Object> orderParameters;

       BinanceSpotTrader(BinanceAccount binanceAccount) {
           int maxAttempts = 5;
           int attempt = 0;
           Exception lastError = null;
           
           while (attempt < maxAttempts) {
               attempt++;
               try {
                   log.info("Creating Binance client, attempt {}/{}", attempt, maxAttempts);
                   Ed25519SignatureGenerator signatureGenerator = new Ed25519SignatureGenerator(String.valueOf(binanceAccount.getPrivate_key()));
                   client = new SpotClientImpl(String.valueOf(binanceAccount.getApi_key()), signatureGenerator, baseUrl.toString());
                   log.info("Binance client created successfully");
                   return; // Успех
                   
               } catch (Exception e) {
                   lastError = e;
                   log.error("Failed to create Binance client, attempt {}/{}: {}", 
                       attempt, maxAttempts, e.getMessage());
                   
                   String message = String.format(
                       "⚠️ Попытка %d/%d создания Binance клиента\n" +
                       "Ошибка: %s",
                       attempt, maxAttempts, e.getMessage()
                   );
                   
                   try {
                       ImageAndMessageSender.sendTelegramMessage(message);
                   } catch (Exception ignored) {}
                   
                   if (attempt < maxAttempts) {
                       try {
                           TimeUnit.SECONDS.sleep(10);
                       } catch (InterruptedException ex) {
                           Thread.currentThread().interrupt();
                           throw new RuntimeException("Interrupted while creating Binance client", ex);
                       }
                   }
               }
           }
           
           // Все попытки неудачны
           String fatalMessage = String.format(
               "🛑 КРИТИЧЕСКАЯ ОШИБКА\n\n" +
               "Не удалось создать Binance клиент после %d попыток\n" +
               "Последняя ошибка: %s\n\n" +
               "Проверьте:\n" +
               "1. API ключи корректны\n" +
               "2. Файл приватного ключа доступен\n" +
               "3. Сеть доступна",
               maxAttempts, lastError != null ? lastError.getMessage() : "Unknown"
           );
           
           try {
               ImageAndMessageSender.sendTelegramMessage(fatalMessage);
           } catch (Exception ignored) {}
           
           throw new RuntimeException("Failed to create Binance client after " + maxAttempts + " attempts", lastError);
       }

       public Double sell(Coin coin, double price, double quantity) {

           Map<String, Object> parameters = new LinkedHashMap<>();
           double adjustedQuantity = Prices.round(quantity-(quantity*0.2/100));
           
           log.info("Attempting to sell {} {} at price {}", adjustedQuantity, coin.getName(), price);
           System.out.println("Quantity: "+ quantity);
           System.out.println("Precised quantity: " + adjustedQuantity);
           
           parameters.put("symbol", coin.getUsdtPair());
           parameters.put("side", "SELL");
           parameters.put("type", "LIMIT");
           parameters.put("timeInForce", "GTC");
           parameters.put("quantity", adjustedQuantity);
           parameters.put("price", price);

           try {
               String response = client.createTrade().newOrder(parameters);
               orderParameters=getOrderParameters(response);
               log.info("Sell order placed successfully");
               return price;
               
           } catch (BinanceConnectorException e) {
               String errorMsg = String.format(
                   "❌ Ошибка продажи %s\n\n" +
                   "Тип: BinanceConnectorException\n" +
                   "Сообщение: %s\n" +
                   "Цена: %s\n" +
                   "Количество: %s",
                   coin.getName(), e.getMessage(), price, adjustedQuantity
               );
               log.error("Sell order failed: {}", e.getMessage());
               System.err.println(errorMsg);
               
               try {
                   ImageAndMessageSender.sendTelegramMessage(errorMsg);
               } catch (Exception ignored) {}
               
               return null;
               
           } catch (BinanceClientException e) {
               String errorMsg = String.format(
                   "❌ Ошибка продажи %s\n\n" +
                   "Тип: BinanceClientException\n" +
                   "Код ошибки: %d\n" +
                   "HTTP статус: %d\n" +
                   "Сообщение: %s\n" +
                   "Детали: %s\n\n" +
                   "Цена: %s\n" +
                   "Количество: %s",
                   coin.getName(),
                   e.getErrorCode(),
                   e.getHttpStatusCode(),
                   e.getMessage(),
                   e.getErrMsg(),
                   price,
                   adjustedQuantity
               );
               log.error("Sell order failed: {} (code: {}, http: {})", 
                   e.getMessage(), e.getErrorCode(), e.getHttpStatusCode());
               System.err.println(errorMsg);
               
               try {
                   ImageAndMessageSender.sendTelegramMessage(errorMsg);
               } catch (Exception ignored) {}
               
               return null;
           }
           }


           public Double buy(Coin coin, double price, double quantity) {
               double adjustedQuantity = Prices.round(quantity-(quantity*0.2/100));
               
               log.info("Attempting to buy {} {} at price {}", adjustedQuantity, coin.getName(), price);
               System.out.println("Quantity: "+ quantity);
               System.out.println("Precised quantity: " + adjustedQuantity);
               
               Map<String, Object> parameters = new LinkedHashMap<>();
               parameters.put("symbol", coin.getUsdtPair());
               parameters.put("side", "BUY");
               parameters.put("type", "LIMIT");
               parameters.put("timeInForce", "GTC");
               parameters.put("quantity", adjustedQuantity);
               parameters.put("price", price);

               try {
                   String response = client.createTrade().newOrder(parameters);
                   orderParameters = getOrderParameters(response);
                   log.info("Buy order placed successfully");
                   return price;
                   
               } catch (BinanceConnectorException e) {
                   String errorMsg = String.format(
                       "❌ Ошибка покупки %s\n\n" +
                       "Тип: BinanceConnectorException\n" +
                       "Сообщение: %s\n" +
                       "Цена: %s\n" +
                       "Количество: %s",
                       coin.getName(), e.getMessage(), price, adjustedQuantity
                   );
                   log.error("Buy order failed: {}", e.getMessage());
                   System.err.println(errorMsg);
                   
                   try {
                       ImageAndMessageSender.sendTelegramMessage(errorMsg);
                   } catch (Exception ignored) {}
                   
               } catch (BinanceClientException e) {
                   String errorMsg = String.format(
                       "❌ Ошибка покупки %s\n\n" +
                       "Тип: BinanceClientException\n" +
                       "Код ошибки: %d\n" +
                       "HTTP статус: %d\n" +
                       "Сообщение: %s\n" +
                       "Детали: %s\n\n" +
                       "Цена: %s\n" +
                       "Количество: %s",
                       coin.getName(),
                       e.getErrorCode(),
                       e.getHttpStatusCode(),
                       e.getMessage(),
                       e.getErrMsg(),
                       price,
                       adjustedQuantity
                   );
                   log.error("Buy order failed: {} (code: {}, http: {})", 
                       e.getMessage(), e.getErrorCode(), e.getHttpStatusCode());
                   System.err.println(errorMsg);
                   
                   try {
                       ImageAndMessageSender.sendTelegramMessage(errorMsg);
                   } catch (Exception ignored) {}
               }
               
           return null;
           }

           public Boolean cancelOrder () {
               try {
                   String response = client.createTrade().cancelOrder(orderParameters);
                   orderParameters=null;
                   return true;
               } catch (BinanceConnectorException e) {
                   System.err.println((String) String.format("fullErrMessage: %s", e.getMessage()));
               } catch (BinanceClientException e) {
                   System.err.println((String) String.format("fullErrMessage: %s \nerrMessage: %s \nerrCode: %d \nHTTPStatusCode: %d",
                           e.getMessage(), e.getErrMsg(), e.getErrorCode(), e.getHttpStatusCode()));
               }
               return false;
           }


           public Map<String, String> getOrder (){
           Map<String, String> result = new HashMap<>();
               try {
                   String response = client.createTrade().getOrder(new HashMap<>(orderParameters));
                   response = response.replaceAll("\"","");
                   String[] respParams = response.split(",");
                   for (String param:respParams){
                       String[] s = param.split(":");
//                       System.out.println(s[0]);
//                       System.out.println(s[1]);
//                       System.out.println();
                       if (s[0].equals("symbol")||s[0].equals("orderId")||s[0].equals("price")||s[0].equals("side")||s[0].equals("status")||s[0].equals("origQty")){
                           result.put(s[0],s[1]);
                       }
                   }
                   return result;
               } catch (BinanceConnectorException e) {
                   System.err.println((String) String.format("fullErrMessage: %s", e.getMessage()));
               } catch (BinanceClientException e) {
                   System.err.println((String) String.format("fullErrMessage: %s \nerrMessage: %s \nerrCode: %d \nHTTPStatusCode: %d",
                           e.getMessage(), e.getErrMsg(), e.getErrorCode(), e.getHttpStatusCode()));
               }
               return result;
           }

       private Map<String,Object> getOrderParameters (String response){
           response = response.substring(1,response.length()-1).replace("\"","");
           String resp[] = response.split(",");
           Map<String,Object> params = new HashMap<>();
           for (String s: resp){
               if (s.contains("orderId:")) {
                   params.put("orderId", s.replace("orderId:","").trim());
               }
               if (s.contains("symbol:")) {
                   params.put("symbol", s.replace("symbol:","").trim());
               }
           }
           return params;
       }


   }

    public  class BinanceWallet extends Wallet {

        private SpotClient client;

        BinanceWallet(BinanceAccount binanceAccount) throws IOException {

                Ed25519SignatureGenerator signatureGenerator = new Ed25519SignatureGenerator(String.valueOf(binanceAccount.getPrivate_key()));
                client = new SpotClientImpl(String.valueOf(binanceAccount.getApi_key()), signatureGenerator, baseUrl.toString());

        }


        @Override
        public double getAmountOfCoin(Coin coin) {
            Map<Coin,Double> assets =  getAllAssets();
            if (assets.containsKey(coin)) return getAllAssets().get(coin);
            return 0;
        }

        @Override
        public double getCoinBalance(Coin coin) {
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
                return getAmountOfCoin(coin);}
            Map<String, Object> parameters = new LinkedHashMap<>();
            String response = client.createTrade().account(parameters);
            try (JsonParser parser = new JsonFactory().createParser(response)) {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (Optional.ofNullable(parser.getCurrentName()).isPresent() && parser.getCurrentName().equals("balances") ) {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            if (Optional.ofNullable(parser.getCurrentName()).isPresent()
                                    && parser.getCurrentName().equals("asset")
                                    && parser.getText().equalsIgnoreCase(coin.getSymbol())) {
                                while (parser.nextToken() != JsonToken.END_OBJECT) {
                                    parser.nextToken();
                                    if (Optional.ofNullable(parser.getCurrentName()).isPresent()  && parser.getCurrentName().equals("free"))
                                    {
                                        return inUSDT(parser.getText(), coin);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return 0;
        }

        @Override
        public Map<Coin, Double> getAllAssets() {
            Map<String, Object> parameters = new LinkedHashMap<>();
            Map<Coin, Double> result = new HashMap<>();
            String response=null;
            try {
                response = client.createTrade().account(parameters);
            } catch (RuntimeException e){
                e.printStackTrace();
            }

            try (JsonParser parser = new JsonFactory().createParser(response)) {
                while (parser.nextToken() != JsonToken.END_ARRAY) {

                    Coin key =null;
                    Double value = null;
                    if (Optional.ofNullable(parser.getCurrentName()).isPresent() && parser.getCurrentName().equals("asset")) {
                        parser.nextToken();
                        try {
                            key = Coin.createCoin(parser.getValueAsString());
//                            System.out.print(key);
                        } catch (Exception e){

                            System.err.println(e);
                        }
                        parser.nextToken();
                        parser.nextToken();
                        value = Double.parseDouble(parser.getText());
//                        System.out.println(value);
                        if (value!=0&&key!=null) result.put(key, value);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return result;
        }


        public double getBalance() {
            return getAllAssets().entrySet().stream()
                    .mapToDouble((entry)->{
                        try {System.out.println(entry.getKey() + " " +Account.getCurrentPrice(entry.getKey())*entry.getValue());
                            return Account.getCurrentPrice(entry.getKey())*entry.getValue();

                        } catch (NoSuchSymbolException e) {
                            log.error("e: ", e);
                            return 0;
                        }
                    })
                    .sum();
        }

        private double inUSDT(String balance, Coin coin) {
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
                return Double.parseDouble(balance);}
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", coin.getUsdtPair());
            String response = client.createMarket().tickerSymbol(parameters);
            try (JsonParser parser = new JsonFactory().createParser(response)) {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (Optional.ofNullable(parser.getCurrentName()).isPresent() && parser.getCurrentName().equals("price")) {
                        parser.nextToken();
                        return Double.parseDouble(balance) * Double.parseDouble(parser.getText());
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return 0;
        }
    }
}
