package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Frozen 40..45 LONG-context study. No regular trading strategy changes. */
@Service
public class LongContextComparisonService {
    private final BacktestService backtests;

    public LongContextComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(LongContextPolicy policy, int tradesClosed,
            int winningTrades, int losingTrades, int longTrades, int longWinners,
            int shortTrades, int shortWinners, int entryFilterBlockedSignals,
            int cooldownBlockedSignals, int longContextBlockedSignals,
            BigDecimal startingEquityUsdt, BigDecimal netClosedUsdt,
            BigDecimal longNetClosedUsdt, BigDecimal shortNetClosedUsdt,
            BigDecimal markedPnlUsdt, BigDecimal feesUsdt,
            BigDecimal maxDrawdownPct, String openPositionSide) {}

    public record Trade(LongContextPolicy policy, String side,
            long enteredAtOpenTime, long exitCandleOpenTime,
            String entryBreakoutState, String entrySetup, String exitReason,
            BigDecimal initialRiskUsdt, BigDecimal netPnlUsdt,
            BigDecimal feesUsdt, EntryFilterComparisonService.EntryFeatures entryFeatures) {}

    public record Comparison(String symbol, String primaryInterval,
            long fromOpenTime, long asOfCloseTime, int replayedCandles,
            List<Summary> summaries, List<Trade> trades, String scope) {}

    public Comparison compare(BacktestRequest request) {
        if (request == null || !"SOLUSDT".equals(request.symbol())
                || request.includeLuna() || request.asOf() == null
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Require SOLUSDT RETEST-only, 3600 5m+15m candles, no Luna");
        }
        List<Summary> summaries = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        Long from = null, until = null;
        Integer candles = null;
        for (LongContextPolicy arm : LongContextPolicy.values()) {
            var replay = backtests.runLongContext(request, arm);
            var result = replay.backtest();
            var p = result.javaPortfolio();
            if (from == null) {
                from = result.fromCandleOpenTime();
                until = result.throughCandleCloseTime();
                candles = result.replayedCandles();
            } else if (from != result.fromCandleOpenTime()
                    || until != result.throughCandleCloseTime()
                    || candles != result.replayedCandles()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "LONG context scenarios must replay identical 5m candle tape");
            }
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal longPnl = BigDecimal.ZERO;
            BigDecimal shortPnl = BigDecimal.ZERO;
            int longCount = 0, longWins = 0, shortCount = 0, shortWins = 0, wins = 0;
            for (var trade : p.trades()) {
                var d = trade.entryDiagnostics();
                if (d == null || d.atSignal() == null || d.atSignal().signal() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Missing pre-entry snapshot for LONG context experiment");
                }
                var features = EntryFilterComparisonService.features(d);
                if (features.signalCloseTime() >= trade.enteredAtOpenTime()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Entry signal snapshot must precede simulated fill");
                }
                if (trade.netPnlUsdt().signum() > 0) wins++;
                total = total.add(trade.netPnlUsdt());
                if ("LONG".equals(trade.side())) {
                    longCount++;
                    longPnl = longPnl.add(trade.netPnlUsdt());
                    if (trade.netPnlUsdt().signum() > 0) longWins++;
                } else if ("SHORT".equals(trade.side())) {
                    shortCount++;
                    shortPnl = shortPnl.add(trade.netPnlUsdt());
                    if (trade.netPnlUsdt().signum() > 0) shortWins++;
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Unknown trade side: " + trade.side());
                }
                trades.add(new Trade(arm, trade.side(), trade.enteredAtOpenTime(),
                        trade.exitCandleOpenTime(), d.atSignal().breakoutState().name(),
                        d.atSignal().signal().setup().name(), trade.exitReason(),
                        trade.initialRiskUsdt(), trade.netPnlUsdt(),
                        trade.feesUsdt(), features));
            }
            if (longCount + shortCount != p.tradesClosed()
                    || wins != p.winningTrades()
                    || p.losingTrades() + wins > p.tradesClosed()
                    || (arm == LongContextPolicy.CONTROL
                        && replay.longContextBlockedSignals() != 0)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "LONG context trades or control counters inconsistent");
            }
            summaries.add(new Summary(arm, p.tradesClosed(), wins, p.losingTrades(),
                    longCount, longWins, shortCount, shortWins,
                    replay.filteredSignals(), replay.reentryBlockedSignals(),
                    replay.longContextBlockedSignals(), p.startingEquityUsdt(),
                    total, longPnl, shortPnl,
                    p.markedEquityUsdt().subtract(p.startingEquityUsdt()),
                    p.feesUsdt(), p.maxDrawdownPct(), p.openPositionSide()));
        }
        return new Comparison(request.symbol(), request.intervals().getFirst(),
                from, until, candles, List.copyOf(summaries), List.copyOf(trades),
                "SIMULATION_ONLY; FROZEN_3_LONG_CONTEXT_POLICIES; "
                + "BLOCK_BOTH; WAIT_60_MIN; RETEST_ONLY; "
                + "DYNAMIC_INDEPENDENT_RISK_LEDGERS; NO_LUNA; NO_ORDERS");
    }
}
