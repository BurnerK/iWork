ALTER TABLE models ADD context_window INT NULL, ADD CONSTRAINT model_context_window CHECK (context_window BETWEEN 8192 AND 131072);
CREATE TABLE conversation_contexts (
    task_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii NOT NULL,
    through_message_id BIGINT NOT NULL,
    history_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    context_json MEDIUMTEXT NOT NULL,
    context_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    summary MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(task_id) REFERENCES tasks(id),
    FOREIGN KEY(owner_id) REFERENCES users(id),
    FOREIGN KEY(conversation_id) REFERENCES conversations(id),
    INDEX context_conversation(owner_id,conversation_id,created_at)
) ENGINE=InnoDB;
