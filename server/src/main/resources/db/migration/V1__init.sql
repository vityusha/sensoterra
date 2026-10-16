-- Сводная миграция для новой установки: последовательное применение V1–V30.
-- Существующие базы данных должны быть пересозданы или вручную приведены к этой версии.

-- ===== V1__init.sql =====
-- Пользователи и роли
CREATE TABLE app_user (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(50) UNIQUE NOT NULL,
  email         VARCHAR(255) UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role          VARCHAR(10) NOT NULL CHECK (role IN ('ADMIN', 'CLIENT')),
  created_at    TIMESTAMPTZ DEFAULT now()
);

-- MQTT-подключения (каждый клиент может иметь своё)
CREATE TABLE mqtt_connection (
  id             BIGSERIAL PRIMARY KEY,
  name           VARCHAR(100),
  broker_url     VARCHAR(255) NOT NULL,
  port           INT NOT NULL,
  tls            BOOLEAN DEFAULT true,
  username       VARCHAR(100),
  password       VARCHAR(100),
  mqtt_client_id VARCHAR(100) UNIQUE NOT NULL,
  is_default     BOOLEAN DEFAULT false,
  created_by     BIGINT REFERENCES app_user(id),
  created_at     TIMESTAMPTZ DEFAULT now()
);

-- Устройства (уникальный chip_id = id)
CREATE TABLE device (
  chip_id            BIGINT PRIMARY KEY,
  name               VARCHAR(100),
  device_type        VARCHAR(20) NOT NULL CHECK (device_type IN ('BLOCK', 'INDIVIDUAL_SENSOR')),
  mqtt_connection_id BIGINT REFERENCES mqtt_connection(id),
  owner_id           BIGINT REFERENCES app_user(id),
  created_at         TIMESTAMPTZ DEFAULT now()
);

-- Реле (внутри блока)
CREATE TABLE relay (
  id             BIGSERIAL PRIMARY KEY,
  device_id      BIGINT REFERENCES device(chip_id) ON DELETE CASCADE,
  relay_number   SMALLINT NOT NULL CHECK (relay_number BETWEEN 1 AND 8),
  name           VARCHAR(50),
  state          SMALLINT,
  activity       BOOLEAN,
  type_enum      VARCHAR(10),
  timer_enabled  BOOLEAN,
  data_type      SMALLINT,
  main_time      TIME,
  duration_main  SMALLINT,
  extra_time     TIME,
  duration_extra SMALLINT,
  interval_h     SMALLINT,
  sensor_id      BIGINT,
  start_time     TIME,
  finish_time    TIME,
  one_limit      INT,
  low_limit      INT,
  high_limit     INT,
  rssi_r         INT,
  vbat           REAL,
  vacc           REAL,
  updated_at     TIMESTAMPTZ DEFAULT now(),
  UNIQUE (device_id, relay_number)
);

-- Датчики (унифицированные: встроенные + индивидуальные)
CREATE TABLE sensor (
  id               BIGSERIAL PRIMARY KEY,
  device_id        BIGINT REFERENCES device(chip_id) ON DELETE CASCADE,
  sensor_chip_id   BIGINT,
  name             VARCHAR(50),
  sensor_type      SMALLINT,
  -- конфигурация (подтверждённая)
  sleep            INT,
  send             INT,
  percent_n        SMALLINT,
  percent_s        SMALLINT,
  min_voltage      INT,
  -- latest readings (последняя телеметрия)
  moisture_adc     INT,
  temperature      REAL,
  humidity         REAL,
  light            INT,
  battery          REAL,
  batload          REAL,
  bat_min          REAL,
  rssi             INT,
  rssi_block       INT,
  vbat_block       REAL,
  vacc_block       REAL,
  num_message      INT,
  firmware_version INT,
  error            VARCHAR(20),
  updated_at       TIMESTAMPTZ DEFAULT now(),
  UNIQUE (device_id, sensor_chip_id)
);

