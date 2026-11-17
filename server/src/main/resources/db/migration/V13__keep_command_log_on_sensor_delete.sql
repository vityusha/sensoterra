ALTER TABLE command_log
    DROP CONSTRAINT command_log_sensor_id_fkey,
    ADD CONSTRAINT command_log_sensor_id_fkey
        FOREIGN KEY (sensor_id) REFERENCES sensor(id) ON DELETE SET NULL;
