package br.com.tradingbot.strategy.independent;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.MC;

/**
 * Causal pre-entry screening. Saturday/Sunday apply to the actual scheduled
 * NEXT-CANDLE OPEN in UTC (not to the signal candle's open/close date).
 * Structural-stop distance uses ONLY frozen signal close/reference and stop,
 * NOT a future entry fill or a post-entry profit/loss observation.
 *
 * This filter does not claim that post-hoc deletion of losers will reproduce
 * the results. Each policy must be independently re-simulated.
 */
final class EntrySelectionFilter {
    record Assessment(boolean allowed, String reason, long nextOpenUtc,
            String nextOpenDayUtc, BigDecimal stopDistancePct,
            List<String> rejectedReasons) {}

    static Assessment assess(EntrySelectionPolicy policy,
            IndependentSetupSignal signal) {
        if(!signal.actionable())
            throw new IllegalArgumentException("Only confirmed actionable entries may be screened");
        // IndependentResearchService evaluates at the 15m close. The
        // simulator fills at the immediately following bar open.
        long nextOpen=Math.addExact(signal.candleCloseTime(),1L);
        var day=Instant.ofEpochMilli(nextOpen).atZone(ZoneOffset.UTC).getDayOfWeek();
        // BASELINE deliberately doesn't gain any extra restrictions.
        if(policy==EntrySelectionPolicy.BASELINE)
            return new Assessment(true,"BASELINE_UNCHANGED",nextOpen,day.name(),
                    validDistance(signal),List.of());
        var failures=new ArrayList<String>();
        if(policy.weekdaysOnly() && (day==DayOfWeek.SATURDAY||day==DayOfWeek.SUNDAY))
            failures.add("ENTRY_UTC_WEEKEND");
        BigDecimal distance=validDistance(signal);
        if(policy.maxStopDistancePct()!=null) {
            if(distance==null)failures.add("ENTRY_INVALID_STOP_DISTANCE");
            else if(distance.compareTo(policy.maxStopDistancePct())>0)
                failures.add(policy.maxStopDistancePct().compareTo(
                        new BigDecimal("0.45"))==0
                        ?"ENTRY_STOP_DISTANCE_GT_045_PCT"
                        :"ENTRY_STOP_DISTANCE_GT_050_PCT");
        }
        return new Assessment(failures.isEmpty(),
                failures.isEmpty()?"ENTRY_SELECTION_APPROVED":failures.getFirst(),
                nextOpen,day.name(),distance,List.copyOf(failures));
    }

    private static BigDecimal validDistance(IndependentSetupSignal signal) {
        BigDecimal ref=signal.referencePrice();
        BigDecimal stop=signal.structuralStop();
        if(ref==null||stop==null||ref.signum()<=0||stop.signum()<=0)
            return null;
        // Wrong-side stops are not a meaningful structural risk distance,
        // even if the paper portfolio would subsequently block them anyway.
        boolean shortSide=signal.side()==IndependentSetupSignal.Side.SHORT;
        if(shortSide ?stop.compareTo(ref)<=0:stop.compareTo(ref)>=0)
            return null;
        return ref.subtract(stop).abs().multiply(BigDecimal.valueOf(100),MC)
                .divide(ref,MC);
    }

    private EntrySelectionFilter() {}
}
