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

    /** Filtered research replay retains the same dynamic order/position ledger. */
    public record FilteredReplay(BacktestResult backtest, int filteredSignals,
            int reentryBlockedSignals) {
        public FilteredReplay(BacktestResult backtest, int filteredSignals) {
            this(backtest, filteredSignals, 0);
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
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(waitPolicy, "waitPolicy");
        if (waitPolicy != ReentryWaitPolicy.NONE
                && (filter != EntryBreakoutFilter.BLOCK_BOTH
                    || !request.intervals().getFirst().equals("5m")
                    || !request.intervals().contains("15m")
                    || request.includeLuna())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Reentry research requires BLOCK_BOTH, 5m+15m and no Luna");
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
        var waitState = new ReentryWaitState(waitPolicy);
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
                var context = prepared.at(bar.closeTime());
                var first = context.timeframes().getFirst();
                if (context.requestedAsOfEpochMs() != bar.closeTime()
                        || first.asOfCloseTime() != bar.closeTime()
                        || first.recentCandles().getLast().openTime() != bar.openTime())
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Contexto nao corresponde ao candle historico avaliado");
                var javaSide = javaLedger.side();
                javaDecision = javaEngine.evaluate(context, new PositionState(javaSide));
                br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend trend15m = null;
                if (waitPolicy != ReentryWaitPolicy.NONE) {
                    trend15m = context.timeframes().stream()
                            .filter(frame -> frame.interval().equals("15m"))
                            .findFirst()
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "15m context missing from replay"))
                            .priceAction().trend();
                    waitState.onClosedCandle(trend15m);
                }
                // BLOCK_BOTH gets precedence over wait so a fresh rejected
                // signal restarts the fixed delay without ever becoming an entry.
                if (filter.blocks(javaDecision, first.priceAction().breakout().state())) {
                    filteredSignals++;
                    if (waitPolicy != ReentryWaitPolicy.NONE) waitState.onEntryRejected(trend15m);
                    javaDecision = rejectedEntry(javaDecision, DecisionResult.Reason.ENTRY_BREAKOUT_FILTER);
                } else if (waitPolicy != ReentryWaitPolicy.NONE && waitState.blocksEntry()
                        && javaDecision.setup() == DecisionResult.Setup.BREAKOUT_RETEST
                        && javaDecision.entry() != DecisionResult.Entry.WAIT) {
                    reentryBlockedSignals++;
                    javaDecision = rejectedEntry(javaDecision, DecisionResult.Reason.ENTRY_REENTRY_WAIT);
                }
                javaRisk = javaLedger.decide(javaDecision, first);
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
        return new FilteredReplay(result, filteredSignals, reentryBlockedSignals);
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
