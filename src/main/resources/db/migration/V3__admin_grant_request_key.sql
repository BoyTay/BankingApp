-- Existing grants predate idempotency; their own IDs are unique backfill keys.
ALTER TABLE admin_grants ADD COLUMN request_key uuid;
UPDATE admin_grants SET request_key = id;
ALTER TABLE admin_grants ALTER COLUMN request_key SET NOT NULL;
ALTER TABLE admin_grants ADD CONSTRAINT unique_admin_grant_request UNIQUE (admin_user_id, request_key);