-- Телеметрия датчиков (hypertable)
CREATE TABLE sensor_telemetry (
  time           TIMESTAMPTZ NOT NULL,
  device_id      BIGINT NOT NULL,
  sensor_chip_id BIGINT,
  sensor_name    VARCHAR(50),
  sensor_type    SMALLINT,
  moisture_adc   INT,
  temperature    REAL,
  humidity       REAL,
  light          INT,
  battery        REAL,
  batload        REAL,
  rssi           INT,
  vbat           REAL,
  vacc           REAL,
  rssi_block     INT,
  num_message    INT,
  error          VARCHAR(20)
);
SELECT create_hypertable('sensor_telemetry', 'time');
CREATE INDEX idx_sensor_telemetry_device_time ON sensor_telemetry (device_id, time DESC);
CREATE INDEX idx_sensor_telemetry_chip_time ON sensor_telemetry (sensor_chip_id, time DESC);

-- События реле (hypertable)
CREATE TABLE relay_event (
  time         TIMESTAMPTZ NOT NULL,
  device_id    BIGINT NOT NULL,
  relay_number SMALLINT NOT NULL,
  state        SMALLINT,
  fault        BOOLEAN,
  rssi_r       INT,
  vbat         REAL,
  vacc         REAL
);
SELECT create_hypertable('relay_event', 'time');
CREATE INDEX idx_relay_event_device_time ON relay_event (device_id, time DESC);

-- Журнал команд
CREATE TABLE command_log (
  id           BIGSERIAL PRIMARY KEY,
  device_id    BIGINT REFERENCES device(chip_id) ON DELETE CASCADE,
  sensor_id    BIGINT REFERENCES sensor(id),
  command_type VARCHAR(20) NOT NULL,
  payload      TEXT NOT NULL,
  topic        VARCHAR(100) NOT NULL,
  sent_at      TIMESTAMPTZ DEFAULT now(),
  confirmed    BOOLEAN DEFAULT false
);
CREATE INDEX idx_command_log_device ON command_log (device_id, sent_at DESC);

-- ===== V2__sensor_number.sql =====
-- Номер слота датчика внутри блока (1..8), известен из топика EVENT_Sn
ALTER TABLE sensor ADD COLUMN sensor_number SMALLINT;

-- ===== V3__sensor_fields_json.sql =====
ALTER TABLE sensor ADD COLUMN IF NOT EXISTS fields_json TEXT;

ALTER TABLE sensor_telemetry ADD COLUMN IF NOT EXISTS fields_json TEXT;

-- ===== V4__sensor_registration.sql =====
-- Реестр зарегистрированных датчиков блока (топик ID/SENSORS)
-- Блок публикует список датчиков, зарегистрированных вручную (ESP в режиме AP):
-- {"S1": chipId, ..., "S8": chipId}, 0 = слот свободен.
-- Sx соответствует EVENT_Sx. К настройке блока допускаются только датчики из этого списка.
CREATE TABLE sensor_registration (
  id             BIGSERIAL PRIMARY KEY,
  device_id      BIGINT NOT NULL REFERENCES device(chip_id) ON DELETE CASCADE,
  slot           SMALLINT NOT NULL CHECK (slot BETWEEN 1 AND 8),
  sensor_chip_id BIGINT,
  updated_at     TIMESTAMPTZ DEFAULT now(),
  UNIQUE (device_id, slot)
);

-- ===== V5__automation.sql =====
-- Сценарии автоматизации: по измерению датчика управлять реле через команду CONTROL_R.
-- Источником измерения может быть индивидуальный датчик (топик chipId/VALUES)
-- либо датчик, работающий через блок реле. Исполнителем выступает реле блока.

