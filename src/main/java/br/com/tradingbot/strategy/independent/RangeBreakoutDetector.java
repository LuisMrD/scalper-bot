package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.*;

/**
 * State belongs to ONE symbol / replay arm. Frozen boxes are built exclusively
 * from candles CLOSED BEFORE the breakout bar. No wick-only breakout passes.
 */
public final class RangeBreakoutDetector {
    public enum Variant { IMMEDIATE, RETEST }
    private final IndependentSetupParameters params;
    private final Variant variant;
    private Box pending;
    private long lastClose=-1;
    private record Box(BigDecimal top,BigDecimal base,long frozenAt,
            long breakoutAt,BigDecimal retestLow,boolean broken) {}
    private record BoxAssessment(Box box,List<String> failures) {}

    public RangeBreakoutDetector(IndependentSetupParameters params,Variant variant) {
        this.params=Objects.requireNonNull(params);
        this.variant=Objects.requireNonNull(variant);
    }

    public IndependentSetupSignal onClosed(List<MarketCandle> tape,BigDecimal tick) {
        if(tick==null||tick.signum()<=0)throw new IllegalArgumentException("tick required");
        check(tape);
        int n=tape.size();
        var current=tape.getLast();
        if(current.closeTime()<=lastClose)
            throw new IllegalArgumentException("Breakout detector requires exactly one advancing closed bar");
        if(lastClose>=0 && current.closeTime()!=lastClose+current.interval().millis())
            throw new IllegalArgumentException("Missing breakout replay candle");
        lastClose=current.closeTime();
        var setup=variant==Variant.IMMEDIATE
                ? IndependentSetupSignal.Setup.RANGE_BREAKOUT
                : IndependentSetupSignal.Setup.RANGE_BREAKOUT_RETEST;
        if(n<Math.max(35,params.consolidationBars()+25))
            return IndependentSetupSignal.rejected(setup,current.closeTime(),
                    "INSUFFICIENT_HISTORY",List.of(),List.of("INSUFFICIENT_HISTORY"));
        if(pending!=null && pending.breakoutAt()>0) {
            var box=pending;
            if(current.close().compareTo(box.base())<0) {
                pending=null;
                return new IndependentSetupSignal(setup,
                        IndependentSetupSignal.Status.INVALIDATED,current.closeTime(),
                        null,null,box.top(),box.base(),"CLOSE_BELOW_FROZEN_BOX_BASE",
                        List.of(),List.of("BOX_THESIS_BROKEN"));
            }
            if(current.closeTime()-box.breakoutAt()
                    >params.retestMaxBars()*current.interval().millis()) {
                pending=null;
                return IndependentSetupSignal.rejected(setup,current.closeTime(),
                        "RETEST_WINDOW_EXPIRED",List.of(),List.of("RETEST_WINDOW_EXPIRED"));
            }
            if(variant==Variant.RETEST) {
                BigDecimal tol=box.top().multiply(params.retestTolerancePct())
                        .divide(BigDecimal.valueOf(100),MC);
                // A candle can wick below old top, but must CLOSE back above it.
                boolean tested=current.low().compareTo(box.top().add(tol))<=0;
                BigDecimal minLow=box.retestLow()==null
                        ?current.low():box.retestLow().min(current.low());
                pending=new Box(box.top(),box.base(),box.frozenAt(),
                        box.breakoutAt(),minLow,box.broken());
                boolean bullish=bullishStrong(current,params.bullishStrongBodyMinRatio())
                        ||bullishHammer(current,params.rejectionWickToBodyMinRatio())
                        ||(n>=2&&current.close().compareTo(tape.get(n-2).high())>0);
                if(tested && current.close().compareTo(box.top())>0 && bullish) {
                    BigDecimal stop=minLow.subtract(tick.multiply(params.stopBufferTicks()));
                    pending=null;
                    if(stop.signum()>0 && stop.compareTo(current.close())<0)
                        return new IndependentSetupSignal(setup,
                                IndependentSetupSignal.Status.CONFIRMED,
                                current.closeTime(),current.close(),stop,
                                box.top(),box.base(),"RETEST_CONFIRMED",
                                List.of("FROZEN_BOX_RETESTED","BULLISH_RESPONSE"),
                                List.of());
                }
                return new IndependentSetupSignal(setup,
                        IndependentSetupSignal.Status.ALERT,current.closeTime(),
                        null,null,box.top(),box.base(),
                        current.close().compareTo(box.top())<0
                                ? "CLOSED_BACK_INSIDE_BOX_ALERT" : "WAIT_RETEST_CONFIRMATION",
                        List.of(),List.of());
            }
        }

        // Freeze a candidate ONCE, using bars already closed before breakout.
        // Later bars cannot redefine resistance (not even after a wick).
        if(pending==null) {
            var assessed=assessBeforeCurrent(tape);
            pending=assessed.box();
            if(pending==null)
                return IndependentSetupSignal.rejected(setup,current.closeTime(),
                        assessed.failures().getFirst(),List.of(),assessed.failures());
        }
        var box=pending;
        var pass=new ArrayList<String>();
        var fail=new ArrayList<String>();
        pass.add("BOX_FROZEN_BEFORE_BREAKOUT");
        if(current.close().compareTo(box.base())<0) {
            pending=null;
            return new IndependentSetupSignal(setup,
                    IndependentSetupSignal.Status.INVALIDATED,current.closeTime(),
                    null,null,box.top(),box.base(),"BOX_BASE_BROKEN_PRE_BREAKOUT",
                    pass,List.of("BOX_BASE_BROKEN_PRE_BREAKOUT"));
        }
        if(current.close().compareTo(box.top())<=0) {
            if(current.high().compareTo(box.top())>0)
                return new IndependentSetupSignal(setup,
                        IndependentSetupSignal.Status.WAIT,current.closeTime(),
                        null,null,box.top(),box.base(),"WICK_ONLY_BREAKOUT",
                        pass,List.of("WICK_ONLY_BREAKOUT"));
            return new IndependentSetupSignal(setup,
                    IndependentSetupSignal.Status.ALERT,current.closeTime(),
                    null,null,box.top(),box.base(),"FROZEN_BOX_CANDIDATE_WAIT",
                    pass,List.of());
        }
        pass.add("CLOSE_ABOVE_FROZEN_TOP");
        int referenceStart=Math.max(0,n-1-params.volumeLookbackBars());
        BigDecimal baseline=meanVolume(tape,referenceStart,n-1);
        if(baseline.signum()<=0 || current.quoteVolume().compareTo(
                baseline.multiply(params.breakoutRelativeVolumeMinRatio()))<=0)
            fail.add("BREAKOUT_VOLUME_NOT_ABOVE_HISTORY");
        else pass.add("BREAKOUT_VOLUME_CONFIRMED");
        if(!fail.isEmpty())
            return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.WAIT,
                    current.closeTime(),null,null,box.top(),box.base(),
                    fail.getFirst(),pass,fail);
        if(variant==Variant.RETEST) {
            pending=new Box(box.top(),box.base(),box.frozenAt(),
                    current.closeTime(),null,true);
            return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.ALERT,
                    current.closeTime(),null,null,box.top(),box.base(),
                    "BREAKOUT_VALID_WAITING_RETEST",pass,List.of());
        }
        BigDecimal referenceStop=params.directStopRule()
                == IndependentSetupParameters.DirectStopRule.BOX_BASE
                ?box.base():current.low();
        BigDecimal stop=referenceStop.subtract(tick.multiply(params.stopBufferTicks()));
        if(stop.signum()<=0||stop.compareTo(current.close())>=0)
            return IndependentSetupSignal.rejected(setup,current.closeTime(),
                    "INVALID_BOX_STOP",pass,List.of("INVALID_BOX_STOP"));
        pending=null; // one confirmed breakout consumes this frozen candidate.
        return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.CONFIRMED,
                current.closeTime(),current.close(),stop,box.top(),box.base(),
                "CLOSED_VOLUME_CONFIRMED_BREAKOUT",pass,List.of());
    }

    /**
     * Evaluate ALL independent prerequisites at the prior close. The first
     * rejection preserves ordering; every failed criterion remains observable.
     * This does not change which boxes are eligible for either breakout arm.
     */
    private BoxAssessment assessBeforeCurrent(List<MarketCandle> tape) {
        int end=tape.size()-1;
        int start=end-params.consolidationBars();
        if(start<25)
            return new BoxAssessment(null,List.of("BOX_INSUFFICIENT_PREBREAKOUT_HISTORY"));

        var failures=new ArrayList<String>();
        var structure=risingStructure(tape,start,params);
        if(!structure.established())
            failures.add("BOX_NO_CONFIRMED_RISING_STRUCTURE");

        BigDecimal previous21=ema(tape,end-1,21);
        BigDecimal now21=ema(tape,end,21);
        if(previous21==null||now21==null||now21.compareTo(previous21)<=0)
            failures.add("BOX_EMA21_NOT_RISING");

        BigDecimal atr=atr(tape,end,params.atrPeriod());
        if(atr==null||atr.signum()<=0)
            failures.add("BOX_ATR_UNAVAILABLE");

        BigDecimal high=tape.get(start).high();
        BigDecimal low=tape.get(start).low();
        for(int i=start;i<end;i++) {
            high=high.max(tape.get(i).high());
            low=low.min(tape.get(i).low());
        }
        if(atr!=null && atr.signum()>0
                && high.subtract(low).compareTo(
                        atr.multiply(params.boxWidthMaxAtr()))>0)
            failures.add("BOX_WIDTH_TOO_WIDE");

        int half=params.consolidationBars()/2;
        BigDecimal earlyRange=meanRange(tape,start,start+half);
        BigDecimal lateRange=meanRange(tape,end-half,end);
        BigDecimal earlyVolume=meanVolume(tape,start,start+half);
        BigDecimal lateVolume=meanVolume(tape,end-half,end);
        if(earlyRange.signum()<=0
                ||lateRange.compareTo(earlyRange.multiply(
                        params.boxProgressiveRangeMaxRatio()))>0)
            failures.add("BOX_RANGE_NOT_CONTRACTING");
        if(earlyVolume.signum()<=0
                ||lateVolume.compareTo(earlyVolume.multiply(
                        params.boxVolumeDryUpMaxRatio()))>0)
            failures.add("BOX_VOLUME_NOT_DRYING");

        if(!failures.isEmpty())
            return new BoxAssessment(null,List.copyOf(failures));
        return new BoxAssessment(
                new Box(high,low,tape.get(end-1).closeTime(),0,null,false),
                List.of());
    }
}
