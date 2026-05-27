CREATE TABLE IF NOT EXISTS session_state (
    instance_id TEXT PRIMARY KEY,
    last_active TIMESTAMP,
    status TEXT
);
