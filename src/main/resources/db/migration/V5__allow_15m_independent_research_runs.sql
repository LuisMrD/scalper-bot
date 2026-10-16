-- Historical research now supports 15-minute primary candles with the
-- completed daily EMA21 direction filter. Keep V3/V4 checksums unchanged.
-- This table persists paper-only research metadata, never exchange orders.
ALTER TABLE independent_setup_research_runs
  DROP CHECK ck_independent_research_timeframe,
  ADD CONSTRAINT ck_independent_research_timeframe
    CHECK (timeframe IN ('5m', '15m', '1d'));
