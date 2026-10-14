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

class CrossAssetMacroMomentumTest {
    static MarketCandle candle(String symbol, long openTime, int index) {
        BigDecimal price = BigDecimal.valueOf(100L + index);
        return new MarketCandle(symbol, CandleInterval.M15, openTime,
                openTime + 900_000L - 1L,
                price, price.add(BigDecimal.ONE), price.subtract(BigDecimal.ONE),
                price, BigDecimal.ONE, price, 4L,
                new BigDecimal("0.5"), new BigDecimal("50"), true);
    }

    static List<MarketCandle> descending(String symbol) {
        var bars = new ArrayList<MarketCandle>();
        for (int i = 0; i < 150; i++)
            bars.add(candle(symbol, (long) i * 900_000L, i));
        Collections.reverse(bars);
        return bars;
    }

    @Test
    void rollingReturnsNeverUseNextUnclosed15mCandle() {
        var candles = descending("BTCUSDT");
        var macro = new CrossAssetMacroMomentum("BTCUSDT", candles);
        long close140 = 141L * 900_000L - 1L;
        var at = macro.at(close140);
        assertEquals(close140, at.lastClosed15mTime());
        assertEquals(0, at.return1hPct().compareTo(
                new BigDecimal("4").multiply(new BigDecimal("100"))
                        .divide(new BigDecimal("236"), java.math.MathContext.DECIMAL64)));
        assertEquals(0, at.return4hPct().compareTo(
                new BigDecimal("16").multiply(new BigDecimal("100"))
                        .divide(new BigDecimal("224"), java.math.MathContext.DECIMAL64)));
        assertEquals(0, at.return24hPct().compareTo(
                new BigDecimal("96").multiply(new BigDecimal("100"))
                        .divide(new BigDecimal("144"), java.math.MathContext.DECIMAL64)));
        var before = macro.at(close140 - 1L);
        assertEquals(close140 - 900_000L, before.lastClosed15mTime());
    }

    @Test
    void missingBarsAndStaleMacroRejectInsteadOfFilling() {
        var bars = descending("ETHUSDT");
        bars.removeIf(x -> x.openTime() == 131L * 900_000L);
        var macro = new CrossAssetMacroMomentum("ETHUSDT", bars);
        long close140 = 141L * 900_000L - 1L;
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> macro.at(close140)).getStatusCode().value());
        var intact = new CrossAssetMacroMomentum("ETHUSDT", descending("ETHUSDT"));
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> intact.at(151L * 900_000L)).getStatusCode().value());
    }

    @Test
    void incompleteLookbackIsNotSilentlyAccepted() {
        var macro = new CrossAssetMacroMomentum("BTCUSDT", descending("BTCUSDT"));
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> macro.at(96L * 900_000L - 1L)).getStatusCode().value());
    }
}
