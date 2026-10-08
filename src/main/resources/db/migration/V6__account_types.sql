ALTER TABLE wallets
    ADD COLUMN account_type varchar(16) NOT NULL DEFAULT 'CHECKING'
        CHECK (account_type IN ('CHECKING', 'SAVINGS', 'CREDIT')),
    ADD COLUMN account_status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (account_status IN ('ACTIVE', 'CLOSED')),
    ADD COLUMN is_default boolean NOT NULL DEFAULT true,
    ADD COLUMN creation_request_key uuid;

ALTER TABLE wallets DROP CONSTRAINT wallets_owner_id_key;

ALTER TABLE wallets ADD CONSTRAINT wallets_default_is_checking
    CHECK (NOT is_default OR account_type = 'CHECKING');

CREATE UNIQUE INDEX wallets_one_default_per_owner_idx ON wallets(owner_id) WHERE is_default;
CREATE UNIQUE INDEX wallets_one_open_request_idx ON wallets(owner_id, creation_request_key)
    WHERE creation_request_key IS NOT NULL;
CREATE INDEX wallets_owner_created_idx ON wallets(owner_id, created_at, id);
