ALTER TABLE alarm ADD COLUMN threshold_side SMALLINT;

ALTER TABLE alarm
    ADD CONSTRAINT chk_alarm_threshold_side CHECK (threshold_side IS NULL OR threshold_side IN (1, 2));
