-- Device owner: access_role OWNER in device_access (see DeviceAccessService.ACCESS_OWNER).
-- Only the owner (or an admin) grants/revokes access and sends invites; guests can't remove it.

-- Existing devices: the earliest account with full control becomes the owner.
UPDATE device_access SET access_role = 'OWNER'
WHERE id IN (
    SELECT DISTINCT ON (chip_id) id
    FROM device_access
    WHERE access_role = 'USER'
      AND chip_id NOT IN (SELECT chip_id FROM device_access WHERE access_role = 'OWNER')
    ORDER BY chip_id, granted_at, id
);

-- At most one owner per device.
CREATE UNIQUE INDEX IF NOT EXISTS idx_device_access_one_owner
    ON device_access (chip_id) WHERE access_role = 'OWNER';
