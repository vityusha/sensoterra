-- Battery type and warning threshold are server-side settings: they are not sent to the
-- firmware, only used to raise the BATTERY alarm. Thresholds are stored in millivolts.
ALTER TABLE sensor
    ADD COLUMN battery_type VARCHAR(16) NOT NULL DEFAULT 'ALKALINE',
    ADD COLUMN warning_voltage INTEGER NOT NULL DEFAULT 2500;
