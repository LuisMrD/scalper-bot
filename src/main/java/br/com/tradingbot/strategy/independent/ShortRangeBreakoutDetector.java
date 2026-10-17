package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.*;

/**
 * SHORT hypothesis, separated from the unchanged long breakout detector:
 * lower highs/lows and falling EMA21 precede a narrow drying box. A SHORT
 * breakout requires CLOSE strictly BELOW its PRE-FROZEN base and volume
 * strictly above the historic baseline. Retest uses former base as resistance.
 */
public final class ShortRangeBreakoutDetector {
    public enum Variant { IMMEDIATE, RETEST }
    private final IndependentSetupParameters p;
    private final Variant variant;
    private Box pending;
    private long lastClose=-1;
    private record Box(BigDecimal top,BigDecimal base,long frozenAt,
            long breakoutAt,BigDecimal retestHigh) {}
    private record BoxAssessment(Box box,List<String> failures) {}
    public ShortRangeBreakoutDetector(IndependentSetupParameters parameters,Variant variant) {
        this.p=Objects.requireNonNull(parameters);
        this.variant=Objects.requireNonNull(variant);
    }
    private IndependentSetupSignal.Setup setup() {
        return variant==Variant.IMMEDIATE
                ?IndependentSetupSignal.Setup.RANGE_BREAKOUT_SHORT
                :IndependentSetupSignal.Setup.RANGE_BREAKOUT_RETEST_SHORT;
    }
    public IndependentSetupSignal onClosed(List<MarketCandle> tape,BigDecimal tick) {
        if(tick==null||tick.signum()<=0)throw new IllegalArgumentException("tick");
        check(tape);
        int n=tape.size();
        var bar=tape.getLast();
        if(bar.closeTime()<=lastClose||lastClose>=0&&bar.closeTime()
                !=lastClose+bar.interval().millis())
            throw new IllegalArgumentException("SHORT breakout requires next closed bar");
        lastClose=bar.closeTime();
        var setup=setup();
        if(n<Math.max(35,p.consolidationBars()+25))
            return IndependentSetupSignal.rejected(setup,bar.closeTime(),
                    "INSUFFICIENT_HISTORY",List.of(),List.of("INSUFFICIENT_HISTORY"));
        if(pending!=null&&pending.breakoutAt()>0) {
            var box=pending;
            if(bar.close().compareTo(box.top())>0) {
                pending=null;
                return new IndependentSetupSignal(setup,
                        IndependentSetupSignal.Status.INVALIDATED,bar.closeTime(),
                        null,null,box.top(),box.base(),"SHORT_CLOSE_ABOVE_FROZEN_BOX_TOP",
                        List.of(),List.of("SHORT_BOX_THESIS_BROKEN"));
            }
            if(bar.closeTime()-box.breakoutAt()>p.retestMaxBars()*bar.interval().millis()) {
                pending=null;
                return IndependentSetupSignal.rejected(setup,bar.closeTime(),
                        "SHORT_RETEST_WINDOW_EXPIRED",List.of(),
                        List.of("SHORT_RETEST_WINDOW_EXPIRED"));
            }
            if(variant==Variant.RETEST) {
                BigDecimal tolerance=box.base().multiply(p.retestTolerancePct())
                        .divide(BigDecimal.valueOf(100),MC);
                boolean touched=bar.high().compareTo(box.base().subtract(tolerance))>=0;
                BigDecimal maximum=box.retestHigh()==null
                        ?bar.high():box.retestHigh().max(bar.high());
                pending=new Box(box.top(),box.base(),box.frozenAt(),
                        box.breakoutAt(),maximum);
                boolean bearish=bearishStrong(bar,p.bullishStrongBodyMinRatio())
                        ||bearishShootingStar(bar,p.rejectionWickToBodyMinRatio())
                        ||bar.close().compareTo(tape.get(n-2).low())<0;
                if(touched&&bar.close().compareTo(box.base())<0&&bearish) {
                    BigDecimal stop=maximum.add(tick.multiply(p.stopBufferTicks()));
                    pending=null;
                    if(stop.compareTo(bar.close())>0)
                        return new IndependentSetupSignal(setup,
                                IndependentSetupSignal.Status.CONFIRMED,
                                bar.closeTime(),bar.close(),stop,box.top(),box.base(),
                                "SHORT_RETEST_CONFIRMED",
                                List.of("FROZEN_BASE_RETESTED","BEARISH_RESPONSE"),
                                List.of());
                }
                return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.ALERT,
                        bar.closeTime(),null,null,box.top(),box.base(),
                        bar.close().compareTo(box.base())>0
                                ?"SHORT_CLOSED_BACK_INSIDE_BOX_ALERT"
                                :"SHORT_WAIT_RETEST_CONFIRMATION",List.of(),List.of());
            }
        }
        if(pending==null) {
            var assessment=assessBeforeCurrent(tape);
            pending=assessment.box();
            if(pending==null)
                return IndependentSetupSignal.rejected(setup,bar.closeTime(),
                        assessment.failures().getFirst(),List.of(),assessment.failures());
        }
        var box=pending;
        var ok=new ArrayList<String>();
        var no=new ArrayList<String>();
        ok.add("SHORT_BOX_FROZEN_BEFORE_BREAKOUT");
        if(bar.close().compareTo(box.top())>0) {
            pending=null;
            return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.INVALIDATED,
                    bar.closeTime(),null,null,box.top(),box.base(),
                    "SHORT_BOX_TOP_BROKEN_PRE_BREAKOUT",ok,
                    List.of("SHORT_BOX_TOP_BROKEN_PRE_BREAKOUT"));
        }
        if(bar.close().compareTo(box.base())>=0) {
            if(bar.low().compareTo(box.base())<0)
                return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.WAIT,
                        bar.closeTime(),null,null,box.top(),box.base(),
                        "SHORT_WICK_ONLY_BREAKOUT",ok,List.of("SHORT_WICK_ONLY_BREAKOUT"));
            return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.ALERT,
                    bar.closeTime(),null,null,box.top(),box.base(),
                    "SHORT_FROZEN_BOX_CANDIDATE_WAIT",ok,List.of());
        }
        ok.add("SHORT_CLOSE_BELOW_FROZEN_BASE");
        BigDecimal avg=meanVolume(tape,Math.max(0,n-1-p.volumeLookbackBars()),n-1);
        if(avg.signum()<=0||bar.quoteVolume().compareTo(avg.multiply(
                p.breakoutRelativeVolumeMinRatio()))<=0)
            no.add("SHORT_BREAKOUT_VOLUME_NOT_ABOVE_HISTORY");
        else ok.add("SHORT_BREAKOUT_VOLUME_CONFIRMED");
        if(!no.isEmpty())
            return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.WAIT,
                    bar.closeTime(),null,null,box.top(),box.base(),no.getFirst(),ok,no);
        if(variant==Variant.RETEST) {
            pending=new Box(box.top(),box.base(),box.frozenAt(),bar.closeTime(),null);
            return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.ALERT,
                    bar.closeTime(),null,null,box.top(),box.base(),
                    "SHORT_BREAKOUT_VALID_WAITING_RETEST",ok,List.of());
        }
        BigDecimal anchor=p.directStopRule()
                ==IndependentSetupParameters.DirectStopRule.BOX_BASE
                ?box.top():bar.high();
        BigDecimal stop=anchor.add(tick.multiply(p.stopBufferTicks()));
        if(stop.compareTo(bar.close())<=0)
            return IndependentSetupSignal.rejected(setup,bar.closeTime(),
                    "SHORT_INVALID_BOX_STOP",ok,List.of("SHORT_INVALID_BOX_STOP"));
        pending=null;
        return new IndependentSetupSignal(setup,IndependentSetupSignal.Status.CONFIRMED,
                bar.closeTime(),bar.close(),stop,box.top(),box.base(),
                "SHORT_CLOSED_VOLUME_CONFIRMED_BREAKOUT",ok,List.of());
    }
    private BoxAssessment assessBeforeCurrent(List<MarketCandle> tape) {
        int end=tape.size()-1,start=end-p.consolidationBars();
        if(start<25)return new BoxAssessment(null,
                List.of("SHORT_BOX_INSUFFICIENT_PREBREAKOUT_HISTORY"));
        var no=new ArrayList<String>();
        if(!fallingStructure(tape,start,p).established())
            no.add("SHORT_BOX_NO_CONFIRMED_FALLING_STRUCTURE");
        var previous=ema(tape,end-1,21);
        var current=ema(tape,end,21);
        if(previous==null||current==null||current.compareTo(previous)>=0)
            no.add("SHORT_BOX_EMA21_NOT_FALLING");
        BigDecimal atr=atr(tape,end,p.atrPeriod());
        if(atr==null||atr.signum()<=0)no.add("SHORT_BOX_ATR_UNAVAILABLE");
        BigDecimal high=tape.get(start).high(),low=tape.get(start).low();
        for(int i=start;i<end;i++) {
            high=high.max(tape.get(i).high());
            low=low.min(tape.get(i).low());
        }
        if(atr!=null&&atr.signum()>0&&high.subtract(low).compareTo(
                atr.multiply(p.boxWidthMaxAtr()))>0)no.add("SHORT_BOX_WIDTH_TOO_WIDE");
        int half=p.consolidationBars()/2;
        BigDecimal earlyRange=meanRange(tape,start,start+half);
        BigDecimal lateRange=meanRange(tape,end-half,end);
        BigDecimal earlyVol=meanVolume(tape,start,start+half);
        BigDecimal lateVol=meanVolume(tape,end-half,end);
        if(earlyRange.signum()<=0||lateRange.compareTo(
                earlyRange.multiply(p.boxProgressiveRangeMaxRatio()))>0)
            no.add("SHORT_BOX_RANGE_NOT_CONTRACTING");
        if(earlyVol.signum()<=0||lateVol.compareTo(
                earlyVol.multiply(p.boxVolumeDryUpMaxRatio()))>0)
            no.add("SHORT_BOX_VOLUME_NOT_DRYING");
        if(!no.isEmpty())return new BoxAssessment(null,List.copyOf(no));
        return new BoxAssessment(new Box(high,low,tape.get(end-1).closeTime(),0,null),
                List.of());
    }
}
