package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.brooks.PriceActionConfiguration;
import br.com.tradingbot.analysis.volume.VolumeConfiguration;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import br.com.tradingbot.risk.RiskManagementConfiguration;
import br.com.tradingbot.risk.RiskPolicy;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Research only: replays exactly the original Java backtest and compares three
 * hypothetical exit rules with frozen entries, quantities and original stops.
 * Never places orders and never invokes Luna.
 */
@Service
public class FirstOppositionComparisonService {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PENNY = new BigDecimal("0.01");

    public enum Rule { FIRST_OPPOSITE_CANDLE, PRICE_ACTION, OPPOSITE_VOLUME }

    public record TradeResult(
            Rule rule, String side, long enteredAtOpenTime, long originalExitAtOpenTime,
            long hypotheticalExitAtOpenTime, Long signalAtCloseTime,
            String originalExitReason, boolean signalTriggered, boolean earlyExit,
            BigDecimal quantity, BigDecimal entryPrice, BigDecimal originalExitPrice,
            BigDecimal hypotheticalExitPrice, BigDecimal originalNetPnlUsdt,
            BigDecimal hypotheticalNetPnlUsdt, BigDecimal deltaUsdt,
            BigDecimal originalFeesUsdt, BigDecimal hypotheticalFeesUsdt) {}

    public record RuleSummary(Rule rule, int trades, int signaledTrades, int earlyExits,
            int originalWins, int hypotheticalWins, int losingToWinning, int winningToLosing,
            BigDecimal originalNetPnlUsdt, BigDecimal hypotheticalNetPnlUsdt,
            BigDecimal deltaUsdt, BigDecimal hypotheticalFeesUsdt) {}

    public record Result(String symbol, String primaryInterval,
            long fromOpenTime, long asOfCloseTime, int originalTrades,
            List<RuleSummary> summaries, List<TradeResult> trades,
            String scope, String executionModel) {}

    private final BacktestService backtests;
    private final MarketDataService market;
    private final PriceActionConfiguration paConfig;
    private final VolumeConfiguration volumeConfig;

    public FirstOppositionComparisonService(BacktestService backtests, MarketDataService market,
            PriceActionConfiguration paConfig, VolumeConfiguration volumeConfig) {
        this.backtests = Objects.requireNonNull(backtests);
        this.market = Objects.requireNonNull(market);
        this.paConfig = Objects.requireNonNull(paConfig);
        this.volumeConfig = Objects.requireNonNull(volumeConfig);
    }

    public Result compare(BacktestRequest request) {
        if (request.includeLuna()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Research comparison is Java-only; includeLuna must be false");
        }
        // Same replay, data and risk policy as the original backtest.
        BacktestResult original = backtests.run(request);
        RiskPolicy policy = backtests.policy(request);
        String tf = request.intervals().getFirst();
        int warmup = volumeConfig.lookback();
        var descending = market.recentAtOrBefore(request.symbol(), tf,
                request.steps() + warmup, original.throughCandleCloseTime());
        if (descending.size() != request.steps() + warmup) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not enough candles for historical volume reference");
        }
        var tape = new ArrayList<>(descending);
        Collections.reverse(tape);
        Map<Long, Integer> index = new HashMap<>();
        long intervalMs = tape.getFirst().interval().millis();
        for (int i = 0; i < tape.size(); i++) {
            MarketCandle candle = tape.get(i);
            if (!candle.closed() || candle.closeTime() - candle.openTime() != intervalMs - 1
                    || (i > 0 && candle.openTime() - tape.get(i - 1).openTime() != intervalMs)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Missing or unordered historical candles");
            }
            index.put(candle.openTime(), i);
        }
        if (tape.get(warmup).openTime() != original.fromCandleOpenTime()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Underlying candle history differs from the original replay");
        }

        EnumMap<Rule, List<TradeResult>> perRule = new EnumMap<>(Rule.class);
        for (Rule rule : Rule.values()) perRule.put(rule, new ArrayList<>());

