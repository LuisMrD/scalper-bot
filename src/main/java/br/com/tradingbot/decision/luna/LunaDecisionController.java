package br.com.tradingbot.decision.luna;

import br.com.tradingbot.decision.DecisionResult;
import br.com.tradingbot.decision.PositionState;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** POST explicito para impedir chamadas pagas ao simplesmente navegar/atualizar uma pagina. */
@Validated
@RestController
@RequestMapping("/api/v1/decision/luna")
public class LunaDecisionController {
    private final LunaDecisionService service;

    public LunaDecisionController(LunaDecisionService service) { this.service = service; }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", service.enabled(), "ready", service.ready(),
                "model", service.model(), "ordersEnabled", false,
                "automaticAnalysis", false);
    }

    @PostMapping("/evaluate")
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
