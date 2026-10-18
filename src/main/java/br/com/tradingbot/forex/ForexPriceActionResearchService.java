package br.com.tradingbot.forex;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * First FX-specific SHORT research baseline, never a Binance trading strategy.
 * Explicit BID on sell, ASK on buy to close, USD per EUR unit, no funding
 * from Binance. Forex rollover/swap is intentionally NOT modelled and must
 * be added before any production/live-use claim. No order gateway is injected.
 *
 * Rules are newly labeled FOREX_PA_V1; the Binance volume/taker filters cannot
 * be reused faithfully on OTC quotes. A broker-specific tick-volume MIDAS
 * trend approximation is optional and is NOT the original retest strategy.
 */
@Service
public final class ForexPriceActionResearchService {
    private static final MathContext MC=MathContext.DECIMAL64;
    private static final BigDecimal PIP=new BigDecimal("0.0001");
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    private static final BigDecimal LOT=new BigDecimal("100000");
    private final ForexHistoricalFeed feed;
    public ForexPriceActionResearchService(ForexHistoricalFeed feed) {
        this.feed=Objects.requireNonNull(feed);
    }

    public record Request(
            String pair,long startOpenUtc,long lastOpenUtc,
            BigDecimal capitalUsd,BigDecimal riskPerTradePct,BigDecimal dailyStopPct,
            int leverage,BigDecimal commissionUsdPer100kPerSide,
            BigDecimal slippagePipsPerSide,BigDecimal stopBufferPips,
            BigDecimal minUnits,BigDecimal unitStep,int maxHoldingBars,
            boolean tickVolumeMidasProxy) {
        public Request {
            if(!CsvForexHistoricalFeed.USD_QUOTE_PAIRS.contains(pair))
                throw new IllegalArgumentException("Pair must have USD quote (EURUSD etc.)");
            if(startOpenUtc<0 || lastOpenUtc<=startOpenUtc
                    ||startOpenUtc%ForexBar.FIFTEEN_MINUTES!=0
                    ||lastOpenUtc%ForexBar.FIFTEEN_MINUTES!=0)
                throw new IllegalArgumentException("Specify aligned UTC start/last 15m candle open times");
            positive(capitalUsd,"capitalUsd");
            positive(riskPerTradePct,"riskPerTradePct");
            positive(dailyStopPct,"dailyStopPct");
            if(riskPerTradePct.compareTo(HUNDRED)>0 ||dailyStopPct.compareTo(HUNDRED)>0)
                throw new IllegalArgumentException("Risk percentage cannot exceed 100");
            if(leverage<1||leverage>100)
                throw new IllegalArgumentException("Explicit leverage 1..100 required");
            nonnegative(commissionUsdPer100kPerSide,"commissionUsdPer100kPerSide");
            nonnegative(slippagePipsPerSide,"slippagePipsPerSide");
            nonnegative(stopBufferPips,"stopBufferPips");
            positive(minUnits,"minUnits");
            positive(unitStep,"unitStep");
            if(maxHoldingBars<1||maxHoldingBars>200)
                throw new IllegalArgumentException("maxHoldingBars must be 1..200");
        }
    }
    public record Trade(long entryUtc,long exitUtc,BigDecimal entryBid,
            BigDecimal exitAsk,BigDecimal stopAtEntry,
            BigDecimal baseCurrencyUnits,BigDecimal netUsd,
            BigDecimal feesUsd,String reason) {}
    public record Report(String pair,String market,String strategy,
            String dataSource,String volumeMeaning,String quoteModel,
            long firstOpenUtc,long lastOpenUtc,int candles,int eligibleSignals,
            int blockedEntries,int winningTrades,BigDecimal startingUsd,
            BigDecimal finalMarkedEquityUsd,BigDecimal realizedUsd,
            BigDecimal feesUsd,BigDecimal grossWinnersUsd,
            BigDecimal grossLosersUsd,BigDecimal worstDrawdownPct,
            boolean openAtEnd,List<Trade> trades,String limitations) {}