-- Сценарий
CREATE TABLE automation (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(100) NOT NULL,
  description TEXT,
  enabled     BOOLEAN DEFAULT true,
  owner_id    BIGINT REFERENCES app_user(id),
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

-- Условия срабатывания сценария (И-группа внутри сценария)
CREATE TABLE automation_condition (
  id             BIGSERIAL PRIMARY KEY,
  automation_id  BIGINT NOT NULL REFERENCES automation(id) ON DELETE CASCADE,
  sensor_chip_id BIGINT NOT NULL,
  field          VARCHAR(40) NOT NULL,   -- moisture_adc, temperature, humidity, light, battery, vbat, rssi ...
  operator       VARCHAR(5) NOT NULL CHECK (operator IN ('GT', 'GE', 'LT', 'LE', 'EQ', 'NE')),
  value          REAL NOT NULL,
  negate         BOOLEAN DEFAULT false
);
CREATE INDEX idx_automation_condition_automation ON automation_condition (automation_id);
CREATE INDEX idx_automation_condition_sensor ON automation_condition (sensor_chip_id);

-- Действия сценария: команда реле блока
-- Публикуется на топик blockId/CONTROL_R с payload "relay_number;state;timestr"
CREATE TABLE automation_action (
  id             BIGSERIAL PRIMARY KEY,
  automation_id  BIGINT NOT NULL REFERENCES automation(id) ON DELETE CASCADE,
  block_id       BIGINT NOT NULL,
  relay_number   SMALLINT NOT NULL CHECK (relay_number BETWEEN 1 AND 8),
  relay_state    SMALLINT NOT NULL CHECK (relay_state IN (0, 1)),
  sort_order     SMALLINT DEFAULT 0
);
CREATE INDEX idx_automation_action_automation ON automation_action (automation_id);

-- История срабатываний сценариев (hypertable)
CREATE TABLE automation_event (
  time           TIMESTAMPTZ NOT NULL,
  automation_id  BIGINT NOT NULL REFERENCES automation(id) ON DELETE CASCADE,
  block_id       BIGINT NOT NULL,
  relay_number   SMALLINT NOT NULL,
  relay_state    SMALLINT,
  sensor_chip_id BIGINT,
  field          VARCHAR(40),
  value          REAL,
  threshold      REAL,
  fired          BOOLEAN
);
SELECT create_hypertable('automation_event', 'time');
CREATE INDEX idx_automation_event_time ON automation_event (time DESC);

-- ===== V6__mqtt_connection_settings.sql =====
-- MQTT-настройки устройства (аналог страницы конфигурации блока):
-- server, portTLS, port, userId, login, password, tls
-- broker_url хранит хост (без схемы); tls=true -> ssl://host:portTLS, иначе tcp://host:port
ALTER TABLE mqtt_connection ADD COLUMN port_tls INT;
ALTER TABLE mqtt_connection ADD COLUMN user_id VARCHAR(20);

-- ===== V7__drop_mqtt_connection.sql =====
-- Убрать настраиваемые MQTT-подключения: клиент настраивает MQTT в прошивке
-- и работает со своими брокерами своими программами.
DROP TABLE mqtt_connection CASCADE;
ALTER TABLE device DROP COLUMN mqtt_connection_id;

-- ===== V8__block_telemetry_and_device_status.sql =====
-- Телеметрия блока управления (RSSI_R, Vbat, Vacc) с временем.
-- Источники: EVENT_R (реле), STATE_R (реле), EVENT_S (датчик блока)
-- STATE_R не имеет поля Time → используется время сервера.
CREATE TABLE block_telemetry (
  time       TIMESTAMPTZ NOT NULL,
  device_id  BIGINT NOT NULL,
  rssi_r     INT,
  vbat       REAL,
  vacc       REAL
);
SELECT create_hypertable('block_telemetry', 'time');
CREATE INDEX idx_block_telemetry_device_time ON block_telemetry (device_id, time DESC);

-- Лог статусов устройства (онлайн/офлайн) из топика STATUS
CREATE TABLE device_status_log (
  time       TIMESTAMPTZ NOT NULL,
  device_id  BIGINT NOT NULL,
  status     VARCHAR(20) NOT NULL
);
SELECT create_hypertable('device_status_log', 'time');
CREATE INDEX idx_device_status_log_device_time ON device_status_log (device_id, time DESC);

-- ===== V9__sensor_power.sql =====
-- Мощность Wi-Fi передатчика датчика (есть только у автономных датчиков)
ALTER TABLE sensor ADD COLUMN IF NOT EXISTS power REAL;
ALTER TABLE sensor_telemetry ADD COLUMN IF NOT EXISTS power REAL;

-- ===== V10__block_sensor_type.sql =====
-- Новый тип устройства BLOCK_SENSOR: датчик, привязанный к слоту блока.
-- Если датчик ошибочно зарегистрирован как INDIVIDUAL_SENSOR, но стоит в sensor_registration —
-- это датчик блока, исправляем автоматически.

ALTER TABLE device DROP CONSTRAINT device_device_type_check;
ALTER TABLE device ADD CONSTRAINT device_device_type_check
    CHECK (device_type IN ('BLOCK', 'INDIVIDUAL_SENSOR', 'BLOCK_SENSOR'));

UPDATE device d
SET device_type = 'BLOCK_SENSOR'
WHERE d.device_type = 'INDIVIDUAL_SENSOR'
  AND EXISTS (
      SELECT 1 FROM sensor_registration r
      WHERE r.sensor_chip_id = d.chip_id
  );

-- ===== V11__sensor_last_time.sql =====
ALTER TABLE sensor ADD COLUMN IF NOT EXISTS last_time timestamp with time zone;

-- ===== V12__server_uptime_log.sql =====
CREATE TABLE IF NOT EXISTS server_uptime_log (
    id          BIGSERIAL PRIMARY KEY,
    started_at  timestamp with time zone NOT NULL,
    stopped_at  timestamp with time zone NULL
);

CREATE INDEX IF NOT EXISTS idx_server_uptime_log_started_at
    ON server_uptime_log (started_at DESC);

-- ===== V13__remove_moisture_sensor_default_name.sql =====
-- Убираем дефолтное имя "Moisture sensor", которое бэкенд ставил
-- автономным датчикам вместо пустого поля (теперь имя берётся из VALUES/Name).
UPDATE sensor SET name = NULL WHERE name = 'Moisture sensor';

-- ===== V14__device_timezone.sql =====
-- Часовой пояс устройства в знаковых минутах от UTC (смещение Java, локальное = UTC + offset).
-- Дефолт 180 = Москва: автономные датчики в прошивке жёстко в Москве, у блоков зона
-- становится известна после прошивки с полем Zone в STATE_R.
ALTER TABLE device ADD COLUMN IF NOT EXISTS tz_offset_minutes INTEGER NOT NULL DEFAULT 180;

-- ===== V15__server_uptime_last_seen.sql =====
ALTER TABLE server_uptime_log ADD COLUMN IF NOT EXISTS last_seen timestamp with time zone;

-- ===== V16__roles_and_device_access.sql =====
-- Роли: ADMIN (производитель, всё), USER (владелец своих устройств), VIEWER (только просмотр).
-- CLIENT переименовывается в USER, CHECK-констрейнт пересоздаётся.
-- Старый констрейнт допускает только ADMIN/CLIENT, поэтому порядок именно такой:
-- снять его → переименовать CLIENT в USER (иначе UPDATE падает) → вернуть с новым списком.
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_role_check;
UPDATE app_user SET role = 'USER' WHERE role = 'CLIENT';
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check CHECK (role IN ('ADMIN', 'USER', 'VIEWER'));

-- Отключение аккаунта без удаления: пока enabled = false, вход и выдача токена запрещены.
ALTER TABLE app_user ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Доступ аккаунта к устройству. Много аккаунтов на одно устройство (семья, подрядчик),
-- в том числе с пониженными правами. Заменяет device.owner_id — источник прав один.
CREATE TABLE device_access (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  chip_id    BIGINT NOT NULL REFERENCES device(chip_id) ON DELETE CASCADE,
  granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, chip_id)
);
CREATE INDEX idx_device_access_chip ON device_access (chip_id);

