package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ShortRangeComparisonServiceTest {
    private static BacktestRequest request(boolean luna, boolean pullback) {
        return new BacktestRequest("SOLUSDT", List.of("5m", "15m"),
                luna ? 3 : 3600, 200, 1791845999999L, luna,
                new BigDecimal("0.01"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("5"),
                BigDecimal.ONE, new BigDecimal("50"), null, pullback, true);
    }

    @Test
    void compareExecutesThreeIndependentPortfolioReplays() {
        var backtests = mock(BacktestService.class);
        var input = request(false, false);
        var result = mock(BacktestResult.class);
        var p = mock(BacktestResult.PortfolioResult.class);
        when(result.fromCandleOpenTime()).thenReturn(1790766000000L);
        when(result.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(result.replayedCandles()).thenReturn(3600);
        when(result.javaPortfolio()).thenReturn(p);
        when(p.trades()).thenReturn(List.of());
        when(p.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(p.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        for (ShortRangePolicy arm : ShortRangePolicy.values()) {
            when(backtests.runShortRange(input, arm))
                    .thenReturn(new BacktestService.FilteredReplay(result, 3, 4, 0,
                            0, arm.active() ? 2 : 0));
        }
        var response = new ShortRangeComparisonService(backtests).compare(input);
        assertEquals(3, response.summaries().size());
        assertEquals(ShortRangePolicy.CONTROL, response.summaries().getFirst().policy());
        assertEquals(0, response.summaries().getFirst().rangeShortBlockedSignals());
        for (ShortRangePolicy arm : ShortRangePolicy.values()) {
            verify(backtests).runShortRange(input, arm);
        }
        verifyNoMoreInteractions(backtests);
    }

    @Test
    void incorrectRiskExperimentNeverHitsHistoricalMarket() {
        var backtests = mock(BacktestService.class);
        var service = new ShortRangeComparisonService(backtests);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(true,false))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(false,true))).getStatusCode().value());
        verifyNoInteractions(backtests);
    }
}
