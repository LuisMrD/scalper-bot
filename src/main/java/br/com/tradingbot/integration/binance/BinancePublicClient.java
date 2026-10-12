package br.com.tradingbot.integration.binance;

import br.com.tradingbot.config.BinanceProperties;
import br.com.tradingbot.market.model.CandleInterval;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class BinancePublicClient {
    private final BinanceProperties properties;
    private final HttpClient http;
    @org.springframework.beans.factory.annotation.Autowired
    public BinancePublicClient(BinanceProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
    }
    BinancePublicClient(BinanceProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }
    public String exchangeInfo() { return get("/fapi/v1/exchangeInfo"); }
    public String candles(String symbol, CandleInterval interval, long from, long until, int limit) {
        String query = "?symbol=" + encode(symbol) + "&interval=" + encode(interval.code())
                + "&startTime=" + from + "&endTime=" + until + "&limit=" + limit;
        return get("/fapi/v1/klines" + query);
    }
    public String get(String path) {
        try {
            var req = HttpRequest.newBuilder(properties.restBaseUrl().resolve(path))
                .GET().timeout(Duration.ofSeconds(15)).header("Accept", "application/json").build();
            var response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) {
                // Sem retry automatico (429, 418 e indisponibilidade devem ser respeitados).
                throw new IllegalStateException("Binance HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consulta Binance interrompida", e);
        } catch (IOException e) {
            throw new IllegalStateException("Falha consultando Binance", e);
        }
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
