package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulation-only research API. Delegates to the existing historical replay,
 * without calling Luna or placing orders.
 */
@RestController
@RequestMapping("/api/v1/backtest")
public class FirstOppositionComparisonController {
    private final FirstOppositionComparisonService service;

    public FirstOppositionComparisonController(FirstOppositionComparisonService service) {
        this.service = service;
    }

    @PostMapping("/compare-first-opposition")
    public FirstOppositionComparisonService.Result compare(@RequestBody BacktestRequest request) {
        return service.compare(request);
    }

    @PostMapping("/compare-profit-protection")
    public FirstOppositionComparisonService.ProfitProtectionResult compareProfitProtection(
            @RequestBody BacktestRequest request) {
        return service.compareProfitProtection(request);
    }
}
