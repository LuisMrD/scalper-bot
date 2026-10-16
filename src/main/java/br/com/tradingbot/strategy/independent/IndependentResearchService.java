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
            boolean candidateBeforeHigherTrend) {}
    public record Report(long runId,String symbol,String interval,long firstOpen,
            long lastClose,int completedBars,String executionModel,
            Map<String,IndependentPaperPortfolio.Summary> portfolios,
            List<Event> events,Map<String,Integer> rejectionCounts) {}

    public Report backtest(IndependentResearchRequest request) {
        return replay(request,false,false);
    }
    /** Separate BTC-only stop hypotheses. Never changes the default /backtest. */
    public Report backtestStops(IndependentResearchRequest request) {
        if(!"BTCUSDT".equals(request.symbol()) || !"15m".equals(request.entryInterval())
                || !"1d".equals(request.trendInterval()) || request.steps()!=2400
                || request.contextCandles()!=60)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stop research requires BTCUSDT 15m/1d, 2400 bars, 60 warmup bars");
        return replay(request,true,false);
    }
    /** BTC-only side-by-side mixed LONG/SHORT reversal EXIT research. */
    public Report backtestReversal(IndependentResearchRequest request) {
        if(!"BTCUSDT".equals(request.symbol()) || !"15m".equals(request.entryInterval())
                || !"1d".equals(request.trendInterval()) || request.steps()!=2400
                || request.contextCandles()!=60)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Reversal exit research requires BTCUSDT 15m/1d, 2400 bars, 60 warmup");
        return replay(request,false,true);
    }
    private Report replay(IndependentResearchRequest request,boolean compareStops,
            boolean compareReversal) {
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
        int required=Math.addExact(request.contextCandles(),request.steps());
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
                tape.get(request.contextCandles()).closeTime(),
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
        var arms=compareReversal
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
            }else accounts.put(arm,new IndependentPaperPortfolio(arm,request));
        }
        var events=new ArrayList<Event>();
        for(int i=request.contextCandles();i<tape.size();i++) {
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
            var signals=compareReversal
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
                        weeklyApproved,dailyCandidate));
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
        long runId=persist(request,events,compareStops,compareReversal);
        var first=tape.get(request.contextCandles());
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
                    :""),

                Map.copyOf(summaries),List.copyOf(events),Map.copyOf(rejectionCounts));
    }

    private long persist(IndependentResearchRequest request,List<Event> events,
            boolean compareStops,boolean compareReversal) {
        var holder=new GeneratedKeyHolder();
        String parameters=compareStops
                ?json.writeValueAsString(Map.of(
                        "request",request,"researchType","BTC_15M_STOP_POLICY_COMPARISON",
                        "policies",List.of(StopResearchPolicy.values())))
                :compareReversal?json.writeValueAsString(Map.of(
                        "request",request,
                        "researchType","BTC_15M_MIXED_REVERSAL_EXIT_COMPARISON",
                        "policies",List.of(ReversalExitPolicy.values())))
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
