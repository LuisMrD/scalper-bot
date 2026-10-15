package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only simulation endpoint for the frozen four-arm SOL experiment. */
@RestController
@RequestMapping("/api/v1/backtest")
public class LongVersusShortComparisonController {
    private final LongVersusShortComparisonService service;

    public LongVersusShortComparisonController(LongVersusShortComparisonService service) {
        this.service = service;
    }

    @PostMapping("/compare-long-vs-short")
    public LongVersusShortComparisonService.Comparison compare(
            @RequestBody BacktestRequest request) {
        return service.compare(request);
    }
}
