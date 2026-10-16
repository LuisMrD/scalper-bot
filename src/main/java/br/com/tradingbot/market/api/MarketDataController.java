package br.com.tradingbot.market.api;

import br.com.tradingbot.config.MarketDataProperties;
import br.com.tradingbot.config.BinanceProperties;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/market-data")
public class MarketDataController {
    private final MarketDataService service;
    private final MarketDataProperties config;
    private final BinanceProperties binance;
    public MarketDataController(MarketDataService service, MarketDataProperties config,
            BinanceProperties binance) {
        this.service = service; this.config = config; this.binance = binance;
    }
    @GetMapping("/settings")
    public Object settings() {
        String host = binance.restBaseUrl().getHost();
        String source = "demo-fapi.binance.com".equalsIgnoreCase(host) ? "TESTNET"
                : "fapi.binance.com".equalsIgnoreCase(host) ? "PRODUCTION" : "UNKNOWN";
        return java.util.Map.of("intervals", config.intervals(),
                "websocketEnabled", config.websocketEnabled(),
                "bootstrapEnabled", config.bootstrapEnabled(),
                "readonly", true, "source", source);
    }
    @GetMapping("/candles")
    public List<MarketCandle> candles(@RequestParam @Pattern(regexp="[A-Z0-9]{5,32}") String symbol,
            @RequestParam String interval,@RequestParam(defaultValue="100") @Min(1) @Max(500) int limit,
            @RequestParam(required=false) Long asOf){
        // Historical preflight must not depend on the LAST 500 candles:
        // the new 450-day study needs completed 1d bars BEFORE its oldest
        // 15m decision. Null preserves the existing recent-candles API.
        return asOf==null?service.recent(symbol,interval,limit)
                :service.recentAtOrBefore(symbol,interval,limit,asOf);
    }
    @PostMapping("/sync")
    public MarketDataService.SyncResult sync(@RequestParam @Pattern(regexp="[A-Z0-9]{5,32}") String symbol,
       @RequestParam String interval,@RequestParam long from,@RequestParam long until){
        return service.sync(symbol,interval,from,until);
    }
    @PostMapping("/bootstrap")
    public List<MarketDataService.SyncResult> bootstrap(){return service.bootstrap();}
}
