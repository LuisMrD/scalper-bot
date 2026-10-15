package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Research, not a live strategy: same 3600 closed 5m candles, independently
 * simulated portfolios, unchanged BLOCK_BOTH and WAIT_12_BARS.
 */
@Service
public class BollingerComparisonService {
    private final BacktestService backtests;

    public BollingerComparisonService(BacktestService backtests) {
        this.backtests = Objects.requireNonNull(backtests);
    }

    public enum Arm { CONTROL, CONTROL_PLUS_BOLLINGER_SMA20_2SIGMA }

    public record Summary(Arm arm, int tradesClosed, int winningTrades, int losingTrades,
            int longTrades, int shortTrades, int bbTrades,
            int outsideBandCloses, int bbConfirmedSignals, int bbExpiredSignals,
            int bbInvalidatedSignals, int bbCooldownRejected, int bbRiskApproved,
            int entryFilterRejected, int cooldownRejected,
            BigDecimal startingEquityUsdt, BigDecimal netClosedUsdt,
            BigDecimal longClosedUsdt, BigDecimal shortClosedUsdt,
            BigDecimal markedPnlUsdt, BigDecimal feesUsdt,
            BigDecimal maxDrawdownPct, String openPositionSide) {}

    public record Trade(Arm arm, String side, String setup,
            long outsideBandCloseTime, long signalCloseTime, String confirmationPattern,
            long enteredAtOpenTime, long exitedAtOpenTime,
            String exitReason, BigDecimal frozenMiddleTargetUsdt,
            BigDecimal initialRiskUsdt, BigDecimal netPnlUsdt,
            BigDecimal netR, BigDecimal feesUsdt) {}

    public record Comparison(String symbol, long fromOpenTime, long asOfCloseTime,
            int candles, int bollingerPeriod, BigDecimal deviations,
            String targetMethod, List<Summary> summaries, List<Trade> trades,
            String scope) {}

    public Comparison compare(BacktestRequest request) {
        if (request == null || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bollinger research: 3600 SOLUSDT 5m+15m, RETEST-only, no Luna");
        }

        var summaries = new ArrayList<Summary>();
        var trades = new ArrayList<Trade>();
        Long from = null, until = null;
        for (Arm arm : Arm.values()) {
            BacktestService.BollingerReplay bb = null;
            var replay = arm == Arm.CONTROL
                    ? backtests.runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH,
                            ReentryWaitPolicy.WAIT_12_BARS)
                    : (bb = backtests.runBollingerStudy(request)).filtered();
            var result = replay.backtest();
            var p = result.javaPortfolio();
            if (result.replayedCandles() != 3600
                    || !result.intervals().equals(List.of("5m", "15m"))
                    || result.throughCandleCloseTime() != request.asOf()
                    || result.fromCandleOpenTime() != request.asOf()
                            - 3600L * 300_000L + 1L
                    || (from != null && (from != result.fromCandleOpenTime()
                                         || until != result.throughCandleCloseTime()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Bollinger scenarios must cover identical complete 300-hour 5m periods");
            }
            from = result.fromCandleOpenTime();
            until = result.throughCandleCloseTime();
            BigDecimal net = BigDecimal.ZERO, longPnl = BigDecimal.ZERO,
                    shortPnl = BigDecimal.ZERO;
            int longs = 0, shorts = 0, bbs = 0, wins = 0, losses = 0;
            for (var t : p.trades()) {
                var d = t.entryDiagnostics();
                if (d == null || d.atSignal() == null
                        || d.atSignal().signal() == null
                        || t.initialRiskUsdt() == null || t.initialRiskUsdt().signum() <= 0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Bollinger trade without pre-entry snapshot and risk");
                var snap = d.atSignal().signal();
                if (snap.candleCloseTime() >= t.enteredAtOpenTime())
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Bollinger result contains lookahead");
                var isBb = snap.setup()
                        == br.com.tradingbot.decision.DecisionResult.Setup.BOLLINGER_MEAN_REVERSION;
                if (isBb && (bb == null || !bb.targets().containsKey(snap.candleCloseTime())
                        || !bb.triggerCloses().containsKey(snap.candleCloseTime())
                        || !bb.confirmationPatterns().containsKey(snap.candleCloseTime())
                        || bb.triggerCloses().get(snap.candleCloseTime())
                                >= snap.candleCloseTime()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Bollinger trade missing prior outside close or reversal confirmation");
                if (isBb) bbs++;
                if (t.netPnlUsdt().signum() > 0) wins++;
                else if (t.netPnlUsdt().signum() < 0) losses++;
                if ("LONG".equals(t.side())) {
                    longs++;
                    longPnl = longPnl.add(t.netPnlUsdt());
                } else if ("SHORT".equals(t.side())) {
                    shorts++;
                    shortPnl = shortPnl.add(t.netPnlUsdt());
                } else throw new ResponseStatusException(HttpStatus.CONFLICT, "Unknown trade side");
                net = net.add(t.netPnlUsdt());
                trades.add(new Trade(arm, t.side(), snap.setup().name(),
                        isBb ? bb.triggerCloses().get(snap.candleCloseTime()) : 0L,
                        snap.candleCloseTime(),
                        isBb ? bb.confirmationPatterns().get(snap.candleCloseTime()) : null,
                        t.enteredAtOpenTime(),
                        t.exitCandleOpenTime(), t.exitReason(),
                        isBb ? bb.targets().get(snap.candleCloseTime()) : null,
                        t.initialRiskUsdt(), t.netPnlUsdt(), t.netRMultiple(),
                        t.feesUsdt()));
            }
            if (p.tradesClosed() != longs+shorts || p.winningTrades() != wins
                    || p.losingTrades() != losses || bbs > (bb == null ? 0 : bb.riskApproved())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Bollinger portfolio trade accounting does not reconcile");
            }
            summaries.add(new Summary(arm, p.tradesClosed(), wins, losses,
                    longs, shorts, bbs,
                    bb == null ? 0 : bb.outsideCloses(),
                    bb == null ? 0 : bb.confirmedSignals(),
                    bb == null ? 0 : bb.expiredSignals(),
                    bb == null ? 0 : bb.invalidatedSignals(),
                    bb == null ? 0 : bb.cooldownRejected(),
                    bb == null ? 0 : bb.riskApproved(),
                    replay.filteredSignals(), replay.reentryBlockedSignals(),
                    p.startingEquityUsdt(), net, longPnl, shortPnl,
                    p.markedEquityUsdt().subtract(p.startingEquityUsdt()),
                    p.feesUsdt(), p.maxDrawdownPct(), p.openPositionSide()));
        }
        return new Comparison(request.symbol(), from, until, 3600,
                BollingerMeanReversion.PERIOD, BollingerMeanReversion.DEVIATIONS,
                "SMA20_FROZEN_AT_OUTSIDE_CLOSE; WAIT_FOR_5M_REVERSAL_CONFIRMATION; "
                    + "HAMMER_OR_SHOOTING_STAR_OR_STRONG_CANDLE_OR_BREAKOUT; "
                    + "MAX_9_COMPLETED_5M_BARS; NEXT_OPEN_FILL; "
                    + "STOP_FIRST; MID_BAND_INTRABAR_LIMIT_OR_HARD_STOP",
                List.copyOf(summaries), List.copyOf(trades),
                "SIMULATION_ONLY; CONTROL_BLOCK_BOTH_WAIT_60_MIN; "
                    + "BB_IS_ADDITIONAL_MEAN_REVERSION_ENTRY_WHEN_CONTROL_WAITS; "
                    + "NO_NEW_FILTERS; NO_LUNA; NO_ORDERS");
    }
}
