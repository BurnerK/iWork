ALTER TABLE tasks
    DROP CHECK task_status,
    ADD CONSTRAINT task_status CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','INTERRUPTED','REMOVED','PAUSED')),
    ADD reply_task_id CHAR(36) CHARACTER SET ascii NULL,
    ADD continued_from_id CHAR(36) CHARACTER SET ascii NULL,
    ADD FOREIGN KEY(reply_task_id) REFERENCES tasks(id),
    ADD FOREIGN KEY(continued_from_id) REFERENCES tasks(id),
    ADD UNIQUE(continued_from_id);
