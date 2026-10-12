package br.com.tradingbot.market.repository;

import br.com.tradingbot.market.model.CandleEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandleRepository extends JpaRepository<CandleEntity, Long> {
    List<CandleEntity> findByInstrument_IdAndTimeframeAndClosedTrueOrderByOpenTimeEpochMsDesc(
            Long instrumentId, String timeframe, Pageable pageable);
    @Query("select max(c.openTimeEpochMs) from CandleEntity c where c.instrument.id=:id and c.timeframe=:frame and c.closed=true")
    Long lastOpenTime(@Param("id") long instrumentId, @Param("frame") String timeframe);
}
