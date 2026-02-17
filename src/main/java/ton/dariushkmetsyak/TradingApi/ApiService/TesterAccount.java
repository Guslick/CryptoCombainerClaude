package ton.dariushkmetsyak.TradingApi.ApiService;

import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfCoinException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfUsdtException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Util.Prices;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class TesterAccount extends Account {
    private final Map<Coin, Double> assets;


    TesterAccount(Map<Coin, Double> assets) throws NoSuchSymbolException {
        this.assets=assets;
//              for (Map.Entry<Coin,Double> coin :assets.entrySet()) {
//                  if (!coin.getKey().getName().equals("Tether"))
//                      Account.getCurrentPrice(coin.getKey());
//              }
    }


    @Override
    protected void initSpotTrader() {
        super.spotTrader = new TesterSpotTrader();
    }

    @Override
    protected void initWallet() {
        super.wallet= new TesterWallet();
    }

       class TesterSpotTrader extends SpotTrader {
           final private Coin USDT;
           private TesterSpotTrader() {
               try {
                   USDT = Coin.createCoin("Tether");
               }
               catch (Exception e) {
                   throw new RuntimeException(e);
               }
           }
        private Map<String,String> orderParams  = new HashMap<>();
        @Override
        public Double buy(Coin coin, double price, double quantity) throws InsufficientAmountOfUsdtException, NoSuchSymbolException {
                int buyAttemts = 10; // число попыток покупки, после которых метод завершится неудачей и вернет false
                double costInUsdt = price*quantity;
                if (!assets.containsKey(USDT)||assets.containsKey(USDT) & assets.get(USDT)<costInUsdt) throw new InsufficientAmountOfUsdtException();
                if (assets.containsKey(USDT) & assets.get(USDT)>=costInUsdt){
                    orderParams.put("side","BUY");
                    orderParams.put("orderId", "test");
                    orderParams.put("price", String.valueOf(price));
                    orderParams.put("status", "NEW");
                    orderParams.put("origQty",String.valueOf(quantity));
                    while (buyAttemts>0){
                        buyAttemts--;
                        if (orderParams.isEmpty()){
                            System.out.println("ORDER IS CANCELLED");
                            return null;
                        }
                        double currentPrice = getCurrentPrice(coin);
                        ImageAndMessageSender.sendTelegramMessage(String.format("SIDE\\BUY    | Order price: %-10.5g | Current price: %-10.5g | \n", Prices.round(price) , Prices.round(currentPrice)));
                        System.out.printf("SIDE\\BUY    | Order price: %-10.5g | Current price: %-10.5g | \n", Prices.round(price) , Prices.round(currentPrice));
                        if (currentPrice <= price) {
                            System.out.println("BUYING " + quantity + " " + coin);
                            assets.compute(USDT, (key,value) -> value-costInUsdt);
                            assets.computeIfPresent(coin, (key,value)->value+quantity);
                            assets.computeIfAbsent(coin, (value)->quantity);
                            System.out.println("You paid: " + costInUsdt + " USDT\n" );
                            return price;
                        }
                        try {
                            TimeUnit.SECONDS.sleep(15);
                        } catch (InterruptedException e) {
                            ImageAndMessageSender.sendTelegramMessage(e.getMessage());
                        }
                    }
                }
                    System.out.println("Exceeded limit of attempts to buy");
                    return null;

            }



        @Override
        public Double sell(Coin coin, double price, double quantity) throws NoSuchSymbolException, InsufficientAmountOfCoinException {

                if (!assets.containsKey(coin) || assets.get(coin)<quantity) throw new InsufficientAmountOfCoinException(coin.getName());
                if (assets.containsKey(coin) && assets.get(coin)>=quantity){
                    orderParams.put("side","SELL");
                    orderParams.put("orderId", "test");
                    orderParams.put("price", String.valueOf(price));
                    orderParams.put("status", "NEW");
                    orderParams.put("origQty",String.valueOf(quantity));
                    while (true){
                        if (orderParams.isEmpty()){
                            System.out.println("ORDER IS CANCELLED");
                            return null;
                        }
                        double currentPrice = getCurrentPrice(coin);
                        ImageAndMessageSender.sendTelegramMessage(String.format("SIDE\\SELL    | Order price: %-10.5g | Current price: %-10.5g | \n", price , currentPrice));
                        System.out.printf("SIDE\\SELL    | Order price: %-10.5g | Current price: %-10.5g | \n", price , currentPrice);
                        if (currentPrice >= price) {
                            double incomeInUsdt = price*quantity;
                            System.out.println("SELLING " + quantity + " " + coin);
                            assets.computeIfPresent(USDT, (key,value) -> value+incomeInUsdt);
                            assets.computeIfAbsent(USDT, (value) -> incomeInUsdt);
                            assets.compute(coin, (key,value)->value-quantity);
                            System.out.println("You got: " + incomeInUsdt + " USDT");
                            return price;
                        }
                        try {
                            price=price/100*99.9;
                            TimeUnit.SECONDS.sleep(15);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                else return null;

        }

        @Override
        public Map<String, String> getOrder() {
            return orderParams;
        }

        @Override
        public Boolean cancelOrder() {
            if (!orderParams.isEmpty()) {
                orderParams.clear();
                return true;
            }
            return false;
        }

    }

    class TesterWallet extends Wallet {
        TesterWallet() {}
        @Override
        public double getAmountOfCoin(Coin coin)  {
            if (assets.containsKey(coin)) {
                return assets.get(coin);
            }
            else
                return 0;
        }

        @Override
        public double getCoinBalance(Coin coin) {
            if (assets.containsKey(coin)) {
                try {
                    if (coin.getName().equals("Tether")) return assets.get(coin);
                    else return getCurrentPrice(coin) * assets.get(coin);
                } catch (NoSuchSymbolException e) {
                    throw new RuntimeException(e);
                }
            }    else
                return 0;
        }

        @Override
        public Map<Coin, Double> getAllAssets() {
          //  return new HashMap<>(assets);
            return assets;
        }

        @Override
        public double getBalance()  {
            double result = 0;
            for (Map.Entry<Coin,Double> entry :assets.entrySet()){
                double price = 0;
                try {
                    if (entry.getKey().getName().equals("Tether")) {
                        price = 1;
                    } else {
                        price = getCurrentPrice(entry.getKey());
                    }
                } catch (NoSuchSymbolException e) {
                    throw new RuntimeException(e);
                }
                result+= entry.getValue()*price;
            }
            return result;
        }
    }
}

