package ton.dariushkmetsyak;

import org.junit.jupiter.api.*;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfCoinException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfUsdtException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Disabled
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TesterAccountTest {
    Account testerAccount;
    Map<Coin,Double> assets;
    Coin TETHER,BITCOIN,ETHEREUM,MINA;
    final double bitcoinDiscount = 1000;
    final double ethereumDiscount = 100;
    double startTetherQuantity;
    @BeforeAll
    void init() {
        assets = new HashMap<>();
        try {
            TETHER= Coin.createCoin("Tether");
            BITCOIN = Coin.createCoin("Bitcoin");
            ETHEREUM = Coin.createCoin("Ethereum");
            MINA = Coin.createCoin("Mina Protocol");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void initAssets() throws NoSuchSymbolException {
        assets.clear();
        startTetherQuantity = (Account.getCurrentPrice(BITCOIN)+Account.getCurrentPrice(ETHEREUM))*2;
        assets.put(TETHER,startTetherQuantity);
        assets.put(BITCOIN,3D);
        assets.put(ETHEREUM,3D);
        testerAccount = AccountBuilder.createNewTester(assets);
    }
    @Test
    void ifWalletIsInitialized (){
        assertNotNull(Account.Wallet.class);
    }

    @Test
    void isBuyingWorkingCorrectTest() throws Exception {
        double BitcoinPrice =  Account.getCurrentPrice(BITCOIN);
        double EthereumPrice = Account.getCurrentPrice(ETHEREUM);
        System.out.println("BALANCES BEFORE BUYING: " + testerAccount.wallet().getAllAssets());
        assertTrue(testerAccount.trader().buy(BITCOIN, BitcoinPrice+bitcoinDiscount,1),"Waiting for TRUE response when buying BITCOIN");
        assertTrue(testerAccount.trader().buy(ETHEREUM, EthereumPrice+ethereumDiscount,1),"Waiting for TRUE response when buying ETHEREUM");
        assertThrows(InsufficientAmountOfUsdtException.class, ()->testerAccount.trader().buy(ETHEREUM, EthereumPrice,100),"Waiting for THROWING InsufficientAmountOfUsdtException when buying ETHEREUM INCORRECT");
        assertEquals(startTetherQuantity-BitcoinPrice-EthereumPrice-(bitcoinDiscount+ethereumDiscount),
                testerAccount.wallet().getAmountOfCoin(TETHER));
        assertEquals(4,testerAccount.wallet().getAmountOfCoin(BITCOIN));
        assertEquals(4,testerAccount.wallet().getAmountOfCoin(ETHEREUM));
        System.out.println("BALANCES AFTER BUYING: " + testerAccount.wallet().getAllAssets());
    }
    @Test
    void isSellingWorkingCorrectTest() throws Exception {
        double BitcoinPrice =  Account.getCurrentPrice(BITCOIN);
        double EthereumPrice = Account.getCurrentPrice(ETHEREUM);
        double MinaPrice = Account.getCurrentPrice(MINA);
        System.out.println("BALANCES BEFORE SELLING: " + testerAccount.wallet().getAllAssets());
        assertTrue(testerAccount.trader().sell(BITCOIN, BitcoinPrice-bitcoinDiscount,1),"Waiting for TRUE response when selling BITCOIN");
        assertTrue(testerAccount.trader().sell(ETHEREUM, EthereumPrice-ethereumDiscount,1),"Waiting for TRUE response when selling ETHEREUM");
        assertThrows(InsufficientAmountOfCoinException.class, ()->testerAccount.trader().sell(ETHEREUM, EthereumPrice,100),"Waiting for THROWING InsufficientAmountOfCoinException when selling ETHEREUM INCORRECT");
        assertThrows(InsufficientAmountOfCoinException.class, ()->testerAccount.trader().sell(MINA, MinaPrice,100),"Waiting for THROWING InsufficientAmountOfCoinException when selling WITHOUT MINA ON WALLET");
        assertEquals(testerAccount.wallet().getAmountOfCoin(TETHER),startTetherQuantity+BitcoinPrice+EthereumPrice-(bitcoinDiscount+ethereumDiscount));
        assertEquals(testerAccount.wallet().getAmountOfCoin(BITCOIN),2);
        assertEquals(testerAccount.wallet().getAmountOfCoin(ETHEREUM),2);
        System.out.println("BALANCES AFTER SELLING: " + testerAccount.wallet().getAllAssets());
    }

    @Test
    void getCurrentPriceTest() throws NoSuchSymbolException {
        assertThrows(NoSuchSymbolException.class, ()->Account.getCurrentPrice(Coin.createCoin("0xanon")));
        assertInstanceOf(Double.class, Account.getCurrentPrice(BITCOIN));
    }
    @Test
    void getAndCancelOrderTest() throws NoSuchSymbolException, InterruptedException {
        double price = Account.getCurrentPrice(BITCOIN)/2;
        Map<String,String> orderParams = new HashMap<>();
        orderParams.put("side","BUY");
        orderParams.put("orderId", "test");
        orderParams.put("price", String.valueOf(price));
        orderParams.put("status", "NEW");
        orderParams.put("origQty",String.valueOf(1.0));
        assertFalse(testerAccount.trader().cancelOrder());
        assertTrue(testerAccount.trader().getOrder().isEmpty());
        new Thread(()-> {
            try {
                testerAccount.trader().buy(BITCOIN,price,1);
            } catch (NoSuchSymbolException | InsufficientAmountOfUsdtException e) {
                throw new RuntimeException(e);
            }
        }).start();
        TimeUnit.SECONDS.sleep(5);
        assertIterableEquals(orderParams.entrySet(),testerAccount.trader().getOrder().entrySet());
        assertTrue(testerAccount.trader().cancelOrder());
        assertFalse(testerAccount.trader().cancelOrder());
        assertTrue(testerAccount.trader().getOrder().isEmpty());
    }

    @Test
    void getAmountOfCoinTest() throws NoSuchSymbolException {
        assertEquals(startTetherQuantity, testerAccount.wallet().getAmountOfCoin(TETHER));
        assertEquals(3, testerAccount.wallet().getAmountOfCoin(BITCOIN));
        assertEquals(3,testerAccount.wallet().getAmountOfCoin(ETHEREUM));
        assertEquals(0,testerAccount.wallet().getAmountOfCoin(MINA));
    }
    @Test
    void getCoinBalanceTest() throws NoSuchSymbolException {
        assertEquals(startTetherQuantity, testerAccount.wallet().getCoinBalance(TETHER));
        assertEquals(3*Account.getCurrentPrice(BITCOIN),testerAccount.wallet().getCoinBalance(BITCOIN),200);
        assertEquals(3*Account.getCurrentPrice(ETHEREUM), testerAccount.wallet().getCoinBalance(ETHEREUM),50);
        assertEquals(0, testerAccount.wallet().getCoinBalance(MINA));

    }
    @Test
    void getBalanceTest(){
        double actual = testerAccount.wallet().getBalance();
        double expected = testerAccount.wallet().getAllAssets().entrySet().stream()
                .mapToDouble(x -> {
                    try {
                        if (x.getKey().getUsdtPair().equals("USDTUSDT")) {             //если USDT то не длаем запрос цены, а передаем количество
                            return x.getValue();
                        }
                        else {
                            return Account.getCurrentPrice(x.getKey()) * x.getValue();
                        }
                    } catch (NoSuchSymbolException e) {
                        return x.getValue();
                    }
                }).sum();
        assertEquals(expected,actual,400);
    }
    @Test
    void getAllAssetsTest(){
        assertEquals(assets, testerAccount.wallet().getAllAssets());
    }

}
