package br.com.tradingbot.strategy.independent;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** No order API is exposed; only synchronous historical research. */
@RestController
@RequestMapping("/api/v1/research/independent-setups")
public final class IndependentResearchController {
    private final IndependentResearchService service;
    public IndependentResearchController(IndependentResearchService service) {
        this.service=service;
    }
    @GetMapping("/status")
    public Map<String,Object> status() {
        return Map.ofEntries(
                Map.entry("mode","PAPER_ONLY"),
                Map.entry("ordersEnabled",false),
                Map.entry("combinedStrategyEnabled",false),
                Map.entry("entryTimeframes",java.util.List.of("5m","15m","1d")),
                Map.entry("higherTrendForDaily","1w"),
                Map.entry("higherTrendFor15m","1d"),
                Map.entry("setupCount",1),
                Map.entry("paperVariants",4),
                Map.entry("allowedDirections",java.util.List.of("LONG","SHORT")),
                Map.entry("higherTrendRule","CLOSED_WEEKLY_EMA21_SLOPE"),
                Map.entry("higherTrendRule15m","CLOSED_DAILY_EMA21_SLOPE"),
                Map.entry("activeSetups",java.util.List.of(
                        "TREND_PULLBACK","TREND_PULLBACK_MIXED")),
                Map.entry("userThresholdsRequired",true));
    }
    @PostMapping("/backtest")
    public IndependentResearchService.Report backtest(
            @RequestBody IndependentResearchRequest request) {
        return service.backtest(request);
    }
}