-- Переносим текущих владельцев и убираем старую колонку.
INSERT INTO device_access (user_id, chip_id)
SELECT owner_id, chip_id FROM device WHERE owner_id IS NOT NULL
ON CONFLICT DO NOTHING;

ALTER TABLE device DROP COLUMN IF EXISTS owner_id;

-- ===== V17__activation_code_and_features.sql =====
-- Ключ активации и флаг платных функций.
--
-- Ключ живёт в строке аккаунта, поэтому привязан к конкретному user.id и не может быть
-- использован другим аккаунтом. Ключ видит только админ (GET /api/admin/users),
-- пользователь лишь вводит его у себя, чтобы включить функции.
--
-- Функции — это управление блоками. Автономные датчики (INDIVIDUAL_SENSOR) и датчики,
-- подключённые к блоку (BLOCK_SENSOR), не тарифицируются: гейт в DeviceAccessService
-- смотрит только на device_type = 'BLOCK'.
--
-- Дефолт FALSE — новый аккаунт управлять блоками не может, пока не введёт ключ.
-- Существующие аккаунты переводим в TRUE: обновление не должно ломать текущую работу.

ALTER TABLE app_user ADD COLUMN features_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN activation_code VARCHAR(64);

-- Ключ уникален: один и тот же код не может висеть на двух аккаунтах.
CREATE UNIQUE INDEX uq_app_user_activation_code ON app_user (activation_code)
    WHERE activation_code IS NOT NULL;

