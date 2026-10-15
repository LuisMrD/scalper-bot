package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ClosedRangeLocationTest {
    static MarketCandle bar(int i, boolean futureSpike) {
        long open = i * 900_000L;
        var low = new BigDecimal("100");
        var high = new BigDecimal(futureSpike ? "900" : "200");
        return new MarketCandle("SOLUSDT", CandleInterval.M15, open, open + 899_999,
                new BigDecimal("150"), high, low, new BigDecimal("150"),
                BigDecimal.ONE, BigDecimal.ONE, 1, BigDecimal.ZERO,
                BigDecimal.ZERO, true);
    }

    static List<MarketCandle> history(int number, boolean futureSpike) {
        var list = new ArrayList<MarketCandle>();
        for (int i = 0; i < number; i++)
            list.add(bar(i, futureSpike && i == number-1));
        Collections.reverse(list);
        return list;
    }

    @Test
    void upperThirdUsesClosedBarsOnlyNotFutureHighs() {
        var probe = new ClosedRangeLocation("SOLUSDT", history(40, true));
        long timeBeforeSpikeCloses = 39L * 900_000L - 1;
        var snapshot = probe.at(timeBeforeSpikeCloses, new BigDecimal("170"));
        assertTrue(snapshot.upperThird());
        assertEquals(0, snapshot.high30().compareTo(new BigDecimal("200")));
        assertEquals(0, snapshot.low30().compareTo(new BigDecimal("100")));
        assertFalse(probe.at(timeBeforeSpikeCloses, new BigDecimal("160")).upperThird());
        assertFalse(probe.at(40L * 900_000L - 1, new BigDecimal("170")).upperThird());
    }

    @Test
    void missingMiddleCandleAndStaleDataNeverProduceAnEntrySignal() {
        var gap = history(45, false);
        gap.removeIf(x -> x.openTime() == 33L * 900_000L);
        var probe = new ClosedRangeLocation("SOLUSDT", gap);
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> probe.at(45L * 900_000L - 1, new BigDecimal("180")))
                .getStatusCode().value());
        var contiguous = new ClosedRangeLocation("SOLUSDT", history(45, false));
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> contiguous.at(46L * 900_000L, new BigDecimal("180")))
                .getStatusCode().value());
    }

    static ClosedRangeLocation flatHistory(boolean hasActivity, boolean missingBar) {
        var bars = new ArrayList<MarketCandle>();
        for (int i = 0; i < 45; i++) {
            if (missingBar && i == 32) continue;
            long open = i * 900_000L;
            bars.add(new MarketCandle("SOLUSDT", CandleInterval.M15,
                    open, open + 899_999, new BigDecimal("147.7"),
                    new BigDecimal("147.7"), new BigDecimal("147.7"),
                    new BigDecimal("147.7"),
                    hasActivity ? BigDecimal.ONE : BigDecimal.ZERO,
                    hasActivity ? BigDecimal.ONE : BigDecimal.ZERO,
                    hasActivity ? 1 : 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, true));
        }
        Collections.reverse(bars);
        return new ClosedRangeLocation("SOLUSDT", bars);
    }

    @Test
    void zeroWidthWithoutAnyRecordedTradeIsNotAnL2Opportunity() {
        var flat = flatHistory(false, false);
        assertTrue(flat.atIfTraded(45L * 900_000L - 1,
                new BigDecimal("147.7")).isEmpty());
        // Existing short-range endpoint retains its strict legacy behavior.
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> flat.at(45L * 900_000L - 1, new BigDecimal("147.7")))
                .getStatusCode().value());
    }

    @Test
    void zeroWidthWithTradesOrMissingHistoryCannotBeSilentlySkipped() {
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> flatHistory(true, false).atIfTraded(45L * 900_000L - 1,
                        new BigDecimal("147.7"))).getStatusCode().value());
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> flatHistory(false, true).atIfTraded(45L * 900_000L - 1,
                        new BigDecimal("147.7"))).getStatusCode().value());
    }

    @Test
    void thirtyCompletedBarsAreMandatoryAndBoundaryIsInclusive() {
        var probe = new ClosedRangeLocation("SOLUSDT", history(35, false));
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> probe.at(29L * 900_000L - 1, new BigDecimal("170")))
                .getStatusCode().value());
        var threshold = new BigDecimal("166.6666666666666666667");
        assertTrue(probe.at(35L * 900_000L - 1, threshold).upperThird());
    }
}
