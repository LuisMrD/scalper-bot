package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.tradingbot.analysis.brooks.PriceActionConfiguration;
import br.com.tradingbot.analysis.volume.VolumeConfiguration;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import br.com.tradingbot.risk.RiskPolicy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FirstOppositionProfitProtectionTest {
    private FirstOppositionComparisonService service() {
        return new FirstOppositionComparisonService(
                mock(BacktestService.class), mock(MarketDataService.class),
                new PriceActionConfiguration(20, 2, 2, 10, 20, 0.6, 0.25, 0.5),
                new VolumeConfiguration(20, 20, 1.8, 0.6, 1.5, 0.7, 0.6, 0.55, 0.75));
    }

    private RiskPolicy policy(String feePct, String slippagePct) {
        return new RiskPolicy(new BigDecimal("1500"), new BigDecimal("0.5"),
                new BigDecimal("3"), 2, new BigDecimal(feePct),
                new BigDecimal(slippagePct), BigDecimal.ONE, new BigDecimal("0.5"));
    }

    private BacktestResult.Trade trade() {
        var trade = mock(BacktestResult.Trade.class);
        when(trade.side()).thenReturn("LONG");
        when(trade.entryPrice()).thenReturn(new BigDecimal("100"));
        when(trade.quantity()).thenReturn(BigDecimal.ONE);
        when(trade.initialRiskUsdt()).thenReturn(BigDecimal.ONE);
        return trade;
    }

    private static MarketCandle candle(int i, String open, String high,
            String low, String close) {
        long start = 60000L * i;
        return new MarketCandle("SOLUSDT", CandleInterval.parse("1m"),
                start, start + 59999, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), new BigDecimal("10"),
                new BigDecimal("1000"), 50, new BigDecimal("4"),
                new BigDecimal("400"), true);
    }

    private List<MarketCandle> tape() {
        return List.of(
                candle(0, "100.0", "100.8", "99.8", "100.6"),
                candle(1, "100.6", "100.8", "100.2", "100.4"),
                candle(2, "100.4", "100.6", "100.1", "100.3"),
                candle(3, "100.3", "100.4", "99.9", "100.1"));
    }

    @Test
    void exitOnlyAfterPriorCloseReachesHalfR() {
        var result = service().scanProtectedSignal(trade(), tape(), 0, 3,
                FirstOppositionComparisonService.Rule.FIRST_OPPOSITE_CANDLE,
                new BigDecimal("0.5"), policy("0", "0"));
        assertEquals(0, result.qualifiedIndex());
        assertEquals(1, result.signalIndex());
    }

    @Test
    void oneRShouldNotTriggerWhenHighestNetCloseWasOnlyPointSixR() {
        var result = service().scanProtectedSignal(trade(), tape(), 0, 3,
                FirstOppositionComparisonService.Rule.FIRST_OPPOSITE_CANDLE,
                BigDecimal.ONE, policy("0", "0"));
        assertEquals(-1, result.qualifiedIndex());
        assertEquals(-1, result.signalIndex());
    }

    @Test
    void sameBarOppositionCannotUseThresholdReachedAtThatBar() {
        var candles = List.of(
                candle(0, "101.0", "101.1", "100.5", "100.6"),
                candle(1, "100.6", "100.7", "100.1", "100.2"),
                candle(2, "100.2", "100.3", "99.9", "100.0"));
        var result = service().scanProtectedSignal(trade(), candles, 0, 2,
                FirstOppositionComparisonService.Rule.FIRST_OPPOSITE_CANDLE,
                new BigDecimal("0.5"), policy("0", "0"));
        assertEquals(0, result.qualifiedIndex());
        assertEquals(1, result.signalIndex());
    }

    @Test
    void noSignalOutsideOriginalExitBoundary() {
        var result = service().scanProtectedSignal(trade(), tape(), 0, 1,
                FirstOppositionComparisonService.Rule.FIRST_OPPOSITE_CANDLE,
                new BigDecimal("0.5"), policy("0", "0"));
        assertEquals(0, result.qualifiedIndex());
        assertEquals(-1, result.signalIndex());
    }

    @Test
    void commissionAndSlippageCountBeforeThresholdIsReached() {
        var result = service().scanProtectedSignal(trade(), tape(), 0, 3,
                FirstOppositionComparisonService.Rule.FIRST_OPPOSITE_CANDLE,
                new BigDecimal("0.5"), policy("0.1", "0.1"));
        assertEquals(-1, result.qualifiedIndex());
        assertEquals(-1, result.signalIndex());
    }
}
