CREATE TABLE invite_code (
    id BIGSERIAL PRIMARY KEY,
    code_hash VARCHAR(128) NOT NULL UNIQUE,
    note VARCHAR(200),
    created_by BIGINT REFERENCES "user"(id),
    used_by_user_id BIGINT UNIQUE REFERENCES "user"(id),
    used_at TIMESTAMP,
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invite_code_used_at ON invite_code(used_at);
CREATE INDEX idx_invite_code_expires_at ON invite_code(expires_at);