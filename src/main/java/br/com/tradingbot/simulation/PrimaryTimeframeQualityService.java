package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only forensic audit of already inspected Testnet windows 46..51.
 * This reports data quality and the actual dynamic decision funnel; it does
 * NOT build a profitability strategy, place an order or infer missing bars.
 */
@Service
public class PrimaryTimeframeQualityService {
    private static final long PERIOD = 300L * 3_600_000L;
    private final MarketDataService data;
    private final BacktestService backtests;

    public PrimaryTimeframeQualityService(MarketDataService data, BacktestService backtests) {
        this.data = Objects.requireNonNull(data);
        this.backtests = Objects.requireNonNull(backtests);
    }

    public record CandleQuality(String interval, int expectedBars, int observedBars,
            int zeroTradeBars, int zeroBaseVolumeBars, int noActivityBars,
            int zeroRangeBars, int zeroRangeWithTradesBars,
            int inconsistentVolumeTradeBars, int longestNoActivityRunBars,
            int longestNoActivityMinutes, BigDecimal noActivityPct) {}

    public record CrossTimeframeQuality(String smallerInterval, String largerInterval,
            int expectedRollups, int ohlcMismatchRollups, int volumeMismatchRollups) {}

    public record TrendDistribution(PrimaryTimeframeArm arm, String interval,
            String role, String trend, int observations, int totalUniqueCandles) {}

    public record DecisionStage(PrimaryTimeframeArm arm, String stage, String reason,
            int decisions, int totalDecisionCandles, int proposedEntries,
            int filledEntries, int blockedByRisk) {}

    public record Audit(String symbol, long fromOpenTime, long asOfCloseTime,
            int periodHours, List<CandleQuality> candles,
            List<CrossTimeframeQuality> crossTimeframes,
            List<TrendDistribution> trends, List<DecisionStage> funnel,
            String interpretation) {}