    private static void positive(BigDecimal v,String name) {
        Objects.requireNonNull(v,name);
        if(v.signum()<=0)throw new IllegalArgumentException(name+" must be positive");
    }
    private static void nonnegative(BigDecimal v,String name) {
        Objects.requireNonNull(v,name);
        if(v.signum()<0)throw new IllegalArgumentException(name+" must be nonnegative");
    }
    private static BigDecimal pct(BigDecimal n) {return n.divide(HUNDRED,MC);}
    private static BigDecimal usdFee(BigDecimal units,Request r) {
        return units.multiply(r.commissionUsdPer100kPerSide(),MC).divide(LOT,MC);
    }
    private static BigDecimal slippage(Request r) {
        return PIP.multiply(r.slippagePipsPerSide(),MC);
    }
    private static BigDecimal floorUnits(BigDecimal n,BigDecimal step) {
        return n.divide(step,0,RoundingMode.FLOOR).multiply(step);
    }
    private static BigDecimal ema(List<BigDecimal> prices,int end,int period) {
        if(end<period)return null;
        BigDecimal value=BigDecimal.ZERO;
        for(int k=0;k<period;k++)value=value.add(prices.get(k),MC);
        value=value.divide(BigDecimal.valueOf(period),MC);
        BigDecimal alpha=BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period+1),MC);
        for(int k=period;k<end;k++)
            value=value.add(alpha.multiply(prices.get(k).subtract(value),MC),MC);
        return value;
    }
    // End-index is exclusive. All swing confirmations use 2 bars on the RIGHT.
    private static boolean confirmedPivot(List<ForexBar> bars,int pivot,int last,
            boolean high) {
        if(pivot<2 || pivot+2>last)return false;
        BigDecimal target=high?bars.get(pivot).askHigh():bars.get(pivot).bidLow();
        for(int j=pivot-2;j<=pivot+2;j++) {
            if(j==pivot)continue;
            BigDecimal adjacent=high?bars.get(j).askHigh():bars.get(j).bidLow();
            if(high?target.compareTo(adjacent)<=0:target.compareTo(adjacent)>=0)
                return false;
        }
        return true;
    }
    private static final class PivotHistory {
        private BigDecimal highPrevious,highCurrent,lowPrevious,lowCurrent;
        private int lastHigh=-1;
        void observe(List<ForexBar> bars,int i) {
            int p=i-2;
            if(confirmedPivot(bars,p,i,true)) {
                highPrevious=highCurrent;
                highCurrent=bars.get(p).askHigh();
                lastHigh=p;
            }
            if(confirmedPivot(bars,p,i,false)) {
                lowPrevious=lowCurrent;
                lowCurrent=bars.get(p).bidLow();
            }
        }
        boolean falling() {
            return highPrevious!=null&&highCurrent!=null
                    &&lowPrevious!=null&&lowCurrent!=null
                    &&highCurrent.compareTo(highPrevious)<0
                    &&lowCurrent.compareTo(lowPrevious)<0;
        }
    }
    private static boolean dailyDown(List<BigDecimal> finishedDays) {
        if(finishedDays.size()<23)return false;
        var now=ema(finishedDays,finishedDays.size(),21);
        var previous=ema(finishedDays,finishedDays.size()-1,21);
        return now.compareTo(previous)<0;
    }
    private static BigDecimal midasTickProxy(List<ForexBar> bars,int anchor,int index) {
        if(anchor<0)return null;
        BigDecimal numerator=BigDecimal.ZERO,weight=BigDecimal.ZERO;
        for(int j=anchor;j<=index;j++) {
            ForexBar bar=bars.get(j);
            if(bar.tickVolume()==null||bar.tickVolume()<=0)
                return null;
            BigDecimal w=BigDecimal.valueOf(bar.tickVolume());
            var midpoint=bar.bidHigh().add(bar.bidLow(),MC)
                    .divide(BigDecimal.valueOf(2),MC);
            numerator=numerator.add(midpoint.multiply(w,MC),MC);
            weight=weight.add(w,MC);
        }
        return weight.signum()>0?numerator.divide(weight,MC):null;
    }
    private record Signal(BigDecimal stop) {}
    private static Signal shortSignal(List<ForexBar> bars,int i,
            List<BigDecimal> closes,PivotHistory structure,
            boolean dailyDown,Request r) {
        if(!dailyDown || !structure.falling()||i<80)return null;
        BigDecimal ema9=ema(closes,closes.size(),9),
                ema21=ema(closes,closes.size(),21),
                prev21=ema(closes,closes.size()-1,21);
        if(ema9==null||ema21==null||prev21==null
                ||ema9.compareTo(ema21)>=0 ||ema21.compareTo(prev21)>=0)
            return null;
        ForexBar current=bars.get(i),previous=bars.get(i-1);
        BigDecimal body=current.bidOpen().subtract(current.bidClose());
        BigDecimal span=current.bidHigh().subtract(current.bidLow());
        if(body.signum()<=0||span.signum()<=0 ||
                body.divide(span,MC).compareTo(new BigDecimal("0.55"))<0
                ||current.bidClose().compareTo(previous.bidLow())>=0)
            return null;
        if(r.tickVolumeMidasProxy()) {
            var curve=midasTickProxy(bars,structure.lastHigh,i);
            if(curve==null||current.bidClose().compareTo(curve)>=0)
                return null;
        }
        BigDecimal tolerance=PIP.multiply(new BigDecimal("6"));
        for(int k=3;k<=5;k++) {
            int from=i-k;
            if(from-k<0)continue;
            if(bars.get(i-1).bidClose().compareTo(bars.get(from).bidOpen())<=0)
                continue;
            BigDecimal bounceHigh=bars.get(from).askHigh();
            BigDecimal recentBodies=BigDecimal.ZERO,olderBodies=BigDecimal.ZERO;
            for(int j=from;j<i;j++) {
                ForexBar candle=bars.get(j);
                bounceHigh=bounceHigh.max(candle.askHigh());
                recentBodies=recentBodies.add(candle.bidClose()
                        .subtract(candle.bidOpen()).abs(),MC);
            }
            for(int j=from-k;j<from;j++) {
                ForexBar candle=bars.get(j);
                olderBodies=olderBodies.add(candle.bidClose()
                        .subtract(candle.bidOpen()).abs(),MC);
            }
            if(olderBodies.signum()<=0 ||recentBodies.compareTo(
                    olderBodies.multiply(new BigDecimal("0.85"),MC))>=0)
                continue;
            BigDecimal lower=ema9.min(ema21).subtract(tolerance),
                    upper=ema9.max(ema21).add(tolerance);
            if(bounceHigh.compareTo(lower)<0 ||bounceHigh.compareTo(upper)>0)
                continue;
            BigDecimal stop=bounceHigh.max(current.askHigh())
                    .add(PIP.multiply(r.stopBufferPips(),MC),MC);
            if(stop.compareTo(current.bidClose())<=0)continue;
            return new Signal(stop);
        }
        return null;
    }
    private static final class Position {
        long entryAt;
        BigDecimal entry,stop,initialStop,units,entryFee;
        int barsHeld;
        Position(long at,BigDecimal entry,BigDecimal stop,BigDecimal units,BigDecimal fee) {
            entryAt=at;this.entry=entry;this.stop=stop;initialStop=stop;
            this.units=units;this.entryFee=fee;
        }
    }
    private static Trade finish(Position position,long at,BigDecimal rawAsk,
            Request r,String reason) {
        BigDecimal exit=rawAsk.add(slippage(r),MC);
        BigDecimal exitFee=usdFee(position.units,r);
        BigDecimal net=position.entry.subtract(exit,MC)
                .multiply(position.units,MC)
                .subtract(position.entryFee,MC).subtract(exitFee,MC);
        return new Trade(position.entryAt,at,position.entry,exit,
                position.initialStop,position.units,net,
                position.entryFee.add(exitFee,MC),reason);
    }

    public Report backtest(Request r) {
        List<ForexBar> bars=feed.closed15m(r.pair());
        if(r.tickVolumeMidasProxy()&&bars.stream().anyMatch(
                bar->bar.tickVolume()==null ||bar.tickVolume()<=0))
            throw new IllegalArgumentException("All candles need positive broker tick_volume for proxy MIDAS");
        if(bars.getFirst().openTime()>r.startOpenUtc()
                ||bars.getLast().openTime()<r.lastOpenUtc())
            throw new IllegalArgumentException("CSV does not cover entire requested backtest period");
        BigDecimal cash=r.capitalUsd(),fees=BigDecimal.ZERO,
                peak=cash,worstDrawdown=BigDecimal.ZERO,
                winners=BigDecimal.ZERO,losers=BigDecimal.ZERO,
                dailyPnl=BigDecimal.ZERO;
        int tradesWon=0,signals=0,blocked=0;
        long day=Long.MIN_VALUE;
        var history=new ArrayList<BigDecimal>();
        var dailyCloses=new ArrayList<BigDecimal>();
        var swings=new PivotHistory();
        LocalDate currentDate=null;
        BigDecimal dateLastClose=null;
        Position position=null;
        Signal pending=null;
        boolean exitAtNextOpen=false;
        var trades=new ArrayList<Trade>();
        long first=r.startOpenUtc(),last=r.lastOpenUtc();
        for(int i=0;i<bars.size();i++) {
            ForexBar bar=bars.get(i);
            LocalDate date=Instant.ofEpochMilli(bar.openTime()).atZone(
                    ZoneOffset.UTC).toLocalDate();
            if(currentDate!=null&&!date.equals(currentDate))
                dailyCloses.add(dateLastClose);
            currentDate=date;dateLastClose=bar.bidClose();
            history.add(bar.bidClose());
            swings.observe(bars,i);
            if(bar.openTime()<first)continue;
            if(bar.openTime()>last)break;
            long barDay=Math.floorDiv(bar.openTime(),86_400_000L);
            if(barDay!=day) {day=barDay;dailyPnl=BigDecimal.ZERO;}
            // A MAX_HOLD exit decided at the last CLOSE executes only NOW at
            // the next OPEN. Never retroactively trade a known candle close.
            if(exitAtNextOpen&&position!=null) {
                Trade t=finish(position,bar.openTime(),bar.askOpen(),r,"MAX_HOLD_NEXT_OPEN");
                cash=cash.add(t.netUsd().add(position.entryFee,MC),MC);
                fees=fees.add(t.feesUsd().subtract(position.entryFee,MC),MC);
                dailyPnl=dailyPnl.add(t.netUsd(),MC);
                if(t.netUsd().signum()>0) {tradesWon++;winners=winners.add(t.netUsd(),MC);}
                else losers=losers.add(t.netUsd().abs(),MC);
                trades.add(t);
                position=null;
            }
            exitAtNextOpen=false;
            // Previous-bar entries also fill only on THIS bar OPEN.
            if(pending!=null &&position==null) {
                BigDecimal fill=bar.bidOpen().subtract(slippage(r),MC);
                BigDecimal stop=pending.stop();
                BigDecimal feeUnit=r.commissionUsdPer100kPerSide().multiply(
                        BigDecimal.valueOf(2),MC).divide(LOT,MC);
                BigDecimal riskPerUnit=stop.subtract(fill).add(slippage(r),MC)
                        .add(feeUnit,MC);
                BigDecimal riskBudget=cash.multiply(pct(r.riskPerTradePct()),MC);
                BigDecimal allowedDaily=r.capitalUsd().multiply(pct(r.dailyStopPct()),MC);
                if(fill.signum()<=0 ||riskPerUnit.signum()<=0 ||
                        dailyPnl.compareTo(allowedDaily.negate())<=0)blocked++;
                else {
                    BigDecimal units=floorUnits(riskBudget.divide(riskPerUnit,MC)
                            .min(cash.multiply(BigDecimal.valueOf(r.leverage()),MC)
                                    .divide(bar.askOpen(),MC)),r.unitStep());
                    if(units.compareTo(r.minUnits())<0)blocked++;
                    else {
                        BigDecimal fee=usdFee(units,r);
                        cash=cash.subtract(fee,MC);
                        fees=fees.add(fee,MC);
                        position=new Position(bar.openTime(),fill,stop,units,fee);
                    }
                }
            }
            pending=null;
            if(position!=null) {
                position.barsHeld++;
                boolean stopHit=bar.askHigh().compareTo(position.stop)>=0;
                if(stopHit) {
                    Trade t=finish(position,bar.openTime(),
                            position.stop.max(bar.askOpen()),r,"STOP");
                    cash=cash.add(t.netUsd().add(position.entryFee,MC),MC);
                    fees=fees.add(t.feesUsd().subtract(position.entryFee,MC),MC);
                    dailyPnl=dailyPnl.add(t.netUsd(),MC);
                    if(t.netUsd().signum()>0) {tradesWon++;winners=winners.add(t.netUsd(),MC);}
                    else losers=losers.add(t.netUsd().abs(),MC);
                    trades.add(t);
                    position=null;
                } else if(position.barsHeld>=r.maxHoldingBars()) {
                    exitAtNextOpen=true;
                }
            }
            BigDecimal marked=cash;
            if(position!=null) {
                marked=marked.add(position.entry.subtract(bar.askClose())
                        .multiply(position.units,MC),MC)
                        .subtract(usdFee(position.units,r),MC);
                // A causally confirmed swing high can tighten the SHORT stop.
                if(swings.highCurrent!=null
                        &&swings.highCurrent.compareTo(position.stop)<0
                        &&swings.highCurrent.compareTo(bar.askClose())>0)
                    position.stop=swings.highCurrent;
            }
            peak=peak.max(marked);
            if(peak.signum()>0)
                worstDrawdown=worstDrawdown.max(peak.subtract(marked,MC)
                        .multiply(HUNDRED,MC).divide(peak,MC));
            if(bar.openTime()==last)break;
            // Signal generated only after the close; next bar fill is optional
            // and is suppressed if an old position is still open.
            if(position==null &&i+1<bars.size()) {
                var signal=shortSignal(bars,i,history,swings,
                        dailyDown(dailyCloses),r);
                if(signal!=null){
                    signals++;
                    ForexBar next=bars.get(i+1);
                    if(next.openTime()==bar.openTime()+ForexBar.FIFTEEN_MINUTES)
                        pending=signal; // no new entry over closed FX weekend
                }
            }
        }
        // An open final-bar position is marked at ASK; it is not a fictitious
        // realized close. Unpaid exit commission is reserved in marked equity.
        BigDecimal finalMarked=cash;
        if(position!=null) {
            ForexBar lastBar=bars.stream()
                    .filter(b->b.openTime()==last).findFirst().orElseThrow();
            finalMarked=finalMarked.add(position.entry.subtract(
                    lastBar.askClose(),MC).multiply(position.units,MC),MC)
                    .subtract(usdFee(position.units,r),MC);
        }
        return new Report(r.pair(),"FOREX_OTC_SPOT",
                r.tickVolumeMidasProxy()?"FOREX_PA_V1_TICK_VOLUME_MIDAS_PROXY_TREND":
                        "FOREX_PA_V1_SHORT_PRICE_ONLY",
                "CSV_BROKER_QUOTES","TICK_VOLUME_IS_NOT_TRADE_VOLUME_OR_TAKER_VOLUME",
                "SELL_BID_BUY_ASK_WITH_EXPLICIT_SLIPPAGE",first,last,
                (int)bars.stream().filter(b->b.openTime()>=first&&b.openTime()<=last).count(),
                signals,blocked,tradesWon,r.capitalUsd(),finalMarked,
                cash.subtract(r.capitalUsd(),MC),fees,winners,losers,
                worstDrawdown,position!=null,List.copyOf(trades),
                "Research only. Forex spot trading is OTC; broker prices differ. "
                +"No historical swap/rollover, partial-fill, margin call, "
                +"stop execution microstructure or quote-latency model. "
                +"Daily session uses UTC calendar and differs from 17:00 New York rollover. "
                +"New Forex PA hypothesis, NOT the unchanged BTC MIDAS/volume algorithm. "
                +"No live trading or broker order API.");
    }
}
