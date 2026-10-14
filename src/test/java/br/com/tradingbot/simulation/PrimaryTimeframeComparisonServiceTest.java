package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PrimaryTimeframeComparisonServiceTest {
    static BacktestRequest request(boolean luna) {
        return new BacktestRequest("SOLUSDT", List.of("5m", "15m"),
                luna ? 3 : 3600, 200, 1791845999999L, luna,
                new BigDecimal("0.01"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("5"),
                new BigDecimal("1"), new BigDecimal("50"), null, false, true, false);
    }

    @Test
    void fourClosed15mBarsBlockExactlySixtyMinutes() {
        var wait = new ReentryWaitState(ReentryWaitPolicy.WAIT_4_BARS_15M);
        wait.onClosedCandle(Trend.RANGE);
        wait.onEntryRejected(Trend.RANGE);
        for (int i = 0; i < 4; i++) {
            wait.onClosedCandle(Trend.UP);
            assertTrue(wait.blocksEntry(), "Must wait until 4 closed 15m bars");
        }
        wait.onClosedCandle(Trend.UP);
        assertFalse(wait.blocksEntry());
        assertEquals(4, ReentryWaitPolicy.WAIT_4_BARS_15M.fixedBars());
        assertEquals(12, ReentryWaitPolicy.WAIT_12_BARS.fixedBars());
    }

    @Test
    void comparesSame300HoursAndKeepsOriginalFiveMinuteReplayIndependent() {
        var backtests = mock(BacktestService.class);
        var request = request(false);
        var five = mock(BacktestResult.class);
        var fifteen = mock(BacktestResult.class);
        var fivePortfolio = mock(BacktestResult.PortfolioResult.class);
        var fifteenPortfolio = mock(BacktestResult.PortfolioResult.class);
        long from = 1791845999999L - 300L * 3_600_000L + 1L;
        when(five.intervals()).thenReturn(List.of("5m", "15m"));
        when(fifteen.intervals()).thenReturn(List.of("15m", "1h"));
        when(five.replayedCandles()).thenReturn(3600);
        when(fifteen.replayedCandles()).thenReturn(1200);
        when(five.fromCandleOpenTime()).thenReturn(from);
        when(fifteen.fromCandleOpenTime()).thenReturn(from);
        when(five.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(fifteen.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(five.javaPortfolio()).thenReturn(fivePortfolio);
        when(fifteen.javaPortfolio()).thenReturn(fifteenPortfolio);
        when(fivePortfolio.trades()).thenReturn(List.of());
        when(fifteenPortfolio.trades()).thenReturn(List.of());
        when(fivePortfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(fifteenPortfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(fivePortfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(fifteenPortfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(backtests.runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS))
                .thenReturn(new BacktestService.FilteredReplay(five, 0, 0));
        when(backtests.runPrimary15mComparison(any(BacktestRequest.class)))
                .thenReturn(new BacktestService.FilteredReplay(fifteen, 0, 0));
        var report = new PrimaryTimeframeComparisonService(backtests).compare(request);
        assertEquals(2, report.summaries().size());
        assertEquals(from, report.fromOpenTime());
        assertEquals(1791845999999L, report.asOfCloseTime());
        assertEquals(3600, report.summaries().getFirst().replayedCandles());
        assertEquals(1200, report.summaries().get(1).replayedCandles());
        assertEquals(4, report.summaries().get(1).cooldownBars());
        var fifteenRequest = org.mockito.ArgumentCaptor.forClass(BacktestRequest.class);
        verify(backtests).runPrimary15mComparison(fifteenRequest.capture());
        var requested = fifteenRequest.getValue();
        assertEquals(List.of("15m", "1h"), requested.intervals());
        assertEquals(1200, requested.steps());
        assertEquals(request.asOf(), requested.asOf());
        assertEquals(request.priceTick(), requested.priceTick());
        assertEquals(request.maxCostToInitialRiskPctOverride(),
                requested.maxCostToInitialRiskPctOverride());
        verify(backtests).runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS);
        verifyNoMoreInteractions(backtests);
    }

    @Test
    void rejectsLunaAndDoesNotRunAnyReplay() {
        var backtests = mock(BacktestService.class);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> new PrimaryTimeframeComparisonService(backtests)
                        .compare(request(true))).getStatusCode().value());
        verifyNoInteractions(backtests);
    }

    @Test
    void failsClosedWhenTimeframePeriodsDiffer() {
        var backtests = mock(BacktestService.class);
        var request = request(false);
        var five = mock(BacktestResult.class);
        when(five.intervals()).thenReturn(List.of("5m","15m"));
        when(five.replayedCandles()).thenReturn(3600);
        when(five.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(five.fromCandleOpenTime()).thenReturn(1791845999999L - 300L*3_600_000L + 1L);
        when(five.javaPortfolio()).thenReturn(mock(BacktestResult.PortfolioResult.class));
        when(backtests.runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS)).thenReturn(
                    new BacktestService.FilteredReplay(five, 0, 0));
        var fifteen = mock(BacktestResult.class);
        when(fifteen.intervals()).thenReturn(List.of("15m","1h"));
        when(fifteen.replayedCandles()).thenReturn(1200);
        long wrongFrom = 1791845999999L - 300L*3_600_000L + 1L + 900_000L;
        when(fifteen.fromCandleOpenTime()).thenReturn(wrongFrom);
        when(backtests.runPrimary15mComparison(any(BacktestRequest.class)))
                .thenReturn(new BacktestService.FilteredReplay(fifteen, 0, 0));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> new PrimaryTimeframeComparisonService(backtests)
                        .compare(request)).getStatusCode().value());
    }
}
