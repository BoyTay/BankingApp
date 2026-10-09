CREATE TABLE notification_settings (
    wallet_id uuid PRIMARY KEY REFERENCES wallets(id) ON DELETE CASCADE,
    low_balance_dong bigint NOT NULL DEFAULT 0 CHECK (low_balance_dong >= 0),
    low_balance_alerted boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- One row per reminder already sent, so the daily job never repeats the same reminder.
CREATE TABLE reminder_log (
    reminder_key varchar(120) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    sent_at timestamptz NOT NULL DEFAULT now()
);
