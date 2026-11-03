-- Admin two-factor authentication (TOTP) and admin audit log.
-- Admins are created only by the server CLI (AdminCli); every ADMIN signs in with a TOTP code.

-- Base32 TOTP secret; NULL = not enrolled yet (enrollment is forced on the next login).
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(64);
-- Last accepted 30 s time step: the same code can't be used twice.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS totp_last_step BIGINT;

-- One-time recovery codes (SHA-256 hex), shown to the admin once at enrollment.
CREATE TABLE IF NOT EXISTS admin_recovery_code (
    id        BIGSERIAL PRIMARY KEY,
    user_id   BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    used_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_admin_recovery_code_user ON admin_recovery_code (user_id);

-- Admin sign-ins and admin actions. No FK: entries outlive deleted accounts.
CREATE TABLE IF NOT EXISTS admin_audit (
    id       BIGSERIAL PRIMARY KEY,
    user_id  BIGINT,
    username VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    event    VARCHAR(32)  NOT NULL,
    ip       VARCHAR(64),
    detail   VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_admin_audit_at ON admin_audit (created_at DESC, id DESC);
