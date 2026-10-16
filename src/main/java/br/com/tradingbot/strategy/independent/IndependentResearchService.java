package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.analysis.context.MarketContextService;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Independent LONG/SHORT pullback-only historical research. Four paper
 * portfolios: strict/mixed price-action pullback for each side. No live orders.
 */
@Service
public class IndependentResearchService {
    private final MarketDataService market;
    private final MarketContextService contexts;
    private final TradingProperties trading;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;
    private static final int MIDAS_WARMUP_BARS=1000;

    @org.springframework.beans.factory.annotation.Autowired
    public IndependentResearchService(MarketDataService market,
            MarketContextService contexts,TradingProperties trading,JdbcTemplate jdbc) {
        this(market,contexts,trading,jdbc,Clock.systemUTC());
    }
    IndependentResearchService(MarketDataService market,MarketContextService contexts,
            TradingProperties trading,JdbcTemplate jdbc,Clock clock) {
        this.market=Objects.requireNonNull(market);
        this.contexts=Objects.requireNonNull(contexts);
        this.trading=Objects.requireNonNull(trading);
        this.jdbc=Objects.requireNonNull(jdbc);
        this.clock=Objects.requireNonNull(clock);
    }
    public record Event(String arm,long closeTime,String status,String reason,
            IndependentSetupSignal signal, String higherTrend,
            long higherCandleCloseTime, boolean higherTrendApproved,
            boolean candidateBeforeHigherTrend,
            Map<String,MidasEntryFilter.Assessment> midasAssessments) {
        Event(String arm,long closeTime,String status,String reason,
                IndependentSetupSignal signal,String higherTrend,
                long higherCandleCloseTime,boolean higherTrendApproved,
                boolean candidateBeforeHigherTrend) {
            this(arm,closeTime,status,reason,signal,higherTrend,
                    higherCandleCloseTime,higherTrendApproved,
                    candidateBeforeHigherTrend,Map.of());
        }
    }
    public record Report(long runId,String symbol,String interval,long firstOpen,
            long lastClose,int completedBars,String executionModel,
            Map<String,IndependentPaperPortfolio.Summary> portfolios,
            List<Event> events,Map<String,Integer> rejectionCounts) {}

