package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class FailedBreakoutL2DetectorTest {
    static MarketCandle bar(String tf, long open, String opening, String high,
            String low, String close) {
        var iv = CandleInterval.parse(tf);
        return new MarketCandle("SOLUSDT", iv, open, open + iv.millis() - 1L,
                new BigDecimal(opening), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), BigDecimal.ONE, BigDecimal.ONE, 1L,
                new BigDecimal("0.3"), new BigDecimal("0.3"), true);
    }

    static FailedBreakoutL2Detector detector() {
        var fifteen = new ArrayList<MarketCandle>();
        for (long i = 0; i < 150; i++) {
            fifteen.add(bar("15m", i * 900_000L, "150", "200", "100", "150"));
        }
        Collections.reverse(fifteen);
        return new FailedBreakoutL2Detector(new ClosedRangeLocation("SOLUSDT", fifteen));
    }

    static List<MarketCandle> validL2() {
        long start = 120L * 900_000L;
        return new ArrayList<>(List.of(
                bar("5m", start, "198", "202", "197", "199"), // failed resistance wick
                bar("5m", start+300_000L, "199", "200", "195", "196"), // first bear
                bar("5m", start+600_000L, "196", "199", "196", "198"), // rebound
                bar("5m", start+900_000L, "198", "199", "196", "197"), // pause
                bar("5m", start+1_200_000L, "197", "198", "193", "194") // second leg
        ));
    }

    @Test
    void onlyCompletedSecondBearLegSignalsWithOldResistance() {
        var d = detector();
        var tape = validL2();
        assertNull(d.detect(tape.subList(0, 4)), "No future second attempt allowed");
        var s = d.detect(tape);
        assertNotNull(s);
        assertEquals(tape.get(0).closeTime(), s.failureCloseTime());
        assertEquals(tape.get(1).closeTime(), s.firstAttemptCloseTime());
        assertEquals(tape.get(2).closeTime(), s.reboundCloseTime());
        assertEquals(tape.get(4).closeTime(), s.secondAttemptCloseTime());
        assertEquals(0, s.priorResistance().compareTo(new BigDecimal("200")));
        assertEquals(0, s.failedBreakHigh().compareTo(new BigDecimal("202")));
    }

    @Test
    void noFalseBreakOrNoReboundOrNoSecondBreakCannotCreateL2() {
        var d = detector();
        var noFailure = validL2();
        var f = noFailure.getFirst();
        noFailure.set(0, bar("5m", f.openTime(), "198", "200", "197", "199"));
        assertNull(d.detect(noFailure));

        var noBounce = validL2();
        var bounce = noBounce.get(2);
        noBounce.set(2, bar("5m", bounce.openTime(), "198", "199", "195", "196"));
        assertNull(d.detect(noBounce));

        var noSecond = validL2();
        var end = noSecond.getLast();
        noSecond.set(4, bar("5m", end.openTime(), "197", "198", "195", "196"));
        assertNull(d.detect(noSecond));
    }

    @Test
    void rejectsMissingPrimaryBarAndSecondSpikeAboveFailedBreakHigh() {
        var d = detector();
        var gap = validL2();
        gap.remove(3);
        assertNull(d.detect(gap));
        var invalid = validL2();
        var bounce = invalid.get(2);
        invalid.set(2, bar("5m", bounce.openTime(), "196", "203", "196", "198"));
        assertNull(d.detect(invalid));
    }

    @Test
    void neverAllowsSecondLegNearBottomThird() {
        var bars = validL2();
        // Separate wick/leg example with a failed breakout followed by an
        // unusually deep downward second leg; outside valid range location.
        var end = bars.getLast();
        bars.set(4, bar("5m", end.openTime(), "197", "198", "120", "120"));
        assertNull(detector().detect(bars));
    }
}
