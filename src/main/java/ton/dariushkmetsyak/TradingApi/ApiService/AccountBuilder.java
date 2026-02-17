package ton.dariushkmetsyak.TradingApi.ApiService;

import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;

import java.io.IOException;
import java.util.Map;

public interface AccountBuilder {
    public enum BINANCE_BASE_URL {
        MAINNET("https://api.binance.com"),
        TESTNET("https://testnet.binance.vision");
        private String url;
        BINANCE_BASE_URL(String url){
             this.url=url;
        }

        @Override
        public String toString() {
            return url;
        }
    }
    static Account createNewBinance(char[] api_key, char[] private_key, BINANCE_BASE_URL baseUrl) throws IOException {
        BinanceAccount binanceAccount = new BinanceAccount(api_key,private_key, baseUrl);
        binanceAccount.initWallet();
        binanceAccount.initSpotTrader();
        return binanceAccount;
    }
    static Account createNewTester (Map<Coin,Double> assets) throws NoSuchSymbolException {
        TesterAccount testerAccount = new TesterAccount(assets);
        testerAccount.initWallet();
        testerAccount.initSpotTrader();
        return testerAccount;
    }
}
