ALTER TABLE users
    ADD COLUMN harmonic_formation_color VARCHAR(7) NOT NULL DEFAULT '#22C55E';

ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_harmonic_formation_color;
ALTER TABLE users ADD CONSTRAINT ck_users_harmonic_formation_color
    CHECK (harmonic_formation_color ~ '^#[0-9A-F]{6}$');

CREATE TABLE user_harmonic_pattern_preferences (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    profile_version VARCHAR(64) NOT NULL,
    preferences_payload TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_harmonic_pattern_preferences_user UNIQUE (user_id),
    CONSTRAINT fk_user_harmonic_pattern_preferences_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
