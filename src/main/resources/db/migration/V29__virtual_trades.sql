CREATE TABLE virtual_trades (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stock_asset_id BIGINT NOT NULL REFERENCES stock_assets(id) ON DELETE CASCADE,
    side VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    analysis_interval VARCHAR(24) NOT NULL,
    entry_price NUMERIC(24, 8) NOT NULL,
    entry_at TIMESTAMPTZ NOT NULL,
    entry_quote_timestamp BIGINT NOT NULL,
    entry_candle_timestamp BIGINT NOT NULL,
    entry_quote_source VARCHAR(80) NOT NULL,
    currency VARCHAR(12) NOT NULL,
    quantity NUMERIC(24, 8),
    notional_value NUMERIC(24, 8),
    entry_snapshot TEXT NOT NULL,
    exit_price NUMERIC(24, 8),
    exit_at TIMESTAMPTZ,
    exit_quote_timestamp BIGINT,
    exit_quote_source VARCHAR(80),
    exit_snapshot TEXT,
    client_request_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_virtual_trade_client_request UNIQUE (client_request_id),
    CONSTRAINT ck_virtual_trade_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_virtual_trade_status CHECK (status IN ('TRACKING', 'CLOSED')),
    CONSTRAINT ck_virtual_trade_prices CHECK (entry_price > 0 AND (exit_price IS NULL OR exit_price > 0)),
    CONSTRAINT ck_virtual_trade_size CHECK (
        (quantity IS NULL OR quantity > 0) AND
        (notional_value IS NULL OR notional_value > 0)
    )
);

CREATE INDEX idx_virtual_trades_user_entry ON virtual_trades(user_id, entry_at DESC);
CREATE INDEX idx_virtual_trades_user_asset ON virtual_trades(user_id, stock_asset_id, entry_at DESC);
