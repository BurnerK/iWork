ALTER TABLE tasks ADD base_version_id CHAR(36) CHARACTER SET ascii NULL,
    ADD FOREIGN KEY(base_version_id) REFERENCES document_versions(id);
ALTER TABLE document_drafts DROP INDEX artifact_id;
ALTER TABLE document_versions ADD title VARCHAR(120) NULL,
    ADD base_version_id CHAR(36) CHARACTER SET ascii NULL,
    ADD FOREIGN KEY(base_version_id) REFERENCES document_versions(id);
UPDATE document_versions v JOIN documents d ON d.id=v.document_id SET v.title=d.title;
ALTER TABLE document_versions MODIFY title VARCHAR(120) NOT NULL;
