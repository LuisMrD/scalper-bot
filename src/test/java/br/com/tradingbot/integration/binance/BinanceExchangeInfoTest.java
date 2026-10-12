package br.com.tradingbot.integration.binance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BinanceExchangeInfoTest {
    private final BinanceExchangeInfo parser=new BinanceExchangeInfo(new JsonMapper());
    private final String info="""
        {"symbols": [
          {"symbol":"BTCUSDT","status":"TRADING","contractType":"PERPETUAL","marginAsset":"USDT","baseAsset":"BTC","quoteAsset":"USDT"},
          {"symbol":"ETHUSDT","status":"BREAK","contractType":"PERPETUAL","marginAsset":"USDT","baseAsset":"ETH","quoteAsset":"USDT"}]}
        """;
    @Test void validatesPerpetualActiveSymbols() {
        assertEquals("BTC",parser.decode(info,Set.of("BTCUSDT")).get("BTCUSDT").baseAsset());
        assertThrows(IllegalArgumentException.class,()->parser.decode(info,Set.of("BTCUSDT","ETHUSDT")));
    }
}
