-- ===== V2__retention.sql =====
-- Сжатие, retention и непрерывные агрегаты для телеметрии (TimescaleDB).
-- Выполняется вне транзакции (V2__retention.sql.conf): CREATE MATERIALIZED VIEW ... continuous
-- и CALL refresh_continuous_aggregate внутри транзакции запрещены. Поэтому скрипт
-- идемпотентный — после сбоя: flyway repair и повторный запуск.
--
-- Сроки:
--   сырые данные   — сжатие через 7 дней (automation_event — 30), удаление через 1 год
--                    (device_status_log — 90 дней);
--   почасовой агрегат sensor_telemetry_hourly — 3 года;
--   суточный агрегат  sensor_telemetry_daily  — бессрочно.
-- Окно обновления агрегатов (start_offset) обязано быть меньше retention сырых данных:
-- refresh по удалённому диапазону стёр бы уже посчитанные агрегаты.
-- Агрегаты зависят от колонок sensor_telemetry — DROP/ALTER этих колонок потребует
-- пересоздания агрегатов.

-- ---------- Последний статус устройства ----------
-- device_status_log теперь чистится, последнее значение храним в device.
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_status VARCHAR(20);
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_status_at TIMESTAMPTZ;

UPDATE device d
SET last_status = s.status, last_status_at = s.time
FROM (
    SELECT DISTINCT ON (device_id) device_id, status, time
    FROM device_status_log
    ORDER BY device_id, time DESC
) s
WHERE s.device_id = d.chip_id AND d.last_status IS NULL;

-- ---------- Сжатие ----------
ALTER TABLE sensor_telemetry SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, sensor_chip_id',
    timescaledb.compress_orderby = 'time DESC');
SELECT add_compression_policy('sensor_telemetry', INTERVAL '7 days', if_not_exists => true);

ALTER TABLE block_telemetry SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id',
    timescaledb.compress_orderby = 'time DESC');
SELECT add_compression_policy('block_telemetry', INTERVAL '7 days', if_not_exists => true);

ALTER TABLE relay_event SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, relay_number',
    timescaledb.compress_orderby = 'time DESC');
SELECT add_compression_policy('relay_event', INTERVAL '7 days', if_not_exists => true);

ALTER TABLE device_status_log SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id',
    timescaledb.compress_orderby = 'time DESC');
SELECT add_compression_policy('device_status_log', INTERVAL '7 days', if_not_exists => true);

ALTER TABLE automation_event SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'block_id',
    timescaledb.compress_orderby = 'time DESC');
SELECT add_compression_policy('automation_event', INTERVAL '30 days', if_not_exists => true);

-- ---------- Retention сырых данных ----------
SELECT add_retention_policy('sensor_telemetry', INTERVAL '1 year', if_not_exists => true);
SELECT add_retention_policy('block_telemetry', INTERVAL '1 year', if_not_exists => true);
SELECT add_retention_policy('relay_event', INTERVAL '1 year', if_not_exists => true);
SELECT add_retention_policy('automation_event', INTERVAL '1 year', if_not_exists => true);
SELECT add_retention_policy('device_status_log', INTERVAL '90 days', if_not_exists => true);

-- command_log — обычная таблица, чистим фоновым заданием TimescaleDB раз в сутки.
CREATE OR REPLACE PROCEDURE sensoterra_cleanup_command_log(job_id INT, config JSONB)
LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM command_log WHERE sent_at < now() - (config->>'retain')::interval;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM timescaledb_information.jobs
                   WHERE proc_name = 'sensoterra_cleanup_command_log') THEN
        PERFORM add_job('sensoterra_cleanup_command_log', INTERVAL '1 day',
                        config => '{"retain": "1 year"}');
    END IF;
END
$$;

-- ---------- Непрерывные агрегаты ----------
-- Бакеты по UTC. materialized_only = false: свежий, ещё не материализованный
-- интервал досчитывается из сырых данных на лету.
CREATE MATERIALIZED VIEW IF NOT EXISTS sensor_telemetry_hourly
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '1 hour', time) AS bucket,
       device_id,
       sensor_chip_id,
       count(*)                 AS samples,
       avg(moisture_adc)::REAL  AS moisture_adc_avg,
       min(moisture_adc)        AS moisture_adc_min,
       max(moisture_adc)        AS moisture_adc_max,
       avg(temperature)::REAL   AS temperature_avg,
       min(temperature)         AS temperature_min,
       max(temperature)         AS temperature_max,
       avg(humidity)::REAL      AS humidity_avg,
       min(humidity)            AS humidity_min,
       max(humidity)            AS humidity_max,
       avg(light)::REAL         AS light_avg,
       max(light)               AS light_max,
       avg(battery)::REAL       AS battery_avg,
       min(battery)             AS battery_min,
       avg(vbat)::REAL          AS vbat_avg,
       min(vbat)                AS vbat_min,
       avg(rssi)::REAL          AS rssi_avg
FROM sensor_telemetry
GROUP BY bucket, device_id, sensor_chip_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('sensor_telemetry_hourly',
    start_offset => INTERVAL '3 days',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '30 minutes',
    if_not_exists => true);
ALTER MATERIALIZED VIEW sensor_telemetry_hourly SET (timescaledb.compress = true);
-- compress_after должен быть больше start_offset политики обновления.
SELECT add_compression_policy('sensor_telemetry_hourly', compress_after => INTERVAL '7 days', if_not_exists => true);
SELECT add_retention_policy('sensor_telemetry_hourly', INTERVAL '3 years', if_not_exists => true);

-- Суточный агрегат строится из сырых данных (а не из почасового), чтобы среднее было
-- взвешенным по реальному числу замеров.
CREATE MATERIALIZED VIEW IF NOT EXISTS sensor_telemetry_daily
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '1 day', time) AS bucket,
       device_id,
       sensor_chip_id,
       count(*)                 AS samples,
       avg(moisture_adc)::REAL  AS moisture_adc_avg,
       min(moisture_adc)        AS moisture_adc_min,
       max(moisture_adc)        AS moisture_adc_max,
       avg(temperature)::REAL   AS temperature_avg,
       min(temperature)         AS temperature_min,
       max(temperature)         AS temperature_max,
       avg(humidity)::REAL      AS humidity_avg,
       min(humidity)            AS humidity_min,
       max(humidity)            AS humidity_max,
       avg(light)::REAL         AS light_avg,
       max(light)               AS light_max,
       avg(battery)::REAL       AS battery_avg,
       min(battery)             AS battery_min,
       avg(vbat)::REAL          AS vbat_avg,
       min(vbat)                AS vbat_min,
       avg(rssi)::REAL          AS rssi_avg
FROM sensor_telemetry
GROUP BY bucket, device_id, sensor_chip_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('sensor_telemetry_daily',
    start_offset => INTERVAL '7 days',
    end_offset => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => true);

-- Разовая материализация уже накопленной истории (на свежей БД — мгновенно).
CALL refresh_continuous_aggregate('sensor_telemetry_hourly', NULL, now() - INTERVAL '1 hour');
CALL refresh_continuous_aggregate('sensor_telemetry_daily', NULL, now() - INTERVAL '1 day');
