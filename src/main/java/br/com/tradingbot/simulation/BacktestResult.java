package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import java.util.List;

/** Results are simulated USDT amounts, never wallet balances from Binance. */
public record BacktestResult(String symbol, List<String> intervals,
        long fromCandleOpenTime, long throughCandleCloseTime,
        int replayedCandles, int decisionCandles, String fillModel,
        PortfolioResult javaPortfolio, PortfolioResult lunaPortfolio,
        boolean comparativeResultsAvailable,
        List<DecisionTrace> javaDecisionHistory, List<DecisionTrace> lunaDecisionHistory) {
    public BacktestResult {
        intervals = List.copyOf(intervals);
        javaDecisionHistory = List.copyOf(javaDecisionHistory);
        lunaDecisionHistory = List.copyOf(lunaDecisionHistory);
    }

    /** Uma observacao no fechamento: decisao e resultado do filtro de risco,
     *  nunca uma execucao; esta so poderia acontecer no candle seguinte. */
    public record DecisionTrace(String engine, String positionSideAtDecision,
            long candleOpenTime, long candleCloseTime,
            DecisionResult.Entry entry, DecisionResult.PositionAction positionAction,
            DecisionResult.Setup setup, DecisionResult.Reason reason,
            PriceActionSnapshot.Trend primaryTrend, List<TimeframeTrend> timeframeTrends,
            VolumeSnapshot.VolumeLevel volumeLevel,
            VolumeSnapshot.TakerBalance takerBalance,
            BigDecimal relativeVolume, BigDecimal relativeSpread,
            VolumeSnapshot.EffortResult effortResult,
            RiskPreviewResult.Action riskAction, RiskPreviewResult.Reason riskReason) {
        public DecisionTrace { timeframeTrends = List.copyOf(timeframeTrends); }
    }

    public record TimeframeTrend(String interval, PriceActionSnapshot.Trend trend,
            long asOfCloseTime) {}

    public record PortfolioResult(String engine, String status,
            BigDecimal startingEquityUsdt, BigDecimal realizedEquityUsdt,
            BigDecimal markedEquityUsdt, BigDecimal totalReturnPct,
            BigDecimal maxDrawdownPct, BigDecimal feesUsdt,
            int tradesClosed, int winningTrades, int losingTrades,
            int entriesBlocked, int entrySignals,
            String openPositionSide, List<Trade> trades) {
        public PortfolioResult { trades = List.copyOf(trades); }
    }
    public record Trade(String side, long enteredAtOpenTime, long exitCandleOpenTime,
            BigDecimal quantity, BigDecimal entryPrice, BigDecimal exitPrice,
            BigDecimal grossPnlUsdt, BigDecimal netPnlUsdt, String exitReason) {}
}
