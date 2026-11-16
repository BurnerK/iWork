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
    CONSTRAINT model_prices CHECK (input_price >= 0 AND output_price >= 0)
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
    spent DECIMAL(18,6) NOT NULL DEFAULT 0,
    reserved DECIMAL(18,6) NOT NULL DEFAULT 0,
    FOREIGN KEY(user_id) REFERENCES users(id),
    CONSTRAINT nonnegative_budget CHECK (budget_limit >= 0 AND spent >= 0 AND reserved >= 0)
) ENGINE=InnoDB;
