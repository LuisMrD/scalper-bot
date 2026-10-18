package br.com.tradingbot.forex;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * OTC spot FX quote bar: 15m UTC BID/ASK OHLC, not exchange traded volume.
 * Tick volume is an optional broker quote-update count, NEVER taker volume.
 */
public record ForexBar(long openTime,BigDecimal bidOpen,BigDecimal bidHigh,
        BigDecimal bidLow,BigDecimal bidClose,BigDecimal askOpen,
        BigDecimal askHigh,BigDecimal askLow,BigDecimal askClose,
        Long tickVolume) {
    static final long FIFTEEN_MINUTES=900_000L;
    public ForexBar {
        if(openTime<0 || openTime%FIFTEEN_MINUTES!=0)
            throw new IllegalArgumentException("Forex bar must open on UTC 15m boundary");
        validate(bidOpen,bidHigh,bidLow,bidClose,"bid");
        validate(askOpen,askHigh,askLow,askClose,"ask");
        if(askOpen.compareTo(bidOpen)<0 || askClose.compareTo(bidClose)<0)
            throw new IllegalArgumentException("Crossed bid/ask at open/close");
        if(tickVolume!=null && tickVolume<0)
            throw new IllegalArgumentException("tick_volume cannot be negative");
    }
    private static void validate(BigDecimal o,BigDecimal h,BigDecimal l,
            BigDecimal c,String side) {
        if(o==null||h==null||l==null||c==null
                ||l.signum()<=0 ||h.compareTo(l)<0
                ||o.compareTo(l)<0 ||o.compareTo(h)>0
                ||c.compareTo(l)<0 ||c.compareTo(h)>0)
            throw new IllegalArgumentException("Invalid "+side+" OHLC");
    }
    long closeTime(){return openTime+FIFTEEN_MINUTES-1;}
    int weekday(){return Instant.ofEpochMilli(openTime).atZone(ZoneOffset.UTC)
            .getDayOfWeek().getValue();}
}
