ALTER TABLE sensor
    ADD COLUMN v2_limit_1 DOUBLE PRECISION,
    ADD COLUMN v2_limit_2 DOUBLE PRECISION,
    ADD COLUMN v2_type_alarm SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN v3_limit_1 DOUBLE PRECISION,
    ADD COLUMN v3_limit_2 DOUBLE PRECISION,
    ADD COLUMN v3_type_alarm SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE sensor
    ADD CONSTRAINT chk_sensor_v2_type_alarm CHECK (v2_type_alarm BETWEEN 0 AND 4),
    ADD CONSTRAINT chk_sensor_v3_type_alarm CHECK (v3_type_alarm BETWEEN 0 AND 4),
    ADD CONSTRAINT chk_sensor_v2_threshold_limits CHECK (v2_limit_1 IS NULL OR v2_limit_2 IS NULL OR v2_limit_1 <= v2_limit_2),
    ADD CONSTRAINT chk_sensor_v3_threshold_limits CHECK (v3_limit_1 IS NULL OR v3_limit_2 IS NULL OR v3_limit_1 <= v3_limit_2);
