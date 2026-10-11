package br.com.tradingbot.integration.binance;

import br.com.tradingbot.market.model.InstrumentMarket;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BinanceExchangeInfo {
    private final JsonMapper mapper;
    public BinanceExchangeInfo(JsonMapper mapper) { this.mapper = mapper; }
    public record SymbolInfo(String symbol, InstrumentMarket market, String baseAsset, String quoteAsset) {}
    public Map<String, SymbolInfo> decode(String json, Set<String> requested) {
        JsonNode rows = mapper.readTree(json).path("symbols");
        if (!rows.isArray()) throw new IllegalArgumentException("exchangeInfo invalido");
        Map<String, SymbolInfo> result = new HashMap<>();
        for (JsonNode row : rows) {
            String symbol = row.path("symbol").asString("");
            if (requested.contains(symbol) && "TRADING".equals(row.path("status").asString(""))
                && "PERPETUAL".equals(row.path("contractType").asString(""))
                ) {
                result.put(symbol, new SymbolInfo(symbol, InstrumentMarket.USD_M_PERPETUAL,
                    row.path("baseAsset").asString(), row.path("quoteAsset").asString()));
            }
        }
        if (!result.keySet().containsAll(requested))
            throw new IllegalArgumentException("Pares USD-M perpetuos ausentes/inativos: " + requested.stream().filter(s->!result.containsKey(s)).toList());
        return result;
    }
}
