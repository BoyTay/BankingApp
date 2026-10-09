CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    wallet_id uuid REFERENCES wallets(id) ON DELETE CASCADE,
    type varchar(32) NOT NULL,
    title varchar(160) NOT NULL,
    body varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz
);
CREATE INDEX notifications_user_idx ON notifications(user_id, created_at DESC, id DESC);
