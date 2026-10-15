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
    void reentersOnlyAfterDailyCloseInsideAndBreakOfPreviousHigh() {
        var state = new BollingerReversalConfirmation(true);
        var tape = initial();
        assertNotNull(new BollingerMeanReversion().detect(tape));
        assertTrue(state.onClosedBar(tape).armed());

        // Still below the lower band and without a microstructural close-break.
        tape.add(candle(20, "80", "84", "76", "83"));
        assertNull(state.onClosedBar(tape).confirmed());
        tape.add(candle(21, "84", "94", "80", "93"));
        var result = state.onClosedBar(tape);
        assertNotNull(result.confirmed());
        assertEquals(BollingerMeanReversion.Side.LONG,
                result.confirmed().trigger().side());
        assertEquals(0, result.confirmed().extremeLow()
                .compareTo(new BigDecimal("76")));
        assertEquals(0, result.confirmed().extremeHigh()
                .compareTo(new BigDecimal("94")));
        assertTrue(result.confirmed().trigger().candleCloseTime()
                < result.confirmed().confirmedAtCloseTime());
    }

    @Test
    void laterInsideCloseStillRequiresPreviousCandleBreak() {
        var state = new BollingerReversalConfirmation(true);
        var tape = initial();
        state.onClosedBar(tape);
        // This candle closes back inside Bollinger but below a previous high 96:
        // the first attempted reaction has no structural breakout confirmation.
        tape.add(candle(20, "82", "96", "79", "84"));
        assertNull(state.onClosedBar(tape).confirmed());
        tape.add(candle(21, "84", "95", "81", "94"));
        assertNull(state.onClosedBar(tape).confirmed(),
                "Although above the lower band, 94 is below previous candle high 96");
    }

    @Test
    void dailyBandRequiresTwentyCompleteBarsIncludingHistoricalWarmup() {
        var tape = initial();
        assertNotNull(new BollingerMeanReversion().detect(tape));
        assertNull(new BollingerMeanReversion().detect(tape.subList(1, 19)));
    }
}
