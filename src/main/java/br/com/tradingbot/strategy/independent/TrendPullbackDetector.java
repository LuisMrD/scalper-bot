package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.*;

/**
 * Long-only research: rising confirmed swing highs/lows -> contracting sell
 * pullback near EMA9/21 -> closed bullish response with increasing taker buy
 * volume. Touching EMA by itself NEVER emits a trade.
 */
public final class TrendPullbackDetector {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final IndependentSetupParameters params;
    public TrendPullbackDetector(IndependentSetupParameters params) {
        this.params = Objects.requireNonNull(params);
    }
    public IndependentSetupSignal evaluate(List<MarketCandle> tape,BigDecimal priceTick) {
        Objects.requireNonNull(priceTick);
        if(priceTick.signum()<=0)throw new IllegalArgumentException("priceTick required");
        check(tape);
        int n=tape.size();
        var passed=new ArrayList<String>();
        var failed=new ArrayList<String>();
        var signalAt=tape.getLast().closeTime();
        if(n<Math.max(35,2*params.pullbackMaxBars()+params.pivotLeft()
                +params.pivotRight()+5))
            return IndependentSetupSignal.rejected(
                    IndependentSetupSignal.Setup.TREND_PULLBACK,signalAt,
                    "INSUFFICIENT_HISTORY",passed,List.of("INSUFFICIENT_HISTORY"));
        MarketCandle last=tape.getLast();
        int k=params.pullbackMinBars();
        // The final k candles BEFORE confirmation must form a retreat.
        int start=n-1-k;
        var structure=risingStructure(tape,start,params);
        if(!structure.established()) failed.add("NO_CONFIRMED_RISING_STRUCTURE");
        else passed.add("CONFIRMED_RISING_STRUCTURE");
        BigDecimal ema9=ema(tape,n-1,9),ema21=ema(tape,n-1,21);
        if(ema9==null||ema21==null || ema9.compareTo(ema21)<0)
            failed.add("EMA_ALIGNMENT");
        else passed.add("EMA_ALIGNMENT");
        BigDecimal pullbackLow=null, sellVolume=BigDecimal.ZERO;
        BigDecimal bodySum=BigDecimal.ZERO;
        boolean bearishRetreat=true;
        for(int i=start;i<n-1;i++) {
            var c=tape.get(i);
            bearishRetreat &= c.close().compareTo(c.open())<0;
            pullbackLow=pullbackLow==null?c.low():pullbackLow.min(c.low());
            sellVolume=sellVolume.add(c.quoteVolume());
            bodySum=bodySum.add(body(c));
        }
        if(!bearishRetreat) failed.add("PULLBACK_NOT_ORDERLY_BEARISH");
        BigDecimal precedingVolume=meanVolume(tape,start-k,start);
        BigDecimal pullbackMean=sellVolume.divide(BigDecimal.valueOf(k),MC);
        BigDecimal precedingBody=BigDecimal.ZERO;
        for(int i=start-k;i<start;i++)precedingBody=precedingBody.add(body(tape.get(i)));
        precedingBody=precedingBody.divide(BigDecimal.valueOf(k),MC);
        BigDecimal pullbackBody=bodySum.divide(BigDecimal.valueOf(k),MC);
        if(precedingVolume.signum()<=0 || pullbackMean.compareTo(precedingVolume
                .multiply(params.pullbackVolumeContractionMaxRatio()))>0)
            failed.add("SELL_VOLUME_NOT_CONTRACTING");
        else passed.add("SELL_VOLUME_CONTRACTING");
        if(precedingBody.signum()<=0 || pullbackBody.compareTo(precedingBody
                .multiply(params.pullbackBodyContractionMaxRatio()))>0)
            failed.add("PULLBACK_BODIES_NOT_SMALLER");
        else passed.add("PULLBACK_BODIES_SMALLER");
        BigDecimal tolerance=ema21.multiply(params.emaTouchTolerancePct())
                .divide(HUNDRED,MC);
        BigDecimal emaZoneLow=ema21.subtract(tolerance);
        BigDecimal emaZoneHigh=ema9.max(ema21).add(tolerance);
        if(pullbackLow==null || pullbackLow.compareTo(emaZoneLow)<0
                || pullbackLow.compareTo(emaZoneHigh)>0)
            failed.add("PULLBACK_NOT_AT_EMA9_21_ZONE");
        else passed.add("EMA9_21_ZONE_TESTED");
        if(structure.lastLow()!=null && (pullbackLow.compareTo(structure.lastLow())<=0
                || last.low().compareTo(structure.lastLow())<=0))
            failed.add("STRUCTURAL_LOW_BROKEN");
        MarketCandle previous=tape.get(n-2);
        if(last.close().compareTo(last.open())<=0
                && last.quoteVolume().compareTo(precedingVolume
                    .multiply(params.exceptionalSellingVolumeMinRatio()))>=0)
            failed.add("ABNORMAL_SELLING_VOLUME");
        if(last.close().compareTo(last.open())<0
                && ratio(body(last),span(last))
                    .compareTo(params.bullishStrongBodyMinRatio())>=0)
            failed.add("DOMINANT_BEARISH_CANDLE");
        boolean strong=bullishStrong(last,params.bullishStrongBodyMinRatio());
        boolean hammer=bullishHammer(last,params.rejectionWickToBodyMinRatio());
        boolean breaks=last.close().compareTo(previous.high())>0
                && last.close().compareTo(last.open())>0;
        if(!(strong||hammer||breaks))
            failed.add("NO_CLOSED_BULLISH_CONFIRMATION");
        else passed.add(strong?"BULLISH_STRENGTH":hammer?"LOWER_WICK_REJECTION":"PREVIOUS_HIGH_CLOSE_BREAK");
        if(last.takerBuyQuoteVolume().compareTo(previous.takerBuyQuoteVolume()
                .multiply(params.buyerVolumeGrowthMinRatio()))<=0)
            failed.add("BUYER_VOLUME_NOT_INCREASING");
        else passed.add("BUYER_VOLUME_INCREASING");
        BigDecimal stop=pullbackLow==null?null:pullbackLow.subtract(
                priceTick.multiply(params.stopBufferTicks()));
        if(stop==null||stop.signum()<=0||stop.compareTo(last.close())>=0)
            failed.add("INVALID_PULLBACK_STOP");
        if(!failed.isEmpty()) {
            var status=failed.contains("STRUCTURAL_LOW_BROKEN")
                    || failed.contains("ABNORMAL_SELLING_VOLUME")
                    || failed.contains("DOMINANT_BEARISH_CANDLE")
                    ? IndependentSetupSignal.Status.INVALIDATED
                    : IndependentSetupSignal.Status.WAIT;
            return new IndependentSetupSignal(IndependentSetupSignal.Setup.TREND_PULLBACK,
                    status,signalAt,null,null,null,null,failed.getFirst(),
                    passed,failed);
        }
        return new IndependentSetupSignal(IndependentSetupSignal.Setup.TREND_PULLBACK,
                IndependentSetupSignal.Status.CONFIRMED,signalAt,last.close(),stop,
                null,null,"BULLISH_PULLBACK_CONFIRMED",passed,List.of());
    }
}
