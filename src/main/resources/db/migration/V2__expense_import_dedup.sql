-- Existing rows, if any, get deterministic placeholder keys and hashes before constraints.
ALTER TABLE import_batches ADD COLUMN request_key uuid;
ALTER TABLE import_batches ADD COLUMN content_sha256 char(64);
UPDATE import_batches
SET request_key = id,
    content_sha256 = lpad(replace(id::text, '-', ''), 64, '0');
ALTER TABLE import_batches ALTER COLUMN request_key SET NOT NULL;
ALTER TABLE import_batches ALTER COLUMN content_sha256 SET NOT NULL;
ALTER TABLE import_batches ADD CONSTRAINT import_owner_request_unique UNIQUE (owner_user_id, request_key);
ALTER TABLE import_batches ADD CONSTRAINT import_owner_format_hash_unique
    UNIQUE (owner_user_id, format_code, content_sha256);
CREATE INDEX imported_expenses_spent_on_idx ON imported_expenses(spent_on, batch_id);
