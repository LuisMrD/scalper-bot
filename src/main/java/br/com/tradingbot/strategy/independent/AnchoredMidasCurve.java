package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.MC;

/**
 * Incremental, volume-weighted MIDAS-style curve, using (high+low)/2 and
 * Binance BASE traded volume. This is a deliberately explicit research
 * approximation, not a claim of reproducing every Levine MIDAS variant.
 *
 * LONG anchors to the latest CONFIRMED swing low; SHORT to the latest
 * CONFIRMED swing high. The pivot at i-right becomes known only AFTER
 * candle i has closed; prior curve values are NEVER retroactively rewritten.
 * A newly confirmed anchor rebuilds the weighted sums from its pivot bar
 * to the current closed bar, then continues incrementally without a
 * 60-candle sliding cutoff.
 */
final class AnchoredMidasCurve {
    record Point(long closeTime,long anchorOpenTime,BigDecimal value) {
        boolean available() { return anchorOpenTime>=0 && value!=null; }
    }

    private final boolean shortSide;
    private final int left,right;
    private int processed=-1;
    private int anchorIndex=-1;
    private BigDecimal weighted=BigDecimal.ZERO,volume=BigDecimal.ZERO;

    AnchoredMidasCurve(boolean shortSide,IndependentSetupParameters parameters) {
        this.shortSide=shortSide;
        left=parameters.pivotLeft();right=parameters.pivotRight();
    }

    Point observe(List<MarketCandle> bars,int i) {
        if(i!=processed+1 || i<0 || i>=bars.size())
            throw new IllegalArgumentException("MIDAS must consume closed 15m bars in sequence");
        MarketCandle current=bars.get(i);
        if(!current.closed())throw new IllegalArgumentException("MIDAS cannot consume unfinished candles");
        if(i>0 && current.openTime()!=bars.get(i-1).closeTime()+1)
            throw new IllegalArgumentException("MIDAS requires contiguous candles");
        processed=i;
        int candidate=i-right;
        if(candidate>=left && pivotConfirmed(bars,candidate)) {
            anchorIndex=candidate;
            weighted=BigDecimal.ZERO;
            volume=BigDecimal.ZERO;
            // The pivot is selected only NOW. It is legal to calculate the
            // current MIDAS value from historical candles back to that pivot,
            // but never to assign this anchor to prior live decisions.
            for(int j=candidate;j<=i;j++)add(bars.get(j));
        }else if(anchorIndex>=0) {
            add(current);
        }
        BigDecimal value=volume.signum()>0
                ?weighted.divide(volume,MC):null;
        return new Point(current.closeTime(),
                anchorIndex>=0?bars.get(anchorIndex).openTime():-1L,value);
    }

    private boolean pivotConfirmed(List<MarketCandle> tape,int candidate) {
        var bar=tape.get(candidate);
        for(int j=candidate-left;j<=candidate+right;j++) {
            if(j==candidate)continue;
            var other=tape.get(j);
            if(shortSide ? bar.high().compareTo(other.high())<=0
                    :bar.low().compareTo(other.low())>=0)
                return false;
        }
        return true;
    }
    private void add(MarketCandle bar) {
        BigDecimal v=bar.baseVolume();
        BigDecimal midpoint=bar.high().add(bar.low()).divide(BigDecimal.valueOf(2),MC);
        weighted=weighted.add(midpoint.multiply(v,MC),MC);
        volume=volume.add(v,MC);
    }
}