UPDATE app_user SET features_enabled = TRUE;

-- ===== V18__device_invite.sql =====
-- Приглашения на доступ к устройству.
--
-- Ссылка-приглашение делает две вещи: залогиненный пользователь по ней принимает
-- приглашение, а не залогиненный — регистрируется по ней (логин+пароль) и сразу
-- получает доступ. Токен одноразовый: после принятия accepted_at заполняется и
-- повторно использовать ссылку нельзя.
--
-- Срок жизни 7 дней задаёт приложение (invite_ttl_days), здесь только колонка.
-- revoked нужен, чтобы приглашение можно было отозвать до принятия.

CREATE TABLE device_invite (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    chip_id BIGINT NOT NULL REFERENCES device(chip_id) ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_by BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    accepted_at TIMESTAMPTZ,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_device_invite_chip ON device_invite (chip_id);
CREATE INDEX idx_device_invite_expires ON device_invite (expires_at);

-- ===== V19__device_key_and_access_role.sql =====
-- Ключ устройства из комплектного листа (ID + ключ → привязка к аккаунту покупателя).
-- Открытый вид: админ должен видеть и копировать его, чтобы отпечатать лист.
ALTER TABLE device ADD COLUMN device_key VARCHAR(32);
CREATE UNIQUE INDEX IF NOT EXISTS idx_device_key ON device (device_key) WHERE device_key IS NOT NULL;

-- Платный доступ — срок, а не код активации: признак ставит админ (позже — оплата),
-- а по features_until гейт сам снимается. NULL = бессрочно.
ALTER TABLE app_user ADD COLUMN features_until TIMESTAMPTZ;
ALTER TABLE app_user DROP COLUMN IF EXISTS activation_code;

-- VIEWER перестаёт быть ролью аккаунта: это доступ к конкретному устройству.
-- Иначе приглашение «только чтение» понизило бы человека целиком и он потерял бы
-- управление своими другими блоками.
ALTER TABLE device_access ADD COLUMN access_role VARCHAR(16) NOT NULL DEFAULT 'USER';
UPDATE device_access SET access_role = 'VIEWER'
  WHERE user_id IN (SELECT id FROM app_user WHERE role = 'VIEWER');
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_role_check;
UPDATE app_user SET role = 'USER' WHERE role = 'VIEWER';
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check CHECK (role IN ('ADMIN', 'USER'));

-- Приглашение выдаёт либо управление, либо только чтение.
ALTER TABLE device_invite ADD COLUMN access_role VARCHAR(16) NOT NULL DEFAULT 'USER';

-- ===== V20__oauth_identity.sql =====
-- Вход через внешнего провайдера (Google).
--
-- Идентичность аккаунта — пара (провайдер, sub), а не email: адрес у Google меняется,
-- а локальная часть вроде john.smith+cottage@gmail.com не годится как ключ.
ALTER TABLE app_user ADD COLUMN auth_provider VARCHAR(20);
ALTER TABLE app_user ADD COLUMN auth_subject VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_user_oauth ON app_user (auth_provider, auth_subject)
  WHERE auth_provider IS NOT NULL;

-- password_hash остаётся NOT NULL: для OAuth-аккаунта туда кладётся хеш случайных
-- байт, поэтому парольный вход для него невозможен, а ветка с null не нужна.

-- ===== V21__block_payment.sql =====
-- Оплата переезжает с аккаунта на блок.
--
-- Покупатель подтверждает блок парой «ID + ключ», поэтому оплату к аккаунту привязать
-- нельзя: заведёшь нового пользователя с тем же листом — и срок начнётся заново.
-- Срок живёт на устройстве, отсчёт идёт с ПЕРВОГО подтверждения ключом, и все, у кого
-- есть доступ к блоку, видят один и тот же остаток.
--
-- Автономные датчики и датчики блока отдельными устройствами не тарифицируются:
-- в block_entitlement попадают только строки device_type = 'BLOCK'.
--
-- server_setting — настройки сервера, которые админ меняет из интерфейса, а не в базе:
-- срок по умолчанию для блоков без собственного плана и признак «дефолт бессрочный».

CREATE TABLE server_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO server_setting (setting_key, setting_value) VALUES
    ('block_paid_days', '365'),
    ('block_paid_unlimited', 'false');

CREATE TABLE block_entitlement (
    chip_id BIGINT PRIMARY KEY REFERENCES device (chip_id) ON DELETE CASCADE,
    -- NULL + unlimited = false: плана нет, берётся дефолт из server_setting.
    plan_days INTEGER,
    unlimited BOOLEAN NOT NULL DEFAULT FALSE,
    -- Отсчёт идёт с первого подтверждения ключом, поэтому до него срок неизвестен.
    started_at TIMESTAMPTZ,
    started_by_user_id BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    -- NULL вместе с unlimited = true означает бессрочный доступ.
    expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT block_entitlement_plan CHECK (plan_days IS NULL OR plan_days > 0)
);

CREATE INDEX idx_block_entitlement_started ON block_entitlement (started_at)
    WHERE started_at IS NOT NULL;

-- Перенос текущего состояния: платный доступ стоял на аккаунтах. Блокам, доступ к
-- которым был у платящего аккаунта, даём бессрочную оплату — иначе покупатели,
-- которые уже заплатили, потеряли бы управление в момент обновления.
-- DISTINCT ON: платящих пользователей на блоке может быть несколько, а строка оплаты
-- на блоке одна — оставляем первого по id, он и станет «регистратором» в истории.
INSERT INTO block_entitlement (chip_id, unlimited, started_at, started_by_user_id, expires_at)
SELECT DISTINCT ON (d.chip_id) d.chip_id, TRUE, now(), u.id, NULL
FROM device d
         JOIN device_access da ON da.chip_id = d.chip_id
         JOIN app_user u ON u.id = da.user_id
WHERE d.device_type = 'BLOCK'
  AND u.features_enabled = TRUE
  AND (u.features_until IS NULL OR u.features_until > now())
ORDER BY d.chip_id, u.id;

-- Оплата на аккаунтах больше не нужна: гейт смотрит только на блок.
ALTER TABLE app_user DROP COLUMN features_enabled;
ALTER TABLE app_user DROP COLUMN features_until;

-- ===== V22__user_prefs.sql =====
-- Настройки отображения в интерфейсе: хранятся в БД, чтобы пользователь не вводил их
-- заново при входе с другого браузера или телефона.
--
-- Важно: всё здесь — per user. Админ настраивает интерфейс так же, как любой другой,
-- своими строками со своим user_id, и ничего не навязывает остальным: чужие настройки
-- он не видит и не переписывает.

-- Устройство: блок или автономный датчик. Скрытие Vacc — вещь конкретного блока
-- (часть блоков питается от аккумулятора, и там напряжение питания нужно видеть),
-- а имя — личный алиас пользователя поверх общего device.name.
CREATE TABLE user_device_pref (
    user_id   BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    chip_id   BIGINT      NOT NULL REFERENCES device (chip_id) ON DELETE CASCADE,
    hide_vacc BOOLEAN     NOT NULL DEFAULT true,
    name      VARCHAR(100),
    PRIMARY KEY (user_id, chip_id)
);

-- Конкретный датчик: какие из его каналов v0..v3 показывать и в каких пределах
-- рисовать график. Не скрываем «по типу датчика»: у конкретного датчика может не быть
-- аппаратно освещённости, а бывает, что он меряет влажность воздуха, а не почвы.
CREATE TABLE user_sensor_pref (
    user_id        BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    sensor_chip_id BIGINT      NOT NULL REFERENCES device (chip_id) ON DELETE CASCADE,
    hidden_fields  VARCHAR(16) NOT NULL DEFAULT '',
    y_range        TEXT,
    PRIMARY KEY (user_id, sensor_chip_id)
);

COMMENT ON TABLE user_device_pref IS 'Личные настройки отображения устройства (блок/датчик): алиас имени, скрытие Vacc';
COMMENT ON TABLE user_sensor_pref IS 'Личные настройки отображения конкретного датчика: скрытые каналы v0..v3, диапазон оси Y (JSON)';

-- ===== V23__user_field_pref.sql =====
-- Подписи каналов и единицы — по типу датчика: они одинаковы для всех датчиков этого типа.
-- Настройки по-прежнему личные (user_id), поэтому у каждого свои подписи и админ ничего
-- не навязывает.
CREATE TABLE user_field_pref (
    user_id     BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    sensor_type SMALLINT    NOT NULL,
    field       VARCHAR(8)  NOT NULL,
    label       VARCHAR(100),
    unit        VARCHAR(20),
    PRIMARY KEY (user_id, sensor_type, field)
);

-- Тип 0 — тот, что был в браузере по умолчанию: заносим его, чтобы у всех переезд
-- выглядел как ничего не изменилось. Остальные типы остаются пустыми: подписи к ним
-- пользователь заводит сам.
INSERT INTO user_field_pref (user_id, sensor_type, field, label, unit)
SELECT id, 0, 'v0', 'Влажность почвы', '' FROM app_user;
INSERT INTO user_field_pref (user_id, sensor_type, field, label, unit)
SELECT id, 0, 'v1', 'Температура воздуха', '°C' FROM app_user;
INSERT INTO user_field_pref (user_id, sensor_type, field, label, unit)
SELECT id, 0, 'v2', 'Влажность воздуха', '%' FROM app_user;
INSERT INTO user_field_pref (user_id, sensor_type, field, label, unit)
SELECT id, 0, 'v3', 'Освещенность', 'Lux' FROM app_user;

COMMENT ON TABLE user_field_pref IS 'Личные подписи каналов и единицы по типу датчика';

-- ===== V24__sensor_field_default.sql =====
-- Дефолтные подписи каналов и единицы по типу датчика — те значения, которые видит
-- пользователь, пока не завёл свои. В отличие от user_field_pref они общие на всех и
-- принадлежат админу: он правит их через интерфейс, а не правкой кода.
--
-- Отдельная таблица нужна ещё и потому, что user_field_pref — персональные строки:
-- «подпись взята из дефолта» и «подпись выбрана руками» там неразличимы, и сменить
-- дефолт у всех сразу, не затирая чужих решений, было нечем.
CREATE TABLE sensor_field_default (
    sensor_type SMALLINT    NOT NULL,
    field       VARCHAR(8)  NOT NULL,
    label       VARCHAR(100),
    unit        VARCHAR(20),
    PRIMARY KEY (sensor_type, field)
);

-- Переносим значения, которые до сих пор были зашиты в коде, чтобы переезд ничего
-- не поменял: type 0 отдавал «Влажность почвы», «Температура воздуха» (°C),
-- «Влажность воздуха» (%) и «Освещенность» (Lux).
INSERT INTO sensor_field_default (sensor_type, field, label, unit) VALUES
    (0, 'v0', 'Влажность почвы', ''),
    (0, 'v1', 'Температура воздуха', '°C'),
    (0, 'v2', 'Влажность воздуха', '%'),
    (0, 'v3', 'Освещенность', 'Lux');

COMMENT ON TABLE sensor_field_default IS 'Дефолтные подписи каналов и единицы по типу датчика; правит админ';

-- ===== V25__invite_no_expiry.sql =====
-- Приглашение на автономный датчик действует бессрочно: у автономного датчика нет
-- срока отключения, и ссылка, которой его привязывают к аккаунту, не должна
-- протухать. У блоков expires_at остаётся заполненным — срок им нужен, просроченные
-- ссылки убираются кнопкой «Очистить завершённые».
ALTER TABLE device_invite ALTER COLUMN expires_at DROP NOT NULL;

-- ===== V26__sensor_calibration.sql =====
-- Калибровка влажности датчика (v0 / moisture_adc -> относительная влажность).
-- Держится три опорных точки АЦП для типа датчика 0 (щуп влажности):
--   value_1 — v0 на воздухе;
--   value_2 — v0 в полностью сухой почве (0% отн. влажности);
--   value_3 — v0 полностью в воде (100% отн. влажности).
-- Дефолты копируются в строку датчика при его появлении и правятся админом
-- (значения ниже — эталонные для прошивки, см. SensorCalibrationService).
CREATE TABLE sensor_calibration (
    id         BIGSERIAL PRIMARY KEY,
    sensor_id  BIGINT NOT NULL UNIQUE REFERENCES sensor(id) ON DELETE CASCADE,
    value_1    INTEGER NOT NULL DEFAULT 1650,
    value_2    INTEGER NOT NULL DEFAULT 1550,
    value_3    INTEGER NOT NULL DEFAULT 400,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Бэкфил: существующим датчикам типа 0 завести строку с дефолтами.
INSERT INTO sensor_calibration (sensor_id, value_1, value_2, value_3)
SELECT s.id, 1650, 1550, 400
FROM sensor s
WHERE s.sensor_type = 0
  AND NOT EXISTS (SELECT 1 FROM sensor_calibration c WHERE c.sensor_id = s.id);

-- ===== V27__sensor_calibration_default.sql =====
-- Дефолтные опорные точки калибровки влажности (АЦП v0) по типу датчика.
-- Копируются в sensor_calibration при появлении датчика типа 0; правит админ
-- из «Значения по умолчанию» на странице настроек: воздух / сухая почва (0%) / вода (100%).
CREATE TABLE sensor_calibration_default (
    sensor_type SMALLINT NOT NULL PRIMARY KEY,
    value_1 INTEGER NOT NULL,
    value_2 INTEGER NOT NULL,
    value_3 INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO sensor_calibration_default (sensor_type, value_1, value_2, value_3)
VALUES (0, 1650, 1550, 400)
ON CONFLICT (sensor_type) DO NOTHING;

-- ===== V28__sensor_type_default_zero.sql =====
-- Датчики по умолчанию имеют тип 0 (влажность почвы); тип из values приходит
-- только если он не 0. Ставим NOT NULL-семантику: NULL = 0.
ALTER TABLE sensor ALTER COLUMN sensor_type SET DEFAULT 0;
UPDATE sensor SET sensor_type = 0 WHERE sensor_type IS NULL;

-- ===== V29__moisture_scale.sql =====
-- Moisture gradient scale per sensor (two points: min/dry, max/wet)
CREATE TABLE moisture_scale (
  id BIGSERIAL PRIMARY KEY,
  sensor_id BIGINT NOT NULL REFERENCES sensor(id) ON DELETE CASCADE,
  min_pct INTEGER NOT NULL CHECK (min_pct >= 0 AND min_pct <= 100),
  max_pct INTEGER NOT NULL CHECK (max_pct >= 0 AND max_pct <= 100),
  min_color VARCHAR(7) NOT NULL,
  max_color VARCHAR(7) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_moisture_pct_range CHECK (min_pct < max_pct),
  CONSTRAINT uq_moisture_scale_sensor UNIQUE (sensor_id)
);
CREATE INDEX idx_moisture_scale_sensor ON moisture_scale(sensor_id);

-- ===== V30__sensor_scale.sql =====
-- Generic gradient scale per sensor field (v1/v2/v3): two points min/max with arbitrary values.
CREATE TABLE sensor_scale (
  id BIGSERIAL PRIMARY KEY,
  sensor_id BIGINT NOT NULL REFERENCES sensor(id) ON DELETE CASCADE,
  field_key VARCHAR(3) NOT NULL, -- 'v1' | 'v2' | 'v3'
  min_value INTEGER NOT NULL,
  max_value INTEGER NOT NULL,
  min_color VARCHAR(7) NOT NULL,
  max_color VARCHAR(7) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_sensor_scale_range CHECK (min_value < max_value),
  CONSTRAINT uq_sensor_scale_sensor_field UNIQUE (sensor_id, field_key)
);
CREATE INDEX idx_sensor_scale_sensor ON sensor_scale(sensor_id);
