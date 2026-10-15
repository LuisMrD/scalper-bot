package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Four independent DAILY portfolios. Twelve fully closed 1d bars per 300h
 * wall-clock window (the other 12h are intentionally not tradable daily bars).
 * Historical context preceding the window is indicator warmup ONLY.
 */
@Service
public final class BollingerDailyComparisonService {
    public static final long DAY_MS = 86_400_000L;
    private final BacktestService backtests;

    public BollingerDailyComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(String arm, int tradesClosed, int wins, int losses,
            int bbTrades, int outsideBandCloses, int confirmedSignals,
            int expiredSignals, int invalidatedSignals, int filteredRejected,
            int riskApproved, BigDecimal startingEquityUsdt,
            BigDecimal netClosedUsdt, BigDecimal markedPnlUsdt,
            BigDecimal feesUsdt, BigDecimal maxDrawdownPct,
            String openPositionSide) {}

    public record Trade(String arm, String side, String setup, long signalCloseTime,
            long enteredAtOpenTime, long exitedAtOpenTime,
            long outsideBandCloseTime, String confirmationPattern,
            BigDecimal entryPrice, BigDecimal initialStop, BigDecimal exitPrice,
            BigDecimal frozenTarget, String exitReason,
            BigDecimal initialRiskUsdt, BigDecimal netPnlUsdt,
            BigDecimal netRMultiple, BigDecimal feesUsdt) {}

    public record Comparison(String symbol, String interval, long firstCandleOpenTime,
            long lastCandleCloseTime, int completeDailyCandles, int period,
            BigDecimal deviations, BigDecimal minimumNetRewardRisk,
            String methodology, List<Summary> summaries, List<Trade> trades) {}

    public Comparison compare(BacktestRequest request) {
        if (request == null || !List.of("SOLUSDT", "DOGEUSDT", "BTCUSDT").contains(request.symbol())
                || !request.intervals().equals(List.of("1d"))
                || request.steps() != 12 || request.asOf() == null
                || request.asOf() % DAY_MS != DAY_MS - 1
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Daily research requires 12 completed 1d candles at UTC 1d close, no Luna");
        var summaries = new ArrayList<Summary>();
        var trades = new ArrayList<Trade>();
        Long first = null, last = null;
        for (String arm : List.of("CONTROL", "LEGACY_BOLLINGER_STRUCTURAL",
                "FILTERED_BOLLINGER_STRUCTURAL", "FILTERED_BOLLINGER_EXTREME")) {
            BacktestService.BollingerReplay study = switch (arm) {
                case "LEGACY_BOLLINGER_STRUCTURAL" ->
                    backtests.runBollingerStudyDaily(request, false,
                            BollingerFilteredPolicy.StopMode.STRUCTURAL);
                case "FILTERED_BOLLINGER_STRUCTURAL" ->
                    backtests.runBollingerStudyDaily(request, true,
                            BollingerFilteredPolicy.StopMode.STRUCTURAL);
                case "FILTERED_BOLLINGER_EXTREME" ->
                    backtests.runBollingerStudyDaily(request, true,
                            BollingerFilteredPolicy.StopMode.EXTREME_CANDLE);
                default -> null;
            };
            var result = study == null
                    ? backtests.runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH,
                            ReentryWaitPolicy.NONE).backtest()
                    : study.filtered().backtest();
            if (result.replayedCandles() != 12
                    || !result.intervals().equals(List.of("1d"))
                    || result.throughCandleCloseTime() != request.asOf()
                    || result.fromCandleOpenTime() != request.asOf() - 12L * DAY_MS + 1
                    || (first != null && (first != result.fromCandleOpenTime()
                            || last != result.throughCandleCloseTime())))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Daily arms must replay the identical twelve completed daily candles");
            first = result.fromCandleOpenTime();
            last = result.throughCandleCloseTime();
            var portfolio = result.javaPortfolio();
            var pnl = BigDecimal.ZERO;
            int bbTrades = 0;
            for (var t : portfolio.trades()) {
                if (t.entryDiagnostics() == null
                        || t.entryDiagnostics().atSignal() == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Missing immutable entry snapshot");
                var snap = t.entryDiagnostics().atSignal().signal();
                boolean bb = snap.setup()
                        == br.com.tradingbot.decision.DecisionResult.Setup.BOLLINGER_MEAN_REVERSION;
                if (bb) {
                    bbTrades++;
                    if (study == null || !study.targets().containsKey(snap.candleCloseTime())
                            || study.triggerCloses().get(snap.candleCloseTime())
                                    >= snap.candleCloseTime())
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "BB trade without earlier outside close");
                }
                pnl = pnl.add(t.netPnlUsdt());
                trades.add(new Trade(arm, t.side(), snap.setup().name(),
                        snap.candleCloseTime(), t.enteredAtOpenTime(),
                        t.exitCandleOpenTime(),
                        bb ? study.triggerCloses().get(snap.candleCloseTime()) : 0,
                        bb ? study.confirmationPatterns().get(snap.candleCloseTime()) : null,
                        t.entryPrice(), t.initialStop(), t.exitPrice(),
                        bb ? study.targets().get(snap.candleCloseTime()) : null,
                        t.exitReason(), t.initialRiskUsdt(), t.netPnlUsdt(),
                        t.netRMultiple(), t.feesUsdt()));
            }
            if (portfolio.tradesClosed() != portfolio.trades().size())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Daily accounting must reconcile");
            summaries.add(new Summary(arm, portfolio.tradesClosed(),
                    portfolio.winningTrades(), portfolio.losingTrades(), bbTrades,
                    study == null ? 0 : study.outsideCloses(),
                    study == null ? 0 : study.confirmedSignals(),
                    study == null ? 0 : study.expiredSignals(),
                    study == null ? 0 : study.invalidatedSignals(),
                    study == null ? 0 : study.filteredRejected(),
                    study == null ? 0 : study.riskApproved(),
                    portfolio.startingEquityUsdt(), pnl,
                    portfolio.markedEquityUsdt().subtract(portfolio.startingEquityUsdt()),
                    portfolio.feesUsdt(), portfolio.maxDrawdownPct(),
                    portfolio.openPositionSide()));
        }
        return new Comparison(request.symbol(), "1d", first, last, 12,
                BollingerMeanReversion.PERIOD, BollingerMeanReversion.DEVIATIONS,
                BollingerFilteredPolicy.MINIMUM_NET_RR,
                "SIMULATION_ONLY; CLOSED_1D_UTC; 300H_WALLCLOCK_12_COMPLETE_BARS; "
                        + "OUTSIDE_CLOSE_ARM; STRICT_CLOSE_BACK_INSIDE; PRIOR_CANDLE_BREAK; "
                        + "VPA_AND_TREND_FILTER; NET_RR_AT_SIGNAL_AND_FILL; "
                        + "STOP_ONE_TICK_BEYOND_OBSERVED_EXTREME; FROZEN_MIDDLE_TARGET; "
                        + "NEXT_OPEN_FILL_STOP_FIRST; NO_LUNA_NO_ORDERS; "
                        + "PREWINDOW_CANDLES_FOR_WARMUP_ONLY",
                List.copyOf(summaries), List.copyOf(trades));
    }
}
