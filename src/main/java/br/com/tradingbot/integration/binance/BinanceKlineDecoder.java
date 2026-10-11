package br.com.tradingbot.integration.binance;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BinanceKlineDecoder {
    private final JsonMapper mapper;
    public BinanceKlineDecoder(JsonMapper mapper) { this.mapper = mapper; }

    public List<MarketCandle> rest(String symbol, CandleInterval interval, String json, long now) {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) throw new IllegalArgumentException("Payload REST nao e uma lista");
        List<MarketCandle> candles = new ArrayList<>();
        for (JsonNode row : root) {
            if (!row.isArray() || row.size() < 11)
                throw new IllegalArgumentException("Kline REST incompleto");
            long close = row.get(6).asLong();
            candles.add(new MarketCandle(symbol, interval, row.get(0).asLong(), close,
                decimal(row.get(1)), decimal(row.get(2)), decimal(row.get(3)), decimal(row.get(4)),
                decimal(row.get(5)), decimal(row.get(7)), row.get(8).asLong(),
                decimal(row.get(9)), decimal(row.get(10)), close < now));
        }
        return candles;
    }

    /** Retorna null para eventos diferentes de kline ou barras ainda abertas. */
    public MarketCandle websocket(String json) {
        JsonNode root = mapper.readTree(json);
        JsonNode data = root.has("data") ? root.get("data") : root;
        if (!"kline".equals(data.path("e").asString(""))) return null;
        // Depois da migracao UM/CM, 1 identifica USD-M e 2 identifica COIN-M.
        if (data.has("st") && data.path("st").asInt() != 1) return null;
        JsonNode k = data.path("k");
        if (!k.path("x").asBoolean(false)) return null;
        String symbol = k.path("s").asString();
        CandleInterval interval = CandleInterval.parse(k.path("i").asString());
        return new MarketCandle(symbol, interval, k.path("t").asLong(), k.path("T").asLong(),
                decimal(k.path("o")), decimal(k.path("h")), decimal(k.path("l")), decimal(k.path("c")),
                decimal(k.path("v")), decimal(k.path("q")), k.path("n").asLong(),
                decimal(k.path("V")), decimal(k.path("Q")), true);
    }

    private static BigDecimal decimal(JsonNode node) {
        return new BigDecimal(node.asString());
    }
}
