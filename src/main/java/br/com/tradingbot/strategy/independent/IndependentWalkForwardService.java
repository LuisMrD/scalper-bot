package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.CandleInterval;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Frozen-parameter OOS portfolio comparison. Every window has independent
 * starting capital, and the user must supply explicit nonoverlapping cutoffs.
 * No optimization/training occurs inside the reported holdout windows.
 */
@Service
public class IndependentWalkForwardService {
    private static final MathContext MC=MathContext.DECIMAL64;
    private final IndependentResearchService backtests;
    public IndependentWalkForwardService(IndependentResearchService backtests) {
        this.backtests=backtests;
    }
    public record Request(List<IndependentResearchRequest> windows) {}
    public record ArmScore(String arm,int windows,int trades,int wins,
            BigDecimal sumRealizedNetUsdt,BigDecimal sumMarkedPnlUsdt,
            BigDecimal profitFactor,BigDecimal netExpectancyPerTradeUsdt,
            BigDecimal worstWindowDrawdownPct) {}
    public record Report(String methodology,List<Long> persistedRunIds,
            List<IndependentResearchService.Report> windows,
            List<ArmScore> aggregate) {}

    public Report compare(Request request) {
        if(request==null||request.windows()==null||request.windows().size()<2
                ||request.windows().size()>24)
            throw new IllegalArgumentException("Explicitly define 2..24 OOS windows");
        var windows=new ArrayList<>(request.windows());
        windows.sort(Comparator.comparing(IndependentResearchRequest::asOf));
        var first=windows.getFirst();
        for(int i=0;i<windows.size();i++) {
            var w=windows.get(i);
            if(!w.symbol().equals(first.symbol())
                    ||!w.entryInterval().equals(first.entryInterval())
                    ||!w.trendInterval().equals(first.trendInterval())
                    ||!w.parameters().equals(first.parameters())
                    ||w.steps()!=first.steps()
                    ||!w.capitalUsdt().equals(first.capitalUsdt())
                    ||!w.riskPerTradePct().equals(first.riskPerTradePct())
                    ||!w.dailyStopPct().equals(first.dailyStopPct())
                    ||w.leverage()!=first.leverage()
                    ||!w.feePerSidePct().equals(first.feePerSidePct())
                    ||!w.slippagePerSidePct().equals(first.slippagePerSidePct())
                    ||!w.spreadPct().equals(first.spreadPct())
                    ||!w.assumedFundingPer8hPct().equals(first.assumedFundingPer8hPct())
                    ||!w.assumedMaintenanceMarginPct().equals(first.assumedMaintenanceMarginPct())
                    ||!w.priceTick().equals(first.priceTick())
                    ||!w.quantityStep().equals(first.quantityStep())
                    ||!w.minimumQuantity().equals(first.minimumQuantity())
                    ||!w.minimumNotionalUsdt().equals(first.minimumNotionalUsdt())
                    ||!w.maxCostToInitialRiskPct().equals(first.maxCostToInitialRiskPct())
                    ||w.maxHoldingCandles()!=first.maxHoldingCandles())
                throw new IllegalArgumentException("OOS requires identical frozen parameters");
            if(i>0 && windows.get(i-1).asOf()
                    +w.steps()*CandleInterval.parse(first.entryInterval()).millis()>w.asOf())
                throw new IllegalArgumentException("OOS trade windows overlap");
        }
        var reports=new ArrayList<IndependentResearchService.Report>();
        var ids=new ArrayList<Long>();
        for(var w:windows) {
            var report=backtests.backtest(w);
            reports.add(report);
            ids.add(report.runId());
        }
        var scores=new ArrayList<ArmScore>();
        for(var arm:List.of("TREND_PULLBACK","TREND_PULLBACK_MIXED",
                "TREND_PULLBACK_SHORT","TREND_PULLBACK_MIXED_SHORT")) {
            int tradeCount=0,wins=0;
            BigDecimal realized=BigDecimal.ZERO,marked=BigDecimal.ZERO,
                    grossWins=BigDecimal.ZERO,grossLoss=BigDecimal.ZERO,
                    dd=BigDecimal.ZERO;
            for(int i=0;i<reports.size();i++) {
                var result=reports.get(i).portfolios().get(arm);
                tradeCount+=result.closedTrades();
                wins+=result.wins();
                realized=realized.add(result.realizedNetUsdt());
                marked=marked.add(result.finalMarkedEquity().subtract(
                        windows.get(i).capitalUsdt()));
                dd=dd.max(result.worstDrawdownPct());
                for(var t:result.trades()) {
                    if(t.netPnlUsdt().signum()>0)grossWins=grossWins.add(t.netPnlUsdt());
                    else grossLoss=grossLoss.subtract(t.netPnlUsdt());
                }
            }
            scores.add(new ArmScore(arm,reports.size(),tradeCount,wins,realized,
                    marked,grossLoss.signum()>0
                            ?grossWins.divide(grossLoss,MC):null,
                    tradeCount>0?realized.divide(BigDecimal.valueOf(tradeCount),MC):null,
                    dd));
        }
        return new Report("FROZEN_PARAMS; DISJOINT_OOS_WINDOWS; NO_TRAINING_OR_LOOKAHEAD; "
                +"PAPER_ONLY; PORTFOLIO_RESETS_PER_WINDOW; COSTS_INCLUDED",
                List.copyOf(ids),List.copyOf(reports),List.copyOf(scores));
    }
}
