package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Three frozen, independent replays: existing strategy versus range abstention
 * versus trading-range top-third condition. This is research, not live trading.
 */
@Service
public class ShortRangeComparisonService {
    private final BacktestService backtests;

    public ShortRangeComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(ShortRangePolicy policy, int tradesClosed, int winningTrades,
            int losingTrades, int shortTrades, int shortWinners, int longTrades,
            int entryFilterBlockedSignals, int cooldownBlockedSignals,
            int rangeShortBlockedSignals, BigDecimal startingEquityUsdt,
            BigDecimal netClosedUsdt, BigDecimal shortNetClosedUsdt,
            BigDecimal longNetClosedUsdt, BigDecimal markedPnlUsdt,
            BigDecimal feesUsdt, BigDecimal maxDrawdownPct, String openPositionSide) {}

    public record Trade(ShortRangePolicy policy, String side,
            long enteredAtOpenTime, long exitCandleOpenTime, String entryBreakoutState,
            String exitReason, BigDecimal initialRiskUsdt, BigDecimal netPnlUsdt,
            BigDecimal feesUsdt, EntryFilterComparisonService.EntryFeatures entryFeatures) {}

    public record Comparison(String symbol, String primaryInterval,
            long fromOpenTime, long asOfCloseTime, int replayedCandles,
            List<Summary> summaries, List<Trade> trades, String scope) {}

    public Comparison compare(BacktestRequest request) {
        if (request == null || !"SOLUSDT".equals(request.symbol()) || request.includeLuna()
                || request.asOf() == null
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Require SOLUSDT RETEST-only 3600 5m bars with 15m context and no Luna");
        }
        var summaries = new ArrayList<Summary>();
        var trades = new ArrayList<Trade>();
        Long from = null, until = null;
        Integer count = null;
        for (var arm : ShortRangePolicy.values()) {
            var replay = backtests.runShortRange(request, arm);
            var result = replay.backtest();
            var p = result.javaPortfolio();
            if (from == null) {
                from = result.fromCandleOpenTime();
                until = result.throughCandleCloseTime();
                count = result.replayedCandles();
            } else if (from != result.fromCandleOpenTime()
                    || until != result.throughCandleCloseTime()
                    || count != result.replayedCandles()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Three range scenarios must replay identical 5m tape");
            }
            BigDecimal closed = BigDecimal.ZERO, shortsPnl = BigDecimal.ZERO,
                    longsPnl = BigDecimal.ZERO;
            int shortTrades = 0, shortWins = 0, longTrades = 0, winners = 0;
            for (var t : p.trades()) {
                if (t.entryDiagnostics() == null || t.entryDiagnostics().atSignal() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Entry-time diagnostic missing in replay");
                }
                closed = closed.add(t.netPnlUsdt());
                if (t.netPnlUsdt().signum() > 0) winners++;
                if ("SHORT".equals(t.side())) {
                    shortTrades++;
                    shortsPnl = shortsPnl.add(t.netPnlUsdt());
                    if (t.netPnlUsdt().signum() > 0) shortWins++;
                } else if ("LONG".equals(t.side())) {
                    longTrades++;
                    longsPnl = longsPnl.add(t.netPnlUsdt());
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Unknown trade side " + t.side());
                }
                trades.add(new Trade(arm, t.side(), t.enteredAtOpenTime(),
                        t.exitCandleOpenTime(),
                        t.entryDiagnostics().atSignal().breakoutState().name(),
                        t.exitReason(), t.initialRiskUsdt(), t.netPnlUsdt(),
                        t.feesUsdt(), EntryFilterComparisonService.features(t.entryDiagnostics())));
            }
            if (shortTrades + longTrades != p.tradesClosed()
                    || winners != p.winningTrades()
                    || p.losingTrades() + winners > p.tradesClosed()
                    || (arm == ShortRangePolicy.CONTROL && replay.shortRangeBlockedSignals() != 0)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Range replay failed trade and control counter reconciliation");
            }
            summaries.add(new Summary(arm, p.tradesClosed(), p.winningTrades(),
                    p.losingTrades(), shortTrades, shortWins, longTrades,
                    replay.filteredSignals(), replay.reentryBlockedSignals(),
                    replay.shortRangeBlockedSignals(), p.startingEquityUsdt(),
                    closed, shortsPnl, longsPnl,
                    p.markedEquityUsdt().subtract(p.startingEquityUsdt()),
                    p.feesUsdt(), p.maxDrawdownPct(), p.openPositionSide()));
        }
        return new Comparison(request.symbol(), request.intervals().getFirst(),
                from, until, count, List.copyOf(summaries), List.copyOf(trades),
                "SIMULATION_ONLY; BLOCK_BOTH; WAIT_60_MIN; "
                + "3_FROZEN_RANGE_SHORT_POLICIES; TRAILING_30_CLOSED_15M_BARS; "
                + "INDEPENDENT_DYNAMIC_REPLAYS; NO_LUNA; NO_ORDERS");
    }
}
