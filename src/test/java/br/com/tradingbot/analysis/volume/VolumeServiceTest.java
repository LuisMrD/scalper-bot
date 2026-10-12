package br.com.tradingbot.analysis.volume;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VolumeServiceTest {
    private static final VolumeConfiguration CONFIG =
            new VolumeConfiguration(20, 10, 1.8, .6, 1.5, .7, .6, .55, .75);

    @Test void restoresAscendingOrderOfPersistedCandles() {
        MarketDataService market = mock(MarketDataService.class);
        var reversed = new ArrayList<MarketCandle>();
        for (int i = 0; i < 21; i++) reversed.add(candle(i));
        Collections.reverse(reversed);
        when(market.recent("BTCUSDT", "1m", 21)).thenReturn(reversed);
        var result = new VolumeService(market, CONFIG).analyze("BTCUSDT", "1m", 21);
        assertEquals(20 * 60_000L + 59_999L, result.asOfCloseTime());
        assertEquals(1, result.recentObservations().size());
        verify(market).recent("BTCUSDT", "1m", 21);
    }

    @Test void doesNotAcceptLimitSmallerThanReferenceWindow() {
        MarketDataService market = mock(MarketDataService.class);
        var e = assertThrows(ResponseStatusException.class,
                () -> new VolumeService(market, CONFIG).analyze("BTCUSDT", "1m", 20));
        assertEquals(400, e.getStatusCode().value());
        verifyNoInteractions(market);
    }

    @Test void insufficientPersistedDataReturns422() {
        MarketDataService market = mock(MarketDataService.class);
        when(market.recent("BTCUSDT", "1m", 21)).thenReturn(java.util.List.of());
        var e = assertThrows(ResponseStatusException.class,
                () -> new VolumeService(market, CONFIG).analyze("BTCUSDT", "1m", 21));
        assertEquals(422, e.getStatusCode().value());
    }

    private static MarketCandle candle(int i) {
        long ms = i * 60_000L;
        return new MarketCandle("BTCUSDT", CandleInterval.M1, ms, ms + 59_999L,
                BigDecimal.valueOf(100), BigDecimal.valueOf(110), BigDecimal.valueOf(90),
                BigDecimal.valueOf(105), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                10, BigDecimal.valueOf(50), BigDecimal.valueOf(50), true);
    }
}
