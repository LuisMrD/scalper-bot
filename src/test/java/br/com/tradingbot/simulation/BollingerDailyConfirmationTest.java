package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BollingerDailyConfirmationTest {
    private static final long DAY = 86_400_000L;
    private static final long START = 1_800_000_000_000L
            - 1_800_000_000_000L % DAY;

    private static MarketCandle candle(int i, String open, String high,
            String low, String close) {
        long at = START + i * DAY;
        return new MarketCandle("SOLUSDT", CandleInterval.D1, at, at + DAY - 1,
                new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close),
                new BigDecimal("1000"), new BigDecimal("100000"),
                100, new BigDecimal("500"), new BigDecimal("50000"), true);
    }

    private static List<MarketCandle> initial() {
        var bars = new ArrayList<MarketCandle>();
        for (int i = 0; i < 19; i++)
            bars.add(candle(i, "100", "101", "99", "100"));
        bars.add(candle(19, "83", "85", "77", "80"));
        return bars;
    }

    @Test
    void reversalHammerMayConfirmWithoutClosingInsideBandOrBreakingPreviousHigh() {
        var state = new BollingerReversalConfirmation();
        var tape = initial();
        assertNotNull(new BollingerMeanReversion().detect(tape));
        assertTrue(state.onClosedBar(tape).armed());

        // Daily bullish hammer; close 82 is still BELOW the lower band
        // and BELOW trigger candle high 85. Old pattern-only rule applies.
        tape.add(candle(20, "79", "83", "70", "82"));
        var result = state.onClosedBar(tape);
        assertNotNull(result.confirmed());
        assertEquals(BollingerReversalConfirmation.Pattern.HAMMER_OR_SHOOTING_STAR,
                result.confirmed().pattern());
        assertEquals(0, result.confirmed().extremeLow()
                .compareTo(new BigDecimal("70")));
        assertEquals(0, result.confirmed().extremeHigh()
                .compareTo(new BigDecimal("85")));
        assertTrue(tape.getLast().close().compareTo(
                new BollingerMeanReversion().bands(tape).lower()) < 0);
        assertTrue(tape.getLast().close().compareTo(tape.get(19).high()) < 0);
        assertTrue(result.confirmed().trigger().candleCloseTime()
                < result.confirmed().confirmedAtCloseTime());
    }

    @Test
    void outsideBandCloseItselfCannotEnterAndBearishFollowThroughCannotConfirmLong() {
        var state = new BollingerReversalConfirmation();
        var tape = initial();
        assertTrue(state.onClosedBar(tape).armed());
        tape.add(candle(20, "82", "84", "72", "77"));
        assertNull(state.onClosedBar(tape).confirmed());
        tape.add(candle(21, "77", "82", "70", "73"));
        assertNull(state.onClosedBar(tape).confirmed());
    }

    @Test
    void dailyBandRequiresTwentyCompleteBarsIncludingHistoricalWarmup() {
        var tape = initial();
        assertNotNull(new BollingerMeanReversion().detect(tape));
        assertNull(new BollingerMeanReversion().detect(tape.subList(1, 19)));
    }
}
