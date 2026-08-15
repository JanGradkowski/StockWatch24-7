CREATE TABLE user_candlestick_pattern_preferences (
    id bigserial primary key,
    user_id BIGINT NOT NULL,
    profile_version VARCHAR(64) NOT NULL,
    preferences_payload TEXT NOT NULL,
    updated_at timestamp with time zone not null default current_timestamp,
    CONSTRAINT uk_user_candlestick_pattern_preferences_user UNIQUE (user_id),
    CONSTRAINT fk_user_candlestick_pattern_preferences_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
