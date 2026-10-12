package br.com.tradingbot.integration.binance;

import static org.junit.jupiter.api.Assertions.*;
import br.com.tradingbot.market.model.CandleInterval;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BinanceKlineDecoderTest {
    private final BinanceKlineDecoder decoder = new BinanceKlineDecoder(new JsonMapper());
    private final String row="[60000,\"100\",\"120\",\"90\",\"115\",\"10\",119999,\"1100\",45,\"6\",\"670\",\"0\"]";

    @Test void restClosedBarsAndPrecision() {
        var parsed=decoder.rest("BTCUSDT",CandleInterval.M1,"["+row+"]",120000);
        assertEquals(1,parsed.size());
        assertTrue(parsed.getFirst().closed());
        assertEquals(0,parsed.getFirst().close().compareTo(new java.math.BigDecimal("115")));
        assertEquals(45,parsed.getFirst().numberOfTrades());
        assertEquals("670",parsed.getFirst().takerBuyQuoteVolume().toPlainString());
    }
    @Test void restDoesNotPersistOpenCandle() {
        assertFalse(decoder.rest("BTCUSDT",CandleInterval.M1,"["+row+"]",119999).getFirst().closed());
    }
    @Test void websocketIgnoresOpenAndAcceptsFinal() {
        String k="{\"e\":\"kline\",\"s\":\"BTCUSDT\",\"st\":1,\"k\":{\"s\":\"BTCUSDT\",\"i\":\"1m\",\"t\":60000,\"T\":119999,\"o\":\"100\",\"h\":\"120\",\"l\":\"90\",\"c\":\"115\",\"v\":\"10\",\"q\":\"1100\",\"n\":45,\"V\":\"6\",\"Q\":\"670\",\"x\":%s}}";
        assertNull(decoder.websocket(String.format(k,"false")));
        var candle=decoder.websocket("{\"stream\":\"btcusdt@kline_1m\",\"data\":"+String.format(k,"true")+"}");
        assertNotNull(candle);
        assertTrue(candle.closed());
    }
    @Test void websocketSkipsCoinM() {
        assertNull(decoder.websocket("{\"e\":\"kline\",\"st\":2}"));
    }
}
