ALTER TABLE tasks ADD context_state VARCHAR(32) CHARACTER SET ascii NOT NULL DEFAULT 'NOT_OBSERVED',
    ADD context_at TIMESTAMP(6) NULL,
    ADD summary_state VARCHAR(32) CHARACTER SET ascii NOT NULL DEFAULT 'NOT_OBSERVED',
    ADD summary_at TIMESTAMP(6) NULL;
ALTER TABLE model_calls ADD error_code VARCHAR(40) CHARACTER SET ascii NULL;
ALTER TABLE material_parses ADD cleanup_attempts INT NOT NULL DEFAULT 0,
    ADD cleanup_attempted_at TIMESTAMP(6) NULL,
    ADD cleanup_confirmed_at TIMESTAMP(6) NULL;
