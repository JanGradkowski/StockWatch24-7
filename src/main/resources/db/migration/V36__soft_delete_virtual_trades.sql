ALTER TABLE virtual_trades
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_virtual_trades_visible_archive
    ON virtual_trades (user_id, entry_at DESC)
    WHERE deleted_at IS NULL;
