package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.List;

/** Results are simulated USDT amounts, never wallet balances from Binance. */
public record BacktestResult(String symbol, List<String> intervals,
        long fromCandleOpenTime, long throughCandleCloseTime,
        int replayedCandles, int decisionCandles, String fillModel,
        PortfolioResult javaPortfolio, PortfolioResult lunaPortfolio,
        boolean comparativeResultsAvailable) {
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
