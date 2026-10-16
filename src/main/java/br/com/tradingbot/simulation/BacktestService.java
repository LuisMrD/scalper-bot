package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.analysis.context.MultiTimeframeMarketContext;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.decision.JavaDecisionConfiguration;
import br.com.tradingbot.decision.JavaDecisionEngine;
import br.com.tradingbot.decision.JavaDecisionParameters;
import br.com.tradingbot.decision.PositionState;
import br.com.tradingbot.decision.luna.LunaDecisionService;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import br.com.tradingbot.risk.RiskManagementConfiguration;
import br.com.tradingbot.risk.RiskPolicy;
import br.com.tradingbot.risk.RiskPreviewResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Finite historical replay, entirely in memory. No exchanges, order endpoints or portfolio writes. */
@Service
public class BacktestService {
    private final MarketDataService market;
    private final MarketContextService contexts;
    private final JavaDecisionParameters defaultJavaParameters;
    private final LunaDecisionService luna;
    private final TradingProperties trading;
    private final RiskManagementConfiguration technical;
    private final Clock clock;

    @Autowired
    public BacktestService(MarketDataService market, MarketContextService contexts,
            JavaDecisionConfiguration javaConfig, LunaDecisionService luna,
            TradingProperties trading, RiskManagementConfiguration technical) {
        this(market, contexts, javaConfig, luna, trading, technical, Clock.systemUTC());
    }

    BacktestService(MarketDataService market, MarketContextService contexts,
            JavaDecisionConfiguration javaConfig, LunaDecisionService luna,
            TradingProperties trading, RiskManagementConfiguration technical, Clock clock) {
        this.market = Objects.requireNonNull(market);
        this.contexts = Objects.requireNonNull(contexts);
        this.defaultJavaParameters = javaConfig.toParameters();
        this.luna = Objects.requireNonNull(luna);
        this.trading = Objects.requireNonNull(trading);
        this.technical = Objects.requireNonNull(technical);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Read-only effective configuration; deliberately excludes credentials and DB settings. */
    public record ResearchConfiguration(
            List<String> symbols, String executionMode, boolean ordersEnabled,
            BigDecimal capitalUsdt, BigDecimal riskPerTradePct, BigDecimal dailyStopPct,
            Integer leverage, BigDecimal feePerSidePct, BigDecimal slippagePerSidePct,
            BigDecimal breakEvenTriggerR, BigDecimal structuralAtrBuffer,
            BigDecimal maxCostToInitialRiskPct, boolean requireHigherTimeframeAlignment,
            boolean requireDominantTakerVolume, int breakoutLookback,
            int retestMaxBars, int maxPrimaryStalenessBars,
            boolean pullbackEnabled, boolean breakoutRetestEnabled) {}

    public ResearchConfiguration researchConfiguration() {
        var risk = trading.risk();
        var java = defaultJavaParameters;
        return new ResearchConfiguration(trading.symbols(), trading.execution().mode().name(),
                trading.execution().ordersEnabled(), risk.capitalUsdt(), risk.riskPerTradePct(),
                risk.dailyStopPct(), risk.leverage(), technical.feePerSidePct(),
                technical.slippagePerSidePct(), technical.breakEvenTriggerR(),
                technical.structuralAtrBuffer(), technical.maxCostToInitialRiskPct(),
                java.requireHigherTimeframeAlignment(), java.requireDominantTakerVolume(),
                java.breakoutLookback(), java.retestMaxBars(), java.maxPrimaryStalenessBars(),
                java.pullbackEnabled(), java.breakoutRetestEnabled());
    }

    /** Filtered research replay retains the same dynamic order/position ledger. */
    public record FilteredReplay(BacktestResult backtest, int filteredSignals,
            int reentryBlockedSignals, int macroBlockedSignals,
            int shortOnlyBlockedSignals, int shortRangeBlockedSignals,
            int l2Signals, int l2BlockedByCooldown, int l2FlatHistoryCandidates,
            int longContextBlockedSignals) {
        public FilteredReplay(BacktestResult backtest, int filteredSignals) {
            this(backtest, filteredSignals, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        public FilteredReplay(BacktestResult backtest, int filteredSignals, int reentryBlockedSignals) {
            this(backtest, filteredSignals, reentryBlockedSignals, 0, 0, 0, 0, 0, 0, 0);
        }
        public FilteredReplay(BacktestResult backtest, int filteredSignals,
                int reentryBlockedSignals, int macroBlockedSignals) {
            this(backtest, filteredSignals, reentryBlockedSignals, macroBlockedSignals, 0, 0, 0, 0, 0, 0);
        }
        public FilteredReplay(BacktestResult backtest, int filteredSignals,
                int reentryBlockedSignals, int macroBlockedSignals, int shortOnlyBlockedSignals) {
            this(backtest, filteredSignals, reentryBlockedSignals, macroBlockedSignals,
                    shortOnlyBlockedSignals, 0, 0, 0, 0, 0);
        }
        public FilteredReplay(BacktestResult backtest, int filteredSignals,
                int reentryBlockedSignals, int macroBlockedSignals, int shortOnlyBlockedSignals,
                int shortRangeBlockedSignals) {
            this(backtest, filteredSignals, reentryBlockedSignals, macroBlockedSignals,
                    shortOnlyBlockedSignals, shortRangeBlockedSignals, 0, 0, 0, 0);
        }

        public FilteredReplay(BacktestResult backtest, int filteredSignals,
                int reentryBlockedSignals, int macroBlockedSignals, int shortOnlyBlockedSignals,
                int shortRangeBlockedSignals, int l2Signals, int l2BlockedByCooldown) {
            this(backtest, filteredSignals, reentryBlockedSignals, macroBlockedSignals,
                    shortOnlyBlockedSignals, shortRangeBlockedSignals, l2Signals,
                    l2BlockedByCooldown, 0, 0);
        }
        public FilteredReplay(BacktestResult backtest, int filteredSignals,
                int reentryBlockedSignals, int macroBlockedSignals, int shortOnlyBlockedSignals,
                int shortRangeBlockedSignals, int l2Signals, int l2BlockedByCooldown,
                int l2FlatHistoryCandidates) {
            this(backtest, filteredSignals, reentryBlockedSignals, macroBlockedSignals,
                    shortOnlyBlockedSignals, shortRangeBlockedSignals,
                    l2Signals, l2BlockedByCooldown, l2FlatHistoryCandidates, 0);
        }
    }

    /** Existing endpoint and production callers retain the exact unfiltered behavior. */
    public BacktestResult run(BacktestRequest request) {
        return runFiltered(request, EntryBreakoutFilter.NONE).backtest();
    }

    /** Only the explicitly requested historical replay is filtered; no global state changes. */
    public FilteredReplay runFiltered(BacktestRequest request, EntryBreakoutFilter filter) {
        return runFiltered(request, filter, ReentryWaitPolicy.NONE);
    }

    /**
     * Dedicated research-only replay. A rejected RETEST entry arms a waiting
     * state; later signals are re-evaluated only with candles closed so far.
     */
    public FilteredReplay runFiltered(BacktestRequest request, EntryBreakoutFilter filter,
            ReentryWaitPolicy waitPolicy) {
        return runFiltered(request, filter, waitPolicy, MagicMacroLongPolicy.NONE);
    }

    /**
     * Four-arm experiment: SHORT_ONLY rejects executable LONG signals before
     * ledger changes. Freed capacity can generate different later SHORT trades.
     */
    public FilteredReplay runLongVersusShort(BacktestRequest request,
            LongVersusShortPolicy experiment) {
        Objects.requireNonNull(experiment, "experiment");
        if (!"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LONG/SHORT comparison requires SOL RETEST 5m/15m and 3600 bars");
        }
        return runFilteredInternal(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS, experiment.macro(), experiment.shortOnly());
    }

    /** Causal range-entry research only; no changes to normal Java strategy. */
    public FilteredReplay runShortRange(BacktestRequest request, ShortRangePolicy rangePolicy) {
        Objects.requireNonNull(rangePolicy, "rangePolicy");
        if (request == null || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Short-range research requires SOLUSDT 5m+15m, 3600 RETEST candles, no Luna");
        }
        return runFilteredInternal(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS, MagicMacroLongPolicy.NONE, false, rangePolicy);
    }

    /**
     * Independent 34..39 L2 research only. The ordinary Java engine never
     * creates L2 orders; it is generated after causal analysis of CLOSED bars.
     */
    public FilteredReplay runL2Range(BacktestRequest request, L2RangePolicy study) {
        Objects.requireNonNull(study, "study");
        if (request == null || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L2 OOS requires SOLUSDT, 3600 5m bars, 15m RANGE, RETEST-only, no Luna");
        }
        try {
            return runFilteredInternal(request, EntryBreakoutFilter.BLOCK_BOTH,
                    ReentryWaitPolicy.WAIT_12_BARS, MagicMacroLongPolicy.NONE, false,
                    ShortRangePolicy.CONTROL, study);
        } catch (ResponseStatusException e) {
            // Public research diagnostics only: names the independent
            // replay that failed while preserving the original HTTP status.
            throw new ResponseStatusException(e.getStatusCode(),
                    "L2 policy " + study + ", asOf " + request.asOf() + ": "
                            + (e.getReason() == null ? "historical data rejected" : e.getReason()),
                    e);
        }
    }

    /**
     * Frozen SOL 5m RETEST research for new windows 40..45.
     * Policy only sees CLOSED 5m/15m context and never alters exits.
     */
    public FilteredReplay runLongContext(BacktestRequest request, LongContextPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (request == null || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LONG context research requires SOLUSDT RETEST 5m/15m, 3600 bars, no Luna");
        }
        return runFilteredInternal(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS, MagicMacroLongPolicy.NONE, false,
                ShortRangePolicy.CONTROL, L2RangePolicy.CONTROL, policy);
    }

    /**
     * Research-only 15m primary/1h context: 1200 completed 15m bars = 300h.
     * No changes to regular engine or already-inspected 5m research.
     */
    public FilteredReplay runPrimary15mComparison(BacktestRequest request) {
        if (request == null || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("15m", "1h"))
                || request.steps() != 1200 || request.asOf() == null
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "15m research requires SOLUSDT 15m+1h, 1200 bars (300 hours), RETEST-only, no Luna");
        }
        return runFilteredInternal(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_4_BARS_15M, MagicMacroLongPolicy.NONE, false,
                ShortRangePolicy.CONTROL, L2RangePolicy.CONTROL, LongContextPolicy.CONTROL);
    }

