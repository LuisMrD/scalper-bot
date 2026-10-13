package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReentryComparisonServiceTest {
    private static BacktestRequest request(boolean includeLuna, boolean pullback) {
        return new BacktestRequest("SOLUSDT", List.of("5m", "15m"), 3600, 200,
                1791845999999L, includeLuna, new BigDecimal("0.01"),
                new BigDecimal("0.001"), new BigDecimal("0.001"),
                new BigDecimal("5"), BigDecimal.ONE,
                new BigDecimal("50"), null, pullback, true);
    }

    @Test
    void comparesFourIndependentReentryPoliciesWithoutTradingOrLuna() {
        var backtests = mock(BacktestService.class);
        var input = request(false, false);
        var result = mock(BacktestResult.class);
        var portfolio = mock(BacktestResult.PortfolioResult.class);
        when(result.fromCandleOpenTime()).thenReturn(1790766000000L);
        when(result.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(result.replayedCandles()).thenReturn(3600);
        when(result.javaPortfolio()).thenReturn(portfolio);
        when(portfolio.trades()).thenReturn(List.of());
        when(portfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(portfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        for (ReentryWaitPolicy policy : ReentryWaitPolicy.values()) {
            when(backtests.runFiltered(input, EntryBreakoutFilter.BLOCK_BOTH, policy))
                    .thenReturn(new BacktestService.FilteredReplay(result, 6,
                            policy == ReentryWaitPolicy.NONE ? 0 : 2));
        }
        var comparison = new ReentryComparisonService(backtests).compare(input);
        assertEquals(4, comparison.summaries().size());
        assertEquals(ReentryWaitPolicy.NONE, comparison.summaries().getFirst().policy());
        assertEquals(0, comparison.summaries().getFirst().rejectedByReentryWait());
        for (ReentryWaitPolicy policy : ReentryWaitPolicy.values()) {
            verify(backtests).runFiltered(input, EntryBreakoutFilter.BLOCK_BOTH, policy);
        }
        verifyNoMoreInteractions(backtests);
    }

    @Test
    void rejectsLunaAndNonRetestSetupsBeforeAnyReplay() {
        var backtests = mock(BacktestService.class);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> new ReentryComparisonService(backtests).compare(request(true, false)))
                .getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> new ReentryComparisonService(backtests).compare(request(false, true)))
                .getStatusCode().value());
        verifyNoInteractions(backtests);
    }
}
