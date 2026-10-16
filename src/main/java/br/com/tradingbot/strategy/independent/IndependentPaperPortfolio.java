package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.*;

/**
 * LONG-only, independent research account. Signals after close; fills at NEXT
 * open with adverse assumed spread/slippage. Stop-first OHLC convention.
 * Funding input is an explicitly assumed rate per 8h, NOT historical Binance
 * funding. No order client is injected.
 */
final class IndependentPaperPortfolio {
    private static final BigDecimal HUNDRED=BigDecimal.valueOf(100);
    private static final long EIGHT_HOURS=8L*60*60*1000;
    private static final long ONE_DAY=24L*60*60*1000;
    record Trade(long signalCloseTime,long entryOpenTime,long exitOpenTime,
            BigDecimal entry,BigDecimal exit,BigDecimal initialStop,
            BigDecimal quantity,BigDecimal netPnlUsdt,
            BigDecimal totalFeesUsdt,BigDecimal fundingUsdt,String reason) {}
    record Summary(String arm,BigDecimal initialCapital,BigDecimal finalMarkedEquity,
            BigDecimal realizedNetUsdt,BigDecimal totalFeesUsdt,
            BigDecimal totalFundingUsdt,int closedTrades,int wins,
            int blockedEntries,int acceptedSignals,BigDecimal worstDrawdownPct,
            boolean openPosition,List<Trade> trades,
            Map<String,Integer> rejectionCounts) {}
    private final IndependentResearchRequest r;
    private final String arm;
    private final List<Trade> trades=new ArrayList<>();
    private final Map<String,Integer> rejected=new HashMap<>();
    private BigDecimal cash,peak,worstDrawdown=BigDecimal.ZERO,fees=BigDecimal.ZERO,funding=BigDecimal.ZERO;
    private long currentDay=-1;
    private BigDecimal realizedToday=BigDecimal.ZERO;
    private Pending pending;
    private Position position;
    private boolean exitNextOpen;
    private String exitReason;
    private int acceptedSignals,blockedEntries;
    private record Pending(long signalClose,BigDecimal stop,
            BigDecimal frozenBoxTop,BigDecimal frozenBoxBase) {}
    private static final class Position {
        final long signalClose, entryAt;
        final BigDecimal entry,initialStop,qty,entryFee,frozenBoxTop,frozenBoxBase;
        boolean alertedInsideBox;
        BigDecimal stop,fees,funding=BigDecimal.ZERO;
        int heldBars;
        Position(Pending p,long entered,BigDecimal price,BigDecimal qty,BigDecimal fee) {
            signalClose=p.signalClose;entryAt=entered;entry=price;
            initialStop=p.stop;stop=p.stop;this.qty=qty;
            frozenBoxTop=p.frozenBoxTop();
            frozenBoxBase=p.frozenBoxBase();
            entryFee=fee;fees=fee;
        }
    }
    IndependentPaperPortfolio(String arm,IndependentResearchRequest r) {
        this.arm=arm;this.r=r;cash=r.capitalUsdt();peak=cash;
    }
    /** Proven monotone under all candidates; used after confirmed pivot closure. */
    static BigDecimal tightenStop(BigDecimal previous,BigDecimal candidate) {
        return previous.max(candidate);
    }
    private BigDecimal percent(BigDecimal n) { return n.divide(HUNDRED,MC); }
    private BigDecimal adverseRate() {
        return percent(r.slippagePerSidePct().add(
                r.spreadPct().divide(BigDecimal.valueOf(2),MC)));
    }
    private BigDecimal buy(BigDecimal px) {
        return px.multiply(BigDecimal.ONE.add(adverseRate()),MC);
    }
    private BigDecimal sell(BigDecimal px) {
        return px.multiply(BigDecimal.ONE.subtract(adverseRate()),MC);
    }
    private BigDecimal floorStep(BigDecimal value,BigDecimal step) {
        return value.divide(step,0,RoundingMode.FLOOR).multiply(step);
    }
    void count(IndependentSetupSignal s) {
        for(String reason:s.rejectedFilters())rejected.merge(reason,1,Integer::sum);
        if(s.status()==IndependentSetupSignal.Status.INVALIDATED)
            rejected.merge(s.reason(),1,Integer::sum);
    }
    void begin(MarketCandle c) {
        long day=c.openTime()/ONE_DAY;
        if(currentDay!=day){currentDay=day;realizedToday=BigDecimal.ZERO;}
        if(exitNextOpen && position!=null)closeAt(c.open(),c.openTime(),exitReason);
        exitNextOpen=false;
        if(pending!=null && position==null) {
            Pending proposal=pending;
            pending=null;
            BigDecimal fill=buy(c.open());
            BigDecimal stop=proposal.stop;
            BigDecimal distance=fill.subtract(stop);
            BigDecimal entryCostRate=percent(r.feePerSidePct())
                    .add(adverseRate());
            BigDecimal unitCosts=fill.add(stop).multiply(entryCostRate,MC);
            BigDecimal costRatio=distance.signum()>0
                    ?unitCosts.divide(distance,MC).multiply(HUNDRED):HUNDRED;
            BigDecimal riskBudget=cash.multiply(percent(r.riskPerTradePct()),MC);
            BigDecimal dailyLimit=r.capitalUsdt().multiply(percent(r.dailyStopPct()));
            // Conservative HYPOTHETICAL long liquidation approximation only.
            // Exact Binance liquidation also depends on notional tiers, wallet
            // balance, cross/isolated margin, maintenance fees and mark price.
            BigDecimal liquidationFloor=fill.multiply(
                    BigDecimal.ONE.subtract(BigDecimal.ONE.divide(
                            BigDecimal.valueOf(r.leverage()),MC))
                    .add(percent(r.assumedMaintenanceMarginPct())),MC);
            if(realizedToday.compareTo(dailyLimit.negate())<=0
                    ||distance.signum()<=0 || stop.compareTo(liquidationFloor)<=0
                    ||costRatio.compareTo(r.maxCostToInitialRiskPct())>0) {
                blockedEntries++;
            }else{
                BigDecimal qty=riskBudget.divide(distance.add(unitCosts),MC)
                        .min(cash.multiply(BigDecimal.valueOf(r.leverage()),MC)
                            .divide(fill,MC));
                qty=floorStep(qty,r.quantityStep());
                BigDecimal notional=qty.multiply(fill,MC);
                if(qty.compareTo(r.minimumQuantity())<0
                        ||notional.compareTo(r.minimumNotionalUsdt())<0){
                    blockedEntries++;
                } else {
                    BigDecimal fee=notional.multiply(percent(r.feePerSidePct()),MC);
                    cash=cash.subtract(fee);
                    fees=fees.add(fee);
                    position=new Position(proposal,c.openTime(),fill,qty,fee);
                }
            }
        }
        if(position!=null) {
            position.heldBars++;
            // Unknown intrabar ordering is settled STOP FIRST.
            if(c.low().compareTo(position.stop)<=0) {
                BigDecimal px=c.open().min(position.stop);
                closeAt(px,c.openTime(),"STOP");
            }
        }
    }
    private void closeAt(BigDecimal raw,long time,String reason) {
        if(position==null)return;
        Position p=position;
        BigDecimal fill=sell(raw);
        BigDecimal exitFee=fill.multiply(p.qty,MC)
                .multiply(percent(r.feePerSidePct()),MC);
        BigDecimal gross=fill.subtract(p.entry).multiply(p.qty,MC);
        BigDecimal realizedNow=gross.subtract(exitFee);
        BigDecimal tradeNet=realizedNow.subtract(p.entryFee).subtract(p.funding);
        cash=cash.add(realizedNow);
        realizedToday=realizedToday.add(tradeNet);
        fees=fees.add(exitFee);
        trades.add(new Trade(p.signalClose,p.entryAt,time,p.entry,fill,
                p.initialStop,p.qty,tradeNet,p.fees.add(exitFee),p.funding,reason));
        position=null;
    }
    void close(MarketCandle candle,IndependentSetupSignal s,
            List<MarketCandle> history) {
        if(position==null) {
            if(s.actionable()&&pending==null) {
                BigDecimal stop=floorStep(s.structuralStop(),r.priceTick());
                if(stop.signum()>0&&stop.compareTo(candle.close())<0) {
                    pending=new Pending(s.candleCloseTime(),stop,
                            s.frozenBoxTop(),s.frozenBoxBase());
                    acceptedSignals++;
                }else blockedEntries++;
            }
            return;
        }
        int fundingPeriods=fundingOccurrences(candle);
        if(fundingPeriods>0) {
            // Hypothetical funding settled at the bar close. A 1d candle
            // spans THREE 8-hour settlements; historical rate and within-bar
            // prices are unknown. This cannot substitute actual funding history.
            BigDecimal charged=candle.close().multiply(position.qty,MC)
                    .multiply(percent(r.assumedFundingPer8hPct()),MC)
                    .multiply(BigDecimal.valueOf(fundingPeriods));
            cash=cash.subtract(charged);
            funding=funding.add(charged);
            position.funding=position.funding.add(charged);
        }
        if(position.frozenBoxTop!=null
                &&candle.close().compareTo(position.frozenBoxTop)<0
                &&position.frozenBoxBase!=null
                &&candle.close().compareTo(position.frozenBoxBase)>=0
                &&!position.alertedInsideBox) {
            rejected.merge("OPEN_POSITION_BACK_INSIDE_BOX_ALERT",1,Integer::sum);
            position.alertedInsideBox=true;
        }
        if((position.frozenBoxBase!=null
                    &&candle.close().compareTo(position.frozenBoxBase)<0)
                ||(s.status()==IndependentSetupSignal.Status.INVALIDATED
                    &&"CLOSE_BELOW_FROZEN_BOX_BASE".equals(s.reason()))) {
            exitNextOpen=true;exitReason="BOX_BASE_INVALIDATION";
        }
        if(position.heldBars>=r.maxHoldingCandles()) {
            exitNextOpen=true;exitReason="MAX_HOLDING";
        }
        updateDrawdown(candle.close());
        // Trail only after user-configured favorable movement in initial R.
        // A confirmed structural low may tighten but can NEVER widen the stop.
        BigDecimal favorable=candle.close().subtract(position.entry);
        BigDecimal needed=position.entry.subtract(position.initialStop)
                .multiply(r.parameters().trailingActivationR());
        if(favorable.compareTo(needed)<0)return;
        // Only pivots whose right confirmation is CLOSED may tighten stop.
        var pivots=pivots(history,history.size(),r.parameters().pivotLeft(),
                r.parameters().pivotRight());
        for(var pivot:pivots) {
            if(!pivot.high() && pivot.confirmedAt()>=position.entryAt) {
                BigDecimal candidate=floorStep(pivot.price().subtract(
                        r.priceTick().multiply(r.parameters().stopBufferTicks())),r.priceTick());
                if(candidate.compareTo(candle.close())<0)
                    position.stop=tightenStop(position.stop,candidate);
            }
        }
    }
    /** Simulation only; expected Binance-style three settlements per completed UTC day. */
    static int fundingOccurrences(MarketCandle candle) {
        if (candle.interval().millis() >= EIGHT_HOURS)
            return Math.toIntExact(candle.interval().millis()/EIGHT_HOURS);
        return candle.closeTime()%EIGHT_HOURS==EIGHT_HOURS-1 ? 1 : 0;
    }
    private void updateDrawdown(BigDecimal lastPrice) {
        BigDecimal marked=cash;
        if(position!=null)marked=marked.add(lastPrice.subtract(position.entry)
                .multiply(position.qty,MC));
        peak=peak.max(marked);
        if(peak.signum()>0)
            worstDrawdown=worstDrawdown.max(peak.subtract(marked)
                    .divide(peak,MC).multiply(HUNDRED));
    }
    Summary summary(MarketCandle last) {
        BigDecimal marked=cash;
        if(position!=null)
            marked=marked.add(last.close().subtract(position.entry)
                    .multiply(position.qty,MC));
        if(marked.compareTo(peak)>0)peak=marked;
        if(peak.signum()>0) {
            BigDecimal dd=peak.subtract(marked).divide(peak,MC).multiply(HUNDRED);
            worstDrawdown=worstDrawdown.max(dd);
        }
        int wins=(int)trades.stream().filter(t->t.netPnlUsdt().signum()>0).count();
        return new Summary(arm,r.capitalUsdt(),marked,cash.subtract(r.capitalUsdt()),
                fees,funding,trades.size(),wins,blockedEntries,acceptedSignals,
                worstDrawdown,position!=null,List.copyOf(trades),Map.copyOf(rejected));
    }
}
