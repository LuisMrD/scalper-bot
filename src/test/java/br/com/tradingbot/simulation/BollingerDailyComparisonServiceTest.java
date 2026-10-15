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
    void comparesIndependentDailyArmsWithExactlyThirtyCompleteCandles() {
        var backtests = mock(BacktestService.class);
        var req = request(30, false);
        var result = mock(BacktestResult.class);
        var portfolio = mock(BacktestResult.PortfolioResult.class);
        var replay = new BacktestService.FilteredReplay(result, 0, 0);
        var study = new BacktestService.BollingerReplay(replay,
                0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of());
        when(result.javaPortfolio()).thenReturn(portfolio);
        when(result.replayedCandles()).thenReturn(30);
        when(result.intervals()).thenReturn(List.of("1d"));
        when(result.fromCandleOpenTime()).thenReturn(AS_OF - 30 * 86_400_000L + 1);
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
        assertTrue(report.signalDiagnostics().isEmpty());
        assertEquals("CONTROL", report.summaries().getFirst().arm());
        assertEquals("FILTERED_BOLLINGER_EXTREME", report.summaries().getLast().arm());
        assertEquals(30, report.completeDailyCandles());
        assertEquals(0, report.minimumNetRewardRisk().compareTo(new BigDecimal("1.5")));
        verify(backtests).runFiltered(req, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.NONE);
        verify(backtests).runBollingerStudyDaily(req, false,
                BollingerFilteredPolicy.StopMode.STRUCTURAL);
        verify(backtests).runBollingerStudyDaily(req, true,
                BollingerFilteredPolicy.StopMode.STRUCTURAL);
        verify(backtests).runBollingerStudyDaily(req, true,
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE);
        verifyNoMoreInteractions(backtests);
    }

    @Test
    void serializesPairedStopDiagnosticsAsStructuredJson() throws Exception {
        var structural = new BollingerFilteredPolicy.Diagnostic(
                BollingerFilteredPolicy.StopMode.STRUCTURAL, false,
                "NET_REWARD_RISK_BELOW_1_5",
                List.of("NET_REWARD_RISK_BELOW_1_5"),
                "RANGE", "NONE", false,
                "NORMAL", "BUY_DOMINANT", "NONE", AS_OF,
                new BigDecimal("95"), new BigDecimal("100"),
                new BigDecimal("89"), new BigDecimal("5"),
                new BigDecimal("6"), new BigDecimal("4.86"),
                new BigDecimal("6.13"), new BigDecimal("0.79"),
                new BigDecimal("2.2"), new BigDecimal("94"), new BigDecimal("97"));
        var extreme = new BollingerFilteredPolicy.Diagnostic(
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE, true,
                null, List.of(), "RANGE", "NONE", false,
                "NORMAL", "BUY_DOMINANT", "NONE", AS_OF,
                new BigDecimal("95"), new BigDecimal("100"),
                new BigDecimal("93.99"), new BigDecimal("5"),
                new BigDecimal("1.01"), new BigDecimal("4.86"),
                new BigDecimal("1.14"), new BigDecimal("4.26"),
                new BigDecimal("13"), new BigDecimal("94"), new BigDecimal("97"));
        var trace = new BollingerDailyComparisonService.SignalDiagnostic(
                "FILTERED_BOLLINGER_EXTREME", AS_OF - 86_400_000L,
                AS_OF, "LONG", "STRONG_REVERSAL_CANDLE", false,
                structural, extreme);
        var json = new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(trace);
        assertTrue(json.contains("\"structural\""));
        assertTrue(json.contains("\"extreme\""));
        assertTrue(json.contains("\"firstRejection\":\"NET_REWARD_RISK_BELOW_1_5\""));
        assertTrue(json.contains("\"netRewardRisk\":4.26"));
        assertTrue(json.contains("\"failedChecks\""));
    }

    @Test
    void rejectsInsufficientOrPaidDailyReplayBeforeAnyMarketRead() {
        var backtests = mock(BacktestService.class);
        var service = new BollingerDailyComparisonService(backtests);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(11, false))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(10, true))).getStatusCode().value());
        verifyNoInteractions(backtests);
    }
}
