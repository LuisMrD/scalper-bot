package br.com.tradingbot.decision;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Arrays;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API experimental: resultado hipotetico, nenhuma rota escreve ordens. */
@Validated
@RestController
@RequestMapping("/api/v1/decision/java")
public class JavaDecisionController {
    private final JavaDecisionService service;

    public JavaDecisionController(JavaDecisionService service) { this.service = service; }

    @GetMapping
    public DecisionResult evaluate(
            @RequestParam @Pattern(regexp = "[A-Z0-9]{5,32}") String symbol,
            @RequestParam(defaultValue = "1m,5m,15m") String intervals,
            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) Long asOf,
            @RequestParam(defaultValue = "FLAT") PositionState.Side positionSide) {
        List<String> frames = Arrays.stream(intervals.split(",", -1)).map(String::trim).toList();
        return service.analyze(symbol, frames, limit, asOf, positionSide);
    }
}
