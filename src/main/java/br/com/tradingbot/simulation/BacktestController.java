package br.com.tradingbot.simulation;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit POST prevents accidental historical paid LLM calls from browser previews. */
@RestController
@RequestMapping("/api/v1/backtest")
public class BacktestController {
    private final BacktestService service;
    public BacktestController(BacktestService service) { this.service = service; }
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("mode", "SIMULATION_ONLY", "ordersEnabled", false,
                "automaticLunaCalls", false, "maxSteps", 18000, "maxLunaSteps", 10);
    }
    @GetMapping("/research-config")
    public BacktestService.ResearchConfiguration researchConfig() {
        return service.researchConfiguration();
    }

    @PostMapping("/run")
    public BacktestResult run(@RequestBody BacktestRequest request) {
        return service.run(request);
    }
}
