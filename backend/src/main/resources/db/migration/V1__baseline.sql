CREATE TABLE users (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) CHARACTER SET ascii NOT NULL,
    role VARCHAR(16) CHARACTER SET ascii NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    auth_version BIGINT NOT NULL DEFAULT 1,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT user_role CHECK (role IN ('ADMIN', 'USER'))
) ENGINE=InnoDB;

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

CREATE TABLE conversations (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    project_id CHAR(36) CHARACTER SET ascii NULL,
    queue_paused BOOLEAN NOT NULL DEFAULT FALSE,
    queue_pause_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT conversation_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    INDEX conversation_owner_order (owner_id, updated_at DESC, id DESC),
    FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE model_settings (
    id INT PRIMARY KEY,
    currency CHAR(3) CHARACTER SET ascii NOT NULL,
    CONSTRAINT singleton_settings CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO model_settings(id,currency) VALUES(1,'CNY');

CREATE TABLE models (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(1000) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    credential_ref VARCHAR(100) CHARACTER SET ascii NOT NULL,
    input_price DECIMAL(18,6) NOT NULL,
    output_price DECIMAL(18,6) NOT NULL,
    enabled BOOLEAN NOT NULL,
    context_window INT NULL,
    CONSTRAINT model_prices CHECK (input_price >= 0 AND output_price >= 0),
    CONSTRAINT model_context_window CHECK (context_window BETWEEN 8192 AND 131072)
) ENGINE=InnoDB;

CREATE TABLE model_access (
    user_id CHAR(36) CHARACTER SET ascii NOT NULL,
    model_id CHAR(36) CHARACTER SET ascii NOT NULL,
    PRIMARY KEY(user_id,model_id),
    FOREIGN KEY(user_id) REFERENCES users(id),
    FOREIGN KEY(model_id) REFERENCES models(id)
) ENGINE=InnoDB;

CREATE TABLE budgets (
    user_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    budget_limit DECIMAL(18,6) NOT NULL,
    spent DECIMAL(30,6) NOT NULL DEFAULT 0,
    reserved DECIMAL(30,6) NOT NULL DEFAULT 0,
    FOREIGN KEY(user_id) REFERENCES users(id),
    CONSTRAINT nonnegative_budget CHECK (budget_limit >= 0 AND spent >= 0 AND reserved >= 0)
) ENGINE=InnoDB;

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

CREATE TABLE tasks (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii NOT NULL,
    model_id CHAR(36) CHARACTER SET ascii NOT NULL,
    request_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii NOT NULL,
    error_code VARCHAR(100) CHARACTER SET ascii NULL,
    execution_state VARCHAR(20) CHARACTER SET ascii NOT NULL,
    active_conversation CHAR(36) CHARACTER SET ascii GENERATED ALWAYS AS (CASE WHEN status='RUNNING' THEN conversation_id ELSE NULL END) STORED,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    output_mode VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'TEXT',
    base_version_id CHAR(36) CHARACTER SET ascii NULL,
    agent_id CHAR(36) CHARACTER SET ascii NULL,
    agent_name VARCHAR(120) NULL,
    agent_instructions TEXT NULL,
    project_id CHAR(36) CHARACTER SET ascii NULL,
    project_name VARCHAR(120) NULL,
    project_instructions TEXT NULL,
    context_state VARCHAR(32) CHARACTER SET ascii NOT NULL DEFAULT 'NOT_OBSERVED',
    context_at TIMESTAMP(6) NULL,
    summary_state VARCHAR(32) CHARACTER SET ascii NOT NULL DEFAULT 'NOT_OBSERVED',
    summary_at TIMESTAMP(6) NULL,
    queue_order BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
    queued_message MEDIUMTEXT NULL,
    queued_auth_version BIGINT NULL,
    reply_task_id CHAR(36) CHARACTER SET ascii NULL,
    continued_from_id CHAR(36) CHARACTER SET ascii NULL,
    tool_progress JSON NULL,
    UNIQUE(owner_id,request_id),
    UNIQUE(active_conversation),
    FOREIGN KEY(owner_id) REFERENCES users(id),
    FOREIGN KEY(conversation_id) REFERENCES conversations(id),
    FOREIGN KEY(model_id) REFERENCES models(id),
    INDEX task_conversation(conversation_id,created_at,id),
    CONSTRAINT task_execution CHECK (execution_state IN ('WAITING','RUNNING','STOP_REQUESTED','STOPPED','UNKNOWN')),
    INDEX task_queue(status,conversation_id,queue_order),
    CONSTRAINT task_status CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','INTERRUPTED','REMOVED','PAUSED')),
    UNIQUE(continued_from_id),
    INDEX task_owner_queue(owner_id,status)
) ENGINE=InnoDB;

CREATE TABLE messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id CHAR(36) CHARACTER SET ascii NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii NOT NULL,
    role VARCHAR(16) CHARACTER SET ascii NOT NULL,
    content MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(task_id,role),
    FOREIGN KEY(task_id) REFERENCES tasks(id),
    FOREIGN KEY(conversation_id) REFERENCES conversations(id),
    CONSTRAINT message_role CHECK (role IN ('user','assistant')),
    INDEX message_conversation(conversation_id,id)
) ENGINE=InnoDB;

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
    cleanup_attempts INT NOT NULL DEFAULT 0,
    cleanup_attempted_at TIMESTAMP(6) NULL,
    cleanup_confirmed_at TIMESTAMP(6) NULL,
    UNIQUE(task_id,material_id),
    FOREIGN KEY(task_id,material_id) REFERENCES task_materials(task_id,material_id)
) ENGINE=InnoDB;

