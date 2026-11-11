ALTER TABLE alarm ADD COLUMN threshold_mode SMALLINT;

ALTER TABLE alarm
    ADD CONSTRAINT chk_alarm_threshold_mode CHECK (threshold_mode IS NULL OR threshold_mode BETWEEN 1 AND 4);
