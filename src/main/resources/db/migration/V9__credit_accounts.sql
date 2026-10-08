ALTER TABLE wallets DROP CONSTRAINT wallets_balance_dong_check;
ALTER TABLE wallets ADD COLUMN credit_limit_dong bigint NOT NULL DEFAULT 0;
ALTER TABLE wallets ADD CONSTRAINT wallets_balance_by_type CHECK (
    (account_type = 'CREDIT' AND credit_limit_dong >= 0
        AND balance_dong BETWEEN -credit_limit_dong AND 0)
    OR
    (account_type <> 'CREDIT' AND credit_limit_dong = 0 AND balance_dong >= 0)
);
ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entries_balance_after_dong_check;

CREATE TABLE credit_charges (
    id uuid PRIMARY KEY,
    wallet_id uuid NOT NULL REFERENCES wallets(id),
    request_key uuid NOT NULL,
    amount_dong bigint NOT NULL CHECK (amount_dong > 0),
    description varchar(200) NOT NULL,
    balance_after_dong bigint NOT NULL CHECK (balance_after_dong <= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (wallet_id, request_key)
);
CREATE INDEX credit_charges_wallet_time_idx ON credit_charges(wallet_id, created_at DESC, id DESC);

CREATE TABLE credit_repayments (
    id uuid PRIMARY KEY,
    credit_wallet_id uuid NOT NULL REFERENCES wallets(id),
    source_wallet_id uuid NOT NULL REFERENCES wallets(id),
    request_key uuid NOT NULL,
    amount_dong bigint NOT NULL CHECK (amount_dong > 0),
    source_balance_after_dong bigint NOT NULL CHECK (source_balance_after_dong >= 0),
    credit_balance_after_dong bigint NOT NULL CHECK (credit_balance_after_dong <= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT credit_repayment_distinct_accounts CHECK (credit_wallet_id <> source_wallet_id),
    UNIQUE (credit_wallet_id, request_key)
);
CREATE INDEX credit_repayments_wallet_time_idx ON credit_repayments(credit_wallet_id, created_at DESC, id DESC);

CREATE TABLE credit_limit_changes (
    id uuid PRIMARY KEY,
    wallet_id uuid NOT NULL REFERENCES wallets(id),
    admin_user_id uuid NOT NULL REFERENCES app_users(id),
    request_key uuid NOT NULL,
    limit_dong bigint NOT NULL CHECK (limit_dong >= 0),
    debt_dong bigint NOT NULL CHECK (debt_dong >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (admin_user_id, request_key)
);

ALTER TABLE ledger_entries ADD COLUMN credit_charge_id uuid REFERENCES credit_charges(id);
ALTER TABLE ledger_entries ADD COLUMN credit_repayment_id uuid REFERENCES credit_repayments(id);
ALTER TABLE ledger_entries DROP CONSTRAINT exactly_one_source;
ALTER TABLE ledger_entries ADD CONSTRAINT exactly_one_source CHECK (
    (CASE WHEN transfer_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN grant_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN fee_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN interest_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN credit_charge_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN credit_repayment_id IS NOT NULL THEN 1 ELSE 0 END) = 1
);
CREATE UNIQUE INDEX ledger_one_credit_charge_idx ON ledger_entries(credit_charge_id)
    WHERE credit_charge_id IS NOT NULL;
CREATE UNIQUE INDEX ledger_credit_repayment_side_idx ON ledger_entries(credit_repayment_id,wallet_id)
    WHERE credit_repayment_id IS NOT NULL;

CREATE TABLE account_closures (
    wallet_id uuid PRIMARY KEY REFERENCES wallets(id),
    request_key uuid NOT NULL,
    closed_at timestamptz NOT NULL DEFAULT now()
);