    public Audit audit(BacktestRequest request) {
        if (request == null || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || request.asOf() % 3_600_000L != 3_599_999L
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())
                || !request.shouldIncludeDecisionHistory()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Quality audit requires SOLUSDT, 3600x5m, hourly close, "
                            + "RETEST-only, includeDecisionHistory=true, no Luna");
        }
        long from = request.asOf() - PERIOD + 1L;
        Map<String, List<MarketCandle>> tapes = new LinkedHashMap<>();
        var candles = new ArrayList<CandleQuality>();
        for (var tf : List.of("5m", "15m", "1h")) {
            int expected = switch (tf) { case "5m" -> 3600; case "15m" -> 1200;
                                        default -> 300; };
            var bars = new ArrayList<>(data.recentAtOrBefore(
                    request.symbol(), tf, expected, request.asOf()));
            Collections.reverse(bars);
            verifyTape(bars, request.symbol(), tf, expected, from, request.asOf());
            tapes.put(tf, List.copyOf(bars));
            candles.add(measure(tf, bars));
        }
        var rollups = List.of(
                compareRollups(tapes.get("5m"), tapes.get("15m"), "5m", "15m", 3),
                compareRollups(tapes.get("15m"), tapes.get("1h"), "15m", "1h", 4));

        var fifteenRequest = new BacktestRequest(request.symbol(), List.of("15m", "1h"),
                1200, request.contextLimit(), request.asOf(), false,
                request.priceTick(), request.quantityStep(), request.minimumQuantity(),
                request.minimumNotionalUsdt(), request.breakEvenTriggerROverride(),
                request.maxCostToInitialRiskPctOverride(), request.maxHoldingCandles(),
                false, true, true);

        var trends = new ArrayList<TrendDistribution>();
        var funnel = new ArrayList<DecisionStage>();
        for (var arm : PrimaryTimeframeArm.values()) {
            var replay = arm == PrimaryTimeframeArm.PRIMARY_5M_CONTEXT_15M
                    ? backtests.runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH,
                            ReentryWaitPolicy.WAIT_12_BARS)
                    : backtests.runPrimary15mComparison(fifteenRequest);
            var result = replay.backtest();
            String primary = arm == PrimaryTimeframeArm.PRIMARY_5M_CONTEXT_15M ? "5m" : "15m";
            String higher = arm == PrimaryTimeframeArm.PRIMARY_5M_CONTEXT_15M ? "15m" : "1h";
            int expected = primary.equals("5m") ? 3600 : 1200;
            if (result.replayedCandles() != expected
                    || !result.intervals().equals(List.of(primary, higher))
                    || result.fromCandleOpenTime() != from
                    || result.throughCandleCloseTime() != request.asOf()
                    || result.javaDecisionHistory().size() != expected - 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Historical decision trace incomplete for " + primary);
            }
            var seen = new LinkedHashMap<String, LinkedHashMap<Long, Trend>>();
            seen.put(primary, new LinkedHashMap<>());
            seen.put(higher, new LinkedHashMap<>());
            Map<String, Integer> stages = new LinkedHashMap<>();
            for (var trace : result.javaDecisionHistory()) {
                for (var tf : trace.timeframeTrends()) {
                    if (!seen.containsKey(tf.interval())) continue;
                    if (tf.asOfCloseTime() > trace.candleCloseTime()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Future higher-timeframe trend in audit");
                    }
                    Trend old = seen.get(tf.interval()).putIfAbsent(
                            tf.asOfCloseTime(), tf.trend());
                    if (old != null && old != tf.trend()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Inconsistent trend at a single completed candle");
                    }
                }
                String stage = stage(trace);
                String reason = "RISK_BLOCKED".equals(stage)
                        ? trace.riskReason().name() : trace.reason().name();
                stages.merge(stage + "|" + reason, 1, Integer::sum);
            }
            for (var item : seen.entrySet()) {
                var count = new EnumMap<Trend, Integer>(Trend.class);
                item.getValue().values().forEach(t -> count.merge(t, 1, Integer::sum));
                for (Trend trend : Trend.values()) {
                    trends.add(new TrendDistribution(arm, item.getKey(),
                            item.getKey().equals(primary) ? "PRIMARY" : "CONTEXT",
                            trend.name(), count.getOrDefault(trend, 0),
                            item.getValue().size()));
                }
            }
            int proposed = stages.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("PROPOSED_ENTRY|"))
                    .mapToInt(Map.Entry::getValue).sum();
            int riskBlocked = stages.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("RISK_BLOCKED|"))
                    .mapToInt(Map.Entry::getValue).sum();
            int filled = result.javaPortfolio().tradesClosed()
                    + ("FLAT".equals(result.javaPortfolio().openPositionSide()) ? 0 : 1);
            int sum = stages.values().stream().mapToInt(Integer::intValue).sum();
            if (sum != expected - 1 || filled > proposed) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Decision funnel does not reconcile with primary closed candles");
            }
            for (var row : stages.entrySet()) {
                String[] parts = row.getKey().split("\\|", 2);
                funnel.add(new DecisionStage(arm, parts[0], parts[1], row.getValue(),
                        expected - 1, proposed, filled, riskBlocked));
            }
        }
        return new Audit(request.symbol(), from, request.asOf(), 300,
                List.copyOf(candles), rollups, List.copyOf(trends), List.copyOf(funnel),
                "RETROSPECTIVE_INSPECTED_W46_51; TESTNET_ONLY; NO_SYNTHETIC_DATA; "
                        + "NO_TRADES_EXECUTED; FUNNEL_ON_POSITION_AWARE_DYNAMIC_REPLAY; "
                        + "TRENDS_UNIQUE_COMPLETED_CANDLES_ONLY; "
                        + "ZERO_ACTIVITY_IS_NOT_A_MISSING_CANDLE; "
                        + "CROSS_TIMEFRAME_DISCREPANCIES_REPORTED_NOT_REPAIRED");
    }

    private static void verifyTape(List<MarketCandle> tape, String symbol, String tf,
            int expected, long from, long until) {
        long step = CandleInterval.parse(tf).millis();
        if (tape.size() != expected)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Missing " + tf + " history: " + tape.size() + "/" + expected);
        for (int i = 0; i < tape.size(); i++) {
            var c = tape.get(i);
            if (!c.closed() || !symbol.equals(c.symbol()) || !tf.equals(c.interval().code())
                    || c.openTime() != from + i * step
                    || c.closeTime() != c.openTime() + step - 1
                    || c.closeTime() > until) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Missing, shifted or unclosed " + tf + " candle at index " + i);
            }
        }
    }

    static CandleQuality measure(String tf, List<MarketCandle> bars) {
        int noTrades = 0, noBase = 0, inactive = 0, zeroRange = 0,
                zeroRangeWithTrades = 0, inconsistent = 0, maxRun = 0, run = 0;
        for (var bar : bars) {
            boolean tradeZero = bar.numberOfTrades() == 0;
            boolean volumeZero = bar.baseVolume().signum() == 0;
            boolean noActivity = tradeZero && volumeZero
                    && bar.quoteVolume().signum() == 0
                    && bar.takerBuyBaseVolume().signum() == 0
                    && bar.takerBuyQuoteVolume().signum() == 0;
            boolean flat = bar.high().compareTo(bar.low()) == 0;
            if (tradeZero) noTrades++;
            if (volumeZero) noBase++;
            if (flat) zeroRange++;
            if (flat && !tradeZero) zeroRangeWithTrades++;
            if (tradeZero != volumeZero) inconsistent++;
            if (noActivity) {
                inactive++;
                run++;
                maxRun = Math.max(maxRun, run);
            } else run = 0;
        }
        return new CandleQuality(tf, bars.size(), bars.size(), noTrades,
                noBase, inactive, zeroRange, zeroRangeWithTrades, inconsistent, maxRun,
                maxRun * (int)(CandleInterval.parse(tf).millis() / 60_000L),
                BigDecimal.valueOf(inactive * 100L)
                        .divide(BigDecimal.valueOf(bars.size()), 6, RoundingMode.HALF_UP));
    }

    static CrossTimeframeQuality compareRollups(List<MarketCandle> smaller,
            List<MarketCandle> larger, String smallTf, String largeTf, int ratio) {
        if (smaller.size() != ratio * larger.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rollup timeframes not matched");
        int ohlc = 0, volume = 0;
        for (int i = 0; i < larger.size(); i++) {
            var large = larger.get(i);
            var first = smaller.get(i*ratio);
            var last = smaller.get(i*ratio + ratio - 1);
            BigDecimal high = first.high(), low = first.low(), base = BigDecimal.ZERO;
            long trades = 0;
            for (int j = 0; j < ratio; j++) {
                var c = smaller.get(i*ratio + j);
                high = high.max(c.high());
                low = low.min(c.low());
                base = base.add(c.baseVolume());
                trades += c.numberOfTrades();
            }
            if (first.openTime() != large.openTime() || last.closeTime() != large.closeTime()
                    || first.open().compareTo(large.open()) != 0
                    || last.close().compareTo(large.close()) != 0
                    || high.compareTo(large.high()) != 0
                    || low.compareTo(large.low()) != 0) ohlc++;
            // Rounding is a provider property; count raw discrepancies for inspection.
            if (base.compareTo(large.baseVolume()) != 0
                    || trades != large.numberOfTrades()) volume++;
        }
        return new CrossTimeframeQuality(smallTf, largeTf, larger.size(), ohlc, volume);
    }

    static String stage(BacktestResult.DecisionTrace trace) {
        if (!"FLAT".equals(trace.positionSideAtDecision())) return "POSITION_ACTIVE";
        if (trace.riskAction() == RiskPreviewResult.Action.BLOCKED) return "RISK_BLOCKED";
        if (trace.riskAction() == RiskPreviewResult.Action.PROPOSED_ENTRY) return "PROPOSED_ENTRY";
        return switch (trace.reason()) {
            case TREND_UNCLEAR -> "TREND_UNCLEAR";
            case NO_SETUP -> "NO_SETUP";
            case VOLUME_NOT_CONFIRMED -> "VOLUME_NOT_CONFIRMED";
            case ENTRY_BREAKOUT_FILTER -> "BREAKOUT_FILTER";
            case ENTRY_REENTRY_WAIT -> "REENTRY_WAIT";
            case HIGHER_TIMEFRAME_CONFLICT -> "HIGHER_TIMEFRAME_CONFLICT";
            case STALE_MARKET_DATA -> "STALE_MARKET_DATA";
            default -> "OTHER_WAIT";
        };
    }
}
