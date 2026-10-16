package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.*;

/**
 * Hypothetical confirmed opposing pressure exit on the completed primary 15m bar.
 * Never substitutes the protective stop; the paper exit fills at NEXT bar's
 * adverse open and is only scheduled after a full price-action + volume proof.
 * No indicator or data from the unfinished daily higher timeframe is used.
 */
final class ReversalExitDetector {
    private ReversalExitDetector() {}

    static boolean confirmed(List<MarketCandle> closed,
            IndependentSetupParameters params,boolean shortPosition) {
        Objects.requireNonNull(closed);
        Objects.requireNonNull(params);
        check(closed);
        int n=closed.size();
        int lookback=params.volumeLookbackBars();
        if(n<lookback+2)return false;
        var candle=closed.getLast();
        var previous=closed.get(n-2);
        BigDecimal bodyFraction=ratio(body(candle),span(candle));
        if(bodyFraction.compareTo(params.bullishStrongBodyMinRatio())<0)
            return false;
        // Two simultaneous price-action requirements: opposing directional
        // candle and a CLOSE through the previous completed bar's extreme.
        boolean reversalPrice=shortPosition
                ?candle.close().compareTo(candle.open())>0
                    &&candle.close().compareTo(previous.high())>0
                :candle.close().compareTo(candle.open())<0
                    &&candle.close().compareTo(previous.low())<0;
        if(!reversalPrice)return false;
        BigDecimal currentAggression=shortPosition
                ?candle.takerBuyQuoteVolume()
                :candle.quoteVolume().subtract(candle.takerBuyQuoteVolume());
        BigDecimal previousAggression=shortPosition
                ?previous.takerBuyQuoteVolume()
                :previous.quoteVolume().subtract(previous.takerBuyQuoteVolume());
        if(previousAggression.signum()<=0
                ||currentAggression.compareTo(previousAggression.multiply(
                        params.buyerVolumeGrowthMinRatio()))<=0)
            return false;
        // Total volume must also exceed the previous 20-bar average;
        // the current confirmation candle is EXCLUDED from that average.
        BigDecimal preceding=meanVolume(closed,n-1-lookback,n-1);
        return preceding.signum()>0 && candle.quoteVolume().compareTo(preceding)>0;
    }
}
