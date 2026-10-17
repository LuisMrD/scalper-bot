package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One chronological paper account per frozen policy, with independent signal
 * filters per direction but shared exposure, realized PnL and drawdown.
 * Reuses ORIGINAL protective stop, pivots, fills and DYNAMIC_TREND_60.
 */
final class SharedEntryPaperPortfolio {
    record SideTrade(String side, IndependentPaperPortfolio.Trade trade) {}
    record Summary(String arm, BigDecimal initialCapital, BigDecimal finalMarkedEquity,
            BigDecimal realizedNetUsdt, BigDecimal totalFeesUsdt, BigDecimal totalFundingUsdt,
            int closedTrades, int wins, int acceptedSignals, int blockedEntries,
            BigDecimal worstDrawdownPct, boolean openPosition,
            List<SideTrade> trades, Map<String,Integer> rejectionCounts) {}

    private final SharedEntryBookPolicy policy;
    private final SharedPaperWallet wallet;
    private final IndependentPaperPortfolio longBook;
    private final IndependentPaperPortfolio shortBook;

    SharedEntryPaperPortfolio(SharedEntryBookPolicy policy, IndependentResearchRequest request) {
        this.policy = policy;
        wallet = new SharedPaperWallet(request.capitalUsdt());
        longBook = new IndependentPaperPortfolio("TREND_PULLBACK_MIXED__" + policy.name(),
                request, HoldingExitPolicy.DYNAMIC_TREND_60, wallet);
        shortBook = new IndependentPaperPortfolio("TREND_PULLBACK_MIXED_SHORT__" + policy.name(),
                request, HoldingExitPolicy.DYNAMIC_TREND_60, wallet);
    }
    SharedEntryBookPolicy policy() { return policy; }
    void begin(MarketCandle candle) {
        wallet.beginDay(candle.openTime());
        // Explicit deterministic arbitration for simultaneous next-open fills.
        longBook.begin(candle);
        shortBook.begin(candle);
    }
    void close(MarketCandle candle, IndependentSetupSignal signal,
            List<MarketCandle> history, WeeklyEmaDirection.Direction higher) {
        var book = signal.side() == IndependentSetupSignal.Side.SHORT ? shortBook : longBook;
        book.count(signal);
        book.close(candle, signal, history, higher);
    }
    void settleFinal(MarketCandle candle, List<MarketCandle> history) {
        longBook.settleFinal(candle, history);
        shortBook.settleFinal(candle, history);
        wallet.mark(candle.close());
    }
    void mark(MarketCandle candle) { wallet.mark(candle.close()); }
    Summary summary(MarketCandle last) {
        wallet.mark(last.close());
        var longs=longBook.summary(last);
        var shorts=shortBook.summary(last);
        var all = new ArrayList<SideTrade>();
        for (var trade : longs.trades()) all.add(new SideTrade("LONG",trade));
        for (var trade : shorts.trades()) all.add(new SideTrade("SHORT",trade));
        all.sort(Comparator.comparingLong((SideTrade item) -> item.trade().entryOpenTime())
                .thenComparing(SideTrade::side));
        var rejection = new LinkedHashMap<String,Integer>();
        longs.rejectionCounts().forEach((reason,count)->rejection.put("LONG:"+reason,count));
        shorts.rejectionCounts().forEach((reason,count)->rejection.put("SHORT:"+reason,count));
        return new Summary(policy.name(),wallet.initialCapital(),wallet.markedEquity(),
                wallet.cash().subtract(wallet.initialCapital()),
                longs.totalFeesUsdt().add(shorts.totalFeesUsdt()),
                longs.totalFundingUsdt().add(shorts.totalFundingUsdt()),
                all.size(),(int)all.stream().filter(t->t.trade().netPnlUsdt().signum()>0).count(),
                longs.acceptedSignals()+shorts.acceptedSignals(),
                longs.blockedEntries()+shorts.blockedEntries(),
                wallet.worstDrawdownPct(),wallet.occupied(),
                List.copyOf(all),Map.copyOf(rejection));
    }
}
