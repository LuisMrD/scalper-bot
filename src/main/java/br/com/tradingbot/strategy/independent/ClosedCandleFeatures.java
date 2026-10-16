package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/** Pure closed-bar computations. The caller supplies an end EXCLUSIVE index. */
final class ClosedCandleFeatures {
    static final MathContext MC = MathContext.DECIMAL64;
    private ClosedCandleFeatures() {}

    static void check(List<MarketCandle> tape) {
        if (tape.isEmpty()) throw new IllegalArgumentException("Missing candles");
        MarketCandle first = tape.getFirst();
        if (first.interval() != CandleInterval.M5 && first.interval() != CandleInterval.M15
                && first.interval() != CandleInterval.D1)
            throw new IllegalArgumentException("Independent setups support only closed 5m, 15m or 1d candles");
        for (int i=0; i<tape.size(); i++) {
            var c=tape.get(i);
            if (!c.closed() || !c.symbol().equals(first.symbol())
                    || c.interval() != first.interval()
                    || (i>0 && c.closeTime()!=tape.get(i-1).closeTime()+first.interval().millis()))
                throw new IllegalArgumentException("Missing/unclosed/noncausal primary candle");
        }
    }
    static BigDecimal ema(List<MarketCandle> tape, int end, int period) {
        if (end < period || period < 2) return null;
        BigDecimal sum=BigDecimal.ZERO;
        for(int i=0;i<period;i++) sum=sum.add(tape.get(i).close());
        BigDecimal value=sum.divide(BigDecimal.valueOf(period),MC);
        BigDecimal alpha=BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period+1L),MC);
        for(int i=period;i<end;i++) {
            value=value.add(alpha.multiply(tape.get(i).close().subtract(value),MC),MC);
        }
        return value;
    }
    static BigDecimal meanRange(List<MarketCandle> tape, int start, int end) {
        BigDecimal sum=BigDecimal.ZERO;
        for(int i=start;i<end;i++) sum=sum.add(span(tape.get(i)));
        return sum.divide(BigDecimal.valueOf(end-start),MC);
    }
    static BigDecimal meanVolume(List<MarketCandle> tape,int start,int end) {
        BigDecimal sum=BigDecimal.ZERO;
        for(int i=start;i<end;i++) sum=sum.add(tape.get(i).quoteVolume());
        return sum.divide(BigDecimal.valueOf(end-start),MC);
    }
    static BigDecimal atr(List<MarketCandle> tape, int end, int period) {
        if(end < period+1) return null;
        BigDecimal sum=BigDecimal.ZERO;
        for(int i=end-period;i<end;i++) {
            var c=tape.get(i);
            BigDecimal prev=tape.get(i-1).close();
            BigDecimal tr=span(c).max(c.high().subtract(prev).abs())
                    .max(c.low().subtract(prev).abs());
            sum=sum.add(tr);
        }
        return sum.divide(BigDecimal.valueOf(period),MC);
    }
    static BigDecimal span(MarketCandle c) { return c.high().subtract(c.low()); }
    static BigDecimal body(MarketCandle c) { return c.close().subtract(c.open()).abs(); }
    static BigDecimal ratio(BigDecimal numerator,BigDecimal denominator) {
        return denominator.signum()==0?BigDecimal.ZERO:numerator.divide(denominator,MC);
    }
    record Pivot(int index,long confirmedAt,BigDecimal price,boolean high) {}
    static List<Pivot> pivots(List<MarketCandle> tape,int end,int left,int right) {
        var out=new ArrayList<Pivot>();
        for(int i=left;i+right<end;i++) {
            boolean high=true,low=true;
            for(int j=i-left;j<=i+right;j++) {
                if(j==i)continue;
                if(tape.get(i).high().compareTo(tape.get(j).high())<=0) high=false;
                if(tape.get(i).low().compareTo(tape.get(j).low())>=0) low=false;
            }
            long confirmed=tape.get(i+right).closeTime();
            if(high)out.add(new Pivot(i,confirmed,tape.get(i).high(),true));
            if(low)out.add(new Pivot(i,confirmed,tape.get(i).low(),false));
        }
        return out;
    }
    record Structure(boolean established,BigDecimal lastLow,BigDecimal lastHigh) {}
    static Structure risingStructure(List<MarketCandle> tape,int end,
            IndependentSetupParameters p) {
        var pivots=pivots(tape,end,p.pivotLeft(),p.pivotRight());
        var highs=pivots.stream().filter(Pivot::high).toList();
        var lows=pivots.stream().filter(x->!x.high()).toList();
        if(highs.size()<2||lows.size()<2)
            return new Structure(false,null,null);
        var h1=highs.get(highs.size()-2);
        var h2=highs.getLast();
        var l1=lows.get(lows.size()-2);
        var l2=lows.getLast();
        return new Structure(h2.price().compareTo(h1.price())>0
                    && l2.price().compareTo(l1.price())>0,
                l2.price(),h2.price());
    }
    static Structure fallingStructure(List<MarketCandle> tape,int end,
            IndependentSetupParameters p) {
        var pivots=pivots(tape,end,p.pivotLeft(),p.pivotRight());
        var highs=pivots.stream().filter(Pivot::high).toList();
        var lows=pivots.stream().filter(x->!x.high()).toList();
        if(highs.size()<2||lows.size()<2)
            return new Structure(false,null,null);
        var h1=highs.get(highs.size()-2);
        var h2=highs.getLast();
        var l1=lows.get(lows.size()-2);
        var l2=lows.getLast();
        return new Structure(h2.price().compareTo(h1.price())<0
                    && l2.price().compareTo(l1.price())<0,
                l2.price(),h2.price());
    }
    static boolean bearishStrong(MarketCandle c,BigDecimal threshold) {
        return c.close().compareTo(c.open())<0
                && ratio(body(c),span(c)).compareTo(threshold)>=0;
    }
    static boolean bearishShootingStar(MarketCandle c,BigDecimal wickBodyRatio) {
        return c.close().compareTo(c.open())<0
                && c.high().subtract(c.open().max(c.close()))
                    .compareTo(body(c).multiply(wickBodyRatio))>=0
                && c.close().subtract(c.low())
                    .compareTo(body(c))<=0;
    }
    static boolean bullishStrong(MarketCandle c,BigDecimal threshold) {
        return c.close().compareTo(c.open())>0
                && ratio(body(c),span(c)).compareTo(threshold)>=0;
    }
    static boolean bullishHammer(MarketCandle c,BigDecimal wickBodyRatio) {
        return c.close().compareTo(c.open())>0
                && c.open().min(c.close()).subtract(c.low())
                    .compareTo(body(c).multiply(wickBodyRatio))>=0
                && c.high().subtract(c.close())
                    .compareTo(body(c))<=0;
    }
}
