package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Descriptive diagnostics only. Two full independent replays of already
 * inspected history; no rules are optimized or executed on an exchange.
 */
@Service
public class ShortRegimeDiagnosticsService {
    private final BacktestService backtests;

    public ShortRegimeDiagnosticsService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(LongVersusShortPolicy policy, int tradesClosed,
            int winningTrades, int losingTrades, int longTrades, int shortTrades,
            int shortWins, BigDecimal netClosedUsdt, BigDecimal longClosedUsdt,
            BigDecimal shortClosedUsdt, BigDecimal startingEquityUsdt,
            BigDecimal markedPnlUsdt, BigDecimal feesUsdt, BigDecimal maxDrawdownPct,
            String openPositionSide, int blockedByEntryFilter, int blockedByCooldown,
            int shortOnlyBlockedLongSignals) {}

    /**
     * Fields through 'estimatedCostToInitialRiskPct' are known by fill.
     * The following fields are POST-ENTRY OUTCOMES, never valid as pre-entry
     * predictors. MFE/MAE and best-close metrics must not enter entry filters.
     */
    public record Trade(LongVersusShortPolicy policy, String side,
            long signalCloseTime, long enteredAtOpenTime, long exitCandleOpenTime,
            String entryBreakoutState, String primaryTrend, String trend15m,
            String volumeLevel, String takerBalance, String effortResult,
            String barDirection, Boolean trendBar, Boolean bullishRejection,
            Boolean bearishRejection, int higherTimeframeConflicts,
            BigDecimal relativeVolume, BigDecimal relativeSpread,
            BigDecimal takerBuyShare, BigDecimal stopDistancePct,
            BigDecimal entryGapPct, BigDecimal estimatedCostToInitialRiskPct,
            BigDecimal atrAtSignal, BigDecimal entryPrice, BigDecimal initialStop,
            BigDecimal initialRiskUsdt, BigDecimal quantity,
            // Outcomes below: NONE of them was known when the position opened.
            String exitReason, BigDecimal finalStop, BigDecimal exitPrice,
            int durationCandles, long durationMillis, int stopAdjustments,
            BigDecimal grossPnlUsdt, BigDecimal netPnlUsdt, BigDecimal feesUsdt,
            BigDecimal slippageUsdt, BigDecimal netR, BigDecimal mfeR,
            BigDecimal maeR, BigDecimal maxEstimatedNetPnlUsdt,
            BigDecimal givebackFromBestCloseUsdt,
            BigDecimal netPnlAt30Candles, BigDecimal netPnlAt60Candles,
            Integer netPositiveCloseCandles, Long firstConfirmedReversalCloseTime) {}

    public record Report(String symbol, long fromOpenTime, long asOfCloseTime,
            int replayedCandles, List<Summary> summaries, List<Trade> trades,
            String methodologicalScope) {}

