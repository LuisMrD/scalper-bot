package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * STRICT prospective OOS study, THREE fully independent shared-equity arms.
 * Uses the existing closed-candle detectors and paper execution unchanged.
 * There is no order client, LLM call, database write or retrospective trade
 * selection. The locked OOS boundary is 2026-10-16T00:00:00Z.
 */
@Service
public final class IndependentSharedOosService {
    static final long OOS_START = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli();
    static final long IN_SAMPLE_LAST_CLOSE =
            Instant.parse("2026-10-15T23:59:59.999Z").toEpochMilli();
    static final int IN_SAMPLE_WINDOWS = 12;
    static final long HISTORICAL_HOLDOUT_START =
            Instant.parse("2023-01-01T00:00:00Z").toEpochMilli();
    static final long HISTORICAL_HOLDOUT_END =
            Instant.parse("2024-12-31T23:59:59.999Z").toEpochMilli();
    // Two FIXED, disjoint twelve-window SHORT-only cohorts. These precede
    // the already examined 2023-2024 holdout and 2025-2026 selection period.
    static final long SHORT_HOLDOUT_2020_21_END =
            Instant.parse("2021-09-30T23:59:59.999Z").toEpochMilli();
    static final long SHORT_HOLDOUT_2021_22_END =
            Instant.parse("2022-12-31T23:59:59.999Z").toEpochMilli();
    static final long SHORT_HOLDOUT_EARLIEST =
            Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
    static final int STEPS = 3600;
    static final int WARMUP = 1000;
    static final long BAR_MILLIS = 900_000L;
    private static final IndependentSetupParameters FROZEN_PARAMETERS =
            new IndependentSetupParameters(2,2,3,5,
                    b("0.5"),b("0.85"),b("0.85"),b("0.65"),
                    b("2"),b("1.2"),b("2"),10,b("2"),
                    b("0.85"),b("0.85"),b("1.2"),6,b("0.2"),
                    b("1"),14,20,IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
    private final MarketDataService market;
    private final MarketContextService contexts;
    private final TradingProperties trading;
    private final Clock clock;

    public record Event(long candleCloseTime, String side, String setupStatus,
            String higherTrend, long higherCloseTime, String midasReason,
            Map<String,String> policyReasons) {}
    public record Report(String symbol,String interval,long firstOpen,long lastClose,
            int completedBars,String executionModel,String selectionCutoffUtc,
            Map<String,SharedEntryPaperPortfolio.Summary> sharedPortfolios,
            List<Event> events) {}

    @org.springframework.beans.factory.annotation.Autowired
    public IndependentSharedOosService(MarketDataService market,
            MarketContextService contexts,TradingProperties trading) {
        this(market,contexts,trading,Clock.systemUTC());
    }
    IndependentSharedOosService(MarketDataService market,
            MarketContextService contexts,TradingProperties trading,Clock clock) {
        this.market=Objects.requireNonNull(market);
        this.contexts=Objects.requireNonNull(contexts);
        this.trading=Objects.requireNonNull(trading);
        this.clock=Objects.requireNonNull(clock);
    }

    private static BigDecimal b(String value) { return new BigDecimal(value); }
    private static boolean eq(BigDecimal actual,String expected) {
        return actual != null && actual.compareTo(b(expected))==0;
    }
    static void validateFrozen(IndependentResearchRequest r) {
        Objects.requireNonNull(r);
        if(!"BTCUSDT".equals(r.symbol()) || !"15m".equals(r.entryInterval())
                || !"1d".equals(r.trendInterval()) || r.steps()!=STEPS
                || r.contextCandles()!=60 || r.maxHoldingCandles()!=30
                || !eq(r.capitalUsdt(),"600") || !eq(r.riskPerTradePct(),"0.5")
                || !eq(r.dailyStopPct(),"3") || r.leverage()!=1
                || !eq(r.feePerSidePct(),"0.05") || !eq(r.slippagePerSidePct(),"0.02")
                || !eq(r.spreadPct(),"0.01") || !eq(r.assumedFundingPer8hPct(),"0")
                || !eq(r.assumedMaintenanceMarginPct(),"0.5")
                || !eq(r.maxCostToInitialRiskPct(),"50")
                || !matchesParameters(r.parameters()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Frozen BTC 15m/closed-1d OOS requires original 12x900h research "
                    +"parameters, $600 shared equity, 0.5% risk, 3% daily stop, "
                    +"1x, baseline fees/slippage/spread/funding and dynamic 60 hold");
        // Exchange contract metadata is deliberately NOT hard-coded; it must
        // be supplied for the same verified BTCUSDT perpetual instrument.
    }

    private static boolean matchesParameters(IndependentSetupParameters p) {
        if(p==null)return false;
        // Compare numeric thresholds by value rather than BigDecimal scale.
        var f=FROZEN_PARAMETERS;
        return p.pivotLeft()==f.pivotLeft() && p.pivotRight()==f.pivotRight()
                && p.pullbackMinBars()==f.pullbackMinBars()
                && p.pullbackMaxBars()==f.pullbackMaxBars()
                && eq(p.emaTouchTolerancePct(),"0.5")
                && eq(p.pullbackVolumeContractionMaxRatio(),"0.85")
                && eq(p.pullbackBodyContractionMaxRatio(),"0.85")
                && eq(p.bullishStrongBodyMinRatio(),"0.65")
                && eq(p.rejectionWickToBodyMinRatio(),"2")
                && eq(p.buyerVolumeGrowthMinRatio(),"1.2")
                && eq(p.exceptionalSellingVolumeMinRatio(),"2")
                && p.consolidationBars()==f.consolidationBars()
                && eq(p.boxWidthMaxAtr(),"2")
                && eq(p.boxProgressiveRangeMaxRatio(),"0.85")
                && eq(p.boxVolumeDryUpMaxRatio(),"0.85")
                && eq(p.breakoutRelativeVolumeMinRatio(),"1.2")
                && p.retestMaxBars()==f.retestMaxBars()
                && eq(p.retestTolerancePct(),"0.2")
                && eq(p.stopBufferTicks(),"1")
                && p.atrPeriod()==f.atrPeriod()
                && p.volumeLookbackBars()==f.volumeLookbackBars()
                && p.directStopRule()==f.directStopRule()
                && eq(p.trailingActivationR(),"1");
    }

    static long firstOpen(long asOf) {
        return Math.subtractExact(asOf, Math.multiplyExact((long)STEPS,BAR_MILLIS))+1L;
    }
    void validatePeriod(IndependentResearchRequest r) {
        if(r.asOf()%BAR_MILLIS!=BAR_MILLIS-1L || r.asOf()>=clock.millis())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "asOf must be a finished 15m candle before current UTC time");
        if(firstOpen(r.asOf())<OOS_START)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only genuine prospective OOS after 2026-10-15 UTC; first full "
                    +"900h window can end 2026-11-22T11:59:59.999Z. "
                    +"Earlier 2025-2026 windows are in-sample.");
    }

