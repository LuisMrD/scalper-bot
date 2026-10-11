package br.com.tradingbot.market.model;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketCandleTest {
    private static BigDecimal n(int value) { return BigDecimal.valueOf(value); }
    @Test void rejectsImpossiblePrices() {
        assertThrows(IllegalArgumentException.class,()->new MarketCandle(
                "BTCUSDT",CandleInterval.M1,60000,119999,n(100),n(90),n(95),n(98),
                n(10),n(1000),1,n(2),n(200),true));
    }
    @Test void intervalsHaveDeterministicDurations() {
        assertEquals(300000L,CandleInterval.parse("5m").millis());
        assertThrows(IllegalArgumentException.class,()->CandleInterval.parse("1M"));
    }
}
