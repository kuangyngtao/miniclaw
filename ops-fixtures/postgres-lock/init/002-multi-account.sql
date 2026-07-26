-- M2-1: Multi-account schema upgrade
-- Adds opening_balance_cents, account_class, reconciliation_runs,
-- and seeds 100 deterministic accounts (1 HOT + 99 NORMAL).

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS opening_balance_cents bigint;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS account_class text NOT NULL DEFAULT 'NORMAL';

CREATE TABLE IF NOT EXISTS reconciliation_runs (
    reconciliation_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id text NOT NULL REFERENCES accounts(account_id),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    status text NOT NULL DEFAULT 'STARTED'
);

-- Update existing acct-001 to have opening_balance and class
UPDATE accounts
   SET opening_balance_cents = balance_cents,
       account_class = 'HOT'
 WHERE account_id = 'acct-001';

-- Seed 99 additional NORMAL accounts
DO $$
DECLARE
    i integer;
    acct_id text;
BEGIN
    FOR i IN 1..99 LOOP
        acct_id := 'acct-' || LPAD(i::text, 4, '0');
        IF NOT EXISTS (SELECT 1 FROM accounts WHERE account_id = acct_id) THEN
            INSERT INTO accounts(account_id, balance_cents, opening_balance_cents, account_class)
            VALUES (acct_id, 100000000, 100000000, 'NORMAL');
        END IF;
    END LOOP;
END $$;

-- Rename acct-001 to follow hot-XXXX convention for clarity
UPDATE accounts SET account_id = 'hot-0001' WHERE account_id = 'acct-001';
UPDATE orders SET account_id = 'hot-0001' WHERE account_id = 'acct-001';

-- Set opening_balance_cents for any account that still has NULL
UPDATE accounts SET opening_balance_cents = balance_cents WHERE opening_balance_cents IS NULL;

-- Make opening_balance_cents NOT NULL after backfill
ALTER TABLE accounts ALTER COLUMN opening_balance_cents SET NOT NULL;

-- Observer role gets read access to reconciliation_runs
GRANT SELECT ON reconciliation_runs TO clawkit_observer;
