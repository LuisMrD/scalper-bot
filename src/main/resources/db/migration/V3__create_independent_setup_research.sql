-- Isolated research-only event ledger; no exchange order identifiers or execution grants.
CREATE TABLE independent_setup_research_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    timeframe VARCHAR(12) NOT NULL,
    as_of_epoch_ms BIGINT NOT NULL,
    steps INT NOT NULL,
    parameter_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_independent_research_timeframe CHECK(timeframe='5m')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE independent_setup_research_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    arm VARCHAR(48) NOT NULL,
    candle_close_epoch_ms BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    reference_price DECIMAL(30,12) NULL,
    structural_stop DECIMAL(30,12) NULL,
    box_top DECIMAL(30,12) NULL,
    box_base DECIMAL(30,12) NULL,
    detail_json LONGTEXT NULL,
    CONSTRAINT fk_independent_event_run FOREIGN KEY(run_id)
        REFERENCES independent_setup_research_runs(id),
    INDEX idx_independent_event_arm_time(run_id,arm,candle_close_epoch_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
