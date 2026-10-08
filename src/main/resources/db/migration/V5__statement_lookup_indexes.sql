CREATE INDEX transfers_sender_statement_idx ON transfers(sender_wallet_id, created_at, id);
CREATE INDEX transfers_recipient_statement_idx ON transfers(recipient_wallet_id, created_at, id);
