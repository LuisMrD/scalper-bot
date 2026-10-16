package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.config.TradingProperties;
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
 * Completely isolated walk-forward-ready replay for two LONG-only setups.
 * Research-only: neither order endpoints nor existing DecisionEngine are used.
 * Three independent paper portfolios; combined live arbitration is disabled.
 */
@Service
public class IndependentResearchService {
    private final MarketDataService market;
    private final TradingProperties trading;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;

    public IndependentResearchService(MarketDataService market,
            TradingProperties trading,JdbcTemplate jdbc) {
        this(market,trading,jdbc,Clock.systemUTC());
    }
    IndependentResearchService(MarketDataService market,TradingProperties trading,
            JdbcTemplate jdbc,Clock clock) {
        this.market=Objects.requireNonNull(market);
        this.trading=Objects.requireNonNull(trading);
        this.jdbc=Objects.requireNonNull(jdbc);
        this.clock=Objects.requireNonNull(clock);
    }
    public record Event(String arm,long closeTime,String status,String reason,
            IndependentSetupSignal signal) {}
    public record Report(long runId,String symbol,String interval,long firstOpen,
            long lastClose,int completedBars,String executionModel,
            Map<String,IndependentPaperPortfolio.Summary> portfolios,
            List<Event> events,Map<String,Integer> rejectionCounts) {}

    public Report backtest(IndependentResearchRequest request) {
        Objects.requireNonNull(request);
        if(!trading.symbols().contains(request.symbol()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Symbol not configured");
        if(request.asOf()>=clock.millis()
                || request.asOf()%CandleInterval.M5.millis()
                        !=CandleInterval.M5.millis()-1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "asOf must be a fully closed historical UTC 5m bar");
        int required=Math.addExact(request.contextCandles(),request.steps());
        var tape=new ArrayList<>(market.recentAtOrBefore(
                request.symbol(),"5m",required,request.asOf()));
        Collections.reverse(tape);
        if(tape.size()!=required)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient persisted closed 5m candles including warmup");
        ClosedCandleFeatures.check(tape);
        if(tape.getLast().closeTime()!=request.asOf())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Latest stored 5m candle does not match asOf");
        var pullback=new TrendPullbackDetector(request.parameters());
        var immediate=new RangeBreakoutDetector(request.parameters(),
                RangeBreakoutDetector.Variant.IMMEDIATE);
        var retest=new RangeBreakoutDetector(request.parameters(),
                RangeBreakoutDetector.Variant.RETEST);
        var accounts=new LinkedHashMap<String,IndependentPaperPortfolio>();
        accounts.put("TREND_PULLBACK",new IndependentPaperPortfolio("TREND_PULLBACK",request));
        accounts.put("RANGE_BREAKOUT",new IndependentPaperPortfolio("RANGE_BREAKOUT",request));
        accounts.put("RANGE_BREAKOUT_RETEST",
                new IndependentPaperPortfolio("RANGE_BREAKOUT_RETEST",request));
        var events=new ArrayList<Event>();
        for(int i=request.contextCandles();i<tape.size();i++) {
            MarketCandle c=tape.get(i);
            int start=Math.max(0,i-request.contextCandles()+1);
            var history=tape.subList(start,i+1);
            for(var account:accounts.values())account.begin(c);
            // The final bar cannot submit a signal without a NEXT opening fill.
            if(i==tape.size()-1)break;
            var signals=List.of(
                    pullback.evaluate(history,request.priceTick()),
                    immediate.onClosed(history,request.priceTick()),
                    retest.onClosed(history,request.priceTick()));
            for(var signal:signals) {
                String arm=signal.setup().name();
                var account=accounts.get(arm);
                account.count(signal);
                account.close(c,signal,history);
                if(signal.status()!=IndependentSetupSignal.Status.WAIT
                        ||!signal.rejectedFilters().isEmpty())
                    events.add(new Event(arm,c.closeTime(),signal.status().name(),
                            signal.reason(),signal));
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
        long runId=persist(request,events);
        var first=tape.get(request.contextCandles());
        return new Report(runId,request.symbol(),"5m",first.openTime(),
                tape.getLast().closeTime(),request.steps(),
                "PAPER_ONLY; T+1_OPEN; STOP_FIRST; HISTORICAL_5M; "
                + "FIXED_ASSUMED_FEES_SPREAD_SLIPPAGE_FUNDING; "
                + "SEPARATE_LONG_ONLY_ACCOUNTS; NO_REAL_ORDERS; NO_COMBINED_ARBITRATION",
                Map.copyOf(summaries),List.copyOf(events),Map.copyOf(rejectionCounts));
    }

    private long persist(IndependentResearchRequest request,List<Event> events) {
        var holder=new GeneratedKeyHolder();
        String parameters=json.writeValueAsString(request);
        jdbc.update(con-> {
            var statement=con.prepareStatement("""
                INSERT INTO independent_setup_research_runs
                (symbol,timeframe,as_of_epoch_ms,steps,parameter_json)
                VALUES(?,?,?,?,?)
                """,java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1,request.symbol());
            statement.setString(2,"5m");
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
