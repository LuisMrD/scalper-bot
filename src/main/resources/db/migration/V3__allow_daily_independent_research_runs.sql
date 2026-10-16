-- Preserve Flyway V2 checksum while extending research to 1d primary / 1w context.
-- No migration changes the existing trading/risk tables.
ALTER TABLE independent_setup_research_runs
  DROP CHECK ck_independent_research_timeframe,
  ADD CONSTRAINT ck_independent_research_timeframe CHECK (timeframe IN ('5m', '1d'));
