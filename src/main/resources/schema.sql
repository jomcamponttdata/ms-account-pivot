CREATE TABLE IF NOT EXISTS accounts (
    id UUID PRIMARY KEY,
    account_number VARCHAR(32) NOT NULL UNIQUE,
    account_type VARCHAR(16) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    maintenance_fee NUMERIC(19, 2),
    max_monthly_movements INTEGER,
    allowed_movement_day INTEGER,
    movements_in_current_month INTEGER NOT NULL DEFAULT 0,
    movements_period VARCHAR(7),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_accounts_type CHECK (account_type IN ('SAVINGS', 'CHECKING', 'FIXED_TERM')),
    CONSTRAINT ck_accounts_balance_nonnegative CHECK (balance >= 0),
    CONSTRAINT ck_accounts_movements_nonnegative CHECK (movements_in_current_month >= 0),
    CONSTRAINT ck_accounts_product_fields CHECK (
        (account_type = 'SAVINGS' AND max_monthly_movements IS NOT NULL
            AND max_monthly_movements > 0 AND maintenance_fee IS NULL
            AND allowed_movement_day IS NULL)
        OR (account_type = 'CHECKING' AND maintenance_fee IS NOT NULL AND maintenance_fee >= 0
            AND max_monthly_movements IS NULL
            AND allowed_movement_day IS NULL)
        OR (account_type = 'FIXED_TERM' AND allowed_movement_day IS NOT NULL
            AND allowed_movement_day BETWEEN 1 AND 31
            AND max_monthly_movements IS NULL AND maintenance_fee IS NULL)
    )
);

CREATE TABLE IF NOT EXISTS account_holders (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    customer_id UUID NOT NULL,
    customer_type VARCHAR(16) NOT NULL,
    account_type VARCHAR(16) NOT NULL,
    CONSTRAINT uq_account_holders_account_customer UNIQUE (account_id, customer_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_personal_account_product
    ON account_holders (customer_id, account_type)
    WHERE customer_type = 'PERSONAL' AND account_type IN ('SAVINGS', 'CHECKING');

CREATE INDEX IF NOT EXISTS ix_account_holders_customer ON account_holders (customer_id);
CREATE INDEX IF NOT EXISTS ix_accounts_created_id ON accounts (created_at, id);

CREATE TABLE IF NOT EXISTS account_authorized_signers (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    full_name VARCHAR(150) NOT NULL,
    document_type VARCHAR(16) NOT NULL,
    document_number VARCHAR(32) NOT NULL
);
