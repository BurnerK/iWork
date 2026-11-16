CREATE TABLE material_parses (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    task_id CHAR(36) CHARACTER SET ascii NOT NULL,
    material_id CHAR(36) CHARACTER SET ascii NOT NULL,
    task_token CHAR(64) CHARACTER SET ascii NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii NOT NULL DEFAULT 'RUNNING',
    cleanup VARCHAR(20) CHARACTER SET ascii NOT NULL DEFAULT 'UNKNOWN',
    error_code VARCHAR(100) CHARACTER SET ascii,
    parsed_text MEDIUMTEXT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(task_id,material_id),
    FOREIGN KEY(task_id,material_id) REFERENCES task_materials(task_id,material_id)
) ENGINE=InnoDB;
