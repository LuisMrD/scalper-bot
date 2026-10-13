package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EntryFilterComparisonServiceTest {
    private static BacktestRequest request(boolean pullback) {
        return new BacktestRequest("SOLUSDT", List.of("5m", "15m"), 3600, 200,
                1791845999999L, false, new BigDecimal("0.01"),
                new BigDecimal("0.001"), new BigDecimal("0.001"),
                new BigDecimal("5"), BigDecimal.ONE, new BigDecimal("50"),
                null, pullback, true);
    }

    @Test
    void fourIndependentReplaysAndUnfilteredBaselineAreMandatory() {
        var backtests = mock(BacktestService.class);
        var input = request(false);
        var result = mock(BacktestResult.class);
        var portfolio = mock(BacktestResult.PortfolioResult.class);
        when(result.fromCandleOpenTime()).thenReturn(1790766000000L);
        when(result.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(result.replayedCandles()).thenReturn(3600);
        when(result.javaPortfolio()).thenReturn(portfolio);
        when(portfolio.trades()).thenReturn(List.of());
        when(portfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(portfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        for (EntryBreakoutFilter filter : EntryBreakoutFilter.values()) {
            when(backtests.runFiltered(input, filter)).thenReturn(
                    new BacktestService.FilteredReplay(result,
                            filter == EntryBreakoutFilter.NONE ? 0 : 2));
        }
        var compared = new EntryFilterComparisonService(backtests).compare(input);
        assertEquals(4, compared.summaries().size());
        assertEquals(0, compared.summaries().getFirst().filteredSignals());
        assertEquals(EntryBreakoutFilter.NONE, compared.summaries().getFirst().filter());
        for (EntryBreakoutFilter filter : EntryBreakoutFilter.values()) {
            verify(backtests).runFiltered(input, filter);
        }
        verifyNoMoreInteractions(backtests);
    }

    @Test
    void refusesDifferentSetupBeforeAnyBacktestRun() {
        var backtests = mock(BacktestService.class);
        var ex = assertThrows(ResponseStatusException.class,
                () -> new EntryFilterComparisonService(backtests).compare(request(true)));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(backtests);
    }
}
