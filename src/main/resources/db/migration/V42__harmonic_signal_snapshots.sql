ALTER TABLE alert_events
    ADD COLUMN harmonic_endpoint_timestamp BIGINT,
    ADD COLUMN harmonic_endpoint_price DOUBLE PRECISION,
    ADD COLUMN harmonic_points_snapshot TEXT,
    ADD COLUMN harmonic_measurements_snapshot TEXT;

CREATE INDEX idx_alert_events_harmonic_endpoint
    ON alert_events (alert_rule_id, harmonic_endpoint_timestamp)
    WHERE harmonic_endpoint_timestamp IS NOT NULL;
