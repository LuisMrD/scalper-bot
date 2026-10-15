package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CrossAssetMacroServiceTest {
    static final long ASOF = 1791845999999L;
    static final long FROM = ASOF - 300L * 3_600_000L + 1;

    private static List<MarketCandle> candles(String symbol) {
        var bars = new ArrayList<MarketCandle>();
        long lastOpen = ASOF - 899_999;
        for (int i = 0; i < 1350; i++) {
            long open = lastOpen - (1349L - i) * 900_000L;
            BigDecimal price = BigDecimal.valueOf(100 + i);
            bars.add(new MarketCandle(symbol, CandleInterval.M15, open, open + 899_999,
                    price, price.add(BigDecimal.ONE), price.subtract(BigDecimal.ONE), price,
                    BigDecimal.ONE, price, 1L, new BigDecimal("0.5"),
                    new BigDecimal("10"), true));
        }
        Collections.reverse(bars);
        return bars;
    }

    @Test
    void usesSameClosed15mCandleForBothAssetsAndNeverLooksAhead() {
        var market = mock(MarketDataService.class);
        when(market.recentAtOrBefore("BTCUSDT", "15m", 1350, ASOF))
                .thenReturn(candles("BTCUSDT"));
        when(market.recentAtOrBefore("ETHUSDT", "15m", 1350, ASOF))
                .thenReturn(candles("ETHUSDT"));
        long first = ASOF - 600_000;
        long second = ASOF - 300_000;
        var report = new CrossAssetMacroService(market).analyze(
                new CrossAssetMacroService.Request(
                        "SOLUSDT", FROM, ASOF, List.of(second, first)));
        assertEquals(2, report.observations().size());
        assertEquals(first, report.observations().getFirst().signalCloseTime());
        for (var item : report.observations()) {
            assertTrue(item.btc().lastClosed15mTime() <= item.signalCloseTime());
            assertEquals(item.btc().lastClosed15mTime(), item.eth().lastClosed15mTime());
            assertTrue(item.btc().return1hPct().signum() > 0);
            assertTrue(item.eth().return4hPct().signum() > 0);
        }
        verify(market).recentAtOrBefore("BTCUSDT", "15m", 1350, ASOF);
        verify(market).recentAtOrBefore("ETHUSDT", "15m", 1350, ASOF);
        verifyNoMoreInteractions(market);
    }

    @Test
    void invalidSignalAndPeriodNeverTouchMarket() {
        var market = mock(MarketDataService.class);
        var analyzer = new CrossAssetMacroService(market);
        var dup = new CrossAssetMacroService.Request(
                "SOLUSDT", FROM, ASOF, List.of(ASOF - 300_000, ASOF - 300_000));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> analyzer.analyze(dup)).getStatusCode().value());
        var prior = new CrossAssetMacroService.Request(
                "SOLUSDT", FROM, ASOF, List.of(FROM - 1));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> analyzer.analyze(prior)).getStatusCode().value());
        verifyNoInteractions(market);
    }
}
