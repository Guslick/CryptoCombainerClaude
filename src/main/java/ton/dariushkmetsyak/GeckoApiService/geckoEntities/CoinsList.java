package ton.dariushkmetsyak.GeckoApiService.geckoEntities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ton.dariushkmetsyak.GeckoApiService.GeckoRequests;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class CoinsList{

    private CoinsList(){}
    static private  ArrayList<Coin> coins = new ArrayList<Coin>();;
    static private final ObjectMapper objectMapper= new ObjectMapper();;
//    static {
//                //String jsonString = GeckoRequests.getCoinsList();
//        try {
//            coins = new ArrayList<Coin>();
//            ArrayList<Coin> al = objectMapper.readValue(jsonString, new TypeReference<ArrayList<Coin>>(){});
//            coins.addAll(al);
//            objectMapper.writeValue(new File("file"),coins);
//
//
//           // coins.remove(getCoinByName("batcat"));  //РЕШИТЬ И УДАЛИТЬ !!!
//        } catch (JsonProcessingException e) {
//            throw new RuntimeException(e);
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }
    public static void loadCoinsFromCoinGecko(){
        String jsonString = GeckoRequests.getCoinsList();
        try {
            coins = new ArrayList<Coin>();
            ArrayList<Coin> al = objectMapper.readValue(jsonString, new TypeReference<ArrayList<Coin>>(){});
            coins.addAll(al);
            objectMapper.writeValue(new File("file"),coins);
           // coins.remove(getCoinByName("batcat"));  //РЕШИТЬ И УДАЛИТЬ !!!
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public static void getCoinsWithMarketData_And_SaveToJsonFile(File file) throws IOException {
        coins.clear();
        int page = 1;
        ArrayList<Coin> mappedAL;
        do {
            try {
                String jsonString = GeckoRequests.getCoinListWithMarketData(page);
                mappedAL = objectMapper.readValue(jsonString, new TypeReference<ArrayList<Coin>>() {});
                coins.addAll(mappedAL);
                System.out.println("Page - " + page + ", added " + mappedAL.size() + " coins to array, total coins: " + coins.size() );
                page++;
                TimeUnit.SECONDS.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

        } while (!mappedAL.isEmpty());
        objectMapper.writeValue(file,coins);
    }

    public static void loadCoinsWithMarketDataFormJsonFile (File file) throws IOException {
        coins.clear();
        coins.addAll(objectMapper.readValue(file, new TypeReference<ArrayList<Coin>>(){}));
    }
    static Coin getCoin (String coinId){
        try {
           return getCoinByName(coinId);
        } catch (Exception e) {
            try {
              return   getCoinBySymbol(coinId);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }
    public static Coin getCoinByName(String coin) throws Exception {
        for (Coin c: coins){

            if(c.getName().equalsIgnoreCase(coin)) {
                return c;
            }
        }
        class NoSuchCoinException extends Exception{
            NoSuchCoinException(String coin){
                super(coin);
            }
        }
        throw new NoSuchCoinException(coin);
    }
    public static Coin getCoinBySymbol(String coin) throws Exception {
        for (Coin c: coins){
            if(c.getSymbol().equalsIgnoreCase(coin)) {
                return c;
            }
        }
        class NoSuchCoinException extends Exception{
            NoSuchCoinException(String coin){
                super(coin);
            }
        }
        throw new NoSuchCoinException(coin);
    }

    public static ArrayList<Coin> getCoins(){return coins;}
}
