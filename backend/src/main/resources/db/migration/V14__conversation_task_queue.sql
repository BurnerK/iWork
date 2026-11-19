ALTER TABLE conversations ADD queue_paused BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE tasks
    DROP CHECK task_status,
    DROP CHECK task_execution,
    ADD queue_order BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
    ADD queued_message MEDIUMTEXT NULL,
    ADD queued_auth_version BIGINT NULL,
    ADD CONSTRAINT task_status CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','INTERRUPTED')),
    ADD CONSTRAINT task_execution CHECK (execution_state IN ('WAITING','RUNNING','STOP_REQUESTED','STOPPED','UNKNOWN')),
    ADD INDEX task_queue(status,conversation_id,queue_order);