    /**
     * Strictly prospective evaluation: historical windows cannot enter this route.
     */
    public Report backtest(IndependentResearchRequest r) {
        return replay(r,StudyMode.PROSPECTIVE_OOS);
    }

    /**
     * Historical diagnostic against the EXACT 12 previously studied windows.
     * Never claim prospective results; selection hypotheses were already
     * informed by these candles. Each window restarts with its own cash.
     */
    public Report backtestInsample(IndependentResearchRequest r) {
        return replay(r,StudyMode.INSAMPLE);
    }

    /**
     * PRE-FROZEN historical holdout. A complete window MUST lie within
     * calendar years 2023-2024 (outside the July 2025 - October 2026
     * selection cohort). Only new data never used for parameter selection
     * provide a credible historical OOS test.
     */
    public Report backtestHistoricalHoldout(IndependentResearchRequest r) {
        return replay(r,StudyMode.HISTORICAL_HOLDOUT);
    }

    /** The three ORIGINAL short policies; LONG is not instantiated. */
    public Report backtestShortHoldout2020_21(IndependentResearchRequest r) {
        return replay(r,StudyMode.SHORT_ONLY_2020_21);
    }
    public Report backtestShortHoldout2021_22(IndependentResearchRequest r) {
        return replay(r,StudyMode.SHORT_ONLY_2021_22);
    }

