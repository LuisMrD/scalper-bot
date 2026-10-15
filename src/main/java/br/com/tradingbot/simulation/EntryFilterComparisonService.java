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

    /**
     * Features observed at the CLOSED entry signal or at the subsequent filled
     * OPEN. No exit information or future candle can enter these fields.
     */
    public record EntryFeatures(
            long signalCloseTime, String primaryTrend, String trend15m,
            int higherTimeframeConflicts, String volumeLevel, String takerBalance,
            BigDecimal relativeVolume, BigDecimal relativeSpread, BigDecimal takerBuyShare,
            String effortResult, String barDirection, Boolean trendBar, Boolean doji,
            Boolean insideBar, Boolean outsideBar, Boolean bullishRejection,
            Boolean bearishRejection, boolean pullbackCandidate, int confirmedPivotCount,
            BigDecimal atrAtSignal, BigDecimal stopDistancePct, BigDecimal entryGapPct,
            BigDecimal estimatedCostToInitialRiskPct, BigDecimal estimatedRoundTripCostUsdt) {}

    public record TradeRow(
            EntryBreakoutFilter filter, String side,
            long enteredAtOpenTime, long exitCandleOpenTime,
            String entryBreakoutState, String exitReason,
            BigDecimal quantity, BigDecimal initialRiskUsdt,
            BigDecimal netPnlUsdt, BigDecimal feesUsdt,
            EntryFeatures entryFeatures, int durationCandles,
            BigDecimal mfeR, BigDecimal maeR, BigDecimal netRMultiple) {}

    public record Comparison(String symbol, String primaryInterval,
            long fromOpenTime, long asOfCloseTime, int replayedCandles,
            List<Summary> summaries, List<TradeRow> trades, String scope) {}

    static EntryFeatures features(BacktestResult.EntryExecutionDiagnostics entry) {
        var atSignal = entry.atSignal();
        var trace = atSignal.signal();
        var bar = atSignal.lastBarPattern();
        String trend15m = trace.timeframeTrends().stream()
                .filter(frame -> frame.interval().equals("15m"))
                .map(frame -> frame.trend().name())
                .findFirst().orElse(null);
        if (trend15m == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Entry snapshot missing 15m trend");
        }
        return new EntryFeatures(
                trace.candleCloseTime(), trace.primaryTrend().name(), trend15m,
                atSignal.higherTimeframeConflicts(), trace.volumeLevel().name(),
                trace.takerBalance().name(), trace.relativeVolume(), trace.relativeSpread(),
                atSignal.takerBuyShareAtSignal(), trace.effortResult().name(),
                bar == null ? null : bar.direction().name(),
                bar == null ? null : bar.trendBar(),
                bar == null ? null : bar.doji(),
                bar == null ? null : bar.insideBar(),
                bar == null ? null : bar.outsideBar(),
                bar == null ? null : bar.bullishRejection(),
                bar == null ? null : bar.bearishRejection(),
                atSignal.pullbackCandidate(), atSignal.confirmedPivotCount(),
                atSignal.atrAtSignal(), entry.stopDistancePct(), entry.entryGapPct(),
                entry.estimatedCostToInitialRiskPct(), entry.estimatedRoundTripCostUsdt());
    }

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
                if (d == null || d.atSignal() == null || d.atSignal().signal() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Closed trade is missing an entry-time snapshot");
                }
                trades.add(new TradeRow(filter, t.side(), t.enteredAtOpenTime(),
                        t.exitCandleOpenTime(), d.atSignal().breakoutState().name(),
                        t.exitReason(), t.quantity(), t.initialRiskUsdt(),
                        t.netPnlUsdt(), t.feesUsdt(), features(d),
                        t.durationCandles(), t.mfeR(), t.maeR(), t.netRMultiple()));
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
