ALTER TABLE sensor
    ADD COLUMN v1_limit_1 DOUBLE PRECISION,
    ADD COLUMN v1_limit_2 DOUBLE PRECISION,
    ADD COLUMN v1_type_alarm SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE sensor
    ADD CONSTRAINT chk_sensor_v1_type_alarm CHECK (v1_type_alarm BETWEEN 0 AND 4),
    ADD CONSTRAINT chk_sensor_v1_threshold_limits CHECK (
        v1_limit_1 IS NULL OR v1_limit_2 IS NULL OR v1_limit_1 <= v1_limit_2
    );