    /**
     * Causal candidate snapshot, evaluated under BOTH stop rules regardless of
     * the active study arm. Diagnostics are independent of order execution.
     */
    public record BollingerFilterTrace(long triggerCloseTime, long confirmationCloseTime,
            String side, String confirmationPattern, boolean cooldownBlocked,
            BollingerFilteredPolicy.Diagnostic structural,
            BollingerFilteredPolicy.Diagnostic extreme) {}

    /** Test-only access to one frozen-target 5m Bollinger research arm. */
    public record BollingerReplay(FilteredReplay filtered, int outsideCloses,
            int confirmedSignals, int expiredSignals, int invalidatedSignals,
            int cooldownRejected, int riskApproved, java.util.Map<Long, BigDecimal> targets,
            java.util.Map<Long, Long> triggerCloses,
            java.util.Map<Long, String> confirmationPatterns, int filteredRejected,
            java.util.List<BollingerFilterTrace> diagnostics) {
        /** Previous ten-argument callers continue to work unchanged. */
        public BollingerReplay(FilteredReplay filtered, int outsideCloses,
                int confirmedSignals, int expiredSignals, int invalidatedSignals,
                int cooldownRejected, int riskApproved, java.util.Map<Long, BigDecimal> targets,
                java.util.Map<Long, Long> triggerCloses,
                java.util.Map<Long, String> confirmationPatterns, int filteredRejected) {
            this(filtered, outsideCloses, confirmedSignals, expiredSignals,
                    invalidatedSignals, cooldownRejected, riskApproved,
                    targets, triggerCloses, confirmationPatterns, filteredRejected,
                    java.util.List.of());
        }
        /** The former constructor keeps legacy research/test callers unchanged. */
        public BollingerReplay(FilteredReplay filtered, int outsideCloses,
                int confirmedSignals, int expiredSignals, int invalidatedSignals,
                int cooldownRejected, int riskApproved, java.util.Map<Long, BigDecimal> targets,
                java.util.Map<Long, Long> triggerCloses,
                java.util.Map<Long, String> confirmationPatterns) {
            this(filtered, outsideCloses, confirmedSignals, expiredSignals,
                    invalidatedSignals, cooldownRejected, riskApproved,
                    targets, triggerCloses, confirmationPatterns, 0);
        }
    }