CREATE TABLE material_upload_gate (
    id TINYINT PRIMARY KEY,
    CONSTRAINT material_upload_gate_singleton CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO material_upload_gate(id) VALUES (1);

CREATE TABLE model_calls (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    task_id CHAR(36) CHARACTER SET ascii NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
    model_id CHAR(36) CHARACTER SET ascii NOT NULL,
    purpose VARCHAR(20) CHARACTER SET ascii NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii NOT NULL DEFAULT 'OPEN',
    input_estimate BIGINT NOT NULL,
    output_bytes BIGINT NOT NULL DEFAULT 0,
    provider_complete BOOLEAN NOT NULL DEFAULT FALSE,
    provider_input BIGINT NULL,
    provider_output BIGINT NULL,
    provider_response_id VARCHAR(200) NULL,
    input_price DECIMAL(18,6) NOT NULL,
    output_price DECIMAL(18,6) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii NOT NULL,
    reservation DECIMAL(30,6) NOT NULL,
    charged DECIMAL(30,6) NOT NULL DEFAULT 0,
    source VARCHAR(40) CHARACTER SET ascii NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at TIMESTAMP(6) NULL,
    usage_at TIMESTAMP(6) NULL,
    ended_at TIMESTAMP(6) NULL,
    error_code VARCHAR(40) CHARACTER SET ascii NULL,
    FOREIGN KEY(task_id) REFERENCES tasks(id),
    FOREIGN KEY(owner_id) REFERENCES users(id),
    FOREIGN KEY(model_id) REFERENCES models(id),
    CHECK (purpose IN (_ascii'MAIN',_ascii'SUMMARY',_ascii'OVERFLOW')),
    CHECK (status IN (_ascii'OPEN',_ascii'CLOSED')),
    CHECK (reservation>=0 AND charged>=0 AND output_bytes>=0 AND input_estimate>0),
    INDEX call_task(task_id,started_at,id)
) ENGINE=InnoDB;

CREATE TABLE model_call_adjustments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    call_id CHAR(36) CHARACTER SET ascii NOT NULL,
    previous_amount DECIMAL(30,6) NOT NULL,
    new_amount DECIMAL(30,6) NOT NULL,
    source VARCHAR(40) CHARACTER SET ascii NOT NULL,
    provider_input BIGINT NULL,
    provider_output BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(call_id) REFERENCES model_calls(id)
) ENGINE=InnoDB;

CREATE TABLE document_drafts (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    task_id CHAR(36) CHARACTER SET ascii NOT NULL UNIQUE,
    artifact_id CHAR(36) CHARACTER SET ascii NOT NULL,
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
    title VARCHAR(120) NOT NULL,
    base_version_id CHAR(36) CHARACTER SET ascii NULL,
    UNIQUE(document_id,version_number),
    FOREIGN KEY(document_id) REFERENCES documents(id),
    FOREIGN KEY(task_id) REFERENCES tasks(id),
    FOREIGN KEY(base_version_id) REFERENCES document_versions(id)
) ENGINE=InnoDB;

ALTER TABLE tasks
    ADD FOREIGN KEY(base_version_id) REFERENCES document_versions(id),
    ADD FOREIGN KEY(agent_id) REFERENCES personal_agents(id),
    ADD FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE SET NULL,
    ADD FOREIGN KEY(reply_task_id) REFERENCES tasks(id),
    ADD FOREIGN KEY(continued_from_id) REFERENCES tasks(id);

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
