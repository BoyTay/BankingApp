-- PostgreSQL/Flyway schema. Monetary values are integral Vietnamese dong.
CREATE TABLE app_users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    display_name varchar(120) NOT NULL,
    password_hash varchar(255) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wallets (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL UNIQUE REFERENCES app_users(id),
    wallet_code varchar(24) NOT NULL UNIQUE,
    balance_dong bigint NOT NULL DEFAULT 0 CHECK (balance_dong >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    token_hash varchar(128) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auth_sessions_user_idx ON auth_sessions(user_id, expires_at DESC);

CREATE TABLE transfers (
    id uuid PRIMARY KEY,
    sender_wallet_id uuid NOT NULL REFERENCES wallets(id),
    recipient_wallet_id uuid NOT NULL REFERENCES wallets(id),
    request_key uuid NOT NULL,
    amount_dong bigint NOT NULL CHECK (amount_dong > 0),
    sender_balance_after_dong bigint NOT NULL CHECK (sender_balance_after_dong >= 0),
    recipient_balance_after_dong bigint NOT NULL CHECK (recipient_balance_after_dong >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT no_self_transfer CHECK (sender_wallet_id <> recipient_wallet_id),
    CONSTRAINT unique_sender_request UNIQUE (sender_wallet_id, request_key)
);

CREATE TABLE ledger_entries (
    id uuid PRIMARY KEY,
    wallet_id uuid NOT NULL REFERENCES wallets(id),
    transfer_id uuid REFERENCES transfers(id),
    grant_id uuid,
    delta_dong bigint NOT NULL CHECK (delta_dong <> 0),
    balance_after_dong bigint NOT NULL CHECK (balance_after_dong >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT exactly_one_source CHECK ((transfer_id IS NOT NULL) <> (grant_id IS NOT NULL))
);
CREATE INDEX ledger_wallet_time_idx ON ledger_entries(wallet_id, created_at DESC, id DESC);
CREATE UNIQUE INDEX ledger_one_transfer_side_idx ON ledger_entries(transfer_id, wallet_id)
    WHERE transfer_id IS NOT NULL;
CREATE UNIQUE INDEX ledger_one_grant_idx ON ledger_entries(grant_id)
    WHERE grant_id IS NOT NULL;

CREATE TABLE admin_grants (
    id uuid PRIMARY KEY,
    admin_user_id uuid NOT NULL REFERENCES app_users(id),
    recipient_wallet_id uuid NOT NULL REFERENCES wallets(id),
    amount_dong bigint NOT NULL CHECK (amount_dong > 0),
    reason varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_grant_fk
    FOREIGN KEY (grant_id) REFERENCES admin_grants(id);

CREATE TABLE import_batches (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL REFERENCES app_users(id),
    format_code varchar(32) NOT NULL,
    source_name varchar(255) NOT NULL,
    row_count integer NOT NULL CHECK (row_count >= 0),
    imported_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE imported_expenses (
    id uuid PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES import_batches(id),
    source_row integer NOT NULL CHECK (source_row > 0),
    spent_on date NOT NULL,
    description varchar(500) NOT NULL,
    category varchar(100) NOT NULL,
    amount_dong bigint NOT NULL CHECK (amount_dong > 0),
    UNIQUE (batch_id, source_row)
);
CREATE INDEX imported_expenses_batch_date_idx ON imported_expenses(batch_id, spent_on);