    static void validateShortHoldoutPeriod(IndependentResearchRequest r,long cohortEnd,
            Clock clock) {
        if(r.asOf()%BAR_MILLIS!=BAR_MILLIS-1L || r.asOf()>=clock.millis())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Short holdout requires a completed UTC 15m candle");
        long windowMs=Math.multiplyExact((long)STEPS,BAR_MILLIS);
        long offset=cohortEnd-r.asOf();
        if(offset<0 || offset%windowMs!=0 || offset/windowMs>=12
                || firstOpen(r.asOf())<SHORT_HOLDOUT_EARLIEST)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SHORT_ONLY requires exactly one of twelve fixed disjoint "
                    +"900h windows anchored on the declared 2021 or 2022 cohort. "
                    +"Cherry-picked offsets and 2023-2026 windows are forbidden.");
    }

    void validateHistoricalHoldoutPeriod(IndependentResearchRequest r) {
        if(r.asOf()%BAR_MILLIS!=BAR_MILLIS-1L || r.asOf()>=clock.millis())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Historical holdout asOf must be a completed UTC 15m close");
        long windowMillis=Math.multiplyExact((long)STEPS,BAR_MILLIS);
        long offset=HISTORICAL_HOLDOUT_END-r.asOf();
        if(offset<0 || offset%windowMillis!=0
                || offset/windowMillis>=IN_SAMPLE_WINDOWS
                || firstOpen(r.asOf())<HISTORICAL_HOLDOUT_START)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Historical holdout accepts only the 12 frozen disjoint "
                    +"900h windows anchored at 2024-12-31T23:59:59.999Z. "
                    +"Arbitrary selection windows and previously inspected "
                    +"2025-2026 data are forbidden.");
    }

    void validateInsamplePeriod(IndependentResearchRequest r) {
        if(r.asOf()%BAR_MILLIS!=BAR_MILLIS-1L || r.asOf()>=clock.millis())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "asOf must be a finished UTC 15m candle");
        long offset=IN_SAMPLE_LAST_CLOSE-r.asOf();
        long windowMillis=Math.multiplyExact((long)STEPS,BAR_MILLIS);
        if(offset<0 || offset%windowMillis!=0
                || offset/windowMillis >= IN_SAMPLE_WINDOWS)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Historical shared test accepts only the 12 frozen, disjoint "
                    +"900h in-sample windows ending at/before "
                    +"2026-10-15T23:59:59.999Z. No OOS result can use this route.");
    }

    private enum StudyMode {
        INSAMPLE, HISTORICAL_HOLDOUT, PROSPECTIVE_OOS,
        SHORT_ONLY_2020_21, SHORT_ONLY_2021_22
    }

    private Report replay(IndependentResearchRequest r,StudyMode mode) {
        validateFrozen(r);
        switch(mode) {
            case INSAMPLE -> validateInsamplePeriod(r);
            case HISTORICAL_HOLDOUT -> validateHistoricalHoldoutPeriod(r);
            case PROSPECTIVE_OOS -> validatePeriod(r);
            case SHORT_ONLY_2020_21 ->
                    validateShortHoldoutPeriod(r,SHORT_HOLDOUT_2020_21_END,clock);
            case SHORT_ONLY_2021_22 ->
                    validateShortHoldoutPeriod(r,SHORT_HOLDOUT_2021_22_END,clock);
        }
        boolean shortOnly=mode==StudyMode.SHORT_ONLY_2020_21
                ||mode==StudyMode.SHORT_ONLY_2021_22;
        if(!trading.symbols().contains(r.symbol()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Symbol not configured");
        int required=WARMUP+STEPS;
        var tape=new ArrayList<>(market.recentAtOrBefore(
                r.symbol(),"15m",required,r.asOf()));
        Collections.reverse(tape);
        if(tape.size()!=required)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Missing closed BTC 15m candles, including 1000-bar MIDAS warmup");
        try {
            ClosedCandleFeatures.check(tape);
        }catch(IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Non-contiguous / unclosed 15m research tape: "+e.getMessage(),e);
        }
        if(tape.getLast().closeTime()!=r.asOf()
                || tape.get(WARMUP).openTime()!=firstOpen(r.asOf()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Research tape does not cover the requested exact period");
        var higherContext=contexts.prepareReplay(r.symbol(),
                List.of("15m","1d"),r.contextCandles(),
                tape.get(WARMUP).closeTime(),tape.getLast().closeTime());
        var longDetector=shortOnly?null:new TrendPullbackDetector(r.parameters(),
                TrendPullbackDetector.RetreatMode.MIXED_NET_DECLINE);
        var shortDetector=new ShortTrendPullbackDetector(r.parameters(),
                ShortTrendPullbackDetector.RetreatMode.MIXED_NET_RISE);
        var longMidas=shortOnly?null:new AnchoredMidasCurve(false,r.parameters());
        var shortMidas=new AnchoredMidasCurve(true,r.parameters());
        var longPoints=shortOnly?null:new ArrayList<AnchoredMidasCurve.Point>(required);
        var shortPoints=new ArrayList<AnchoredMidasCurve.Point>(required);
        var books=new LinkedHashMap<String,SharedEntryPaperPortfolio>();
        for(var policy:SharedEntryBookPolicy.values())
            books.put(policy.name(),new SharedEntryPaperPortfolio(policy,r,shortOnly));
        var events=new ArrayList<Event>(2*(STEPS-1));
        for(int i=0;i<tape.size();i++) {
            if(!shortOnly)longPoints.add(longMidas.observe(tape,i));
            shortPoints.add(shortMidas.observe(tape,i));
            if(i<WARMUP)continue;
            MarketCandle candle=tape.get(i);
            var history=tape.subList(Math.max(0,i-r.contextCandles()+1),i+1);
            for(var book:books.values())book.begin(candle);
            if(i==tape.size()-1) {
                for(var book:books.values())book.settleFinal(candle,history);
                break;
            }
            var higher=higherContext.at(candle.closeTime()).timeframes().get(1);
            var direction=DailyEmaDirection.fromClosedDays(
                    higher.recentCandles(),candle.closeTime());
            if(higher.recentCandles().getLast().closeTime()>candle.closeTime())
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Lookahead detected in closed 1d trend");
            var signals=shortOnly
                    ?List.of(shortDetector.evaluate(history,r.priceTick()))
                    :List.of(longDetector.evaluate(history,r.priceTick()),
                            shortDetector.evaluate(history,r.priceTick()));
            for(var original:signals) {
                var signal=original;
                boolean approved=WeeklyEmaDirection.allows(direction,signal.side());
                if(!approved && signal.actionable()) {
                    var failure=signal.side()==IndependentSetupSignal.Side.SHORT
                            ?"HIGHER_TIMEFRAME_NOT_DOWN":"HIGHER_TIMEFRAME_NOT_UP";
                    var failures=new ArrayList<>(signal.rejectedFilters());
                    failures.add(failure);
                    signal=reject(signal,candle.closeTime(),failure,failures);
                }
                String midasReason="NOT_APPLICABLE";
                // The identical original hybrid for ALL frozen configurations.
                if(signal.actionable()) {
                    boolean isShort=signal.side()==IndependentSetupSignal.Side.SHORT;
                    var gate=MidasHybridPolicy.HYBRID_LONG_BASELINE_SHORT_MIDAS_RETEST
                            .entryPolicy(isShort);
                    var assessment=MidasEntryFilter.assess(gate,isShort,tape,
                            isShort?shortPoints:longPoints,i,r.parameters());
                    midasReason=assessment.reason();
                    if(!assessment.allowed()) {
                        var failures=new ArrayList<>(signal.rejectedFilters());
                        failures.add(assessment.reason());
                        signal=reject(signal,candle.closeTime(),assessment.reason(),failures);
                    }
                }
                var reasons=new LinkedHashMap<String,String>();
                for(var book:books.values()) {
                    var screened=signal;
                    String selectionReason="NO_ACTIONABLE_SIGNAL";
                    if(signal.actionable()) {
                        var filter=book.policy().forSide(signal.side());
                        var assessed=EntrySelectionFilter.assess(filter,signal);
                        selectionReason=assessed.reason();
                        if(!assessed.allowed()) {
                            var fails=new ArrayList<>(signal.rejectedFilters());
                            fails.addAll(assessed.rejectedReasons());
                            screened=reject(signal,candle.closeTime(),assessed.reason(),fails);
                        }
                    }
                    reasons.put(book.policy().name(),selectionReason);
                    book.close(candle,screened,history,direction);
                }
                events.add(new Event(candle.closeTime(),signal.side().name(),
                        signal.status().name(),direction.name(),
                        higher.recentCandles().getLast().closeTime(),
                        midasReason,Map.copyOf(reasons)));
            }
            for(var book:books.values())book.mark(candle);
        }
        var summaries=new LinkedHashMap<String,SharedEntryPaperPortfolio.Summary>();
        for(var book:books.values())
            summaries.put(book.policy().name(),book.summary(tape.getLast()));
        return new Report(r.symbol(),"15m",tape.get(WARMUP).openTime(),
                tape.getLast().closeTime(),STEPS,
                (switch(mode) {
                    case INSAMPLE -> "PAPER_ONLY; HISTORICAL_INSAMPLE_ONLY; TWELVE_FROZEN_WINDOWS;";
                    case HISTORICAL_HOLDOUT -> "PAPER_ONLY; PRE_FROZEN_HISTORICAL_HOLDOUT;"
                            +" 2023_2024_ONLY; NOT_PROSPECTIVE;";
                    case PROSPECTIVE_OOS -> "PAPER_ONLY; STRICT_PROSPECTIVE_OOS;";
                    case SHORT_ONLY_2020_21 -> "PAPER_ONLY; SHORT_ONLY_HOLDOUT_2020_21;"
                            +" 12_DISJOINT_WINDOWS; NO_LONG_SIGNALS_OR_TRADES;";
                    case SHORT_ONLY_2021_22 -> "PAPER_ONLY; SHORT_ONLY_HOLDOUT_2021_22;"
                            +" 12_DISJOINT_WINDOWS; NO_LONG_SIGNALS_OR_TRADES;";
                })
                +" THREE_FROZEN_SHARED_WALLETS; ONE_POSITION_TOTAL;"
                +" LONG_FILL_FIRST_ON_TIES; NEXT_OPEN_STOP_FIRST;"
                +" BASELINE_LONG_MIDAS_RETEST_SHORT; DYNAMIC_TREND_60;"
                +" CLOSED_DAILY_EMA21; WEEKEND_NEXT_OPEN_UTC;"
                +" STRUCTURAL_STOP_UNCHANGED; 1000_CAUSAL_MIDAS_WARMUP;"
                +" FIXED_ASSUMED_FUNDING_0; NO_REAL_ORDERS",
                "2026-10-15T23:59:59.999Z",
                Map.copyOf(summaries),List.copyOf(events));
    }

    private static IndependentSetupSignal reject(IndependentSetupSignal s,
            long closeTime,String reason,List<String> failures) {
        return new IndependentSetupSignal(s.setup(),IndependentSetupSignal.Status.WAIT,
                closeTime,null,null,s.frozenBoxTop(),s.frozenBoxBase(),
                reason,s.passedFilters(),failures);
    }
}
