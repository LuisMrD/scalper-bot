package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.*;

/**
 * SHORT-only hypothesis: confirmed lower highs/lows, contracting bullish
 * bounce towards EMA9/21, then bearish response with expanding taker selling.
 * The long detector is left completely unchanged.
 */
public final class ShortTrendPullbackDetector {
    public enum RetreatMode { ALL_BULLISH, MIXED_NET_RISE }
    private static final BigDecimal HUNDRED=BigDecimal.valueOf(100);
    private final IndependentSetupParameters p;
    private final RetreatMode mode;
    public ShortTrendPullbackDetector(IndependentSetupParameters parameters,RetreatMode mode) {
        this.p=Objects.requireNonNull(parameters);
        this.mode=Objects.requireNonNull(mode);
    }
    private IndependentSetupSignal.Setup setup() {
        return mode==RetreatMode.ALL_BULLISH
                ?IndependentSetupSignal.Setup.TREND_PULLBACK_SHORT
                :IndependentSetupSignal.Setup.TREND_PULLBACK_MIXED_SHORT;
    }
    public IndependentSetupSignal evaluate(List<MarketCandle> tape,BigDecimal tick) {
        IndependentSetupSignal best=null;
        for(int k=p.pullbackMinBars();k<=p.pullbackMaxBars();k++) {
            var found=evaluate(tape,tick,k);
            if(found.actionable())return found;
            if(best==null||found.rejectedFilters().size()<best.rejectedFilters().size())
                best=found;
        }
        return best;
    }
    private IndependentSetupSignal evaluate(List<MarketCandle> tape,BigDecimal tick,int k) {
        if(tick==null||tick.signum()<=0)throw new IllegalArgumentException("priceTick");
        check(tape);
        int n=tape.size();
        var ok=new ArrayList<String>();
        var no=new ArrayList<String>();
        long at=tape.getLast().closeTime();
        if(n<Math.max(35,2*p.pullbackMaxBars()+p.pivotLeft()+p.pivotRight()+5))
            return IndependentSetupSignal.rejected(setup(),at,"INSUFFICIENT_HISTORY",
                    ok,List.of("INSUFFICIENT_HISTORY"));
        var last=tape.getLast();
        int start=n-1-k;
        var structure=fallingStructure(tape,start,p);
        if(!structure.established())no.add("SHORT_NO_CONFIRMED_FALLING_STRUCTURE");
        else ok.add("CONFIRMED_FALLING_STRUCTURE");
        BigDecimal ema9=ema(tape,n-1,9),ema21=ema(tape,n-1,21);
        if(ema9==null||ema21==null||ema9.compareTo(ema21)>0)
            no.add("SHORT_EMA_ALIGNMENT");
        else ok.add("SHORT_EMA_ALIGNMENT");
        BigDecimal bounceHigh=null,volume=BigDecimal.ZERO,bodySum=BigDecimal.ZERO;
        boolean allBullish=true,anyBullish=false;
        for(int i=start;i<n-1;i++) {
            var bar=tape.get(i);
            boolean bullish=bar.close().compareTo(bar.open())>0;
            allBullish &= bullish;
            anyBullish |= bullish;
            bounceHigh=bounceHigh==null?bar.high():bounceHigh.max(bar.high());
            volume=volume.add(bar.quoteVolume());
            bodySum=bodySum.add(body(bar));
        }
        if(mode==RetreatMode.ALL_BULLISH) {
            if(!allBullish)no.add("SHORT_PULLBACK_NOT_ORDERLY_BULLISH");
        } else {
            boolean upwardBounce=anyBullish
                    &&tape.get(n-2).close().compareTo(tape.get(start).open())>0;
            if(!upwardBounce)no.add("SHORT_MIXED_PULLBACK_NO_NET_RISE");
            else ok.add("SHORT_MIXED_PULLBACK_NET_RISE");
        }
        BigDecimal referenceVolume=meanVolume(tape,start-k,start);
        BigDecimal bounceVolume=volume.divide(BigDecimal.valueOf(k),MC);
        if(referenceVolume.signum()<=0||bounceVolume.compareTo(
                referenceVolume.multiply(p.pullbackVolumeContractionMaxRatio()))>0)
            no.add("SHORT_BUY_VOLUME_NOT_CONTRACTING");
        else ok.add("SHORT_BUY_VOLUME_CONTRACTING");
        BigDecimal earlierBody=BigDecimal.ZERO;
        for(int i=start-k;i<start;i++)earlierBody=earlierBody.add(body(tape.get(i)));
        earlierBody=earlierBody.divide(BigDecimal.valueOf(k),MC);
        BigDecimal bounceBody=bodySum.divide(BigDecimal.valueOf(k),MC);
        if(earlierBody.signum()<=0||bounceBody.compareTo(
                earlierBody.multiply(p.pullbackBodyContractionMaxRatio()))>0)
            no.add("SHORT_PULLBACK_BODIES_NOT_SMALLER");
        else ok.add("SHORT_PULLBACK_BODIES_SMALLER");
        if(ema9!=null&&ema21!=null) {
            BigDecimal tolerance=ema21.multiply(p.emaTouchTolerancePct())
                    .divide(HUNDRED,MC);
            if(bounceHigh==null||bounceHigh.compareTo(ema9.min(ema21)
                    .subtract(tolerance))<0||bounceHigh.compareTo(ema21.add(tolerance))>0)
                no.add("SHORT_PULLBACK_NOT_AT_EMA9_21_ZONE");
            else ok.add("SHORT_EMA9_21_ZONE_TESTED");
        }
        if(structure.lastHigh()!=null&&(bounceHigh.compareTo(structure.lastHigh())>=0
                ||last.high().compareTo(structure.lastHigh())>=0))
            no.add("SHORT_STRUCTURAL_HIGH_BROKEN");
        if(last.close().compareTo(last.open())>=0
                &&last.quoteVolume().compareTo(referenceVolume.multiply(
                    p.exceptionalSellingVolumeMinRatio()))>=0)
            no.add("SHORT_ABNORMAL_BUYING_VOLUME");
        if(bullishStrong(last,p.bullishStrongBodyMinRatio()))
            no.add("SHORT_DOMINANT_BULLISH_CANDLE");
        var previous=tape.get(n-2);
        boolean strength=bearishStrong(last,p.bullishStrongBodyMinRatio());
        boolean star=bearishShootingStar(last,p.rejectionWickToBodyMinRatio());
        boolean breakLow=last.close().compareTo(previous.low())<0
                &&last.close().compareTo(last.open())<0;
        if(!(strength||star||breakLow))
            no.add("SHORT_NO_CLOSED_BEARISH_CONFIRMATION");
        else ok.add(strength?"BEARISH_STRENGTH":star?"UPPER_WICK_REJECTION":"PREVIOUS_LOW_CLOSE_BREAK");
        BigDecimal sellNow=last.quoteVolume().subtract(last.takerBuyQuoteVolume());
        BigDecimal sellPrevious=previous.quoteVolume().subtract(previous.takerBuyQuoteVolume());
        if(sellNow.signum()<=0||sellNow.compareTo(sellPrevious.multiply(
                p.buyerVolumeGrowthMinRatio()))<=0)
            no.add("SHORT_SELLER_VOLUME_NOT_INCREASING");
        else ok.add("SHORT_SELLER_VOLUME_INCREASING");
        BigDecimal stop=bounceHigh==null?null:bounceHigh.add(
                tick.multiply(p.stopBufferTicks()));
        if(stop==null||stop.signum()<=0||stop.compareTo(last.close())<=0)
            no.add("SHORT_INVALID_PULLBACK_STOP");
        if(!no.isEmpty()) {
            var status=no.contains("SHORT_STRUCTURAL_HIGH_BROKEN")
                    ||no.contains("SHORT_ABNORMAL_BUYING_VOLUME")
                    ||no.contains("SHORT_DOMINANT_BULLISH_CANDLE")
                    ?IndependentSetupSignal.Status.INVALIDATED
                    :IndependentSetupSignal.Status.WAIT;
            return new IndependentSetupSignal(setup(),status,at,null,null,null,
                    null,no.getFirst(),ok,no);
        }
        return new IndependentSetupSignal(setup(),IndependentSetupSignal.Status.CONFIRMED,
                at,last.close(),stop,null,null,"BEARISH_PULLBACK_CONFIRMED",ok,List.of());
    }
}
