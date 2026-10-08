CREATE TABLE auth_rate_limits (
    scope varchar(32) NOT NULL,
    key_hash char(64) NOT NULL,
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 21),
    window_ends_at timestamptz NOT NULL,
    PRIMARY KEY (scope, key_hash)
);
CREATE INDEX auth_rate_limits_expiry_idx ON auth_rate_limits(window_ends_at);
