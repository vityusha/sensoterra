ALTER TABLE sensor
    ADD COLUMN v0_limit_1 DOUBLE PRECISION,
    ADD COLUMN v0_limit_2 DOUBLE PRECISION,
    ADD COLUMN v0_type_alarm SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE sensor
    ADD CONSTRAINT chk_sensor_v0_type_alarm CHECK (v0_type_alarm BETWEEN 0 AND 4),
    ADD CONSTRAINT chk_sensor_v0_threshold_limits CHECK (v0_limit_1 IS NULL OR v0_limit_2 IS NULL OR v0_limit_1 <= v0_limit_2),
    ADD CONSTRAINT chk_sensor_v0_threshold_percent CHECK
        ((v0_limit_1 IS NULL OR v0_limit_1 BETWEEN 0 AND 100) AND (v0_limit_2 IS NULL OR v0_limit_2 BETWEEN 0 AND 100));
