ALTER TABLE wallets ADD COLUMN fee_starts_on date NOT NULL
    DEFAULT ((date_trunc('month', timezone('UTC', now()))::date + interval '1 month')::date);

CREATE TABLE account_fees (
    id uuid PRIMARY KEY,
    wallet_id uuid NOT NULL REFERENCES wallets(id),
    fee_code varchar(32) NOT NULL,
    period_start date NOT NULL,
    amount_dong bigint NOT NULL CHECK (amount_dong > 0),
    status varchar(8) NOT NULL DEFAULT 'DUE' CHECK (status IN ('DUE', 'PAID')),
    created_at timestamptz NOT NULL DEFAULT now(),
    paid_at timestamptz,
    CONSTRAINT account_fee_once_per_period UNIQUE (wallet_id, fee_code, period_start),
    CONSTRAINT account_fee_paid_time CHECK ((status = 'PAID') = (paid_at IS NOT NULL))
);
CREATE INDEX account_fees_due_idx ON account_fees(status, wallet_id, period_start);

ALTER TABLE ledger_entries ADD COLUMN fee_id uuid REFERENCES account_fees(id);
ALTER TABLE ledger_entries DROP CONSTRAINT exactly_one_source;
ALTER TABLE ledger_entries ADD CONSTRAINT exactly_one_source CHECK (
    (CASE WHEN transfer_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN grant_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN fee_id IS NOT NULL THEN 1 ELSE 0 END) = 1
);
CREATE UNIQUE INDEX ledger_one_fee_idx ON ledger_entries(fee_id) WHERE fee_id IS NOT NULL;
