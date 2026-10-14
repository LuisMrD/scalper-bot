package br.com.tradingbot.simulation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only retrospective research endpoint; not used by execution engine. */
@RestController
@RequestMapping("/api/v1/backtest")
public class ShortRegimeDiagnosticsController {
    private final ShortRegimeDiagnosticsService service;

    public ShortRegimeDiagnosticsController(ShortRegimeDiagnosticsService service) {
        this.service = service;
    }

    @PostMapping("/short-regime-diagnostics")
    public ShortRegimeDiagnosticsService.Report analyze(@RequestBody BacktestRequest request) {
        return service.analyze(request);
    }
}
