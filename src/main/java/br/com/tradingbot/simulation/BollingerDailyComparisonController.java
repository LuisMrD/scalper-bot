package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Daily historical research; never places orders or invokes paid models. */
@RestController
@RequestMapping("/api/v1/backtest")
public final class BollingerDailyComparisonController {
    private final BollingerDailyComparisonService service;

    public BollingerDailyComparisonController(BollingerDailyComparisonService service) {
        this.service = service;
    }

    @PostMapping("/compare-bollinger-daily")
    public BollingerDailyComparisonService.Comparison compare(
            @RequestBody BacktestRequest request) {
        return service.compare(request);
    }
}
