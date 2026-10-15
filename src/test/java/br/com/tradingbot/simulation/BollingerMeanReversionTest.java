package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BollingerMeanReversionTest {
    private static BigDecimal b(String x) { return new BigDecimal(x); }

    private static List<MarketCandle> prices(int count100, int countOther,
            String other, boolean withTrades) {
        var candles=new ArrayList<MarketCandle>();
        long start=1800000000000L - 1800000000000L % 300000L;
        for(int i=0; i<count100+countOther;i++){
            BigDecimal px=i<count100 ? b("100") : b(other);
            long open=start+i*300000L;
            candles.add(new MarketCandle("SOLUSDT",CandleInterval.M5,
                    open, open+299999, px, px.add(b("20")), px.subtract(b("20")),
                    px, withTrades ? BigDecimal.ONE : BigDecimal.ZERO,
                    withTrades ? b("100") : BigDecimal.ZERO,
                    withTrades ? 1 : 0, BigDecimal.ZERO, BigDecimal.ZERO, true));
        }
        return candles;
    }

    @Test
    void aCloseStrictlyBelowLowerBandSuggestsLongAtFrozenSma20() {
        var signal=new BollingerMeanReversion().detect(prices(19,1,"80",true));
        assertNotNull(signal);
        assertEquals(BollingerMeanReversion.Side.LONG,signal.side());
        assertTrue(signal.close().compareTo(signal.lower())<0);
        assertEquals(0,signal.middle().compareTo(b("99")));
    }

    @Test
    void closeStrictlyAboveUpperBandSuggestsShort() {
        var signal=new BollingerMeanReversion().detect(prices(19,1,"120",true));
        assertNotNull(signal);
        assertEquals(BollingerMeanReversion.Side.SHORT,signal.side());
        assertTrue(signal.close().compareTo(signal.upper())>0);
        assertEquals(0,signal.middle().compareTo(b("101")));
    }

    @Test
    void touchingBandIsNotClosingOutside() {
        // 16x100 plus 4x110: SMA 102, population sigma 4, upper 110 EXACT.
        assertNull(new BollingerMeanReversion().detect(prices(16,4,"110",true)));
        // 16x110 plus 4x100: SMA 108, sigma 4, lower 100 EXACT.
        var list=prices(16,4,"100",true);
        var rewritten=new ArrayList<MarketCandle>();
        for(int i=0; i<list.size();i++){
            var c=list.get(i);
            BigDecimal px=i<16 ? b("110") : b("100");
            rewritten.add(new MarketCandle(c.symbol(),c.interval(),c.openTime(),c.closeTime(),
                    px,px.add(b("20")),px.subtract(b("20")),px,c.baseVolume(),
                    c.quoteVolume(),c.numberOfTrades(),c.takerBuyBaseVolume(),
                    c.takerBuyQuoteVolume(),true));
        }
        assertNull(new BollingerMeanReversion().detect(rewritten));
    }

    @Test
    void wickPiercesButCloseInsideDoesNotQualify() {
        var bars=prices(20,0,"100",true);
        // All closes are 100: even though the wick extends through
        // hypothetical bands, zero-variance close history is invalid.
        assertNull(new BollingerMeanReversion().detect(bars));
        assertNull(new BollingerMeanReversion().detect(prices(19,1,"80",false)));
        assertNull(new BollingerMeanReversion().detect(bars.subList(0,19)));
    }

    @Test
    void previousInactiveCandleInvalidatesBandEvenIfSignalCandleIsActive() {
        var source = new ArrayList<>(prices(19, 1, "80", true));
        var old = source.get(5);
        source.set(5, new MarketCandle(old.symbol(), old.interval(),
                old.openTime(), old.closeTime(), old.open(), old.high(),
                old.low(), old.close(), BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, BigDecimal.ZERO, true));
        assertNull(new BollingerMeanReversion().detect(source));
    }

    @Test
    void missingHistoricalBarIsNeverTreatedAsNoSignal() {
        var candles=prices(19,1,"80",true);
        var broken=new ArrayList<>(candles);
        broken.remove(2);
        broken.add(0,candles.getFirst()); // one duplicate and a missing candle
        assertThrows(IllegalArgumentException.class,
                ()->new BollingerMeanReversion().detect(broken));
    }
}
