CREATE TABLE projects (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
    name VARCHAR(120) NOT NULL,
    instructions TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(owner_id) REFERENCES users(id),
    INDEX project_owner(owner_id,updated_at)
) ENGINE=InnoDB;
ALTER TABLE conversations ADD project_id CHAR(36) CHARACTER SET ascii NULL,
    ADD FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE SET NULL;
ALTER TABLE tasks ADD project_id CHAR(36) CHARACTER SET ascii NULL,
    ADD project_name VARCHAR(120) NULL,
    ADD project_instructions TEXT NULL,
    ADD FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE SET NULL;
CREATE TABLE project_materials (
    project_id CHAR(36) CHARACTER SET ascii NOT NULL,
    material_id CHAR(36) CHARACTER SET ascii NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(project_id,material_id),
    FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY(material_id) REFERENCES materials(id)
) ENGINE=InnoDB;
CREATE TABLE project_documents (
    project_id CHAR(36) CHARACTER SET ascii NOT NULL,
    document_id CHAR(36) CHARACTER SET ascii NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(project_id,document_id),
    FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY(document_id) REFERENCES documents(id)
) ENGINE=InnoDB;
