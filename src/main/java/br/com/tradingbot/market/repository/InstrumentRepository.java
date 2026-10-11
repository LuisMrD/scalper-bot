package br.com.tradingbot.market.repository;

import br.com.tradingbot.market.model.InstrumentEntity;
import br.com.tradingbot.market.model.InstrumentMarket;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<InstrumentEntity, Long> {
    Optional<InstrumentEntity> findBySymbolAndMarketType(String symbol, InstrumentMarket marketType);
}
