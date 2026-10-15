package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Historical simulations only: never calls Binance order API or paid LLM. */
@RestController
@RequestMapping("/api/v1/backtest")
public class BollingerComparisonController {
    private final BollingerComparisonService comparison;

    public BollingerComparisonController(BollingerComparisonService comparison) {
        this.comparison = comparison;
    }

    @PostMapping("/compare-bollinger-5m")
    public BollingerComparisonService.Comparison compare(@RequestBody BacktestRequest request) {
        return comparison.compare(request);
    }
}
