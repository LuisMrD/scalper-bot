package br.com.tradingbot.analysis.volume;

import br.com.tradingbot.analysis.volume.VolumeSnapshot.EffortResult;
import br.com.tradingbot.analysis.volume.VolumeSnapshot.TakerBalance;
import br.com.tradingbot.analysis.volume.VolumeSnapshot.VolumeLevel;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VolumeAnalyzerTest {
    private static final VolumeParameters P = new VolumeParameters(20, 20, 1.8, .6, 1.5, .7, .6, .55, .75);
    private final VolumeAnalyzer analyzer = new VolumeAnalyzer(P);

    @Test void highEffortSmallSpreadDoesNotAssertReversal() {
        var bars = history();
        bars.add(candle(20, 100, 102, 99, 101, 300, 200, true));
        var s = analyzer.analyze(bars);
        var last = s.recentObservations().getLast();
        assertEquals(new BigDecimal("3"), last.relativeVolume());
        assertEquals(VolumeLevel.HIGH, last.volumeLevel());
        assertEquals(EffortResult.HIGH_EFFORT_SMALL_SPREAD, last.effortResult());
        assertEquals(TakerBalance.BUY_DOMINANT, last.takerBalance());
    }

    @Test void bullishWideSpreadHighVolumeIsDescriptive() {
        var bars = history();
        bars.add(candle(20, 100, 130, 90, 128, 200, 135, true));
        assertEquals(EffortResult.BULLISH_WIDE_SPREAD_HIGH_VOLUME,
                analyzer.analyze(bars).recentObservations().getLast().effortResult());
    }

    @Test void bearishWideSpreadHighVolumeIsDescriptive() {
        var bars = history();
        bars.add(candle(20, 100, 110, 60, 63, 200, 40, true));
        var last = analyzer.analyze(bars).recentObservations().getLast();
        assertEquals(EffortResult.BEARISH_WIDE_SPREAD_HIGH_VOLUME, last.effortResult());
        assertEquals(TakerBalance.SELL_DOMINANT, last.takerBalance());
    }

    @Test void upperRejectionIsNotReportedAsAbsorption() {
        var bars = history();
        bars.add(candle(20, 100, 150, 95, 98, 200, 95, true));
        assertEquals(EffortResult.HIGH_VOLUME_UPPER_REJECTION,
                analyzer.analyze(bars).recentObservations().getLast().effortResult());
    }

    @Test void lowVolumeIsSeparatedFromNeutral() {
        var bars = history();
        bars.add(candle(20, 100, 110, 90, 105, 50, 25, true));
        assertEquals(VolumeLevel.LOW, analyzer.analyze(bars).recentObservations().getLast().volumeLevel());
    }

    @Test void zeroReferenceVolumeAndZeroCurrentVolumeAreUnknownNotInfinity() {
        var bars = new ArrayList<MarketCandle>();
        for (int i = 0; i < 20; i++) bars.add(candle(i, 100, 110, 90, 105, 0, 0, true));
        bars.add(candle(20, 100, 110, 90, 105, 0, 0, true));
        var last = analyzer.analyze(bars).recentObservations().getLast();
        assertNull(last.relativeVolume());
        assertNull(last.takerBuyShare());
        assertEquals(VolumeLevel.UNAVAILABLE, last.volumeLevel());
        assertEquals(TakerBalance.UNAVAILABLE, last.takerBalance());
    }

    @Test void zeroReferenceSpreadDoesNotFabricateRatio() {
        var bars = new ArrayList<MarketCandle>();
        for (int i = 0; i < 20; i++) bars.add(candle(i, 100, 100, 100, 100, 100, 50, true));
        bars.add(candle(20, 100, 110, 90, 105, 200, 100, true));
        assertNull(analyzer.analyze(bars).recentObservations().getLast().relativeSpread());
    }

    @Test void ignoresFutureBarsWhenComputingPreviousObservation() {
        var bars = history();
        bars.add(candle(20, 100, 102, 99, 101, 300, 200, true));
        var original = analyzer.analyze(bars).recentObservations().getLast();
        bars.add(candle(21, 100, 110, 90, 105, 1000000, 500000, true));
        var appended = analyzer.analyze(bars).recentObservations().get(0);
        assertEquals(original, appended);
    }

    @Test void rejectsTooLittleHistory() {
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(history()));
    }

    @Test void rejectsTemporalGapAndUnclosedCandles() {
        var bars = history();
        bars.add(candle(22, 100, 110, 90, 105, 200, 100, true));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(bars));
        bars.removeLast();
        bars.add(candle(20, 100, 110, 90, 105, 200, 100, false));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(bars));
    }

    @Test void protectsSnapshotAgainstExternalModification() {
        var bars = history();
        bars.add(candle(20, 100, 110, 90, 105, 200, 100, true));
        var snapshot = analyzer.analyze(bars);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.recentObservations().clear());
    }

    private static ArrayList<MarketCandle> history() {
        var list = new ArrayList<MarketCandle>();
        for (int i = 0; i < 20; i++) list.add(candle(i, 100, 110, 90, 105, 100, 50, true));
        return list;
    }

    private static MarketCandle candle(int n, int open, int high, int low, int close,
                                       long quoteVol, long takerQuote, boolean closed) {
        return new MarketCandle("BTCUSDT", CandleInterval.M1,
                n * 60_000L, n * 60_000L + 59_999L,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high), BigDecimal.valueOf(low), BigDecimal.valueOf(close),
                BigDecimal.valueOf(quoteVol), BigDecimal.valueOf(quoteVol), 10L,
                BigDecimal.valueOf(takerQuote), BigDecimal.valueOf(takerQuote), closed);
    }
}
