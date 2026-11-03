-- Alarm states (AlarmService): one row per episode, open while ended_at IS NULL.
-- chip_id is the subject (sensor chip or block); device_id is the device that grants access to
-- it (the block for a sensor in a block slot, the sensor itself when standalone).
CREATE TABLE alarm (
    id          BIGSERIAL PRIMARY KEY,
    chip_id     BIGINT           NOT NULL,
    device_id   BIGINT           NOT NULL REFERENCES device (chip_id) ON DELETE CASCADE,
    alarm_type  VARCHAR(20)      NOT NULL,
    started_at  TIMESTAMPTZ      NOT NULL,
    ended_at    TIMESTAMPTZ,
    data_value  DOUBLE PRECISION
);

-- At most one open episode per subject and type.
CREATE UNIQUE INDEX idx_alarm_open ON alarm (chip_id, alarm_type) WHERE ended_at IS NULL;
CREATE INDEX idx_alarm_device_type_started ON alarm (device_id, alarm_type, started_at DESC);

-- A finished alarm the user has looked at (clicked in the list): shown in normal font.
CREATE TABLE alarm_read (
    alarm_id BIGINT NOT NULL REFERENCES alarm (id) ON DELETE CASCADE,
    user_id  BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    PRIMARY KEY (alarm_id, user_id)
);
CREATE INDEX idx_alarm_read_user ON alarm_read (user_id);

-- When the user last opened the alarms page: newer starts/ends light the bell's red dot.
CREATE TABLE alarm_seen (
    user_id BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    seen_at TIMESTAMPTZ NOT NULL
);