    public Report analyze(BacktestRequest request) {
        if (request == null || request.includeLuna()
                || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Diagnostics require SOL RETEST 5m+15m, 3600 bars, no Luna");
        }
        var summaries = new ArrayList<Summary>();
        var trades = new ArrayList<Trade>();
        Long from = null;
        Long until = null;
        Integer candleCount = null;
        for (var policy : List.of(LongVersusShortPolicy.CONTROL, LongVersusShortPolicy.SHORT_ONLY)) {
            var replay = backtests.runLongVersusShort(request, policy);
            var result = replay.backtest();
            var portfolio = result.javaPortfolio();
            if (from == null) {
                from = result.fromCandleOpenTime();
                until = result.throughCandleCloseTime();
                candleCount = result.replayedCandles();
            } else if (from != result.fromCandleOpenTime()
                    || until != result.throughCandleCloseTime()
                    || candleCount != result.replayedCandles()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Control and SHORT_ONLY do not share the same 5m tape");
            }
            int longs = 0, shorts = 0, shortWins = 0, wins = 0;
            var closed = BigDecimal.ZERO;
            var longPnl = BigDecimal.ZERO;
            var shortPnl = BigDecimal.ZERO;
            for (var t : portfolio.trades()) {
                var d = t.entryDiagnostics();
                if (d == null || d.atSignal() == null || d.atSignal().signal() == null
                        || t.timing() == null || t.initialRiskUsdt() == null
                        || t.initialRiskUsdt().signum() <= 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Closed trade missing entry and outcome diagnostics");
                }
                var f = EntryFilterComparisonService.features(d);
                if (f.signalCloseTime() >= t.enteredAtOpenTime()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Entry features have lookahead on signal close");
                }
                var timing = t.timing();
                closed = closed.add(t.netPnlUsdt());
                if (t.netPnlUsdt().signum() > 0) wins++;
                if ("SHORT".equals(t.side())) {
                    shorts++;
                    shortPnl = shortPnl.add(t.netPnlUsdt());
                    if (t.netPnlUsdt().signum() > 0) shortWins++;
                } else if ("LONG".equals(t.side())) {
                    longs++;
                    longPnl = longPnl.add(t.netPnlUsdt());
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Unknown trade side");
                }
                trades.add(new Trade(policy, t.side(), f.signalCloseTime(),
                        t.enteredAtOpenTime(), t.exitCandleOpenTime(),
                        d.atSignal().breakoutState().name(), f.primaryTrend(), f.trend15m(),
                        f.volumeLevel(), f.takerBalance(), f.effortResult(), f.barDirection(),
                        f.trendBar(), f.bullishRejection(), f.bearishRejection(),
                        f.higherTimeframeConflicts(), f.relativeVolume(), f.relativeSpread(),
                        f.takerBuyShare(), f.stopDistancePct(), f.entryGapPct(),
                        f.estimatedCostToInitialRiskPct(), f.atrAtSignal(),
                        t.entryPrice(), t.initialStop(), t.initialRiskUsdt(), t.quantity(),
                        t.exitReason(), t.finalStop(), t.exitPrice(), t.durationCandles(),
                        t.durationMillis(), t.stopAdjustments(), t.grossPnlUsdt(),
                        t.netPnlUsdt(), t.feesUsdt(), t.slippageUsdt(), t.netRMultiple(),
                        t.mfeR(), t.maeR(), timing.maxEstimatedNetPnlUsdt(),
                        timing.givebackFromBestCloseUsdt(), timing.netPnlAt30Candles(),
                        timing.netPnlAt60Candles(), timing.netPositiveCloseCandles(),
                        timing.firstConfirmedReversalCloseTime()));
            }
            if (longs + shorts != portfolio.tradesClosed() ||
                    wins != portfolio.winningTrades() ||
                    (policy == LongVersusShortPolicy.SHORT_ONLY && longs != 0)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Trade count or SHORT_ONLY integrity violation");
            }
            summaries.add(new Summary(policy, portfolio.tradesClosed(), wins,
                    portfolio.losingTrades(), longs, shorts, shortWins, closed,
                    longPnl, shortPnl, portfolio.startingEquityUsdt(),
                    portfolio.markedEquityUsdt().subtract(portfolio.startingEquityUsdt()),
                    portfolio.feesUsdt(), portfolio.maxDrawdownPct(), portfolio.openPositionSide(),
                    replay.filteredSignals(), replay.reentryBlockedSignals(),
                    replay.shortOnlyBlockedSignals()));
        }
        return new Report(request.symbol(), from, until, candleCount,
                List.copyOf(summaries), List.copyOf(trades),
                "RETROSPECTIVE_DIAGNOSTIC_ONLY; CONTROL_VS_SHORT_ONLY; "
                + "ENTRY_FEATURES_PRE_FILL; EXIT_METRICS_POST_FILL; "
                + "NO_NEW_FILTER; NO_LUNA; NO_ORDERS");
    }
}
