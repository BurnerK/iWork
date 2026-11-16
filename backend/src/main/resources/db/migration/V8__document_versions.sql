ALTER TABLE tasks ADD COLUMN output_mode VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'TEXT';
CREATE TABLE document_drafts (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    task_id CHAR(36) CHARACTER SET ascii NOT NULL UNIQUE,
    artifact_id CHAR(36) CHARACTER SET ascii NOT NULL UNIQUE,
    version_id CHAR(36) CHARACTER SET ascii NOT NULL UNIQUE,
    title VARCHAR(120) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
    FOREIGN KEY(task_id) REFERENCES tasks(id)
) ENGINE=InnoDB;
CREATE TABLE documents (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(owner_id) REFERENCES users(id),
    FOREIGN KEY(conversation_id) REFERENCES conversations(id)
) ENGINE=InnoDB;
CREATE TABLE document_versions (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    document_id CHAR(36) CHARACTER SET ascii NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii NOT NULL UNIQUE,
    version_number INT NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(document_id,version_number),
    FOREIGN KEY(document_id) REFERENCES documents(id),
    FOREIGN KEY(task_id) REFERENCES tasks(id)
) ENGINE=InnoDB;
