package br.com.tradingbot.simulation;

import br.com.tradingbot.decision.DecisionResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Independent dynamic replay of frozen RANGE SHORT, false-breakout L2 study. */
@Service
public class L2RangeComparisonService {
    private final BacktestService backtests;

    public L2RangeComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(L2RangePolicy policy, int tradesClosed, int winningTrades,
            int losingTrades, int longTrades, int shortTrades, int shortWinners,
            int l2ClosedTrades, int l2Signals, int l2BlockedByCooldown,
            int entryFilterBlockedSignals, int cooldownBlockedSignals,
            int rangeRetestBlockedSignals, BigDecimal startingEquityUsdt,
            BigDecimal netClosedUsdt, BigDecimal longNetClosedUsdt,
            BigDecimal shortNetClosedUsdt, BigDecimal markedPnlUsdt,
            BigDecimal feesUsdt, BigDecimal maxDrawdownPct, String openPositionSide) {}

    public record Trade(L2RangePolicy policy, String side, String entrySetup,
            long enteredAtOpenTime, long exitCandleOpenTime,
            String entryBreakoutState, String exitReason,
            BigDecimal initialRiskUsdt, BigDecimal netPnlUsdt,
            BigDecimal feesUsdt, EntryFilterComparisonService.EntryFeatures entryFeatures) {}

    public record Comparison(String symbol, String primaryInterval,
            long fromOpenTime, long asOfCloseTime, int replayedCandles,
            List<Summary> summaries, List<Trade> trades, String scope) {}

    public Comparison compare(BacktestRequest request) {
        if (request == null || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L2 experiment requires SOL RETEST 5m+15m, 3600 candles, no Luna");
        }
        var summaries = new ArrayList<Summary>();
        var trades = new ArrayList<Trade>();
        Long from = null, until = null;
        Integer candles = null;
        for (L2RangePolicy arm : L2RangePolicy.values()) {
            var replay = backtests.runL2Range(request, arm);
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
                        "Three L2 arms must use the same 5m market tape");
            }
            BigDecimal closed = BigDecimal.ZERO, longPnl = BigDecimal.ZERO,
                    shortPnl = BigDecimal.ZERO;
            int longs = 0, shorts = 0, shortWins = 0, winners = 0, l2Filled = 0;
            for (var t : p.trades()) {
                var diag = t.entryDiagnostics();
                if (diag == null || diag.atSignal() == null
                        || diag.atSignal().signal() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Missing pre-entry snapshot in L2 research portfolio");
                }
                var entrySetup = diag.atSignal().signal().setup();
                if (entrySetup == DecisionResult.Setup.RANGE_FALSE_BREAK_L2) {
                    l2Filled++;
                    if (!"SHORT".equals(t.side())
                            || arm != L2RangePolicy.REPLACE_RANGE_SHORT_RETEST_WITH_L2) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "L2 setup appeared outside its permitted SHORT scenario");
                    }
                }
                closed = closed.add(t.netPnlUsdt());
                if (t.netPnlUsdt().signum() > 0) winners++;
                if ("SHORT".equals(t.side())) {
                    shorts++;
                    shortPnl = shortPnl.add(t.netPnlUsdt());
                    if (t.netPnlUsdt().signum() > 0) shortWins++;
                } else if ("LONG".equals(t.side())) {
                    longs++;
                    longPnl = longPnl.add(t.netPnlUsdt());
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Invalid trade side " + t.side());
                }
                trades.add(new Trade(arm, t.side(), entrySetup.name(),
                        t.enteredAtOpenTime(), t.exitCandleOpenTime(),
                        diag.atSignal().breakoutState().name(), t.exitReason(),
                        t.initialRiskUsdt(), t.netPnlUsdt(), t.feesUsdt(),
                        EntryFilterComparisonService.features(diag)));
            }
            if (longs + shorts != p.tradesClosed()
                    || winners != p.winningTrades()
                    || p.losingTrades() + winners > p.tradesClosed()
                    || (arm != L2RangePolicy.REPLACE_RANGE_SHORT_RETEST_WITH_L2
                        && replay.l2Signals() != 0)
                    || l2Filled > replay.l2Signals()
                    || (arm == L2RangePolicy.CONTROL
                        && replay.shortRangeBlockedSignals() != 0)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "L2 portfolio or signal counters failed reconciliation");
            }
            summaries.add(new Summary(arm, p.tradesClosed(), p.winningTrades(),
                    p.losingTrades(), longs, shorts, shortWins, l2Filled,
                    replay.l2Signals(), replay.l2BlockedByCooldown(),
                    replay.filteredSignals(), replay.reentryBlockedSignals(),
                    replay.shortRangeBlockedSignals(), p.startingEquityUsdt(),
                    closed, longPnl, shortPnl,
                    p.markedEquityUsdt().subtract(p.startingEquityUsdt()),
                    p.feesUsdt(), p.maxDrawdownPct(), p.openPositionSide()));
        }
        return new Comparison(request.symbol(), request.intervals().getFirst(),
                from, until, candles, List.copyOf(summaries), List.copyOf(trades),
                "SIMULATION_ONLY; THREE_INDEPENDENT_DYNAMIC_REPLAYS; "
                + "RETEST_BASELINE; BLOCK_BOTH; WAIT_60_MIN; "
                + "BROOKS_INSPIRED_30X15M_FAILED_BREAK_L2_3_TO_9X5M; "
                + "CONFIRMED_PIVOT_STOP; NO_LUNA; NO_ORDERS");
    }
}
