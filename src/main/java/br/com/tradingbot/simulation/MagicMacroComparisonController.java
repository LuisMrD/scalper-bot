package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Experiment exclusively in memory. Neither Binance orders nor Luna calls. */
@RestController
@RequestMapping("/api/v1/backtest")
public class MagicMacroComparisonController {
    private final MagicMacroComparisonService comparison;

    public MagicMacroComparisonController(MagicMacroComparisonService comparison) {
        this.comparison = comparison;
    }

    @PostMapping("/compare-magic-macro")
    public MagicMacroComparisonService.Comparison compare(@RequestBody BacktestRequest request) {
        return comparison.compare(request);
    }
}
