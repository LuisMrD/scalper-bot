package br.com.tradingbot.strategy.independent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit OOS replay only. The API never opens exchange orders. */
@RestController
@RequestMapping("/api/v1/research/independent-setups")
public final class IndependentWalkForwardController {
    private final IndependentWalkForwardService service;
    public IndependentWalkForwardController(IndependentWalkForwardService service) {
        this.service=service;
    }
    @PostMapping("/walkforward")
    public IndependentWalkForwardService.Report compare(
            @RequestBody IndependentWalkForwardService.Request request) {
        return service.compare(request);
    }
}
