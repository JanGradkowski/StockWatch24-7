ALTER TABLE alert_events
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE congressional_trade_deliveries
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE insider_trade_deliveries
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_alert_events_visible_archive
    ON alert_events (alert_rule_id, sent_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_congressional_deliveries_visible_archive
    ON congressional_trade_deliveries (subscription_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_insider_deliveries_visible_archive
    ON insider_trade_deliveries (subscription_id, created_at DESC)
    WHERE deleted_at IS NULL;
