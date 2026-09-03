-- The default backfills existing users and supports inserts during deployment.
ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';
