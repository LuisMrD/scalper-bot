package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pre-registered 4-way RETEST study using INDEPENDENT dynamic portfolios.
 * Blocked LONGs can free portfolio capacity; new SHORT entries are recalculated.
 */
@Service
public class LongVersusShortComparisonService {
    private final BacktestService backtests;

    public LongVersusShortComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(LongVersusShortPolicy policy, int entryFilterBlockedSignals,
            int reentryWaitBlockedSignals, int macroBlockedLongSignals,
            int shortOnlyBlockedLongSignals, int tradesClosed,
            int winningTrades, int losingTrades, int longTrades, int longWinners,
            int shortTrades, int shortWinners, BigDecimal longClosedNetPnlUsdt,
            BigDecimal shortClosedNetPnlUsdt, int entriesBlockedByRisk,
            BigDecimal startingEquityUsdt, BigDecimal netClosedUsdt,
            BigDecimal realizedEquityUsdt, BigDecimal markedEquityUsdt,
            BigDecimal markedPnlUsdt, BigDecimal maxDrawdownPct,
            BigDecimal feesUsdt, String openPositionSide) {}

    public record Trade(LongVersusShortPolicy policy, String side,
            long enteredAtOpenTime, long exitCandleOpenTime,
            String entryBreakoutState, String exitReason,
            BigDecimal initialRiskUsdt, BigDecimal netPnlUsdt,
            BigDecimal feesUsdt, EntryFilterComparisonService.EntryFeatures entryFeatures) {}

    public record Comparison(String symbol, String primaryInterval,
            long fromOpenTime, long asOfCloseTime, int replayedCandles,
            List<Summary> summaries, List<Trade> trades, String scope) {}

    public Comparison compare(BacktestRequest request) {
        if (request == null || request.includeLuna()
                || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Study requires SOLUSDT RETEST-only 3600 candles, 5m+15m, no Luna");
        }
        var summaries = new ArrayList<Summary>();
        var trades = new ArrayList<Trade>();
        Long from = null;
        Long until = null;
        Integer candles = null;
        for (LongVersusShortPolicy policy : LongVersusShortPolicy.values()) {
            var replay = backtests.runLongVersusShort(request, policy);
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
                        "Four scenarios must replay identical market periods");
            }
            var closed = BigDecimal.ZERO;
            var longPnl = BigDecimal.ZERO;
            var shortPnl = BigDecimal.ZERO;
            int longs = 0, shorts = 0, longWins = 0, shortWins = 0;
            for (var t : p.trades()) {
                var d = t.entryDiagnostics();
                if (d == null || d.atSignal() == null || d.atSignal().signal() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Closed trade is missing its pre-entry signal snapshot");
                }
                closed = closed.add(t.netPnlUsdt());
                switch (t.side()) {
                    case "LONG" -> {
                        longs++;
                        longPnl = longPnl.add(t.netPnlUsdt());
                        if (t.netPnlUsdt().signum() > 0) longWins++;
                    }
                    case "SHORT" -> {
                        shorts++;
                        shortPnl = shortPnl.add(t.netPnlUsdt());
                        if (t.netPnlUsdt().signum() > 0) shortWins++;
                    }
                    default -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Unknown trade direction " + t.side());
                }
                trades.add(new Trade(policy, t.side(), t.enteredAtOpenTime(),
                        t.exitCandleOpenTime(), d.atSignal().breakoutState().name(),
                        t.exitReason(), t.initialRiskUsdt(), t.netPnlUsdt(),
                        t.feesUsdt(), EntryFilterComparisonService.features(d)));
            }
            if (p.tradesClosed() != longs + shorts
                    || p.winningTrades() != longWins + shortWins
                    || (policy == LongVersusShortPolicy.SHORT_ONLY && longs > 0)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Trade counts or SHORT_ONLY policy integrity check failed");
            }
            summaries.add(new Summary(policy, replay.filteredSignals(),
                    replay.reentryBlockedSignals(), replay.macroBlockedSignals(),
                    replay.shortOnlyBlockedSignals(), p.tradesClosed(), p.winningTrades(),
                    p.losingTrades(), longs, longWins, shorts, shortWins,
                    longPnl, shortPnl, p.entriesBlocked(), p.startingEquityUsdt(),
                    closed, p.realizedEquityUsdt(), p.markedEquityUsdt(),
                    p.markedEquityUsdt().subtract(p.startingEquityUsdt()),
                    p.maxDrawdownPct(), p.feesUsdt(), p.openPositionSide()));
        }
        return new Comparison(request.symbol(), request.intervals().getFirst(),
                from, until, candles, List.copyOf(summaries), List.copyOf(trades),
                "SIMULATION_ONLY; FOUR_DYNAMIC_LONG_SHORT_PORTFOLIOS; "
                + "BLOCK_BOTH; WAIT_60_MIN; FROZEN_6H_9H_DIP_3H; "
                + "NO_LUNA; NO_TRADING_ORDERS");
    }
}