    private static final class BollingerCounter {
        int outsideCloses, confirmedSignals, expiredSignals, invalidatedSignals;
        int cooldownRejected, riskApproved, filteredRejected;
        boolean filtered;
        BollingerFilteredPolicy.StopMode stopMode = BollingerFilteredPolicy.StopMode.STRUCTURAL;
        BollingerFilteredPolicy.VolumeMode volumeMode =
                BollingerFilteredPolicy.VolumeMode.DIRECTIONAL_LEGACY;
        final java.util.Map<Long, BigDecimal> targets = new java.util.LinkedHashMap<>();
        final java.util.Map<Long, Long> triggerCloses = new java.util.LinkedHashMap<>();
        final java.util.Map<Long, String> patterns = new java.util.LinkedHashMap<>();
        final java.util.List<BollingerFilterTrace> diagnostics = new java.util.ArrayList<>();
    }

    public BollingerReplay runBollingerStudy(BacktestRequest request) {
        if (request == null || !List.of("SOLUSDT", "DOGEUSDT", "BTCUSDT").contains(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.asOf() == null
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bollinger study: SOL/DOGE/BTC USDT RETEST 5m/15m 3600 bars, no Luna");
        }
        var counters = new BollingerCounter();
        var filtered = runFilteredInternal(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS, MagicMacroLongPolicy.NONE, false,
                ShortRangePolicy.CONTROL, L2RangePolicy.CONTROL,
                LongContextPolicy.CONTROL, counters);
        return new BollingerReplay(filtered, counters.outsideCloses,
                counters.confirmedSignals, counters.expiredSignals, counters.invalidatedSignals,
                counters.cooldownRejected, counters.riskApproved,
                java.util.Map.copyOf(counters.targets),
                java.util.Map.copyOf(counters.triggerCloses),
                java.util.Map.copyOf(counters.patterns), counters.filteredRejected);
    }

    /**
     * Daily research: 30 complete 1d candles per nonoverlapping time window,
     * with indicator history loaded before each independent window.
     * Three variants can run without changing the original 5m experiment.
     */
    public BollingerReplay runBollingerStudyDaily(BacktestRequest request,
            boolean filtered, BollingerFilteredPolicy.StopMode stopMode) {
        return runBollingerStudyDaily(request, filtered, stopMode,
                BollingerFilteredPolicy.VolumeMode.DIRECTIONAL_LEGACY);
    }

    /** Experimental independent VPA arm; all existing callers remain unchanged. */
    public BollingerReplay runBollingerStudyDaily(BacktestRequest request,
            boolean filtered, BollingerFilteredPolicy.StopMode stopMode,
            BollingerFilteredPolicy.VolumeMode volumeMode) {
        if (request == null || !List.of("SOLUSDT", "DOGEUSDT", "BTCUSDT").contains(request.symbol())
                || !request.intervals().equals(List.of("1d"))
                || request.steps() != 30 || request.asOf() == null
                || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Daily Bollinger: exactly 30 closed 1d bars, RETEST only, no Luna");
        var counters = new BollingerCounter();
        counters.filtered = filtered;
        counters.stopMode = stopMode;
        counters.volumeMode = java.util.Objects.requireNonNull(volumeMode);
        var replay = runFilteredInternal(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.NONE, MagicMacroLongPolicy.NONE, false,
                ShortRangePolicy.CONTROL, L2RangePolicy.CONTROL,
                LongContextPolicy.CONTROL, counters);
        return new BollingerReplay(replay, counters.outsideCloses,
                counters.confirmedSignals, counters.expiredSignals, counters.invalidatedSignals,
                counters.cooldownRejected, counters.riskApproved,
                java.util.Map.copyOf(counters.targets),
                java.util.Map.copyOf(counters.triggerCloses),
                java.util.Map.copyOf(counters.patterns), counters.filteredRejected,
                java.util.List.copyOf(counters.diagnostics));
    }

    /** Existing six-scenario macro endpoint remains unchanged. */
    public FilteredReplay runFiltered(BacktestRequest request, EntryBreakoutFilter filter,
            ReentryWaitPolicy waitPolicy, MagicMacroLongPolicy macroPolicy) {
        return runFilteredInternal(request, filter, waitPolicy, macroPolicy, false);
    }

    private FilteredReplay runFilteredInternal(BacktestRequest request,
            EntryBreakoutFilter filter, ReentryWaitPolicy waitPolicy,
            MagicMacroLongPolicy macroPolicy, boolean blockAllLongs) {
        return runFilteredInternal(request, filter, waitPolicy, macroPolicy, blockAllLongs,
                ShortRangePolicy.CONTROL, L2RangePolicy.CONTROL, LongContextPolicy.CONTROL);
    }

    private FilteredReplay runFilteredInternal(BacktestRequest request,
            EntryBreakoutFilter filter, ReentryWaitPolicy waitPolicy,
            MagicMacroLongPolicy macroPolicy, boolean blockAllLongs, ShortRangePolicy shortRange) {
        return runFilteredInternal(request, filter, waitPolicy, macroPolicy, blockAllLongs,
                shortRange, L2RangePolicy.CONTROL, LongContextPolicy.CONTROL);
    }

    private FilteredReplay runFilteredInternal(BacktestRequest request,
            EntryBreakoutFilter filter, ReentryWaitPolicy waitPolicy,
            MagicMacroLongPolicy macroPolicy, boolean blockAllLongs, ShortRangePolicy shortRange,
            L2RangePolicy l2Study) {
        return runFilteredInternal(request, filter, waitPolicy, macroPolicy, blockAllLongs,
                shortRange, l2Study, LongContextPolicy.CONTROL);
    }

    private FilteredReplay runFilteredInternal(BacktestRequest request,
            EntryBreakoutFilter filter, ReentryWaitPolicy waitPolicy,
            MagicMacroLongPolicy macroPolicy, boolean blockAllLongs, ShortRangePolicy shortRange,
            L2RangePolicy l2Study, LongContextPolicy longPolicy) {
        return runFilteredInternal(request, filter, waitPolicy, macroPolicy,
                blockAllLongs, shortRange, l2Study, longPolicy, null);
    }

    private FilteredReplay runFilteredInternal(BacktestRequest request,
            EntryBreakoutFilter filter, ReentryWaitPolicy waitPolicy,
            MagicMacroLongPolicy macroPolicy, boolean blockAllLongs, ShortRangePolicy shortRange,
            L2RangePolicy l2Study, LongContextPolicy longPolicy, BollingerCounter bollinger) {
        if (bollinger != null && (filter != EntryBreakoutFilter.BLOCK_BOTH
                || (waitPolicy != ReentryWaitPolicy.WAIT_12_BARS
                    && !(waitPolicy == ReentryWaitPolicy.NONE
                        && request.intervals().equals(List.of("1d"))))
                || blockAllLongs
                || shortRange != ShortRangePolicy.CONTROL || l2Study != L2RangePolicy.CONTROL
                || longPolicy != LongContextPolicy.CONTROL || macroPolicy.active()
                || request.includeLuna())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bollinger cannot combine with other experimental policies");
        }
        Objects.requireNonNull(longPolicy, "longPolicy");
        if (longPolicy != LongContextPolicy.CONTROL && (blockAllLongs
                || shortRange != ShortRangePolicy.CONTROL
                || l2Study != L2RangePolicy.CONTROL || macroPolicy.active()
                || filter != EntryBreakoutFilter.BLOCK_BOTH
                || waitPolicy != ReentryWaitPolicy.WAIT_12_BARS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LONG research may not be combined with other experimental gates");
        }
        Objects.requireNonNull(l2Study, "l2Study");
        Objects.requireNonNull(shortRange, "shortRange");
        if (l2Study != L2RangePolicy.CONTROL
                && (shortRange != ShortRangePolicy.CONTROL || blockAllLongs
                    || macroPolicy.active() || filter != EntryBreakoutFilter.BLOCK_BOTH
                    || waitPolicy != ReentryWaitPolicy.WAIT_12_BARS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L2 cannot be combined with other experimental entry filters");
        }
        if (shortRange.active() && (blockAllLongs || macroPolicy.active()
                || filter != EntryBreakoutFilter.BLOCK_BOTH
                || waitPolicy != ReentryWaitPolicy.WAIT_12_BARS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Short-range study must preserve BLOCK_BOTH and 60-minute cooldown");
        }
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(waitPolicy, "waitPolicy");
        Objects.requireNonNull(macroPolicy, "macroPolicy");
        if (blockAllLongs && (macroPolicy != MagicMacroLongPolicy.NONE
                || filter != EntryBreakoutFilter.BLOCK_BOTH
                || waitPolicy != ReentryWaitPolicy.WAIT_12_BARS
                || !"SOLUSDT".equals(request.symbol())
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.includeLuna())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SHORT_ONLY can only run in BLOCK_BOTH + 60min SOL RETEST study");
        }
        if (macroPolicy.active() && (filter != EntryBreakoutFilter.BLOCK_BOTH
                || waitPolicy != ReentryWaitPolicy.WAIT_12_BARS
                || !request.symbol().equals("SOLUSDT")
                || !request.intervals().equals(List.of("5m", "15m"))
                || request.steps() != 3600 || request.includeLuna()
                || !Boolean.FALSE.equals(request.pullbackEnabledOverride())
                || !Boolean.TRUE.equals(request.breakoutRetestEnabledOverride())
                || !trading.symbols().containsAll(List.of("BTCUSDT", "ETHUSDT")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Magic macro research requires SOLUSDT, 3600 5m, 15m context, "
                    + "BLOCK_BOTH, WAIT_12_BARS, RETEST-only, no Luna and BTC/ETH enabled");
        }
        if (waitPolicy != ReentryWaitPolicy.NONE
                && (filter != EntryBreakoutFilter.BLOCK_BOTH || request.includeLuna()
                    || !(waitPolicy == ReentryWaitPolicy.WAIT_4_BARS_15M
                        ? request.intervals().equals(List.of("15m", "1h"))
                            && request.steps() == 1200
                        : request.intervals().getFirst().equals("5m")
                            && request.intervals().contains("15m")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Reentry research requires BLOCK_BOTH with 5m+15m or"
                            + " WAIT_4_BARS_15M with 15m+1h and 1200 bars, no Luna");
        }
        if (filter != EntryBreakoutFilter.NONE && request.includeLuna())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Filtered JAVA research does not support includeLuna=true");
        // Enabling LUNA globally does not trigger billing: caller MUST opt in on this POST.
        if (request.includeLuna() && !luna.ready())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Luna precisa de LUNA_ENABLED=true e OPENAI_API_KEY para backtest pago");
        if (!trading.symbols().contains(request.symbol()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Par nao configurado");
        for (var interval : request.intervals()) {
            try { CandleInterval.parse(interval); }
            catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
            }
        }
        RiskPolicy policy = policy(request);
        long now = clock.millis();
        long cutoff = request.asOf() == null ? now : request.asOf();
        if (cutoff < 0 || cutoff > now)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asOf invalido ou futuro");
        // Exactly the same primary timeframe candles (and close times) for BOTH ledgers.
        var tape = new ArrayList<MarketCandle>(market.recentAtOrBefore(
                request.symbol(), request.intervals().getFirst(), request.steps(), cutoff));
        Collections.reverse(tape);
        if (tape.size() != request.steps())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Candles primarios insuficientes para replay solicitado");
        for (int i=0; i<tape.size(); i++) {
            var bar = tape.get(i);
            if (!bar.closed() || !bar.symbol().equals(request.symbol())
                    || !bar.interval().code().equals(request.intervals().getFirst())
                    || bar.closeTime() > cutoff || (i>0 && bar.openTime()
                        - tape.get(i-1).openTime() != bar.interval().millis()))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Historico com lacunas, candlesticks abertos ou timestamps invalidos");
        }
        // Macro data are read from LOCAL CLOSED TESTNET 15m candles, only
        // when the explicit macro experiment is active. 1350 bars cover
        // 1200x15m (300 hours) plus >9h lookback without future peeking.
        CrossAssetMacroMomentum btcMacro = null;
        CrossAssetMacroMomentum ethMacro = null;
        if (macroPolicy.active()) {
            if (cutoff % 900_000L != 899_999L) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Macro asOf must be aligned to closed UTC 15m");
            }
            btcMacro = new CrossAssetMacroMomentum("BTCUSDT",
                    market.recentAtOrBefore("BTCUSDT", "15m", 1350, cutoff));
            ethMacro = new CrossAssetMacroMomentum("ETHUSDT",
                    market.recentAtOrBefore("ETHUSDT", "15m", 1350, cutoff));
        }
        // This is a separate read-only research dataset of CLOSED SOL 15m bars.
        // Only the upper-third arm needs it. Blocking all RANGE shorts needs
        // only the already as-of-safe 15m trend from prepared context.
        ClosedRangeLocation rangeLocation = null;
        if (shortRange == ShortRangePolicy.UPPER_THIRD_OF_30_CLOSED_15M_BARS
                || l2Study == L2RangePolicy.REPLACE_RANGE_SHORT_RETEST_WITH_L2) {
            rangeLocation = new ClosedRangeLocation(request.symbol(),
                    market.recentAtOrBefore(request.symbol(), "15m", 1350, cutoff));
        }
        FailedBreakoutL2Detector l2Detector =
                l2Study == L2RangePolicy.REPLACE_RANGE_SHORT_RETEST_WITH_L2
                    ? new FailedBreakoutL2Detector(rangeLocation) : null;
        // Uma carga por timeframe, seguida de janelas asOf isoladas em memoria.
        // A barra final nao emite decisao, pois nao existe T+1 para executa-la.
        var prepared = contexts.prepareReplay(request.symbol(), request.intervals(),
                request.contextLimit(), tape.getFirst().closeTime(),
                tape.get(tape.size() - 2).closeTime());
        boolean includeDecisionHistory = request.shouldIncludeDecisionHistory();
        var javaHistory = new ArrayList<BacktestResult.DecisionTrace>(
                includeDecisionHistory ? tape.size() - 1 : 0);
        var lunaHistory = new ArrayList<BacktestResult.DecisionTrace>(
                includeDecisionHistory && request.includeLuna() ? tape.size() - 1 : 0);
        // Immutable engine local to this replay; concurrent HTTP requests cannot
        // change another replay's setup flags or the configured global strategy.
        var javaEngine = new JavaDecisionEngine(resolveJavaParameters(request));
        var javaLedger = new BacktestLedger("JAVA", policy, request);
        BacktestLedger lunaLedger = request.includeLuna() ? new BacktestLedger("LUNA", policy, request) : null;
        int filteredSignals = 0;
        int reentryBlockedSignals = 0;
        int macroBlockedSignals = 0;
        int shortOnlyBlockedSignals = 0;
        int shortRangeBlockedSignals = 0;
        int longContextBlockedSignals = 0;
        int l2Signals = 0;
        int l2BlockedByCooldown = 0;
        long lastL2FailureUsed = -1L;
        var waitState = new ReentryWaitState(waitPolicy);
        var bollingerConfirmation = bollinger == null
                ? null : new BollingerReversalConfirmation();
        var bollingerPolicy = bollinger == null || !bollinger.filtered
                ? null : new BollingerFilteredPolicy();
        for (int i=0; i<tape.size(); i++) {
            MarketCandle bar = tape.get(i);
            javaLedger.begin(bar);
            if (lunaLedger != null) lunaLedger.begin(bar);
            // Last bar only settles pending executions and marks portfolio; no future bar
            // exists to execute a newly issued signal, so no LLM request is performed.
            DecisionResult javaDecision = null;
            DecisionResult lunaDecision = null;
            RiskPreviewResult javaRisk = null;
            RiskPreviewResult lunaRisk = null;
            if (i < tape.size()-1) {
                MultiTimeframeMarketContext context;
                try {
                    context = prepared.at(bar.closeTime());
                } catch (ResponseStatusException e) {
                    throw new ResponseStatusException(e.getStatusCode(),
                            "Market context on closed " + request.intervals().getFirst()
                                    + " bar " + bar.closeTime()
                                    + ": " + e.getReason(), e);
                }
                var first = context.timeframes().getFirst();
                if (context.requestedAsOfEpochMs() != bar.closeTime()
                        || first.asOfCloseTime() != bar.closeTime()
                        || first.recentCandles().getLast().openTime() != bar.openTime())
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Contexto nao corresponde ao candle historico avaliado");
                var javaSide = javaLedger.side();
                javaDecision = javaEngine.evaluate(context, new PositionState(javaSide));
                if (bollinger != null && javaLedger.isBollingerPosition()) {
                    // Existing BB position waits for its frozen middle SMA or
                    // initial hard stop. It never uses the retest reversal exit.
                    javaDecision = new DecisionResult("JAVA", request.symbol(), bar.interval().code(),
                            bar.closeTime(), bar.closeTime(), DecisionResult.Entry.WAIT,
                            DecisionResult.PositionAction.HOLD, DecisionResult.Setup.NONE,
                            DecisionResult.Reason.POSITION_HOLD);
                }
                br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend trend15m = null;
                if (waitPolicy != ReentryWaitPolicy.NONE) {
                    trend15m = context.timeframes().stream()
                            .filter(frame -> frame.interval().equals(
                                    waitPolicy == ReentryWaitPolicy.WAIT_4_BARS_15M ? "1h" : "15m"))
                            .findFirst()
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "Required higher-timeframe trend missing from replay"))
                            .priceAction().trend();
                    waitState.onClosedCandle(trend15m);
                }
                // BLOCK_BOTH gets precedence over wait so a fresh rejected
                // signal restarts the fixed delay without ever becoming an entry.
                boolean originalFiltered = filter.blocks(javaDecision,
                        first.priceAction().breakout().state());
                if (originalFiltered) {
                    filteredSignals++;
                    if (waitPolicy != ReentryWaitPolicy.NONE) waitState.onEntryRejected(trend15m);
                    javaDecision = rejectedEntry(javaDecision, DecisionResult.Reason.ENTRY_BREAKOUT_FILTER);
                } else if (waitPolicy != ReentryWaitPolicy.NONE && waitState.blocksEntry()
                        && javaDecision.setup() == DecisionResult.Setup.BREAKOUT_RETEST
                        && javaDecision.entry() != DecisionResult.Entry.WAIT) {
                    reentryBlockedSignals++;
                    javaDecision = rejectedEntry(javaDecision, DecisionResult.Reason.ENTRY_REENTRY_WAIT);
                }
                // SHORT_ONLY is tested AFTER original rejection and cooldown,
                // and BEFORE independent ledger mutation. No future data used.
                if (blockAllLongs && LongVersusShortPolicy.SHORT_ONLY.rejectsLong(javaDecision)) {
                    shortOnlyBlockedSignals++;
                    javaDecision = rejectedEntry(javaDecision, DecisionResult.Reason.ENTRY_SHORT_ONLY_FILTER);
                }
                // Never alter SHORT entry rules; LONG macro inputs are completed 15m bars.
                if (macroPolicy.active()
                        && javaDecision.entry() == DecisionResult.Entry.LONG
                        && javaDecision.setup() == DecisionResult.Setup.BREAKOUT_RETEST) {
                    var btcAtSignal = btcMacro.atMagic(bar.closeTime());
                    var ethAtSignal = ethMacro.atMagic(bar.closeTime());
                    if (btcAtSignal.lastClosed15mTime() != ethAtSignal.lastClosed15mTime()) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "BTC/ETH macro candle close times differ at SOL signal");
                    }
                    if (!macroPolicy.allows(btcAtSignal, ethAtSignal)) {
                        macroBlockedSignals++;
                        javaDecision = rejectedEntry(javaDecision, DecisionResult.Reason.ENTRY_MACRO_FILTER);
                    }
                }
                if (longPolicy != LongContextPolicy.CONTROL
                        && longPolicy.blocks(javaDecision, trend15m,
                                first.priceAction().breakout().state(),
                                first.volume().recentObservations().getLast().volumeLevel())) {
                    longContextBlockedSignals++;
                    javaDecision = rejectedEntry(javaDecision,
                            DecisionResult.Reason.ENTRY_LONG_CONTEXT_FILTER);
                }
                // The new gate acts on a completed signal only, after all
                // prior filters, before risk and NEXT_OPEN fill. It never
                // changes LONG, exits, HOLD, or already-open positions.
                if (shortRange.active()
                        && javaDecision.entry() == DecisionResult.Entry.SHORT
                        && javaDecision.setup() == DecisionResult.Setup.BREAKOUT_RETEST
                        && javaDecision.positionAction() == DecisionResult.PositionAction.NONE
                        && trend15m == br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend.RANGE) {
                    boolean block = shortRange == ShortRangePolicy.BLOCK_ALL_15M_RANGE_SHORTS;
                    if (shortRange == ShortRangePolicy.UPPER_THIRD_OF_30_CLOSED_15M_BARS) {
                        block = !rangeLocation.at(bar.closeTime(), bar.close()).upperThird();
                    }
                    if (block) {
                        shortRangeBlockedSignals++;
                        javaDecision = rejectedEntry(javaDecision, DecisionResult.Reason.ENTRY_SHORT_RANGE_FILTER);
                    }
                }
                // Retest SHORT continuation in RANGE is suppressed in BOTH
                // non-control arms. L2 arm may propose its separate reversal
                // signal on the same closed bar if it fulfills ALL safety
                // conditions, with no pending position and no original
                // BLOCK_BOTH rejection.
                if (l2Study != L2RangePolicy.CONTROL
                        && trend15m == br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend.RANGE
                        && javaDecision.entry() == DecisionResult.Entry.SHORT
                        && javaDecision.setup() == DecisionResult.Setup.BREAKOUT_RETEST) {
                    shortRangeBlockedSignals++;
                    javaDecision = rejectedEntry(javaDecision, DecisionResult.Reason.ENTRY_SHORT_RANGE_FILTER);
                }
                if (l2Detector != null && javaSide == br.com.tradingbot.decision.PositionState.Side.FLAT
                        && trend15m == br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend.RANGE
                        && !originalFiltered
                        && (javaDecision.reason() == DecisionResult.Reason.NO_SETUP
                            || javaDecision.reason() == DecisionResult.Reason.TREND_UNCLEAR
                            || javaDecision.reason() == DecisionResult.Reason.ENTRY_SHORT_RANGE_FILTER)) {
                    FailedBreakoutL2Detector.Signal proposed;
                    try {
                        proposed = l2Detector.detect(first.recentCandles());
                    } catch (ResponseStatusException e) {
                        throw new ResponseStatusException(e.getStatusCode(),
                                "L2 historical range on closed 5m bar " + bar.closeTime()
                                        + ": " + e.getReason(), e);
                    }
                    if (proposed != null && proposed.failureCloseTime() != lastL2FailureUsed) {
                        if (waitState.blocksEntry()) {
                            l2BlockedByCooldown++;
                        } else {
                            // The shared risk engine chooses the LATEST
                            // confirmed HIGH pivot as the structural stop.
                            // Ensure that pivot is AT/ABOVE the failed
                            // breakout wick, otherwise reject the L2 signal.
                            var riskPivot = first.priceAction().confirmedPivots().stream()
                                    .filter(p -> p.type() ==
                                        br.com.tradingbot.analysis.brooks.PriceActionSnapshot.PivotType.HIGH
                                            && p.confirmedAtCloseTime() <= bar.closeTime()
                                            && p.price().compareTo(bar.close()) > 0)
                                    .max(java.util.Comparator.comparingLong(
                                            br.com.tradingbot.analysis.brooks.PriceActionSnapshot
                                            .ConfirmedPivot::confirmedAtCloseTime));
                            var volume = first.volume().recentObservations().getLast();
                            if (riskPivot.isPresent()
                                    && riskPivot.get().price().compareTo(proposed.failedBreakHigh()) >= 0
                                    && volume.takerBalance() ==
                                        br.com.tradingbot.analysis.volume.VolumeSnapshot.TakerBalance.SELL_DOMINANT
                                    && volume.volumeLevel() !=
                                        br.com.tradingbot.analysis.volume.VolumeSnapshot.VolumeLevel.LOW
                                    && volume.volumeLevel() !=
                                        br.com.tradingbot.analysis.volume.VolumeSnapshot.VolumeLevel.UNAVAILABLE) {
                                javaDecision = new DecisionResult("JAVA", request.symbol(), "5m",
                                        context.requestedAsOfEpochMs(), bar.closeTime(),
                                        DecisionResult.Entry.SHORT,
                                        DecisionResult.PositionAction.NONE,
                                        DecisionResult.Setup.RANGE_FALSE_BREAK_L2,
                                        DecisionResult.Reason.RANGE_FALSE_BREAK_L2_CONFIRMED);
                                l2Signals++;
                                lastL2FailureUsed = proposed.failureCloseTime();
                            }
                        }
                    }
                }
                BollingerReversalConfirmation.Confirmation bbConfirmed = null;
                BigDecimal bbExtremeStop = null;
                if (bollingerConfirmation != null) {
                    boolean bbEligible = javaSide == PositionState.Side.FLAT
                            && javaDecision.entry() == DecisionResult.Entry.WAIT
                            && javaDecision.positionAction() == DecisionResult.PositionAction.NONE
                            && !originalFiltered;
                    if (!bbEligible) {
                        bollingerConfirmation.skipClosedBar(bar);
                    } else {
                        // Step 1: outside close ONLY arms the candidate.
                        // Step 2: a LATER closed 5m candle must show a bullish
                        // or bearish reversal pattern before an entry is proposed.
                        var bb = bollingerConfirmation.onClosedBar(first.recentCandles());
                        if (bb.armed()) bollinger.outsideCloses++;
                        if (bb.expired()) bollinger.expiredSignals++;
                        if (bb.invalidated()) bollinger.invalidatedSignals++;
                        bbConfirmed = bb.confirmed();
                        if (bbConfirmed != null) {
                            bollinger.confirmedSignals++;
                            // Evaluate both stops on the SAME immutable closed-bar
                            // context, including candidates blocked by the first
                            // filter. This is research-only and cannot place an order.
                            BollingerFilteredPolicy.Diagnostic structural = null;
                            BollingerFilteredPolicy.Diagnostic extreme = null;
                            if (bollingerPolicy != null) {
                                structural = bollingerPolicy.diagnose(context, bbConfirmed,
                                        request, policy,
                                        BollingerFilteredPolicy.StopMode.STRUCTURAL,
                                        bollinger.volumeMode);
                                extreme = bollingerPolicy.diagnose(context, bbConfirmed,
                                        request, policy,
                                        BollingerFilteredPolicy.StopMode.EXTREME_CANDLE,
                                        bollinger.volumeMode);
                                bollinger.diagnostics.add(new BollingerFilterTrace(
                                        bbConfirmed.trigger().candleCloseTime(),
                                        bbConfirmed.confirmedAtCloseTime(),
                                        bbConfirmed.trigger().side().name(),
                                        bbConfirmed.pattern().name(),
                                        waitState.blocksEntry(), structural, extreme));
                            }
                            if (waitState.blocksEntry()) {
                                bollinger.cooldownRejected++;
                                bbConfirmed = null;
                            } else {
                                if (bollingerPolicy != null) {
                                    var chosen = bollinger.stopMode
                                            == BollingerFilteredPolicy.StopMode.STRUCTURAL
                                            ? structural : extreme;
                                    if (!chosen.approved()) {
                                        bollinger.filteredRejected++;
                                        bbConfirmed = null;
                                    } else if (bollinger.stopMode
                                            == BollingerFilteredPolicy.StopMode.EXTREME_CANDLE) {
                                        bbExtremeStop = chosen.stopPrice();
                                    }
                                }
                            }
                            if (bbConfirmed != null) {
                                javaDecision = new DecisionResult("JAVA", request.symbol(), bar.interval().code(),
                                        context.requestedAsOfEpochMs(), bar.closeTime(),
                                        bbConfirmed.trigger().side()
                                                == BollingerMeanReversion.Side.LONG
                                                ? DecisionResult.Entry.LONG : DecisionResult.Entry.SHORT,
                                        DecisionResult.PositionAction.NONE,
                                        DecisionResult.Setup.BOLLINGER_MEAN_REVERSION,
                                        DecisionResult.Reason.BOLLINGER_REVERSAL_CONFIRMED);
                            }
                        }
                    }
                }
                javaRisk = javaLedger.decide(javaDecision, first, bbExtremeStop);
                if (bbConfirmed != null
                        && javaRisk.action() == RiskPreviewResult.Action.PROPOSED_ENTRY) {
                    // Middle SMA was fixed at the OUTSIDE close, not recomputed
                    // at reversal confirmation or by looking ahead to the fill.
                    var frozenMiddle = bbConfirmed.trigger().middle();
                    javaLedger.attachPendingBollingerTarget(bar.closeTime(), frozenMiddle,
                            bollinger.filtered ? BollingerFilteredPolicy.MINIMUM_NET_RR : null);
                    bollinger.targets.put(bar.closeTime(), frozenMiddle);
                    bollinger.triggerCloses.put(bar.closeTime(),
                            bbConfirmed.trigger().candleCloseTime());
                    bollinger.patterns.put(bar.closeTime(), bbConfirmed.pattern().name());
                    bollinger.riskApproved++;
                }
                if (includeDecisionHistory
                        || javaRisk.action() == RiskPreviewResult.Action.PROPOSED_ENTRY) {
                    var javaTrace = trace(context, javaDecision, javaRisk, javaSide.name());
                    if (includeDecisionHistory) javaHistory.add(javaTrace);
                    if (javaRisk.action() == RiskPreviewResult.Action.PROPOSED_ENTRY)
                        javaLedger.attachPendingEntrySnapshot(entrySnapshot(context, javaTrace));
                }
                if (lunaLedger != null) {
                    // No second DB read or context build: same snapshot, independent portfolio.
                    var lunaSide = lunaLedger.side();
                    lunaDecision = luna.evaluateContext(context, lunaSide);
                    lunaRisk = lunaLedger.decide(lunaDecision, first);
                    if (includeDecisionHistory
                            || lunaRisk.action() == RiskPreviewResult.Action.PROPOSED_ENTRY) {
                        var lunaTrace = trace(context, lunaDecision, lunaRisk, lunaSide.name());
                        if (includeDecisionHistory) lunaHistory.add(lunaTrace);
                        if (lunaRisk.action() == RiskPreviewResult.Action.PROPOSED_ENTRY)
                            lunaLedger.attachPendingEntrySnapshot(entrySnapshot(context, lunaTrace));
                    }
                }
            }
            javaLedger.markClose(bar.close(), bar.closeTime(), javaDecision, javaRisk);
            if (lunaLedger != null)
                lunaLedger.markClose(bar.close(), bar.closeTime(), lunaDecision, lunaRisk);
        }
        MarketCandle last = tape.getLast();
        BacktestResult result = new BacktestResult(request.symbol(), request.intervals(), tape.getFirst().openTime(),
                last.closeTime(), tape.size(), tape.size()-1,
                "NEXT_OPEN_WITH_ADVERSE_SLIPPAGE_STOP_FIRST",
                javaLedger.result(last.close()),
                lunaLedger == null ? null : lunaLedger.result(last.close()), lunaLedger != null,
                javaHistory, lunaHistory);
        return new FilteredReplay(result, filteredSignals, reentryBlockedSignals,
                macroBlockedSignals, shortOnlyBlockedSignals, shortRangeBlockedSignals,
                l2Signals, l2BlockedByCooldown,
                l2Detector == null ? 0 : l2Detector.zeroActivityFlatCandidates(),
                longContextBlockedSignals);
    }

    private static DecisionResult rejectedEntry(DecisionResult original, DecisionResult.Reason reason) {
        return new DecisionResult(original.engine(), original.symbol(), original.entryInterval(),
                original.requestedAsOfEpochMs(), original.evaluatedCandleCloseTime(),
                DecisionResult.Entry.WAIT, DecisionResult.PositionAction.NONE,
                DecisionResult.Setup.NONE, reason);
    }

    private static BacktestResult.DecisionTrace trace(MultiTimeframeMarketContext context,
            DecisionResult decision, RiskPreviewResult risk, String sideAtDecision) {
        var primary = context.timeframes().getFirst();
        var bar = primary.recentCandles().getLast();
        var observation = primary.volume().recentObservations().getLast();
        var trends = context.timeframes().stream().map(frame ->
                new BacktestResult.TimeframeTrend(frame.interval(),
                        frame.priceAction().trend(), frame.asOfCloseTime())).toList();
        return new BacktestResult.DecisionTrace(decision.engine(), sideAtDecision,
                bar.openTime(), bar.closeTime(), decision.entry(),
                decision.positionAction(), decision.setup(), decision.reason(),
                primary.priceAction().trend(), trends,
                observation.volumeLevel(), observation.takerBalance(),
                observation.relativeVolume(), observation.relativeSpread(),
                observation.effortResult(), risk.action(), risk.reason());
    }

    /**
     * Called at the setup's CONFIRMED CLOSED candle. Higher frames can close
     * earlier, never after the signal. No next-bar OHLC or outcome is included.
     */
    private static BacktestResult.EntrySignalSnapshot entrySnapshot(
            MultiTimeframeMarketContext context, BacktestResult.DecisionTrace trace) {
        var primary = context.timeframes().getFirst();
        var pa = primary.priceAction();
        var bar = primary.recentCandles().getLast();
        var obs = primary.volume().recentObservations().getLast();
        var latestPattern = pa.recentBars().isEmpty() ? null : pa.recentBars().getLast();
        if (latestPattern != null && latestPattern.openTime() != bar.openTime())
            latestPattern = null;
        int conflicts = (int) context.timeframes().stream().skip(1)
                .filter(x -> x.priceAction().trend() != pa.trend()).count();
        return new BacktestResult.EntrySignalSnapshot(trace, latestPattern,
                pa.breakout().state(), pa.pullbackCandidate(), pa.confirmedPivots().size(),
                primary.marketLevels().atr(), bar.close(), obs.takerBuyShare(), conflicts);
    }

    JavaDecisionParameters resolveJavaParameters(BacktestRequest request) {
        var base = defaultJavaParameters;
        boolean pullback = request.pullbackEnabledOverride() == null
                ? base.pullbackEnabled() : request.pullbackEnabledOverride();
        boolean retest = request.breakoutRetestEnabledOverride() == null
                ? base.breakoutRetestEnabled() : request.breakoutRetestEnabledOverride();
        // JavaDecisionParameters enforces at least one enabled setup.
        try {
            return new JavaDecisionParameters(base.breakoutLookback(), base.retestMaxBars(),
                    base.maxPrimaryStalenessBars(), base.requireHigherTimeframeAlignment(),
                    base.requireDominantTakerVolume(), pullback, retest);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    RiskPolicy policy(BacktestRequest request) {
        var r = trading.risk();
        if (!r.isConfigured() || technical.feePerSidePct() == null
                || technical.slippagePerSidePct() == null || technical.breakEvenTriggerR() == null
                || technical.structuralAtrBuffer() == null)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Configure TRADING_CAPITAL_USDT, TRADING_RISK_PER_TRADE_PCT, TRADING_DAILY_STOP_PCT, "
                    + "TRADING_LEVERAGE, RISK_FEE_PER_SIDE_PCT e RISK_SLIPPAGE_PER_SIDE_PCT");
        try {
            var beTrigger = request.breakEvenTriggerROverride() == null
                    ? technical.breakEvenTriggerR() : request.breakEvenTriggerROverride();
            var rawCostLimit = request.maxCostToInitialRiskPctOverride() == null
                    ? technical.maxCostToInitialRiskPct()
                    : request.maxCostToInitialRiskPctOverride();
            var costLimit = rawCostLimit != null && rawCostLimit.signum() == 0 ? null : rawCostLimit;
            return new RiskPolicy(r.capitalUsdt(), r.riskPerTradePct(), r.dailyStopPct(),
                    r.leverage(), technical.feePerSidePct(), technical.slippagePerSidePct(),
                    beTrigger, technical.structuralAtrBuffer(), costLimit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
