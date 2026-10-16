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
    @GetMapping("/stop-study/status")
    public Map<String,Object> stopStudyStatus() {
        return Map.of(
                "mode","PAPER_ONLY",
                "ordersEnabled",false,
                "symbol","BTCUSDT",
                "entryInterval","15m",
                "trendInterval","1d",
                "policyCount",StopResearchPolicy.values().length,
                "baseArms",4,
                "policies",java.util.Arrays.stream(StopResearchPolicy.values())
                        .map(Enum::name).toList());
    }
    @PostMapping("/backtest-stops")
    public IndependentResearchService.Report backtestStops(
            @RequestBody IndependentResearchRequest request) {
        return service.backtestStops(request);
    }

    @GetMapping("/reversal-study/status")
    public Map<String,Object> reversalStudyStatus() {
        return Map.of(
                "mode","PAPER_ONLY",
                "ordersEnabled",false,
                "symbol","BTCUSDT",
                "entryInterval","15m",
                "trendInterval","1d",
                "policyCount",ReversalExitPolicy.values().length,
                "baseArms",2,
                "policies",java.util.Arrays.stream(ReversalExitPolicy.values())
                        .map(Enum::name).toList());
    }
    @PostMapping("/backtest-reversal")
    public IndependentResearchService.Report backtestReversal(
            @RequestBody IndependentResearchRequest request) {
        return service.backtestReversal(request);
    }

    @GetMapping("/mixed-baseline-900h/status")
    public Map<String,Object> mixedBaseline900hStatus() {
        return Map.of(
                "mode","PAPER_ONLY",
                "ordersEnabled",false,
                "symbol","BTCUSDT",
                "entryInterval","15m",
                "trendInterval","1d",
                "capitalUsdt",600,
                "barsPerWindow",3600,
                "windows",12,
                "baseArms",2,
                "exitPolicy","BASELINE");
    }
    @PostMapping("/backtest-mixed-baseline-900h")
    public IndependentResearchService.Report backtestMixedBaseline900h(
            @RequestBody IndependentResearchRequest request) {
        return service.backtestMixedBaseline900h(request);
    }

    @GetMapping("/midas-study/status")
    public Map<String,Object> midasStudyStatus() {
        return Map.ofEntries(
                Map.entry("mode","PAPER_ONLY"),
                Map.entry("ordersEnabled",false),
                Map.entry("symbol","BTCUSDT"),
                Map.entry("entryInterval","15m"),
                Map.entry("trendInterval","1d"),
                Map.entry("capitalUsdt",600),
                Map.entry("barsPerWindow",3600),
                Map.entry("windows",12),
                Map.entry("baseArms",2),
                Map.entry("midasWarmupBars",1000),
                Map.entry("entryPolicies",java.util.Arrays.stream(MidasEntryPolicy.values())
                        .map(Enum::name).toList()));
    }
    @PostMapping("/backtest-midas")
    public IndependentResearchService.Report backtestMidas(
            @RequestBody IndependentResearchRequest request) {
        return service.backtestMidas900h(request);
    }

    @PostMapping("/backtest")
    public IndependentResearchService.Report backtest(
            @RequestBody IndependentResearchRequest request) {
        return service.backtest(request);
    }
}
