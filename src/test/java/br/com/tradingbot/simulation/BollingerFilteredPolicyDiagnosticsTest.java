package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.context.MarketContextSnapshot;
import br.com.tradingbot.analysis.context.MultiTimeframeMarketContext;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot;
import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.risk.RiskPolicy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BollingerFilteredPolicyDiagnosticsTest {
    private static final long CLOSE = 1_800_000_086_399L;
    private static final String SYMBOL = "SOLUSDT";
    private static final BollingerFilteredPolicy FILTER = new BollingerFilteredPolicy();

    private static BigDecimal bd(String value) { return new BigDecimal(value); }

    private static RiskPolicy risk() {
        return new RiskPolicy(bd("1500"), bd("0.5"), bd("3"), 1,
                bd("0.05"), bd("0.02"), bd("1"), bd("1"), bd("50"));
    }

    private static BacktestRequest request() {
        return new BacktestRequest(SYMBOL, List.of("1d"), 30, 50, CLOSE,
                false, bd("0.01"), bd("0.001"), bd("0.001"),
                bd("5"), BigDecimal.ONE, bd("50"), null, false, true, false);
    }

    private static BollingerReversalConfirmation.Confirmation signal() {
        var trigger = new BollingerMeanReversion.Signal(BollingerMeanReversion.Side.LONG,
                CLOSE - CandleInterval.D1.millis(), bd("94.5"), bd("96"),
                bd("94"), bd("94.6"), bd("100"), bd("105.4"));
        return new BollingerReversalConfirmation.Confirmation(
                trigger, CLOSE,
                BollingerReversalConfirmation.Pattern.STRONG_REVERSAL_CANDLE,
                bd("94"), bd("97"));
    }

    private static MultiTimeframeMarketContext context(
            PriceActionSnapshot.Trend trend,
            PriceActionSnapshot.BreakoutState breakout,
            VolumeSnapshot.VolumeLevel level) {
        var candle = new MarketCandle(SYMBOL, CandleInterval.D1,
                CLOSE - CandleInterval.D1.millis() + 1, CLOSE,
                bd("94.5"), bd("97"), bd("94.5"), bd("95"),
                bd("1000"), bd("95000"), 100,
                bd("600"), bd("56000"), true);
        var pa = new PriceActionSnapshot(SYMBOL, "1d", CLOSE, 50, trend,
                List.of(), List.of(new PriceActionSnapshot.ConfirmedPivot(
                    PriceActionSnapshot.PivotType.LOW,
                    CLOSE - 3 * CandleInterval.D1.millis(),
                    CLOSE - CandleInterval.D1.millis(), bd("90"))),
                new PriceActionSnapshot.Breakout(breakout, null, null), false);
        var observation = new VolumeSnapshot.Observation(
                candle.openTime(), CLOSE, candle.baseVolume(), candle.quoteVolume(),
                bd("0.8"), bd("1"), bd("0.60"), level,
                VolumeSnapshot.SpreadLevel.NORMAL,
                VolumeSnapshot.TakerBalance.BUY_DOMINANT,
                VolumeSnapshot.EffortResult.NONE);
        var volume = new VolumeSnapshot(SYMBOL, "1d", CLOSE, 50, 20,
                List.of(observation));
        var levels = new MarketLevelsSnapshot(SYMBOL, "1d", CLOSE, 50,
                bd("95"), bd("1"), bd("1"), List.of(), null);
        var frame = new MarketContextSnapshot(SYMBOL, "1d", CLOSE, CLOSE, 50,
                List.of(candle), pa, volume, levels);
        return new MultiTimeframeMarketContext(SYMBOL, CLOSE, List.of(frame));
    }

    @Test
    void keepsBothRiskAssessmentsEvenWhenVolumeRejectsTheSignal() {
        var market = context(PriceActionSnapshot.Trend.RANGE,
                PriceActionSnapshot.BreakoutState.NONE, VolumeSnapshot.VolumeLevel.LOW);
        var structural = FILTER.diagnose(market, signal(), request(), risk(),
                BollingerFilteredPolicy.StopMode.STRUCTURAL);
        var extreme = FILTER.diagnose(market, signal(), request(), risk(),
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE);

        assertFalse(structural.approved());
        assertFalse(extreme.approved());
        assertEquals("LOW_OR_MISSING_VOLUME", structural.firstRejection());
        assertEquals("LOW_OR_MISSING_VOLUME", extreme.firstRejection());
        assertTrue(structural.failedChecks().contains("NET_REWARD_RISK_BELOW_1_5"));
        assertFalse(extreme.failedChecks().contains("NET_REWARD_RISK_BELOW_1_5"));
        assertEquals(0, bd("89").compareTo(structural.stopPrice()));
        assertEquals(0, bd("93.99").compareTo(extreme.stopPrice()));
        assertEquals(0, bd("100").compareTo(extreme.frozenMiddleTarget()));
        assertTrue(structural.netRewardRisk().compareTo(bd("1.5")) < 0);
        assertTrue(extreme.netRewardRisk().compareTo(bd("1.5")) > 0);
        assertEquals("LOW_OR_MISSING_VOLUME",
                FILTER.evaluate(market, signal(), request(), risk(),
                    BollingerFilteredPolicy.StopMode.EXTREME_CANDLE).rejection());
    }

    @Test
    void healthyVolumePreservesOldExecutionApprovalAndStopSelection() {
        var market = context(PriceActionSnapshot.Trend.RANGE,
                PriceActionSnapshot.BreakoutState.NONE, VolumeSnapshot.VolumeLevel.NORMAL);
        var structural = FILTER.evaluate(market, signal(), request(), risk(),
                BollingerFilteredPolicy.StopMode.STRUCTURAL);
        var extreme = FILTER.evaluate(market, signal(), request(), risk(),
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE);

        assertFalse(structural.approved());
        assertEquals("NET_REWARD_RISK_BELOW_1_5", structural.rejection());
        assertTrue(extreme.approved());
        assertNull(extreme.rejection());
        assertEquals(0, bd("93.99").compareTo(extreme.stop()));
        assertNull(structural.stop());
    }

    @Test
    void strongCountertrendRemainsTheFirstRejectionYetReportsRisk() {
        var market = context(PriceActionSnapshot.Trend.DOWN,
                PriceActionSnapshot.BreakoutState.DOWN_FOLLOW_THROUGH,
                VolumeSnapshot.VolumeLevel.NORMAL);
        var extreme = FILTER.diagnose(market, signal(), request(), risk(),
                BollingerFilteredPolicy.StopMode.EXTREME_CANDLE);
        assertEquals("STRONG_PRIMARY_TREND", extreme.firstRejection());
        assertEquals(0, bd("93.99").compareTo(extreme.stopPrice()));
        assertTrue(extreme.netRewardRisk().compareTo(bd("1.5")) > 0);
        assertFalse(extreme.approved());
    }
}
