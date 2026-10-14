package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PrimaryTimeframeQualityServiceTest {
    private static final long CUTOFF = 1791845999999L;
    private static final long FROM = CUTOFF - 300L * 3_600_000L + 1L;
    private static final BigDecimal PRICE = new BigDecimal("147.70");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    static MarketCandle candle(String tf, long open, boolean trading, boolean flat) {
        var interval = CandleInterval.parse(tf);
        BigDecimal high = flat ? PRICE : PRICE.add(BigDecimal.ONE);
        return new MarketCandle("SOLUSDT", interval, open,
                open + interval.millis() - 1, PRICE, high, PRICE, PRICE,
                trading ? BigDecimal.ONE : ZERO,
                trading ? PRICE : ZERO, trading ? 1 : 0,
                ZERO, ZERO, true);
    }

    static List<MarketCandle> tape(String tf, int count) {
        var interval = CandleInterval.parse(tf);
        var candles = new ArrayList<MarketCandle>(count);
        for (int i=0; i<count; i++) {
            candles.add(candle(tf, FROM + i*interval.millis(), false, true));
        }
        return candles;
    }

    @Test
    void countsInactiveFlatAndActiveZeroRangeWithoutTreatingAsMissing() {
        var bars = List.of(
                candle("15m", FROM, false, true),
                candle("15m", FROM+900_000, false, true),
                candle("15m", FROM+1_800_000, true, true),
                candle("15m", FROM+2_700_000, false, false));
        var q = PrimaryTimeframeQualityService.measure("15m", bars);
        assertEquals(4, q.observedBars());
        assertEquals(3, q.zeroTradeBars());
        assertEquals(3, q.noActivityBars());
        assertEquals(3, q.zeroRangeBars());
        assertEquals(1, q.zeroRangeWithTradesBars());
        assertEquals(2, q.longestNoActivityRunBars());
        assertEquals(30, q.longestNoActivityMinutes());
        assertEquals(0, q.noActivityPct().compareTo(new BigDecimal("75.000000")));
    }

    @Test
    void rollupDetectsPriceAndTradeCountDisagreementsWithoutFalsifyingData() {
        var small = List.of(
                candle("5m", FROM, true, true),
                candle("5m", FROM+300_000, true, true),
                candle("5m", FROM+600_000, true, true));
        var large = List.of(candle("15m", FROM, true, true));
        var check = PrimaryTimeframeQualityService.compareRollups(small, large, "5m", "15m", 3);
        assertEquals(1, check.expectedRollups());
        assertEquals(0, check.ohlcMismatchRollups());
        assertEquals(1, check.volumeMismatchRollups());
    }

    @Test
    void distinguishesDecisionFunnelGatesFromPositionManagement() {
        var blocked = trace("FLAT", "5m", 899999L, "15m", 899999L,
                DecisionResult.Reason.VOLUME_NOT_CONFIRMED, RiskPreviewResult.Action.NO_ACTION);
        assertEquals("VOLUME_NOT_CONFIRMED", PrimaryTimeframeQualityService.stage(blocked));
        var holding = trace("SHORT", "5m", 899999L, "15m", 899999L,
                DecisionResult.Reason.POSITION_HOLD, RiskPreviewResult.Action.NO_ACTION);
        assertEquals("POSITION_ACTIVE", PrimaryTimeframeQualityService.stage(holding));
    }

    static BacktestResult.DecisionTrace trace(String side, String primary, long close,
            String higher, long higherClose, DecisionResult.Reason reason,
            RiskPreviewResult.Action action) {
        return new BacktestResult.DecisionTrace("JAVA", side, close - 299_999, close,
                DecisionResult.Entry.WAIT, DecisionResult.PositionAction.NONE,
                DecisionResult.Setup.NONE, reason, Trend.RANGE,
                List.of(new BacktestResult.TimeframeTrend(primary, Trend.RANGE, close),
                        new BacktestResult.TimeframeTrend(higher, Trend.RANGE, higherClose)),
                br.com.tradingbot.analysis.volume.VolumeSnapshot.VolumeLevel.LOW,
                br.com.tradingbot.analysis.volume.VolumeSnapshot.TakerBalance.BALANCED,
                ZERO, ZERO,
                br.com.tradingbot.analysis.volume.VolumeSnapshot.EffortResult.NEUTRAL,
                action, RiskPreviewResult.Reason.NO_ENTRY_SIGNAL);
    }

    @Test
    void fullMockAuditReconcilesThreeTimeframesAndBothDecisionHistories() {
        var market = mock(MarketDataService.class);
        var backtests = mock(BacktestService.class);
        for (var tf : List.of("5m","15m","1h")) {
            int count = tf.equals("5m") ? 3600 : tf.equals("15m") ? 1200 : 300;
            var data = new ArrayList<>(tape(tf, count));
            Collections.reverse(data);
            when(market.recentAtOrBefore("SOLUSDT", tf, count, CUTOFF))
                    .thenReturn(data);
        }
        BacktestRequest req = request(true);
        var five = mock(BacktestResult.class);
        var fifteen = mock(BacktestResult.class);
        configureResult(five, "5m", "15m", 3600);
        configureResult(fifteen, "15m", "1h", 1200);
        when(backtests.runFiltered(req, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS))
                .thenReturn(new BacktestService.FilteredReplay(five, 0, 0));
        when(backtests.runPrimary15mComparison(any(BacktestRequest.class)))
                .thenReturn(new BacktestService.FilteredReplay(fifteen, 0, 0));
        var report = new PrimaryTimeframeQualityService(market, backtests).audit(req);
        assertEquals(FROM, report.fromOpenTime());
        assertEquals(3, report.candles().size());
        assertEquals(2, report.crossTimeframes().size());
        assertEquals(16, report.trends().size());
        assertEquals(2, report.funnel().size());
        assertEquals(3600, report.candles().getFirst().noActivityBars());
        assertEquals(0, report.crossTimeframes().getFirst().ohlcMismatchRollups());
        assertEquals(3599, report.funnel().getFirst().decisions());
        assertEquals(1199, report.funnel().get(1).decisions());
        verify(backtests).runFiltered(req, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS);
        var captor = org.mockito.ArgumentCaptor.forClass(BacktestRequest.class);
        verify(backtests).runPrimary15mComparison(captor.capture());
        assertEquals(List.of("15m", "1h"), captor.getValue().intervals());
        assertEquals(Boolean.TRUE, captor.getValue().includeDecisionHistory());
    }

    private static void configureResult(BacktestResult result,
            String primary, String higher, int bars) {
        when(result.replayedCandles()).thenReturn(bars);
        when(result.intervals()).thenReturn(List.of(primary, higher));
        when(result.fromCandleOpenTime()).thenReturn(FROM);
        when(result.throughCandleCloseTime()).thenReturn(CUTOFF);
        var history = new ArrayList<BacktestResult.DecisionTrace>(bars-1);
        long step = CandleInterval.parse(primary).millis();
        long higherStep = CandleInterval.parse(higher).millis();
        for (int i=0;i<bars-1;i++) {
            long close=FROM+(i+1L)*step-1L;
            long higherClose=((close+1L)/higherStep)*higherStep-1L;
            history.add(trace("FLAT", primary, close, higher, higherClose,
                    DecisionResult.Reason.NO_SETUP,RiskPreviewResult.Action.NO_ACTION));
        }
        when(result.javaDecisionHistory()).thenReturn(history);
        var portfolio=mock(BacktestResult.PortfolioResult.class);
        when(portfolio.openPositionSide()).thenReturn("FLAT");
        when(result.javaPortfolio()).thenReturn(portfolio);
    }

    private static BacktestRequest request(boolean decisionHistory) {
        return new BacktestRequest("SOLUSDT", List.of("5m", "15m"), 3600, 200,
                CUTOFF, false, new BigDecimal("0.01"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("5"),
                BigDecimal.ONE, new BigDecimal("50"), null, false, true,
                decisionHistory);
    }

    @Test
    void refusesIncompleteDecisionHistoryAndNeverQueriesMarketData() {
        var market = mock(MarketDataService.class);
        var backtests = mock(BacktestService.class);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> new PrimaryTimeframeQualityService(market, backtests)
                        .audit(request(false))).getStatusCode().value());
        verifyNoInteractions(market, backtests);
    }
}
