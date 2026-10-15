package br.com.tradingbot.simulation;

import br.com.tradingbot.decision.DecisionResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Strict matched-period, independent dynamical portfolios: 5m vs 15m. */
@Service
public class PrimaryTimeframeComparisonService {
    private final BacktestService backtests;

    public PrimaryTimeframeComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record Summary(PrimaryTimeframeArm arm, String primaryInterval,
            String contextInterval, int replayedCandles, int cooldownBars,
            int tradesClosed, int winningTrades, int losingTrades,
            int longTrades, int shortTrades, int longWins, int shortWins,
            int entryFilterBlockedSignals, int cooldownBlockedSignals,
            BigDecimal startingEquityUsdt, BigDecimal netClosedUsdt,
            BigDecimal longNetClosedUsdt, BigDecimal shortNetClosedUsdt,
            BigDecimal markedPnlUsdt, BigDecimal feesUsdt, BigDecimal maxDrawdownPct,
            String openPositionSide) {}

    public record Trade(PrimaryTimeframeArm arm, String side, String entrySetup,
            long signalCloseTime, long enteredAtOpenTime, long exitCandleOpenTime,
            String primaryTrend, String contextTrend, String breakoutState,
            String volumeLevel, String exitReason,
            BigDecimal initialRiskUsdt, BigDecimal netPnlUsdt, BigDecimal netR,
            BigDecimal mfeR, BigDecimal maeR, BigDecimal feesUsdt,
            BigDecimal estimatedCostToInitialRiskPct) {}

    public record Comparison(String symbol, long fromOpenTime, long asOfCloseTime,
            int periodHours, List<Summary> summaries, List<Trade> trades, String scope) {}

    public Comparison compare(BacktestRequest request) {
        if (request == null || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || request.asOf() % 3_600_000L != 3_599_999L
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Require SOLUSDT 5m+15m baseline, 3600 bars, hourly-aligned asOf, "
                            + "RETEST-only and no Luna");
        }
        // Identical risk, contracts, exit and cost overrides; the primary
        // interval and the context interval change together by design.
        var fifteen = new BacktestRequest(request.symbol(), List.of("15m", "1h"),
                1200, request.contextLimit(), request.asOf(), false,
                request.priceTick(), request.quantityStep(), request.minimumQuantity(),
                request.minimumNotionalUsdt(), request.breakEvenTriggerROverride(),
                request.maxCostToInitialRiskPctOverride(), request.maxHoldingCandles(),
                false, true, false);

        var summaries = new ArrayList<Summary>();
        var trades = new ArrayList<Trade>();
        Long from = null, until = null;
        for (var arm : PrimaryTimeframeArm.values()) {
            var replay = arm == PrimaryTimeframeArm.PRIMARY_5M_CONTEXT_15M
                    ? backtests.runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH,
                            ReentryWaitPolicy.WAIT_12_BARS)
                    : backtests.runPrimary15mComparison(fifteen);
            var result = replay.backtest();
            var p = result.javaPortfolio();
            int expectedBars = arm == PrimaryTimeframeArm.PRIMARY_5M_CONTEXT_15M
                    ? 3600 : 1200;
            String primary = arm == PrimaryTimeframeArm.PRIMARY_5M_CONTEXT_15M ? "5m" : "15m";
            String higher = arm == PrimaryTimeframeArm.PRIMARY_5M_CONTEXT_15M ? "15m" : "1h";
            if (result.replayedCandles() != expectedBars
                    || !result.intervals().equals(List.of(primary, higher))
                    || result.throughCandleCloseTime() != request.asOf()
                    || result.fromCandleOpenTime() != request.asOf() - 300L*3_600_000L + 1L
                    || (from != null && (from != result.fromCandleOpenTime()
                                        || until != result.throughCandleCloseTime()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Timeframe comparison must use exactly the same 300h period");
            }
            from = result.fromCandleOpenTime();
            until = result.throughCandleCloseTime();
            int longs = 0, shorts = 0, wins = 0, longWins = 0, shortWins = 0;
            BigDecimal net = BigDecimal.ZERO, longPnl = BigDecimal.ZERO,
                    shortPnl = BigDecimal.ZERO;
            for (var trade : p.trades()) {
                var d = trade.entryDiagnostics();
                if (d == null || d.atSignal() == null || d.atSignal().signal() == null
                        || trade.initialRiskUsdt() == null
                        || trade.initialRiskUsdt().signum() <= 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Missing historical entry details or initial trade risk");
                }
                var signal = d.atSignal().signal();
                if (signal.candleCloseTime() >= trade.enteredAtOpenTime()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Entry features have lookahead on simulated fill");
                }
                var higherTrend = signal.timeframeTrends().stream()
                        .filter(f -> f.interval().equals(higher))
                        .findFirst().orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.CONFLICT, "Missing as-of " + higher + " context"));
                if (higherTrend.asOfCloseTime() > signal.candleCloseTime()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Higher-timeframe candle closes after entry signal");
                }
                net = net.add(trade.netPnlUsdt());
                boolean win = trade.netPnlUsdt().signum() > 0;
                if (win) wins++;
                if ("LONG".equals(trade.side())) {
                    longs++;
                    longPnl = longPnl.add(trade.netPnlUsdt());
                    if (win) longWins++;
                } else if ("SHORT".equals(trade.side())) {
                    shorts++;
                    shortPnl = shortPnl.add(trade.netPnlUsdt());
                    if (win) shortWins++;
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Unknown trade side " + trade.side());
                }
                trades.add(new Trade(arm, trade.side(),
                        signal.setup().name(), signal.candleCloseTime(),
                        trade.enteredAtOpenTime(), trade.exitCandleOpenTime(),
                        signal.primaryTrend().name(), higherTrend.trend().name(),
                        d.atSignal().breakoutState().name(), signal.volumeLevel().name(),
                        trade.exitReason(), trade.initialRiskUsdt(), trade.netPnlUsdt(),
                        trade.netRMultiple(), trade.mfeR(), trade.maeR(),
                        trade.feesUsdt(), d.estimatedCostToInitialRiskPct()));
            }
            if (longs + shorts != p.tradesClosed()
                    || wins != p.winningTrades() || longWins + shortWins != wins
                    || p.losingTrades() + wins > p.tradesClosed()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Timeframe trade counts failed full dynamic reconciliation");
            }
            summaries.add(new Summary(arm, primary, higher, expectedBars,
                    expectedBars == 3600 ? 12 : 4, p.tradesClosed(),
                    p.winningTrades(), p.losingTrades(), longs, shorts, longWins, shortWins,
                    replay.filteredSignals(), replay.reentryBlockedSignals(),
                    p.startingEquityUsdt(), net, longPnl, shortPnl,
                    p.markedEquityUsdt().subtract(p.startingEquityUsdt()),
                    p.feesUsdt(), p.maxDrawdownPct(), p.openPositionSide()));
        }
        return new Comparison(request.symbol(), from, until, 300,
                List.copyOf(summaries), List.copyOf(trades),
                "SIMULATION_ONLY; TWO_INDEPENDENT_DYNAMIC_PORTFOLIOS; "
                + "5M_15M_VS_15M_1H; 300_HOURS_MATCHED; "
                + "BLOCK_BOTH; WAIT_60_MINUTES; RETEST_ONLY; "
                + "NO_PRODUCTION_CANDLES; NO_LUNA; NO_ORDERS");
    }
}
