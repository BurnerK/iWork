CREATE TABLE material_upload_gate (
    id TINYINT PRIMARY KEY,
    CONSTRAINT material_upload_gate_singleton CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO material_upload_gate(id) VALUES (1);
