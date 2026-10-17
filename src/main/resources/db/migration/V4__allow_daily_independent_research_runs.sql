-- Keep already published migration checksums intact.
-- Enable both five-minute and daily independent study ledgers.
ALTER TABLE independent_setup_research_runs
  DROP CHECK ck_independent_research_timeframe,
  ADD CONSTRAINT ck_independent_research_timeframe CHECK (timeframe IN ('5m', '1d'));
