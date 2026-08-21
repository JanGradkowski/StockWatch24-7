CREATE TABLE user_elliott_wave_preferences (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    profile_version VARCHAR(64) NOT NULL,
    preferences_payload TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_elliott_wave_preferences_user UNIQUE (user_id),
    CONSTRAINT fk_user_elliott_wave_preferences_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
