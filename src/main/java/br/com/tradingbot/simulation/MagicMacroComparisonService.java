package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Six independent 5m RETEST replays with frozen 3/6/9-hour BTC/ETH LONG gates.
 * All scenarios preserve BLOCK_BOTH + 60-minute wait and all SHORT decisions.
 * No simulated result is inferred by deleting original trades after the fact.
 */
@Service
public class MagicMacroComparisonService {
    private final BacktestService backtests;

    public MagicMacroComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(MagicMacroLongPolicy macroPolicy, int filteredSignals,
            int reentryBlockedSignals, int macroBlockedLongSignals,
            int tradesClosed, int winningTrades, int losingTrades,
            int entriesBlockedByRisk, BigDecimal startingEquityUsdt,
            BigDecimal netClosedUsdt, BigDecimal realizedEquityUsdt,
            BigDecimal markedEquityUsdt, BigDecimal markedPnlUsdt,
            BigDecimal maxDrawdownPct, BigDecimal feesUsdt, String openPositionSide) {}

    public record Trade(MagicMacroLongPolicy macroPolicy, String side,
            long enteredAtOpenTime, long exitCandleOpenTime,
            String entryBreakoutState, String exitReason,
            BigDecimal initialRiskUsdt, BigDecimal netPnlUsdt, BigDecimal feesUsdt,
            EntryFilterComparisonService.EntryFeatures entryFeatures) {}

    public record Comparison(String symbol, String primaryInterval, long fromOpenTime,
            long asOfCloseTime, int replayedCandles,
            List<Summary> summaries, List<Trade> trades, String scope) {}

    public Comparison compare(BacktestRequest request) {
        if (request.includeLuna() || !request.symbol().equals("SOLUSDT")
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Magic macro experiment requires SOLUSDT 5m+15m, 3600 candles, "
                            + "RETEST-only and no Luna");
        }
        var summaries = new ArrayList<Summary>();
        var trades = new ArrayList<Trade>();
        Long from = null;
        Long until = null;
        Integer candles = null;
        for (MagicMacroLongPolicy policy : MagicMacroLongPolicy.values()) {
            var replay = backtests.runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH,
                    ReentryWaitPolicy.WAIT_12_BARS, policy);
            var result = replay.backtest();
            var portfolio = result.javaPortfolio();
            if (from == null) {
                from = result.fromCandleOpenTime();
                until = result.throughCandleCloseTime();
                candles = result.replayedCandles();
            } else if (from != result.fromCandleOpenTime()
                    || until != result.throughCandleCloseTime()
                    || candles != result.replayedCandles()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Magic macro scenarios do not share the same historical 5m tape");
            }
            BigDecimal closed = BigDecimal.ZERO;
            for (var t : portfolio.trades()) {
                var d = t.entryDiagnostics();
                if (d == null || d.atSignal() == null || d.atSignal().signal() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Entry-time snapshot missing in macro trade");
                }
                closed = closed.add(t.netPnlUsdt());
                trades.add(new Trade(policy, t.side(), t.enteredAtOpenTime(),
                        t.exitCandleOpenTime(), d.atSignal().breakoutState().name(),
                        t.exitReason(), t.initialRiskUsdt(), t.netPnlUsdt(),
                        t.feesUsdt(), EntryFilterComparisonService.features(d)));
            }
            if (portfolio.tradesClosed() != portfolio.trades().size()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Portfolio trade count mismatch");
            }
            summaries.add(new Summary(policy, replay.filteredSignals(),
                    replay.reentryBlockedSignals(), replay.macroBlockedSignals(),
                    portfolio.tradesClosed(), portfolio.winningTrades(),
                    portfolio.losingTrades(), portfolio.entriesBlocked(),
                    portfolio.startingEquityUsdt(), closed,
                    portfolio.realizedEquityUsdt(), portfolio.markedEquityUsdt(),
                    portfolio.markedEquityUsdt().subtract(portfolio.startingEquityUsdt()),
                    portfolio.maxDrawdownPct(), portfolio.feesUsdt(),
                    portfolio.openPositionSide()));
        }
        return new Comparison(request.symbol(), request.intervals().getFirst(),
                from, until, candles, List.copyOf(summaries), List.copyOf(trades),
                "SIMULATION_ONLY; 3/6/9_HOUR_MACRO_RESEARCH; "
                        + "ALL_SHORTS_UNCHANGED; BLOCK_BOTH; WAIT_60_MIN; "
                        + "INDEPENDENT_DYNAMIC_JAVA_REPLAY; NO_LUNA; NO_ORDERS");
    }
}
