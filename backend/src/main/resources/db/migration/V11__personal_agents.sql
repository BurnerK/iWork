CREATE TABLE personal_agents (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
    name VARCHAR(120) NOT NULL,
    instructions TEXT NOT NULL,
    model_id CHAR(36) CHARACTER SET ascii NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(owner_id) REFERENCES users(id),
    FOREIGN KEY(model_id) REFERENCES models(id),
    INDEX agent_owner(owner_id,updated_at)
) ENGINE=InnoDB;
ALTER TABLE tasks
    ADD agent_id CHAR(36) CHARACTER SET ascii NULL,
    ADD agent_name VARCHAR(120) NULL,
    ADD agent_instructions TEXT NULL,
    ADD FOREIGN KEY(agent_id) REFERENCES personal_agents(id);
