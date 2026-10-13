package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Four fully independent JAVA ledger replays. This is NOT historical trade
 * subtraction: rejecting a signal can change all subsequent entries and stops.
 * There is no exchange trading, no portfolio persistence and no Luna call.
 */
@Service
public class EntryFilterComparisonService {
    private final BacktestService backtests;

    public EntryFilterComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(
            EntryBreakoutFilter filter, int filteredSignals, int tradesClosed,
            int winningTrades, int losingTrades, int entriesBlockedByRisk,
            BigDecimal startingEquityUsdt, BigDecimal netClosedUsdt,
            BigDecimal realizedEquityUsdt, BigDecimal markedEquityUsdt,
            BigDecimal markedPnlUsdt, BigDecimal totalReturnPct,
            BigDecimal maxDrawdownPct, BigDecimal feesUsdt, String openPositionSide) {}

    public record TradeRow(
            EntryBreakoutFilter filter, String side,
            long enteredAtOpenTime, long exitCandleOpenTime,
            String entryBreakoutState, String exitReason,
            BigDecimal quantity, BigDecimal initialRiskUsdt,
            BigDecimal netPnlUsdt, BigDecimal feesUsdt) {}

    public record Comparison(String symbol, String primaryInterval,
            long fromOpenTime, long asOfCloseTime, int replayedCandles,
            List<Summary> summaries, List<TradeRow> trades, String scope) {}

    public Comparison compare(BacktestRequest request) {
        if (request.includeLuna()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This experiment does not allow paid Luna evaluations");
        }
        if (!"5m".equals(request.intervals().getFirst())
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Research requires primary=5m and RETEST-only overrides");
        }
        List<Summary> summaries = new ArrayList<>();
        List<TradeRow> trades = new ArrayList<>();
        Long from = null;
        Long until = null;
        Integer candles = null;
        for (EntryBreakoutFilter filter : EntryBreakoutFilter.values()) {
            BacktestService.FilteredReplay replay = backtests.runFiltered(request, filter);
            BacktestResult result = replay.backtest();
            var p = result.javaPortfolio();
            if (from == null) {
                from = result.fromCandleOpenTime();
                until = result.throughCandleCloseTime();
                candles = result.replayedCandles();
            } else if (from != result.fromCandleOpenTime()
                    || until != result.throughCandleCloseTime()
                    || candles != result.replayedCandles()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Scenario tape shifted between independent replays");
            }
            BigDecimal closedNet = BigDecimal.ZERO;
            for (BacktestResult.Trade t : p.trades()) {
                closedNet = closedNet.add(t.netPnlUsdt());
                var d = t.entryDiagnostics();
                if (d == null || d.atSignal() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Closed trade is missing an entry-time snapshot");
                }
                trades.add(new TradeRow(filter, t.side(), t.enteredAtOpenTime(),
                        t.exitCandleOpenTime(), d.atSignal().breakoutState().name(),
                        t.exitReason(), t.quantity(), t.initialRiskUsdt(),
                        t.netPnlUsdt(), t.feesUsdt()));
            }
            if (p.tradesClosed() != p.trades().size()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Closed-trade count differs from portfolio ledger");
            }
            summaries.add(new Summary(filter, replay.filteredSignals(), p.tradesClosed(),
                    p.winningTrades(), p.losingTrades(), p.entriesBlocked(),
                    p.startingEquityUsdt(), closedNet,
                    p.realizedEquityUsdt(), p.markedEquityUsdt(),
                    p.markedEquityUsdt().subtract(p.startingEquityUsdt()),
                    p.totalReturnPct(), p.maxDrawdownPct(),
                    p.feesUsdt(), p.openPositionSide()));
        }
        return new Comparison(request.symbol(), request.intervals().getFirst(),
                from, until, candles, List.copyOf(summaries), List.copyOf(trades),
                "DYNAMIC_JAVA_REPLAY_5M_RETEST; FULL_RISK_AND_STOP_RECALCULATION; "
                    + "INDEPENDENT_300_HOUR_WINDOWS; NO_LUNA; NO_ORDERS");
    }
}
