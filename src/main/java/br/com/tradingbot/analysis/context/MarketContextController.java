package br.com.tradingbot.analysis.context;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Snapshot somente leitura; nenhuma rota executa ordem nem consulta OpenAI. */
@Validated
@RestController
@RequestMapping("/api/v1/analysis/market-context")
public class MarketContextController {
    private final MarketContextService service;

    public MarketContextController(MarketContextService service) { this.service = service; }

    @GetMapping
    public MarketContextSnapshot single(
            @RequestParam @Pattern(regexp = "[A-Z0-9]{5,32}") String symbol,
            @RequestParam String interval,
            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) Long asOf) {
        return service.analyze(symbol, interval, limit, asOf);
    }

    @GetMapping("/multi")
    public MultiTimeframeMarketContext multiple(
            @RequestParam @Pattern(regexp = "[A-Z0-9]{5,32}") String symbol,
            @RequestParam(defaultValue = "1m,5m,15m") List<String> intervals,
            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) Long asOf) {
        return service.analyzeMultiple(symbol, intervals, limit, asOf);
    }
}
