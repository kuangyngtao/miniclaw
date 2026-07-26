-- M2-3/R1: Idempotent role creation
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'clawkit_app') THEN
        CREATE ROLE clawkit_app LOGIN PASSWORD 'fixture-app-only' CONNECTION LIMIT 20;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'clawkit_observer') THEN
        CREATE ROLE clawkit_observer LOGIN PASSWORD 'fixture-observer-only' CONNECTION LIMIT 3;
    END IF;
END $$;
GRANT pg_read_all_stats TO clawkit_observer;

CREATE TABLE accounts (
    account_id text PRIMARY KEY,
    balance_cents bigint NOT NULL CHECK (balance_cents >= 0)
);
CREATE TABLE orders (
    request_id uuid PRIMARY KEY,
    account_id text NOT NULL REFERENCES accounts(account_id),
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO accounts(account_id, balance_cents) VALUES ('acct-001', 100000000);
GRANT CONNECT ON DATABASE clawkit TO clawkit_app, clawkit_observer;
GRANT SELECT, INSERT, UPDATE ON accounts, orders TO clawkit_app;