    public Report backtest(IndependentResearchRequest request) {
        return replay(request,false,false,false,false,false,false);
    }
    /** Separate BTC-only stop hypotheses. Never changes the default /backtest. */
    public Report backtestStops(IndependentResearchRequest request) {
        if(!"BTCUSDT".equals(request.symbol()) || !"15m".equals(request.entryInterval())
                || !"1d".equals(request.trendInterval()) || request.steps()!=2400
                || request.contextCandles()!=60)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stop research requires BTCUSDT 15m/1d, 2400 bars, 60 warmup bars");
        return replay(request,true,false,false,false,false,false);
    }
    /** BTC-only side-by-side mixed LONG/SHORT reversal EXIT research. */
    public Report backtestReversal(IndependentResearchRequest request) {
        if(!"BTCUSDT".equals(request.symbol()) || !"15m".equals(request.entryInterval())
                || !"1d".equals(request.trendInterval()) || request.steps()!=2400
                || request.contextCandles()!=60)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Reversal exit research requires BTCUSDT 15m/1d, 2400 bars, 60 warmup");
        return replay(request,false,true,false,false,false,false);
    }
    /**
     * Frozen BTC-only study: 12 disjoint 900-hour windows, 15m with closed 1d
     * direction, $600 initial capital per mixed-side paper account. The only
     * exit management is the ORIGINAL hard structural stop, structural
     * trailing, invalidation and original 30-bar maximum holding.
     */
    public Report backtestMixedBaseline900h(IndependentResearchRequest request) {
        if(!"BTCUSDT".equals(request.symbol()) || !"15m".equals(request.entryInterval())
                || !"1d".equals(request.trendInterval()) || request.steps()!=3600
                || request.contextCandles()!=60
                || request.capitalUsdt().compareTo(new java.math.BigDecimal("600"))!=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BTC baseline study requires BTCUSDT 15m/1d, 3600 bars, 60 warmup, $600 capital");
        return replay(request,false,false,true,false,false,false);
    }

    /** BTC $600, 12x900h, mixed setups with BASELINE vs two optional
     * MIDAS entry filters. Requires an additional 1,000 causal warmup bars
     * to prevent an arbitrary 60-bar MIDAS anchoring cutoff. */
    public Report backtestMidas900h(IndependentResearchRequest request) {
        if(!"BTCUSDT".equals(request.symbol()) || !"15m".equals(request.entryInterval())
                || !"1d".equals(request.trendInterval()) || request.steps()!=3600
                || request.contextCandles()!=60
                || request.capitalUsdt().compareTo(new java.math.BigDecimal("600"))!=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MIDAS study requires BTCUSDT 15m/1d, 3600 bars, 60 signal context, $600 capital");
        return replay(request,false,false,false,true,false,false);
    }

    /** In-sample DIAGNOSTIC ONLY, preserving the original 12 x 900h
     * BTC comparison and adding one hybrid policy for each mixed direction. */
    public Report backtestMidasHybrid900h(IndependentResearchRequest request) {
        validateHybridRequest(request);
        return replay(request,false,false,false,false,true,false);
    }

    /**
     * Prospective OUT-OF-SAMPLE gate: all 900 hours of 15m decisions must
     * start on/after 2026-10-16T00:00:00Z. Earlier 450d were already
     * inspected in hypothesis selection; do not relabel them as OOS.
     * The 1000 pre-start warmup bars may be earlier, as historical context.
     */
    public Report backtestMidasHybridOos900h(IndependentResearchRequest request) {
        validateHybridRequest(request);
        long firstOpen=request.asOf()-(long)request.steps()*900_000L+1L;
        long oosStart=java.time.Instant.parse("2026-10-16T00:00:00Z").toEpochMilli();
        if(firstOpen<oosStart)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "OOS HYBRID requires every decision candle from 2026-10-16 UTC onward; earliest full 900h completed window closes on 2026-11-22 11:59:59.999Z");
        return replay(request,false,false,false,false,true,true);
    }

    private static void validateHybridRequest(IndependentResearchRequest request) {
        Objects.requireNonNull(request);
        if(!"BTCUSDT".equals(request.symbol()) || !"15m".equals(request.entryInterval())
                || !"1d".equals(request.trendInterval()) || request.steps()!=3600
                || request.contextCandles()!=60
                || request.capitalUsdt().compareTo(new java.math.BigDecimal("600"))!=0
                || request.maxHoldingCandles()!=30)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Hybrid requires BTCUSDT 15m/closed 1d, $600, 3600 bars, 60 context, 30-bar max holding");
    }

    private Report replay(IndependentResearchRequest request,boolean compareStops,
            boolean compareReversal,boolean mixedBaselineOnly,boolean compareMidas,
            boolean compareHybrid,boolean hybridOos) {
        Objects.requireNonNull(request);
        if(!trading.symbols().contains(request.symbol()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Symbol not configured");
        var primaryInterval = CandleInterval.parse(request.entryInterval());
        if(request.asOf()>=clock.millis()
                || request.asOf()%primaryInterval.millis()
                        !=primaryInterval.millis()-1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "asOf must be a fully closed UTC " + request.entryInterval() + " candle");
        int warmup=(compareMidas||compareHybrid)?MIDAS_WARMUP_BARS:request.contextCandles();
        int required=Math.addExact(warmup,request.steps());
        var tape=new ArrayList<>(market.recentAtOrBefore(
                request.symbol(),request.entryInterval(),required,request.asOf()));
        Collections.reverse(tape);
        if(tape.size()!=required)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient persisted closed " + request.entryInterval()
                            + " candles including warmup");
        ClosedCandleFeatures.check(tape);
        var higherContext=contexts.prepareReplay(request.symbol(),
                List.of(request.entryInterval(),request.trendInterval()),
                request.contextCandles(),
                tape.get(warmup).closeTime(),
                tape.getLast().closeTime());
        if(tape.getLast().closeTime()!=request.asOf())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Latest stored " + request.entryInterval()
                            + " candle does not match asOf");
        var pullback=new TrendPullbackDetector(request.parameters());
        var mixedPullback=new TrendPullbackDetector(request.parameters(),
                TrendPullbackDetector.RetreatMode.MIXED_NET_DECLINE);
        var shortStrict=new ShortTrendPullbackDetector(request.parameters(),
                ShortTrendPullbackDetector.RetreatMode.ALL_BULLISH);
        var shortMixed=new ShortTrendPullbackDetector(request.parameters(),
                ShortTrendPullbackDetector.RetreatMode.MIXED_NET_RISE);
        var accounts=new LinkedHashMap<String,IndependentPaperPortfolio>();
        var arms=(compareReversal||mixedBaselineOnly||compareMidas||compareHybrid)
                ?List.of("TREND_PULLBACK_MIXED","TREND_PULLBACK_MIXED_SHORT")
                :List.of("TREND_PULLBACK","TREND_PULLBACK_MIXED",
                        "TREND_PULLBACK_SHORT","TREND_PULLBACK_MIXED_SHORT");
        for(var arm:arms) {
            if(compareStops) {
                for(var policy:StopResearchPolicy.values()) {
                    String label=arm+"__"+policy.name();
                    accounts.put(label,new IndependentPaperPortfolio(label,request,policy));
                }
            }else if(compareReversal) {
                for(var policy:ReversalExitPolicy.values()) {
                    String label=arm+"__"+policy.name();
                    accounts.put(label,new IndependentPaperPortfolio(label,request,
                            StopResearchPolicy.BASELINE,policy));
                }
            }else if(compareMidas) {
                for(var policy:MidasEntryPolicy.values())
                    accounts.put(arm+"__"+policy.name(),
                            new IndependentPaperPortfolio(arm+"__"+policy.name(),request));
            }else if(compareHybrid) {
                for(var policy:MidasHybridPolicy.values())
                    accounts.put(arm+"__"+policy.name(),
                            new IndependentPaperPortfolio(arm+"__"+policy.name(),request));
            }else accounts.put(arm,new IndependentPaperPortfolio(arm,request));
        }
        var events=new ArrayList<Event>();
        var midasLong=(compareMidas||compareHybrid)
                ?new AnchoredMidasCurve(false,request.parameters()):null;
        var midasShort=(compareMidas||compareHybrid)
                ?new AnchoredMidasCurve(true,request.parameters()):null;
        var longPoints=new ArrayList<AnchoredMidasCurve.Point>();
        var shortPoints=new ArrayList<AnchoredMidasCurve.Point>();
        for(int i=0;i<tape.size();i++) {
            if(compareMidas||compareHybrid) {
                // Feed each fully closed primary candle ONCE, from the 1,000
                // bar warmup onward. No retroactive revisions to prior points.
                longPoints.add(midasLong.observe(tape,i));
                shortPoints.add(midasShort.observe(tape,i));
            }
            if(i<warmup)continue;
            MarketCandle c=tape.get(i);
            int start=Math.max(0,i-request.contextCandles()+1);
            var history=tape.subList(start,i+1);
            for(var account:accounts.values())account.begin(c);
            // Final bar settles the already-open paper position, but cannot
            // create a signal without a NEXT open available for execution.
            if(i==tape.size()-1) {
                for(var account:accounts.values())account.settleFinal(c,history);
                break;
            }
            var observed=higherContext.at(c.closeTime());
            var higher=observed.timeframes().get(1);
            // 1d/1w retains its existing weekly filter; 15m/1d reads
            // ONLY completed UTC daily bars. No unfinished higher candle.
            WeeklyEmaDirection.Direction direction =
                    "1d".equals(request.trendInterval())
                        ?DailyEmaDirection.fromClosedDays(higher.recentCandles(),c.closeTime())
                        :WeeklyEmaDirection.fromClosedWeeks(higher.recentCandles(),c.closeTime());
            var signals=(compareReversal||mixedBaselineOnly||compareMidas||compareHybrid)
                    ?List.of(mixedPullback.evaluate(history,request.priceTick()),
                            shortMixed.evaluate(history,request.priceTick()))
                    :List.of(pullback.evaluate(history,request.priceTick()),
                            mixedPullback.evaluate(history,request.priceTick()),
                            shortStrict.evaluate(history,request.priceTick()),
                            shortMixed.evaluate(history,request.priceTick()));
            for(var original:signals) {
                var signal=original;
                boolean dailyCandidate=original.actionable();
                boolean weeklyApproved=WeeklyEmaDirection.allows(direction,signal.side());
                if(!weeklyApproved && dailyCandidate) {
                    String reason=signal.side()==IndependentSetupSignal.Side.SHORT
                            ?"HIGHER_TIMEFRAME_NOT_DOWN":"HIGHER_TIMEFRAME_NOT_UP";
                    var failures=new ArrayList<>(signal.rejectedFilters());
                    failures.add(reason);
                    signal=new IndependentSetupSignal(signal.setup(),
                            IndependentSetupSignal.Status.WAIT,c.closeTime(),null,
                            null,signal.frozenBoxTop(),signal.frozenBoxBase(),
                            reason,signal.passedFilters(),failures);
                }
                String arm=signal.setup().name();
                var midasAssessments=new LinkedHashMap<String,MidasEntryFilter.Assessment>();
                if(compareStops) {
                    for(var policy:StopResearchPolicy.values()) {
                        var account=accounts.get(arm+"__"+policy.name());
                        account.count(signal);
                        account.close(c,signal,history);
                    }
                }else if(compareReversal) {
                    for(var policy:ReversalExitPolicy.values()) {
                        var account=accounts.get(arm+"__"+policy.name());
                        account.count(signal);
                        account.close(c,signal,history);
                    }
                }else if(compareHybrid) {
                    boolean isShort=signal.side()==IndependentSetupSignal.Side.SHORT;
                    for(var policy:MidasHybridPolicy.values()) {
                        var account=accounts.get(arm+"__"+policy.name());
                        var actualFilter=policy.entryPolicy(isShort);
                        IndependentSetupSignal filtered=signal;
                        if(signal.actionable()) {
                            // LONG HYBRID is ALWAYS the unmodified original.
                            // SHORT HYBRID reuses the already tested MIDAS
                            // filter; neither variant changes protective stops.
                            var pts=isShort?shortPoints:longPoints;
                            var assessment=MidasEntryFilter.assess(actualFilter,
                                    isShort,tape,pts,i,request.parameters());
                            midasAssessments.put(policy.name(),assessment);
                            if(!assessment.allowed()) {
                                var failed=new ArrayList<>(signal.rejectedFilters());
                                failed.add(assessment.reason());
                                filtered=new IndependentSetupSignal(signal.setup(),
                                        IndependentSetupSignal.Status.WAIT,c.closeTime(),
                                        null,null,signal.frozenBoxTop(),
                                        signal.frozenBoxBase(),assessment.reason(),
                                        signal.passedFilters(),failed);
                            }
                        }
                        account.count(filtered);
                        account.close(c,filtered,history);
                    }
                }else if(compareMidas) {
                    for(var policy:MidasEntryPolicy.values()) {
                        var account=accounts.get(arm+"__"+policy.name());
                        IndependentSetupSignal filtered=signal;
                        if(signal.actionable()) {
                            var points=signal.side()==IndependentSetupSignal.Side.SHORT
                                    ?shortPoints:longPoints;
                            var assessment=MidasEntryFilter.assess(policy,
                                    signal.side()==IndependentSetupSignal.Side.SHORT,
                                    tape,points,i,request.parameters());
                            midasAssessments.put(policy.name(),assessment);
                            if(!assessment.allowed()) {
                                var failures=new ArrayList<>(signal.rejectedFilters());
                                failures.add(assessment.reason());
                                filtered=new IndependentSetupSignal(signal.setup(),
                                        IndependentSetupSignal.Status.WAIT,c.closeTime(),
                                        null,null,signal.frozenBoxTop(),signal.frozenBoxBase(),
                                        assessment.reason(),signal.passedFilters(),failures);
                            }
                        }
                        account.count(filtered);
                        account.close(c,filtered,history);
                    }
                }else {
                    var account=accounts.get(arm);
                    account.count(signal);
                    account.close(c,signal,history);
                }
                // Every fully closed primary bar reports its last completed
                // higher-timeframe EMA21 direction. WAIT bars are included.
                events.add(new Event(arm,c.closeTime(),signal.status().name(),
                        signal.reason(),signal,direction.name(),
                        higher.recentCandles().getLast().closeTime(),
                        weeklyApproved,dailyCandidate,Map.copyOf(midasAssessments)));
            }
        }
        var summaries=new LinkedHashMap<String,IndependentPaperPortfolio.Summary>();
        var rejectionCounts=new LinkedHashMap<String,Integer>();
        for(var item:accounts.entrySet()) {
            var result=item.getValue().summary(tape.getLast());
            summaries.put(item.getKey(),result);
            result.rejectionCounts().forEach((reason,count)->
                    rejectionCounts.merge(item.getKey()+":"+reason,count,Integer::sum));
        }
        long runId=persist(request,events,compareStops,compareReversal,
                mixedBaselineOnly,compareMidas,compareHybrid,hybridOos);
        var first=tape.get(warmup);
        return new Report(runId,request.symbol(),request.entryInterval(),first.openTime(),
                tape.getLast().closeTime(),request.steps(),
                "PAPER_ONLY; T+1_OPEN; STOP_FIRST; PRIMARY_" + request.entryInterval()
                + "; HIGHER_TREND_" + request.trendInterval() + "; ONLY_COMPLETED_HIGHER_BARS; "
                + "FIXED_ASSUMED_FEES_SPREAD_SLIPPAGE_FUNDING; "
                + "STRICT_AND_MIXED_PULLBACK_ONLY; NO_RANGE_BOX_STRATEGY; "
                + "HIGHER_EMA21_SLOPE_ONLY; CLOSED_1D_FOR_15M_CLOSED_1W_FOR_1D; "
                + "INDEPENDENT_LONG_SHORT_PAPER_ACCOUNTS; "
                + "NO_REAL_ORDERS; NO_COMBINED_ARBITRATION"
                + (compareStops?"; BTC_STOP_POLICY_RESEARCH; 5_POLICIES;"
                    +" SAME_DAILY_SIGNALS_PER_BASE_ARM; INITIAL_RISK_SIZED_WITH_ACTUAL_STOP"
                    :"")
                + (compareReversal?"; BTC_MIXED_REVERSAL_EXIT_RESEARCH; 3_POLICIES;"
                    +" PROTECTIVE_STRUCTURAL_STOP_UNCHANGED; OPPOSITE_PA_VOLUME_NEXT_OPEN;"
                    +" OPTIONAL_CONFIRMED_SWING_STRUCTURE_BREAK"
                    :"")
                + (mixedBaselineOnly?"; BTC_MIXED_ONLY_BASELINE_900H; 600_USDT_PER_ACCOUNT;"
                    +" 2_MIXED_ARMS; ORIGINAL_HARD_STOP_TRAILING_MAX_HOLD;"
                    +" 12_DISJOINT_900H_WINDOWS_RESET_PER_WINDOW"
                    :"")
                + (compareMidas?"; BTC_MIXED_MIDAS_900H; 3_ENTRY_POLICIES;"
                    +" 1000_CAUSAL_PRIMARY_WARMUP; ANCHORED_BASE_VOLUME_MIDPOINT;"
                    +" CONFIRMED_SWING_LOW_HIGH; ORIGINAL_STRUCTURAL_STOP;"
                    +" 600_USDT_PER_ACCOUNT; NO_REAL_ORDERS"
                    :"")
                + (compareHybrid?"; BTC_MIXED_MIDAS_HYBRID_900H; 2_POLICY_AB;"
                    +" LONG_BASELINE_SHORT_MIDAS_RETEST; ORIGINAL_STOP;"
                    +" 600_USDT_PER_ACCOUNT; 1000_BAR_MIDAS_WARMUP;"
                    +(hybridOos?" PROSPECTIVE_OOS_POST_20261015":" INSAMPLE_DIAGNOSTIC_ONLY")
                    :""),

                Map.copyOf(summaries),List.copyOf(events),Map.copyOf(rejectionCounts));
    }

    private long persist(IndependentResearchRequest request,List<Event> events,
            boolean compareStops,boolean compareReversal,boolean mixedBaselineOnly,
            boolean compareMidas,boolean compareHybrid,boolean hybridOos) {
        var holder=new GeneratedKeyHolder();
        String parameters=compareStops
                ?json.writeValueAsString(Map.of(
                        "request",request,"researchType","BTC_15M_STOP_POLICY_COMPARISON",
                        "policies",List.of(StopResearchPolicy.values())))
                :compareReversal?json.writeValueAsString(Map.of(
                        "request",request,
                        "researchType","BTC_15M_MIXED_REVERSAL_EXIT_COMPARISON",
                        "policies",List.of(ReversalExitPolicy.values())))
                :mixedBaselineOnly?json.writeValueAsString(Map.of(
                        "request",request,
                        "researchType","BTC_15M_MIXED_BASELINE_12X900H"))
                :compareMidas?json.writeValueAsString(Map.of(
                        "request",request,
                        "researchType","BTC_15M_MIXED_MIDAS_12X900H",
                        "primaryWarmupBars",MIDAS_WARMUP_BARS,
                        "policies",List.of(MidasEntryPolicy.values())))
                :compareHybrid?json.writeValueAsString(Map.of(
                        "request",request,
                        "researchType",hybridOos?"BTC_HYBRID_PROSPECTIVE_OOS"
                                :"BTC_HYBRID_INSAMPLE_DIAGNOSTIC",
                        "primaryWarmupBars",MIDAS_WARMUP_BARS,
                        "policies",List.of(MidasHybridPolicy.values()),
                        "frozenDecisionCutoffUtc","2026-10-15T23:59:59.999Z"))
                :json.writeValueAsString(request);
        jdbc.update(con-> {
            var statement=con.prepareStatement("""
                INSERT INTO independent_setup_research_runs
                (symbol,timeframe,as_of_epoch_ms,steps,parameter_json)
                VALUES(?,?,?,?,?)
                """,java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1,request.symbol());
            statement.setString(2,request.entryInterval());
            statement.setLong(3,request.asOf());
            statement.setInt(4,request.steps());
            statement.setString(5,parameters);
            return statement;
        },holder);
        long runId=Objects.requireNonNull(holder.getKey(),"Missing generated research run ID").longValue();
        jdbc.batchUpdate("""
            INSERT INTO independent_setup_research_events
            (run_id,arm,candle_close_epoch_ms,event_type,reason,
             reference_price,structural_stop,box_top,box_base,detail_json)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,events,250,(ps,event)-> {
                var signal=event.signal();
                ps.setLong(1,runId);
                ps.setString(2,event.arm());
                ps.setLong(3,event.closeTime());
                ps.setString(4,event.status());
                ps.setString(5,event.reason());
                ps.setBigDecimal(6,signal.referencePrice());
                ps.setBigDecimal(7,signal.structuralStop());
                ps.setBigDecimal(8,signal.frozenBoxTop());
                ps.setBigDecimal(9,signal.frozenBoxBase());
                ps.setString(10,json.writeValueAsString(signal));
            });
        return runId;
    }
}
