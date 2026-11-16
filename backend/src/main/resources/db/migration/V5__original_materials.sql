CREATE TABLE materials (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
    name VARCHAR(200) NOT NULL,
    format VARCHAR(8) CHARACTER SET ascii NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(owner_id) REFERENCES users(id),
    CONSTRAINT material_format CHECK (format IN ('txt','md')),
    CONSTRAINT material_size CHECK (byte_size > 0 AND byte_size <= 2097152),
    INDEX material_owner(owner_id,created_at,id)
) ENGINE=InnoDB;
CREATE TABLE task_materials (
    task_id CHAR(36) CHARACTER SET ascii NOT NULL,
    material_id CHAR(36) CHARACTER SET ascii NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY(task_id,material_id),
    UNIQUE(task_id,position),
    FOREIGN KEY(task_id) REFERENCES tasks(id),
    FOREIGN KEY(material_id) REFERENCES materials(id)
) ENGINE=InnoDB;
