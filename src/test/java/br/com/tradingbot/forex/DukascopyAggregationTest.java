package br.com.tradingbot.forex;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DukascopyAggregationTest {
    private DukascopyForexDownloader.Minute minute(int index,String open,
            String high,String low,String close) {
        return new DukascopyForexDownloader.Minute(index,
                new BigDecimal(open),new BigDecimal(high),
                new BigDecimal(low),new BigDecimal(close));
    }
    @Test void sparseMinuteQuotesProduceOnlyObservedOhlcWithoutForwardFill() {
        var m0=minute(1,"1.10500","1.10520","1.10490","1.10510");
        var m2=minute(3,"1.10510","1.10530","1.10500","1.10520");
        var mLast=minute(13,"1.10520","1.10525","1.10480","1.10495");
        Map<Integer,DukascopyForexDownloader.Minute> observed=
                Map.of(1,m0,3,m2,13,mLast);
        var bar=DukascopyForexDownloader.aggregate(observed,0);
        assertNotNull(bar);
        assertEquals(3,DukascopyForexDownloader.countObserved(observed,0));
        assertEquals(0,bar.open().compareTo(new BigDecimal("1.10500")));
        assertEquals(0,bar.high().compareTo(new BigDecimal("1.10530")));
        assertEquals(0,bar.low().compareTo(new BigDecimal("1.10480")));
        assertEquals(0,bar.close().compareTo(new BigDecimal("1.10495")));
        assertNull(DukascopyForexDownloader.aggregate(observed,15));
    }
}
