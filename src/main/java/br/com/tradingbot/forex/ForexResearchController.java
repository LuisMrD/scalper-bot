package br.com.tradingbot.forex;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FX spot CSV paper study; NEVER a broker order route. */
@RestController
@RequestMapping("/api/v1/research/forex")
public final class ForexResearchController {
    private final ForexPriceActionResearchService research;
    public ForexResearchController(ForexPriceActionResearchService research) {
        this.research=research;
    }
    @GetMapping("/status")
    public Map<String,Object> status() {
        return Map.of(
                "market","FOREX_OTC_SPOT",
                "mode","CSV_PAPER_ONLY_NO_ORDERS",
                "supportedPairs",java.util.List.of("EURUSD","GBPUSD","AUDUSD","NZDUSD"),
                "interval","15m",
                "direction","LONG_AND_SHORT_INITIAL_BASELINE",
                "priceSource","CSV_BID_ASK",
                "volume","TICK_VOLUME_OPTIONAL_NOT_TAKER_VOLUME",
                "strategies",java.util.List.of("FOREX_PA_V1_SHORT_PRICE_ONLY",
                        "FOREX_PA_V1_TICK_VOLUME_MIDAS_PROXY_TREND"));
    }
    public record DukascopyImportRequest(String pair,java.time.LocalDate fromUtc,
            java.time.LocalDate untilUtc) {}

    private DukascopyForexDownloader downloader;

    @org.springframework.beans.factory.annotation.Autowired
    void setDownloader(DukascopyForexDownloader downloader) {
        this.downloader=downloader;
    }

    /** Fetch only public historical BID/ASK quotes; no keys, no orders. */
    @PostMapping("/import/dukascopy")
    public DukascopyForexDownloader.DownloadReport importDukascopy(
            @RequestBody DukascopyImportRequest request) {
        return downloader.download(request.pair(),request.fromUtc(),request.untilUtc());
    }

    @PostMapping("/backtest")
    public ForexPriceActionResearchService.Report backtest(
            @RequestBody ForexPriceActionResearchService.Request request) {
        return research.backtest(request);
    }
}
