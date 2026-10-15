package br.com.tradingbot.simulation;

import br.com.tradingbot.market.service.MarketDataService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Research-only as-of BTC/ETH momentum for supplied SOL entry SIGNAL timestamps.
 * Never opens a position, calls an LLM, or fetches a candle newer than the signal.
 * Historical closed 15m candles are loaded from the same local TESTNET database.
 */
@Service
public class CrossAssetMacroService {
    private static final long WINDOW_MS = 300L * 3_600_000L;
    // 300h=1200 bars plus 24h=96 warmup and 54 spare for alignment/gaps.
    private static final int CANDLE_LIMIT = 1350;
    private final MarketDataService market;

    public CrossAssetMacroService(MarketDataService market) {
        this.market = Objects.requireNonNull(market);
    }

    public record Request(String symbol, long fromOpenTime, long asOfCloseTime,
            List<Long> signalCloseTimes) {}

    public record Observation(long signalCloseTime,
            CrossAssetMacroMomentum.Snapshot btc,
            CrossAssetMacroMomentum.Snapshot eth) {}

    public record Report(String symbol, long fromOpenTime, long asOfCloseTime,
            String marketDataSource, String timeframe,
            List<Observation> observations, String methodology) {}

    public Report analyze(Request input) {
        if (input == null || !"SOLUSDT".equals(input.symbol())
                || input.fromOpenTime() < 0
                || input.asOfCloseTime() < input.fromOpenTime()
                || input.asOfCloseTime() - input.fromOpenTime() + 1 != WINDOW_MS
                || input.asOfCloseTime() % 900_000 != 899_999
                || input.signalCloseTimes() == null
                || input.signalCloseTimes().isEmpty()
                || input.signalCloseTimes().size() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide SOLUSDT, exactly 300h, aligned 15m asOf and 1..500 entry signals");
        }
        var signals = new ArrayList<>(input.signalCloseTimes());
        if (signals.contains(null) || new HashSet<>(signals).size() != signals.size()
                || signals.stream().anyMatch(t -> t < input.fromOpenTime()
                    || t > input.asOfCloseTime() || t % 300_000 != 299_999)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Signal times must be unique, within the window and closed 5m candles");
        }
        signals.sort(Long::compare);
        CrossAssetMacroMomentum btc = new CrossAssetMacroMomentum("BTCUSDT",
                market.recentAtOrBefore("BTCUSDT", "15m",
                        CANDLE_LIMIT, input.asOfCloseTime()));
        CrossAssetMacroMomentum eth = new CrossAssetMacroMomentum("ETHUSDT",
                market.recentAtOrBefore("ETHUSDT", "15m",
                        CANDLE_LIMIT, input.asOfCloseTime()));
        var observations = new ArrayList<Observation>();
        for (long timestamp : signals) {
            var btcAt = btc.at(timestamp);
            var ethAt = eth.at(timestamp);
            if (btcAt.lastClosed15mTime() != ethAt.lastClosed15mTime()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "BTC and ETH macro candles are not time-aligned at " + timestamp);
            }
            observations.add(new Observation(timestamp, btcAt, ethAt));
        }
        return new Report(input.symbol(), input.fromOpenTime(), input.asOfCloseTime(),
                "LOCAL_CLOSED_15M", "15m",
                List.copyOf(observations),
                "Rolling 1h/4h/24h returns from completed 15m candles; "
                + "each snapshot is <= SOL signal close; no future data or trade outcomes");
    }
}
