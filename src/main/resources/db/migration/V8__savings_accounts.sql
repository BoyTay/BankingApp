CREATE TABLE savings_accounts (
    wallet_id uuid PRIMARY KEY REFERENCES wallets(id),
    funding_wallet_id uuid NOT NULL REFERENCES wallets(id),
    opening_transfer_id uuid NOT NULL UNIQUE REFERENCES transfers(id),
    principal_dong bigint NOT NULL CHECK (principal_dong >= 100000),
    term_days integer NOT NULL CHECK (term_days BETWEEN 1 AND 3650),
    annual_rate_bps integer NOT NULL CHECK (annual_rate_bps BETWEEN 1 AND 10000),
    opened_at timestamptz NOT NULL DEFAULT now(),
    matures_on date NOT NULL,
    closing_request_key uuid,
    closing_transfer_id uuid UNIQUE REFERENCES transfers(id),
    interest_dong bigint,
    fee_dong bigint,
    closed_at timestamptz,
    CONSTRAINT savings_distinct_accounts CHECK (wallet_id <> funding_wallet_id),
    CONSTRAINT savings_closure_complete CHECK (
        (closing_request_key IS NULL AND closing_transfer_id IS NULL AND interest_dong IS NULL
            AND fee_dong IS NULL AND closed_at IS NULL)
        OR
        (closing_request_key IS NOT NULL AND closing_transfer_id IS NOT NULL
            AND interest_dong IS NOT NULL AND interest_dong >= 0
            AND fee_dong IS NOT NULL AND fee_dong >= 0 AND closed_at IS NOT NULL)
    )
);

CREATE TABLE savings_interest (
    id uuid PRIMARY KEY,
    wallet_id uuid NOT NULL UNIQUE REFERENCES savings_accounts(wallet_id),
    amount_dong bigint NOT NULL CHECK (amount_dong > 0),
    credited_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE ledger_entries ADD COLUMN interest_id uuid REFERENCES savings_interest(id);
ALTER TABLE ledger_entries DROP CONSTRAINT exactly_one_source;
ALTER TABLE ledger_entries ADD CONSTRAINT exactly_one_source CHECK (
    (CASE WHEN transfer_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN grant_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN fee_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN interest_id IS NOT NULL THEN 1 ELSE 0 END) = 1
);
CREATE UNIQUE INDEX ledger_one_interest_idx ON ledger_entries(interest_id)
    WHERE interest_id IS NOT NULL;
