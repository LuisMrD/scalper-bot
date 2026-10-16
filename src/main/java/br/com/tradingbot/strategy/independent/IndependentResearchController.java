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
        return Map.of("mode","PAPER_ONLY","ordersEnabled",false,
                "combinedStrategyEnabled",false,
                "timeframe","5m","setupCount",2,
                "paperVariants",3,"userThresholdsRequired",true);
    }
    @PostMapping("/backtest")
    public IndependentResearchService.Report backtest(
            @RequestBody IndependentResearchRequest request) {
        return service.backtest(request);
    }
}
