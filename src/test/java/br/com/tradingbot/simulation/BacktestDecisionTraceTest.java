package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BacktestDecisionTraceTest {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static BacktestRequest request(int steps, boolean luna) {
        return new BacktestRequest("BTCUSDT", List.of("1m", "5m", "15m"),
                steps, 200, null, luna, ONE, ONE, ONE, ONE);
    }

    @Test
    void accepts18000CandlesButRejects18001AndRetainsTenCandleLunaLimit() {
        assertEquals(18000, request(18000, false).steps());
        assertThrows(IllegalArgumentException.class, () -> request(18001, false));
        assertThrows(IllegalArgumentException.class, () -> request(11, true));
        assertEquals(10, request(10, true).steps());
    }

    @Test
    void decisionAuditAndFrameTrendsAreImmutableAndDoNotClaimExecution() {
        var trends = new ArrayList<>(List.of(
                new BacktestResult.TimeframeTrend("1m", PriceActionSnapshot.Trend.UP, 60000L),
                new BacktestResult.TimeframeTrend("5m", PriceActionSnapshot.Trend.RANGE, 59999L)));
        var trace = new BacktestResult.DecisionTrace("JAVA", "FLAT", 1L, 60000L,
                DecisionResult.Entry.WAIT, DecisionResult.PositionAction.NONE,
                DecisionResult.Setup.NONE, DecisionResult.Reason.HIGHER_TIMEFRAME_CONFLICT,
                PriceActionSnapshot.Trend.UP, trends,
                br.com.tradingbot.analysis.volume.VolumeSnapshot.VolumeLevel.NORMAL,
                br.com.tradingbot.analysis.volume.VolumeSnapshot.TakerBalance.BALANCED,
                new BigDecimal("1.2"), new BigDecimal("0.8"),
                br.com.tradingbot.analysis.volume.VolumeSnapshot.EffortResult.NONE,
                RiskPreviewResult.Action.NO_ACTION, RiskPreviewResult.Reason.NO_ENTRY_SIGNAL);
        trends.clear();
        assertEquals(2, trace.timeframeTrends().size());
        assertEquals(DecisionResult.Reason.HIGHER_TIMEFRAME_CONFLICT, trace.reason());
        assertEquals(RiskPreviewResult.Action.NO_ACTION, trace.riskAction());
        assertThrows(UnsupportedOperationException.class, () -> trace.timeframeTrends().clear());
    }
}
