-- An owner may trust the admins with full control of all their devices (main menu toggle).
-- Off by default: on an owned device an admin only views (DeviceAccessService.adminMayManage).
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS admin_access BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS admin_access_since TIMESTAMPTZ;
