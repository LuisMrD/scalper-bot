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
 * LONG or SHORT, separate research accounts. Signals after close; fills at NEXT
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
            BigDecimal totalFeesUsdt,BigDecimal fundingUsdt,String reason,
            BigDecimal mfeR,BigDecimal maeR,BigDecimal netR) {
        // Backward-compatible constructor for legacy research fixtures;
        // production stop-study fills the three extra excursion fields.
        Trade(long signalCloseTime,long entryOpenTime,long exitOpenTime,
                BigDecimal entry,BigDecimal exit,BigDecimal initialStop,
                BigDecimal quantity,BigDecimal netPnlUsdt,
                BigDecimal totalFeesUsdt,BigDecimal fundingUsdt,String reason) {
            this(signalCloseTime,entryOpenTime,exitOpenTime,entry,exit,initialStop,
                    quantity,netPnlUsdt,totalFeesUsdt,fundingUsdt,reason,
                    BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);
        }
    }
    record Summary(String arm,BigDecimal initialCapital,BigDecimal finalMarkedEquity,
            BigDecimal realizedNetUsdt,BigDecimal totalFeesUsdt,
            BigDecimal totalFundingUsdt,int closedTrades,int wins,
            int blockedEntries,int acceptedSignals,BigDecimal worstDrawdownPct,
            boolean openPosition,List<Trade> trades,
            Map<String,Integer> rejectionCounts) {}
    private final IndependentResearchRequest r;
    private final String arm;
    private final boolean shortSide;
    private final StopResearchPolicy stopPolicy;
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
        BigDecimal maxObservedPrice,minObservedPrice;
        int heldBars;
        Position(Pending p,long entered,BigDecimal price,BigDecimal qty,BigDecimal fee) {
            signalClose=p.signalClose;entryAt=entered;entry=price;
            initialStop=p.stop;stop=p.stop;this.qty=qty;
            frozenBoxTop=p.frozenBoxTop();
            frozenBoxBase=p.frozenBoxBase();
            entryFee=fee;fees=fee;
            maxObservedPrice=price;minObservedPrice=price;
        }
    }
    IndependentPaperPortfolio(String arm,IndependentResearchRequest r) {
        this(arm,r,StopResearchPolicy.BASELINE);
    }
    IndependentPaperPortfolio(String arm,IndependentResearchRequest r,
            StopResearchPolicy policy) {
        this.arm=arm;this.r=r;
        this.stopPolicy=java.util.Objects.requireNonNull(policy);
        // The separate stop study uses labels like TREND_PULLBACK_SHORT__ATR_050.
        shortSide=arm.contains("_SHORT");
        cash=r.capitalUsdt();peak=cash;
    }
    /** Proven monotone under all candidates; used after confirmed pivot closure. */
    static BigDecimal tightenStop(BigDecimal previous,BigDecimal candidate) {
        return previous.max(candidate);
    }
    static BigDecimal tightenShortStop(BigDecimal previous,BigDecimal candidate) {
        return previous.min(candidate);
    }
    private BigDecimal directionalPnl(BigDecimal entry,BigDecimal exit) {
        return shortSide?entry.subtract(exit):exit.subtract(entry);
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
        // One occurrence per unique failed filter and evaluated candle.
        // INVALIDATED.reason is descriptive metadata, not another rejection
        // when rejectedFilters already includes the underlying violation.
        var unique=new java.util.LinkedHashSet<>(s.rejectedFilters());
        if(unique.isEmpty() && s.status()==IndependentSetupSignal.Status.INVALIDATED)
            unique.add(s.reason());
        for(String reason:unique) rejected.merge(reason,1,Integer::sum);
    }
    void begin(MarketCandle c) {
        long day=c.openTime()/ONE_DAY;
        if(currentDay!=day){currentDay=day;realizedToday=BigDecimal.ZERO;}
        if(exitNextOpen && position!=null)closeAt(c.open(),c.openTime(),exitReason);
        exitNextOpen=false;
        if(pending!=null && position==null) {
            Pending proposal=pending;
            pending=null;
            BigDecimal fill=shortSide?sell(c.open()):buy(c.open());
            BigDecimal stop=proposal.stop;
            BigDecimal distance=shortSide?stop.subtract(fill):fill.subtract(stop);
            BigDecimal entryCostRate=percent(r.feePerSidePct())
                    .add(adverseRate());
            BigDecimal unitCosts=fill.add(stop).multiply(entryCostRate,MC);
            BigDecimal costRatio=distance.signum()>0
                    ?unitCosts.divide(distance,MC).multiply(HUNDRED):HUNDRED;
            BigDecimal riskBudget=cash.multiply(percent(r.riskPerTradePct()),MC);
            BigDecimal dailyLimit=r.capitalUsdt().multiply(percent(r.dailyStopPct()));
            // Approximate hypothetical liquidation limits, not Binance's
            // real mark-price/tier/cross-isolated margin liquidation formula.
            BigDecimal leverageFraction=BigDecimal.ONE.divide(
                    BigDecimal.valueOf(r.leverage()),MC);
            BigDecimal marginRate=percent(r.assumedMaintenanceMarginPct());
            BigDecimal liquidationBarrier=fill.multiply(shortSide
                    ?BigDecimal.ONE.add(leverageFraction).subtract(marginRate)
                    :BigDecimal.ONE.subtract(leverageFraction).add(marginRate),MC);
            boolean stopPastLiquidation=shortSide
                    ?stop.compareTo(liquidationBarrier)>=0
                    :stop.compareTo(liquidationBarrier)<=0;
            if(realizedToday.compareTo(dailyLimit.negate())<=0
                    ||distance.signum()<=0 ||stopPastLiquidation
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
            position.maxObservedPrice=position.maxObservedPrice.max(c.high());
            position.minObservedPrice=position.minObservedPrice.min(c.low());
            // Unknown intrabar ordering is settled STOP FIRST.
            if(shortSide?c.high().compareTo(position.stop)>=0
                    :c.low().compareTo(position.stop)<=0) {
                BigDecimal px=shortSide?c.open().max(position.stop)
                        :c.open().min(position.stop);
                closeAt(px,c.openTime(),"STOP");
            }
        }
    }
    private void closeAt(BigDecimal raw,long time,String reason) {
        if(position==null)return;
        Position p=position;
        BigDecimal fill=shortSide?buy(raw):sell(raw);
        BigDecimal exitFee=fill.multiply(p.qty,MC)
                .multiply(percent(r.feePerSidePct()),MC);
        BigDecimal gross=directionalPnl(p.entry,fill).multiply(p.qty,MC);
        BigDecimal realizedNow=gross.subtract(exitFee);
        BigDecimal tradeNet=realizedNow.subtract(p.entryFee).subtract(p.funding);
        cash=cash.add(realizedNow);
        realizedToday=realizedToday.add(tradeNet);
        fees=fees.add(exitFee);
        BigDecimal initialR=p.entry.subtract(p.initialStop).abs();
        BigDecimal favorable=shortSide
                ?p.entry.subtract(p.minObservedPrice)
                :p.maxObservedPrice.subtract(p.entry);
        BigDecimal adverse=shortSide
                ?p.maxObservedPrice.subtract(p.entry)
                :p.entry.subtract(p.minObservedPrice);
        BigDecimal capitalAtRisk=initialR.multiply(p.qty,MC);
        trades.add(new Trade(p.signalClose,p.entryAt,time,p.entry,fill,
                p.initialStop,p.qty,tradeNet,p.fees.add(exitFee),p.funding,reason,
                initialR.signum()>0?favorable.divide(initialR,MC):BigDecimal.ZERO,
                initialR.signum()>0?adverse.divide(initialR,MC):BigDecimal.ZERO,
                capitalAtRisk.signum()>0?tradeNet.divide(capitalAtRisk,MC):BigDecimal.ZERO));
        position=null;
    }
    void close(MarketCandle candle,IndependentSetupSignal s,
            List<MarketCandle> history) {
        if(position==null) {
            if(s!=null && s.actionable() && pending==null) {
                if((s.side()==IndependentSetupSignal.Side.SHORT)!=shortSide)
                    throw new IllegalArgumentException("Signal/portfolio direction mismatch");
                BigDecimal rawStop=s.structuralStop();
                if(stopPolicy.initialAtrMultiplier().signum()>0) {
                    BigDecimal currentAtr=atr(history,history.size(),r.parameters().atrPeriod());
                    if(currentAtr==null||currentAtr.signum()<=0) {
                        blockedEntries++;
                        return;
                    }
                    BigDecimal margin=currentAtr.multiply(stopPolicy.initialAtrMultiplier(),MC);
                    rawStop=shortSide?rawStop.add(margin):rawStop.subtract(margin);
                }
                BigDecimal stop=roundStop(rawStop);
                boolean valid=shortSide?stop.compareTo(candle.close())>0
                        :stop.compareTo(candle.close())<0;
                if(stop.signum()>0&&valid) {
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
            if(shortSide)charged=charged.negate();
            cash=cash.subtract(charged);
            funding=funding.add(charged);
            position.funding=position.funding.add(charged);
        }
        if(position.frozenBoxTop!=null && position.frozenBoxBase!=null
                &&(shortSide
                    ?candle.close().compareTo(position.frozenBoxBase)>0
                        &&candle.close().compareTo(position.frozenBoxTop)<=0
                    :candle.close().compareTo(position.frozenBoxTop)<0
                        &&candle.close().compareTo(position.frozenBoxBase)>=0)
                &&!position.alertedInsideBox) {
            rejected.merge("OPEN_POSITION_BACK_INSIDE_BOX_ALERT",1,Integer::sum);
            position.alertedInsideBox=true;
        }
        boolean boxBroken=shortSide
                ?position.frozenBoxTop!=null
                    &&candle.close().compareTo(position.frozenBoxTop)>0
                :position.frozenBoxBase!=null
                    &&candle.close().compareTo(position.frozenBoxBase)<0;
        if(boxBroken || s!=null && s.status()==IndependentSetupSignal.Status.INVALIDATED
                &&(shortSide
                    ?"SHORT_CLOSE_ABOVE_FROZEN_BOX_TOP".equals(s.reason())
                    :"CLOSE_BELOW_FROZEN_BOX_BASE".equals(s.reason()))) {
            exitNextOpen=true;
            exitReason=shortSide?"SHORT_BOX_TOP_INVALIDATION":"BOX_BASE_INVALIDATION";
        }
        if(position.heldBars>=r.maxHoldingCandles()) {
            exitNextOpen=true;exitReason="MAX_HOLDING";
        }
        updateDrawdown(candle.close());
        // All candidates are calculated AFTER the completed bar: effective
        // only on the NEXT 15m bar, never on the candle used to calculate ATR.
        if(stopPolicy.breakEven()||stopPolicy.atrTrailing()) {
            applyExperimentalTrailing(candle,history);
            return;
        }
        // Trail only after user-configured favorable movement in initial R.
        // A confirmed structural low may tighten but can NEVER widen the stop.
        BigDecimal favorable=directionalPnl(position.entry,candle.close());
        BigDecimal needed=position.entry.subtract(position.initialStop).abs()
                .multiply(r.parameters().trailingActivationR());
        if(favorable.compareTo(needed)<0)return;
        // Only pivots whose right confirmation is CLOSED may tighten stop.
        var pivots=pivots(history,history.size(),r.parameters().pivotLeft(),
                r.parameters().pivotRight());
        for(var pivot:pivots) {
            if(pivot.high()==shortSide && pivot.confirmedAt()>=position.entryAt) {
                BigDecimal raw=shortSide
                        ?pivot.price().add(r.priceTick().multiply(
                            r.parameters().stopBufferTicks()))
                        :pivot.price().subtract(r.priceTick().multiply(
                            r.parameters().stopBufferTicks()));
                BigDecimal candidate=shortSide
                        ?raw.divide(r.priceTick(),0,RoundingMode.CEILING)
                            .multiply(r.priceTick())
                        :floorStep(raw,r.priceTick());
                if(shortSide?candidate.compareTo(candle.close())>0
                        :candidate.compareTo(candle.close())<0)
                    position.stop=shortSide
                            ?tightenShortStop(position.stop,candidate)
                            :tightenStop(position.stop,candidate);
            }
        }
    }
    private BigDecimal roundStop(BigDecimal raw) {
        return shortSide?raw.divide(r.priceTick(),0,RoundingMode.CEILING)
                .multiply(r.priceTick()):floorStep(raw,r.priceTick());
    }

    /**
     * Exact simulated scratch level for the modeled adverse entry fill, future
     * adverse exit fill, BOTH commissions and any funding already charged.
     * The raw stop is rounded away from entry, not to a fictitious free exit.
     */
    private BigDecimal costAdjustedBreakEven(Position p) {
        BigDecimal fee=percent(r.feePerSidePct());
        BigDecimal costPerUnit=p.funding.divide(p.qty,MC);
        BigDecimal exitAdverse=adverseRate();
        BigDecimal numerator=shortSide
                ?p.entry.multiply(BigDecimal.ONE.subtract(fee),MC).subtract(costPerUnit)
                :p.entry.multiply(BigDecimal.ONE.add(fee),MC).add(costPerUnit);
        BigDecimal denominator=shortSide
                ?BigDecimal.ONE.add(exitAdverse).multiply(BigDecimal.ONE.add(fee),MC)
                :BigDecimal.ONE.subtract(exitAdverse).multiply(BigDecimal.ONE.subtract(fee),MC);
        if(denominator.signum()<=0)throw new IllegalArgumentException("Invalid fee/slippage");
        return roundStop(numerator.divide(denominator,MC));
    }
    private void tightenAfterClose(BigDecimal candidate,BigDecimal lastClose) {
        if(candidate.signum()<=0)return;
        if(shortSide) {
            if(candidate.compareTo(lastClose)>0)
                position.stop=tightenShortStop(position.stop,candidate);
        }else {
            if(candidate.compareTo(lastClose)<0)
                position.stop=tightenStop(position.stop,candidate);
        }
    }
    private void applyExperimentalTrailing(MarketCandle candle,List<MarketCandle> history) {
        BigDecimal risk=position.entry.subtract(position.initialStop).abs();
        BigDecimal favorable=directionalPnl(position.entry,candle.close());
        if(favorable.compareTo(risk.multiply(stopPolicy.activationR(),MC))<0)return;
        if(stopPolicy.breakEven()) {
            tightenAfterClose(costAdjustedBreakEven(position),candle.close());
            return;
        }
        if(stopPolicy.atrTrailing()) {
            BigDecimal value=atr(history,history.size(),r.parameters().atrPeriod());
            if(value==null||value.signum()<=0)return;
            BigDecimal delta=value.multiply(stopPolicy.trailingAtrMultiplier(),MC);
            BigDecimal raw=shortSide
                    ?position.minObservedPrice.add(delta)
                    :position.maxObservedPrice.subtract(delta);
            tightenAfterClose(roundStop(raw),candle.close());
        }
    }

    /** On the final completed bar, settle funding/trailing without emitting an unfillable signal. */
    void settleFinal(MarketCandle candle,List<MarketCandle> history) {
        close(candle,null,history);
    }

    /** Simulation only; expected Binance-style three settlements per completed UTC day. */
    static int fundingOccurrences(MarketCandle candle) {
        if (candle.interval().millis() >= EIGHT_HOURS)
            return Math.toIntExact(candle.interval().millis()/EIGHT_HOURS);
        return candle.closeTime()%EIGHT_HOURS==EIGHT_HOURS-1 ? 1 : 0;
    }
    private void updateDrawdown(BigDecimal lastPrice) {
        BigDecimal marked=cash;
        if(position!=null)marked=marked.add(directionalPnl(position.entry,lastPrice)
                .multiply(position.qty,MC));
        peak=peak.max(marked);
        if(peak.signum()>0)
            worstDrawdown=worstDrawdown.max(peak.subtract(marked)
                    .divide(peak,MC).multiply(HUNDRED));
    }
    Summary summary(MarketCandle last) {
        BigDecimal marked=cash;
        if(position!=null)
            marked=marked.add(directionalPnl(position.entry,last.close())
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
