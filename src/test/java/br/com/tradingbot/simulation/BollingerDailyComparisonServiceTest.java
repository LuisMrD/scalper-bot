package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BollingerDailyComparisonServiceTest {
    private static final long AS_OF = 1791763199999L;

    private static BacktestRequest request(int steps, boolean luna) {
        return new BacktestRequest("BTCUSDT", List.of("1d"),
                steps, 50, AS_OF, luna, new BigDecimal("0.1"),
                new BigDecimal("0.001"), new BigDecimal("0.001"),
                new BigDecimal("5"), BigDecimal.ONE,
                new BigDecimal("50"), null, false, true, false);
    }

    @Test
    void comparesIndependentDailyArmsWithExactlyTwelveCompleteCandles() {
        var backtests = mock(BacktestService.class);
        var req = request(12, false);
        var result = mock(BacktestResult.class);
        var portfolio = mock(BacktestResult.PortfolioResult.class);
        var replay = new BacktestService.FilteredReplay(result, 0, 0);
        var study = new BacktestService.BollingerReplay(replay,
                0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of());
        when(result.javaPortfolio()).thenReturn(portfolio);
        when(result.replayedCandles()).thenReturn(12);
        when(result.intervals()).thenReturn(List.of("1d"));
        when(result.fromCandleOpenTime()).thenReturn(AS_OF - 12 * 86_400_000L + 1);
        when(result.throughCandleCloseTime()).thenReturn(AS_OF);
        when(portfolio.trades()).thenReturn(List.of());
        when(portfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(portfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(backtests.runFiltered(req, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.NONE)).thenReturn(replay);
        when(backtests.runBollingerStudyDaily(eq(req), anyBoolean(),
                any(BollingerFilteredPolicy.StopMode.class))).thenReturn(study);

        var report = new BollingerDailyComparisonService(backtests).compare(req);
        assertEquals(4, report.summaries().size());
        assertEquals("CONTROL", report.summaries().getFirst().arm());
        assertEquals("FILTERED_BOLLINGER_EXTREME", report.summaries().getLast().arm());
        assertEquals(12, report.completeDailyCandles());
        assertEquals(0, report.minimumNetRewardRisk().compareTo(new BigDecimal("1.5")));
        verify(backtests).runBollingerStudyDaily(req, false,
                BollingerFilteredPolicy.StopMode.STRUCTURAL);
        verify(backtests).runBollingerStudyDaily(req, true,
                BollingerFilteredPolicy.StopMode.STRUCTURAL);
        verify(backtests).runBollingerStudyDaily(req, true,
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE);
        verifyNoMoreInteractions(backtests);
    }

    @Test
    void rejectsInsufficientOrPaidDailyReplayBeforeAnyMarketRead() {
        var backtests = mock(BacktestService.class);
        var service = new BollingerDailyComparisonService(backtests);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(11, false))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(12, true))).getStatusCode().value());
        verifyNoInteractions(backtests);
    }
}
