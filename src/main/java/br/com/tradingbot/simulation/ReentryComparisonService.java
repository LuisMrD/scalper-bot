package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Independent dynamic JAVA simulations; never interacts with live orders or Luna. */
@Service
public class ReentryComparisonService {
    private final BacktestService backtests;

    public ReentryComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(ReentryWaitPolicy policy, int rejectedByEntryFilter,
            int rejectedByReentryWait, int tradesClosed, int winningTrades,
            int losingTrades, int entriesBlockedByRisk, BigDecimal startingEquityUsdt,
            BigDecimal netClosedUsdt, BigDecimal realizedEquityUsdt,
            BigDecimal markedEquityUsdt, BigDecimal markedPnlUsdt,
            BigDecimal maxDrawdownPct, BigDecimal feesUsdt, String openPositionSide) {}

    public record Trade(ReentryWaitPolicy policy, String side,
            long enteredAtOpenTime, long exitCandleOpenTime,
            String entryBreakoutState, String exitReason,
            BigDecimal initialRiskUsdt, BigDecimal netPnlUsdt, BigDecimal feesUsdt,
            EntryFilterComparisonService.EntryFeatures entryFeatures) {}

    public record Comparison(String symbol, String primaryInterval,
            long fromOpenTime, long asOfCloseTime, int replayedCandles,
            List<Summary> summaries, List<Trade> trades, String scope) {}

    public Comparison compare(BacktestRequest request) {
        if (request.includeLuna()
                || !request.intervals().equals(List.of("5m", "15m"))
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use only 5m+15m, RETEST-only, includeLuna=false for reentry research");
        }
        List<Summary> summaries = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        Long from = null;
        Long until = null;
        Integer bars = null;

        // Freeze original 5m four-arm study. Research-only 15m four-bar
        // cooldown is intentionally NOT an additional 5m comparison arm.
        for (ReentryWaitPolicy policy : List.of(ReentryWaitPolicy.NONE,
                ReentryWaitPolicy.WAIT_WHILE_15M_RANGE,
                ReentryWaitPolicy.WAIT_3_BARS,
                ReentryWaitPolicy.WAIT_12_BARS)) {
            var replay = backtests.runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH, policy);
            var result = replay.backtest();
            var p = result.javaPortfolio();
            if (from == null) {
                from = result.fromCandleOpenTime();
                until = result.throughCandleCloseTime();
                bars = result.replayedCandles();
            } else if (from != result.fromCandleOpenTime()
                    || until != result.throughCandleCloseTime()
                    || bars != result.replayedCandles()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Scenarios do not replay identical historical periods");
            }
            BigDecimal closed = BigDecimal.ZERO;
            for (var t : p.trades()) {
                var diagnostics = t.entryDiagnostics();
                if (diagnostics == null || diagnostics.atSignal() == null
                        || diagnostics.atSignal().signal() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Entry snapshot is missing for closed trade");
                }
                closed = closed.add(t.netPnlUsdt());
                trades.add(new Trade(policy, t.side(), t.enteredAtOpenTime(),
                        t.exitCandleOpenTime(), diagnostics.atSignal().breakoutState().name(),
                        t.exitReason(), t.initialRiskUsdt(), t.netPnlUsdt(),
                        t.feesUsdt(), EntryFilterComparisonService.features(diagnostics)));
            }
            if (p.tradesClosed() != p.trades().size()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Trade totals incomplete for " + policy);
            }
            summaries.add(new Summary(policy, replay.filteredSignals(),
                    replay.reentryBlockedSignals(), p.tradesClosed(),
                    p.winningTrades(), p.losingTrades(), p.entriesBlocked(),
                    p.startingEquityUsdt(), closed, p.realizedEquityUsdt(),
                    p.markedEquityUsdt(),
                    p.markedEquityUsdt().subtract(p.startingEquityUsdt()),
                    p.maxDrawdownPct(), p.feesUsdt(), p.openPositionSide()));
        }
        return new Comparison(request.symbol(), request.intervals().getFirst(),
                from, until, bars, List.copyOf(summaries), List.copyOf(trades),
                "RESEARCH_ONLY; INDEPENDENT_DYNAMIC_JAVA_REPLAY; "
                + "ENTRY_FILTER_BLOCK_BOTH; REENTRY_WAIT_EXPERIMENT; "
                + "NO_LUNA; NO_TRADING_ORDERS");
    }
}