        for (BacktestResult.Trade trade : original.javaPortfolio().trades()) {
            Integer start = index.get(trade.enteredAtOpenTime());
            Integer end = index.get(trade.exitCandleOpenTime());
            if (start == null || end == null || start < warmup || end < start) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Trade is outside the available original candle history");
            }
            BigDecimal originalFeesComputed = fee(trade.entryPrice(), trade.quantity(), policy)
                    .add(fee(trade.exitPrice(), trade.quantity(), policy));
            if (originalFeesComputed.subtract(trade.feesUsdt()).abs().compareTo(PENNY) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Risk fee configuration no longer matches original trade fees");
            }
            for (Rule rule : Rule.values()) {
                int signalIndex = -1;
                // Stop and existing exits retain priority. A same-candle stop
                // leaves no completed in-position candle to evaluate.
                for (int i = start; i < end; i++) {
                    if (matches(rule, trade.side(), tape, i)) {
                        signalIndex = i;
                        break;
                    }
                }
                boolean changed = signalIndex >= 0;
                // A signal at candle CLOSE only fills at the next candle OPEN.
                int exitIndex = changed ? signalIndex + 1 : end;
                if (exitIndex >= tape.size() || exitIndex > end) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "No valid next candle for hypothetical exit");
                }
                MarketCandle exitBar = tape.get(exitIndex);
                BigDecimal exitPrice = changed
                        ? adverseExitFill(exitBar.open(), trade.side(), policy)
                        : trade.exitPrice();
                BigDecimal net = changed ? calculateNet(trade, exitPrice, policy)
                        : trade.netPnlUsdt();
                BigDecimal fees = changed
                        ? fee(trade.entryPrice(), trade.quantity(), policy)
                          .add(fee(exitPrice, trade.quantity(), policy))
                        : trade.feesUsdt();
                perRule.get(rule).add(new TradeResult(rule, trade.side(),
                        trade.enteredAtOpenTime(), trade.exitCandleOpenTime(),
                        exitBar.openTime(), changed ? tape.get(signalIndex).closeTime() : null,
                        trade.exitReason(), changed, changed && exitIndex < end,
                        trade.quantity(), trade.entryPrice(), trade.exitPrice(), exitPrice,
                        trade.netPnlUsdt(), net, net.subtract(trade.netPnlUsdt()),
                        trade.feesUsdt(), fees));
            }
        }

        List<RuleSummary> summaries = new ArrayList<>();
        List<TradeResult> flat = new ArrayList<>();
        for (Rule rule : Rule.values()) {
            List<TradeResult> rows = perRule.get(rule);
            flat.addAll(rows);
            BigDecimal initial = BigDecimal.ZERO, revised = BigDecimal.ZERO, fees = BigDecimal.ZERO;
            int signaled = 0, early = 0, initialWins = 0, revisedWins = 0, rescued = 0, lost = 0;
            for (TradeResult row : rows) {
                initial = initial.add(row.originalNetPnlUsdt());
                revised = revised.add(row.hypotheticalNetPnlUsdt());
                fees = fees.add(row.hypotheticalFeesUsdt());
                if (row.signalTriggered()) signaled++;
                if (row.earlyExit()) early++;
                boolean wasWin = row.originalNetPnlUsdt().signum() > 0;
                boolean nowWin = row.hypotheticalNetPnlUsdt().signum() > 0;
                if (wasWin) initialWins++;
                if (nowWin) revisedWins++;
                if (!wasWin && nowWin) rescued++;
                if (wasWin && !nowWin) lost++;
            }
            summaries.add(new RuleSummary(rule, rows.size(), signaled, early, initialWins,
                    revisedWins, rescued, lost, initial, revised, revised.subtract(initial), fees));
        }
        return new Result(request.symbol(), tf, original.fromCandleOpenTime(),
                original.throughCandleCloseTime(), original.javaPortfolio().trades().size(),
                List.copyOf(summaries), List.copyOf(flat),
                "FROZEN_ORIGINAL_CLOSED_TRADES_ONLY; original entries do not shift",
                "SIGNAL_ON_CLOSED_BAR; EXIT_AT_NEXT_OPEN_WITH_ADVERSE_SLIPPAGE; "
                    + "ORIGINAL_STOP_FIRST; NO_FUNDING_OR_REAL_ORDERS");
    }

    private boolean matches(Rule rule, String side, List<MarketCandle> tape, int i) {
        MarketCandle c = tape.get(i);
        boolean bearish = side.equals("LONG");
        boolean oppositeBar = bearish ? c.close().compareTo(c.open()) < 0
                : c.close().compareTo(c.open()) > 0;
        if (rule == Rule.FIRST_OPPOSITE_CANDLE) return oppositeBar;
        BigDecimal span = c.high().subtract(c.low());
        if (rule == Rule.PRICE_ACTION) {
            if (span.signum() <= 0) return false;
            BigDecimal bodyRatio = c.close().subtract(c.open()).abs().divide(span, MC);
            BigDecimal upperRatio = c.high().subtract(c.open().max(c.close())).divide(span, MC);
            BigDecimal lowerRatio = c.open().min(c.close()).subtract(c.low()).divide(span, MC);
            boolean doji = bodyRatio.compareTo(BigDecimal.valueOf(paConfig.dojiBodyMaxRatio())) <= 0;
            boolean trendBar = !doji && bodyRatio.compareTo(
                    BigDecimal.valueOf(paConfig.trendBodyMinRatio())) >= 0;
            BigDecimal midpoint = c.low().add(span.multiply(new BigDecimal("0.5")));
            boolean rejection = bearish
                    ? upperRatio.compareTo(BigDecimal.valueOf(paConfig.rejectionWickMinRatio())) >= 0
                        && c.close().compareTo(midpoint) <= 0
                    : lowerRatio.compareTo(BigDecimal.valueOf(paConfig.rejectionWickMinRatio())) >= 0
                        && c.close().compareTo(midpoint) >= 0;
            return (oppositeBar && trendBar) || rejection;
        }
        if (!oppositeBar) return false;
        int lookback = volumeConfig.lookback();
        if (i < lookback) return false;
        BigDecimal totalPrevious = BigDecimal.ZERO;
        for (int j = i - lookback; j < i; j++) {
            totalPrevious = totalPrevious.add(tape.get(j).quoteVolume());
        }
        BigDecimal mean = totalPrevious.divide(BigDecimal.valueOf(lookback), MC);
        if (mean.signum() <= 0 || c.quoteVolume().signum() <= 0) return false;
        BigDecimal rel = c.quoteVolume().divide(mean, MC);
        if (rel.compareTo(BigDecimal.valueOf(volumeConfig.highVolumeRatio())) < 0) return false;
        BigDecimal takerShare = c.takerBuyQuoteVolume().divide(c.quoteVolume(), MC);
        BigDecimal threshold = BigDecimal.valueOf(volumeConfig.strongTakerShare());
        return bearish ? takerShare.compareTo(ONE.subtract(threshold)) <= 0
                : takerShare.compareTo(threshold) >= 0;
    }

    private static BigDecimal adverseExitFill(BigDecimal open, String side, RiskPolicy policy) {
        BigDecimal rate = policy.slippagePerSidePct().divide(HUNDRED, MC);
        return side.equals("LONG") ? open.multiply(ONE.subtract(rate), MC)
                : open.multiply(ONE.add(rate), MC);
    }

    private static BigDecimal fee(BigDecimal px, BigDecimal qty, RiskPolicy policy) {
        return px.multiply(qty, MC).multiply(policy.feePerSidePct().divide(HUNDRED, MC), MC);
    }

    private static BigDecimal calculateNet(BacktestResult.Trade trade, BigDecimal exitPrice,
            RiskPolicy policy) {
        BigDecimal gross = trade.side().equals("LONG")
                ? exitPrice.subtract(trade.entryPrice()).multiply(trade.quantity(), MC)
                : trade.entryPrice().subtract(exitPrice).multiply(trade.quantity(), MC);
        return gross.subtract(fee(trade.entryPrice(), trade.quantity(), policy))
                .subtract(fee(exitPrice, trade.quantity(), policy));
    }
}
