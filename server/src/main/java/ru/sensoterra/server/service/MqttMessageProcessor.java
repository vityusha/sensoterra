package ru.sensoterra.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.sensoterra.server.ws.DeviceStatusWebSocketBroadcaster;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public class MqttMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MqttMessageProcessor.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMAT_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern UNQUOTED_VALUE = Pattern.compile("(\"[^\"\\\\\r\n]+\"\\s*:\\s*)((?=[^{}\\[\\]\",;\\s])[^{}\\[\\]\",;\r\n]+)");
    // Possessive \s*+ : with plain \s* both quantifiers overlap and a long run of spaces
    // costs O(n^2) steps on the single Paho callback thread.
    private static final Pattern EMPTY_VALUE = Pattern.compile("(\"[^\"\\\\\r\n]+\"\\s*+:\\s*+)(?=[,}])");
    /** Device payloads are a few hundred bytes; anything far larger is junk or an attack. */
    static final int MAX_PAYLOAD_CHARS = 16 * 1024;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final TelemetryService telemetryService;
    private final DeviceStateStore deviceStateStore;
    private final DeviceStatusWebSocketBroadcaster broadcaster;
    private final RelayCommandConfirmationService confirmationService;
    private final DeviceZoneService deviceZoneService;

    public MqttMessageProcessor(TelemetryService telemetryService,
                                DeviceStateStore deviceStateStore,
                                DeviceStatusWebSocketBroadcaster broadcaster,
                                RelayCommandConfirmationService confirmationService,
                                DeviceZoneService deviceZoneService) {
        this.telemetryService = telemetryService;
        this.deviceStateStore = deviceStateStore;
        this.broadcaster = broadcaster;
        this.confirmationService = confirmationService;
        this.deviceZoneService = deviceZoneService;
    }

    public void process(String topic, String payload, boolean retained) {
        if (payload != null && payload.length() > MAX_PAYLOAD_CHARS) {
            log.warn("Dropping oversized MQTT payload on {} ({} chars)", topic, payload.length());
            return;
        }
        try {
            String[] parts = topic.split("/");
            if (parts.length < 2) {
                return;
            }
            long deviceId;
            try {
                deviceId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                log.warn("Ignoring topic with non-numeric device id: {}", topic);
                return;
            }
            String type = parts[1];
            deviceStateStore.touch(deviceId);

            if (type.startsWith("EVENT_R")) {
                Integer relayIndex = suffixNumber(type, "EVENT_R");
                if (relayIndex != null) {
                    handleRelayEvent(deviceId, relayIndex, payload, retained);
                }
            } else if (type.startsWith("STATE_R")) {
                Integer relayIndex = suffixNumber(type, "STATE_R");
                if (relayIndex != null) {
                    handleRelayState(deviceId, relayIndex, payload);
                }
            } else if (type.startsWith("EVENT_S")) {
                Integer sensorIndex = suffixNumber(type, "EVENT_S");
                if (sensorIndex != null) {
                    handleSensorEvent(deviceId, sensorIndex, payload, retained);
                }
            } else if (type.equals("VALUES")) {
                handleValues(deviceId, payload, retained);
            } else if (type.equals("SENSORS")) {
                handleSensors(deviceId, payload);
            } else if (type.equals("STATUS")) {
                handleStatus(deviceId, payload);
            } else if (type.equals("PING")) {
                log.debug("Heartbeat PING from device {}", deviceId);
            } else {
                log.debug("Unknown topic type {}: {}", type, topic);
            }
        } catch (Exception e) {
            log.error("Error processing topic {}", topic, e);
        }
    }

    private void handleStatus(Long deviceId, String payload) {
        String status = payload.trim().isEmpty() ? "unknown" : payload.trim();
        String previous = deviceStateStore.getStatus(deviceId);
        if (previous.equals(status)) {
            log.debug("Device {} status {} (unchanged, skipping)", deviceId, status);
            return;
        }
        deviceStateStore.setStatus(deviceId, status);
        telemetryService.saveDeviceStatus(deviceId, status);
        broadcaster.broadcast(Map.of(
                "type", "status",
                "deviceId", deviceId,
                "status", status,
                "timestamp", System.currentTimeMillis()));
        log.info("Device {} status: {}", deviceId, status);
    }

    private void handleRelayEvent(Long deviceId, int relayIndex, String payload, boolean retained) {
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        int relayNumber = node.path("Relay").asInt(relayIndex);
        Boolean fault = node.get("Fault") != null ? node.get("Fault").asBoolean() : null;
        Integer state = intValue(node.get("State"));
        telemetryService.onRelayEvent(
                deviceId,
                relayNumber,
                parseTime(node.get("Time"), retained, deviceZoneService.zoneFor(deviceId)),
                state,
                Boolean.TRUE.equals(fault),
                intValue(node.get("RSSI_R")),
                floatValue(node.get("Vbat")),
                floatValue(node.get("Vacc")));
        telemetryService.saveBlockTelemetry(
                deviceId,
                intValue(node.get("RSSI_R")),
                floatValue(node.get("Vbat")),
                floatValue(node.get("Vacc")));
        if (state != null) {
            confirmationService.confirm(deviceId, relayNumber, state);
        }
        broadcaster.broadcast(Map.of(
                "type", "relayEvent",
                "deviceId", deviceId,
                "relay", relayNumber,
                "state", state != null ? state : 0,
                "fault", Boolean.TRUE.equals(fault),
                "timestamp", System.currentTimeMillis()));
    }

    private void handleRelayState(Long deviceId, int relayIndex, String payload) {
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        Integer zoneMinutes = intValue(node.get("Zone"));
        if (zoneMinutes != null) {
            deviceZoneService.updateZone(deviceId, zoneMinutes);
        }
        Short relayNumber = (short) node.path("Relay").asInt(relayIndex);
        Boolean activity = yesNo(node.get("Activity"));
        Boolean timer = yesNo(node.get("Timer"));
        TelemetryService.RelayData data = new TelemetryService.RelayData(relayNumber)
                .with(
                        node.has("Name") ? node.get("Name").asText() : null,
                        shortValue(node.get("State")),
                        activity,
                        node.has("Type") ? node.get("Type").asText() : null,
                        timer,
                        shortValue(node.get("S_data")),
                        localTimeValue(node.get("Main_time")),
                        shortValue(node.get("Duration_main")),
                        localTimeValue(node.get("Extra_time")),
                        shortValue(node.get("Duration_extra")),
                        shortValue(node.get("Interval")),
                        longValue(node.get("Sensor_ID")),
                        localTimeValue(node.get("Start_time")),
                        localTimeValue(node.get("Finish_time")),
                        intValue(node.get("One_limit")),
                        intValue(node.get("Low_limit")),
                        intValue(node.get("High_limit")),
                        intValue(node.get("RSSI_R")),
                        floatValue(node.get("Vbat")),
                        floatValue(node.get("Vacc")));
        telemetryService.upsertRelay(deviceId, data);
        telemetryService.saveBlockTelemetry(
                deviceId,
                data.rssiR,
                data.vbat,
                data.vacc);
        if (data.state != null) {
            confirmationService.confirm(deviceId, data.relayNumber, data.state);
        }
        confirmationService.confirmRelayParams(deviceId, data.relayNumber);
        broadcaster.broadcast(Map.of(
                "type", "relayState",
                "deviceId", deviceId,
                "relay", data.relayNumber,
                "state", data.state != null ? data.state : 0,
                "timestamp", System.currentTimeMillis()));
    }

    private void handleSensorEvent(Long deviceId, int sensorNumber, String payload, boolean retained) {
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        Long chipId = longValue(node.get("ID"));
        if (chipId == null) {
            return;
        }
        if (!telemetryService.isSensorRegisteredInSlot(deviceId, sensorNumber, chipId)) {
            log.debug("Ignoring EVENT_S{} from device {}: sensor {} is not registered in that slot",
                    sensorNumber, deviceId, chipId);
            return;
        }
        Integer numMessage = intValue(node.get("Num_message"));
        Instant time = parseTime(node.get("Time"), retained, deviceZoneService.zoneFor(deviceId));
        if (telemetryService.isStaleDuplicate(deviceId, chipId, numMessage, time)) {
            log.debug("Sensor {} dup EVENT_S {} at {} for device {}", chipId, numMessage, time, deviceId);
            return;
        }
        TelemetryService.SensorData data = new TelemetryService.SensorData();
        data.sensorNumber = (short) sensorNumber;
        data.name = node.has("Name") ? node.get("Name").asText() : null;
        data.sensorType = shortValue(node.get("Type"));
        data.numMessage = numMessage;
        data.battery = floatValue(node.get("Battery"));
        data.batload = floatValue(node.get("BatLoad"));
        data.moistureAdc = intValue(node.get("V0"));
        data.temperature = floatValue(node.get("V1"));
        data.humidity = floatValue(node.get("V2"));
        data.light = intValue(node.get("V3"));
        data.sleep = intValue(node.get("Sleep"));
        data.send = intValue(node.get("Send"));
        data.percentN = firstShort(node, "LN", "percent_n");
        data.percentS = firstShort(node, "LS", "percent_s");
        data.minVoltage = minVoltageMv(node);
        data.rssi = intValue(node.get("RSSI"));
        data.rssiBlock = intValue(node.get("RSSI_R"));
        data.vbat = floatValue(node.get("Vbat"));
        data.vacc = floatValue(node.get("Vacc"));
        data.power = floatValue(node.get("Power"));
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= 3; i++) {
            if (node.has("V" + i)) {
                JsonNode f = node.get("V" + i);
                fields.put("v" + i, f.isNumber() ? f.numberValue() : f.asText());
            }
        }
        if (!fields.isEmpty()) {
            data.fields = fields;
        }
        telemetryService.upsertDevice(deviceId, "BLOCK");
        telemetryService.onSensorEvent(deviceId, chipId, data, time);
        telemetryService.saveBlockTelemetry(
                deviceId,
                data.rssiBlock,
                data.vbat,
                data.vacc);
        confirmationService.confirmSensorParams(deviceId,
                data.sleep, data.send, data.percentN != null ? data.percentN.intValue() : null,
                data.percentS != null ? data.percentS.intValue() : null, data.minVoltage);
        broadcaster.broadcast(Map.of(
                "type", "sensor",
                "deviceId", deviceId,
                "chipId", chipId,
                "name", data.name != null ? data.name : "",
                "moisture", data.moistureAdc != null ? data.moistureAdc : 0,
                "temperature", data.temperature != null ? data.temperature : 0,
                "humidity", data.humidity != null ? data.humidity : 0,
                "light", data.light != null ? data.light : 0,
                "timestamp", System.currentTimeMillis()));
    }

    private void handleSensors(Long deviceId, String payload) {
        JsonNode node = parse(payload);
        if (node == null || !node.isObject()) {
            return;
        }
        java.util.Map<Integer, Long> slots = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 8; i++) {
            JsonNode slotNode = node.get("S" + i);
            if (slotNode == null || slotNode.isMissingNode() || slotNode.isNull() || slotNode.asText("").isBlank()) {
                continue;
            }
            Long chipId = longValue(slotNode);
            slots.put(i, chipId != null ? chipId : 0L);
        }
        telemetryService.updateRegisteredSensors(deviceId, slots);
        broadcaster.broadcast(Map.of(
                "type", "sensors",
                "deviceId", deviceId,
                "timestamp", System.currentTimeMillis()));
        log.debug("Device {} sensors registration updated: {} slots", deviceId, slots.size());
    }

    private void handleValues(Long deviceId, String payload, boolean retained) {
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        Long chipId = longValue(node.get("ID"));
        if (chipId == null) {
            chipId = deviceId;
        }
        if (log.isDebugEnabled()) {
            log.debug("VALUES raw for {} (dev {}): {}", chipId, deviceId, node.toString().replace("\n", " "));
        }
        if (telemetryService.isRegisteredOnBlock(chipId)) {
            log.debug("Sensor {} is registered on a block; ignoring own VALUES topic", chipId);
            return;
        }
        Integer numMessage = intValue(node.get("Number"));
        Instant time = parseTime(node.get("Time"), retained, deviceZoneService.zoneFor(deviceId));
        if (telemetryService.isStaleDuplicate(deviceId, chipId, numMessage, time)) {
            log.debug("Sensor {} dup VALUES {} at {} for device {}", chipId, numMessage, time, deviceId);
            return;
        }
        TelemetryService.SensorData data = new TelemetryService.SensorData();
        data.name = node.has("Name") ? node.get("Name").asText() : null;
        data.firmwareVersion = intValue(node.get("version"));
        data.numMessage = numMessage;
        data.battery = floatValue(node.get("Battery"));
        data.batload = floatValue(node.get("BatLoad"));
        data.batMin = floatValue(node.get("BatMin"));
        data.rssi = intValue(node.get("RSSI"));
        data.power = floatValue(node.get("Power"));
        // Старые прошивки шлют влажность под ключом ADC, новые — Moisture.
        data.moistureAdc = intValue(node.has("Moisture") ? node.get("Moisture") : node.get("ADC"));
        data.temperature = floatValue(node.get("Temperature"));
        data.humidity = floatValue(node.get("Humidity"));
        data.light = intValue(node.get("Light"));
        data.error = node.has("Error") ? node.get("Error").asText() : null;
        data.sleep = intValue(node.get("Sleep"));
        data.send = intValue(node.get("Send"));
        data.percentN = firstShort(node, "LN", "percent_n");
        data.percentS = firstShort(node, "LS", "percent_s");
        data.minVoltage = minVoltageMv(node);
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= 3; i++) {
            String key = "v" + i;
            if (node.has(key)) {
                JsonNode f = node.get(key);
                fields.put(key, f.isNumber() ? f.numberValue() : f.asText());
            }
        }
        if (!fields.isEmpty()) {
            data.fields = fields;
        }
        telemetryService.upsertDevice(deviceId, "INDIVIDUAL_SENSOR");
        telemetryService.onSensorEvent(deviceId, chipId, data, parseTime(node.get("Time"), retained, deviceZoneService.zoneFor(deviceId)));
        confirmationService.confirmSensorParams(deviceId,
                data.sleep, data.send, data.percentN != null ? data.percentN.intValue() : null,
                data.percentS != null ? data.percentS.intValue() : null, data.minVoltage);
        broadcaster.broadcast(Map.of(
                "type", "sensor",
                "deviceId", deviceId,
                "chipId", chipId,
                "name", data.name != null ? data.name : "",
                "moisture", data.moistureAdc != null ? data.moistureAdc : 0,
                "temperature", data.temperature != null ? data.temperature : 0,
                "humidity", data.humidity != null ? data.humidity : 0,
                "light", data.light != null ? data.light : 0,
                "timestamp", System.currentTimeMillis()));
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            // Firmware sends invalid JSON (empty values, ';' instead of ','); normalize() usually fixes it,
            // so warn only if it does not.
            log.debug("Cannot parse MQTT payload ({} bytes), normalizing: {}", payload.length(), e.getMessage());
            String normalized = normalize(payload);
            try {
                return objectMapper.readTree(normalized);
            } catch (Exception e2) {
                log.warn("Cannot parse MQTT payload even after normalization: {}; raw: {}",
                        e2.getMessage(), payload.replace("\n", " "));
                return null;
            }
        }
    }

    private String normalize(String payload) {
        String s = payload;
        s = s.replaceAll(";\\s*(?=\"|})", ",");
        s = s.replaceAll(";\\s*$", "");
        s = EMPTY_VALUE.matcher(s).replaceAll("$1null");
        Matcher m = UNQUOTED_VALUE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String value = m.group(2).trim();
            String replacement;
            if (isJsonLiteral(value)) {
                replacement = m.group(1) + value;
            } else {
                replacement = m.group(1) + "\"" + value + "\"";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String result = sb.toString();
        String trimmed = result.trim();
        if (trimmed.startsWith("{") && !trimmed.endsWith("}")) {
            result = result + "\n}";
        }
        return result;
    }

    private static boolean isJsonLiteral(String v) {
        return v.equals("true") || v.equals("false") || v.equals("null")
                || v.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    }

    private Instant parseTime(JsonNode node, boolean retained, ZoneId zone) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return retained ? null : Instant.now();
        }
        String text = node.asText().trim();
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{TIME_FORMAT, TIME_FORMAT_ISO}) {
            try {
                return LocalDateTime.parse(text, fmt).atZone(zone).toInstant();
            } catch (Exception ignored) {
                // try next format
            }
        }
        return retained ? null : Instant.now();
    }

    private LocalTime localTimeValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.asText("").isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean yesNo(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asText().equalsIgnoreCase("Yes");
    }

    private Short shortValue(JsonNode node) {
        Integer i = intValue(node);
        return i == null ? null : i.shortValue();
    }

    /**
     * Минимальное напряжение в милливольтах. Прошивки называют поле по-разному: новые
     * шлют MIN_V сразу в мВ, а 1.2.x (например, 14288428) — BatMin в вольтах. Без
     * перевода 2,5 В превратились бы в 2 мВ, и подтверждение настроек не сошлось бы.
     */
    private Integer minVoltageMv(JsonNode node) {
        Integer mv = firstInt(node, "MIN_V", "min_voltage");
        if (mv != null) {
            return mv;
        }
        Float volts = floatValue(node.get("BatMin"));
        return volts == null ? null : Math.round(volts * 1000f);
    }

    private Integer firstInt(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull() && !v.isMissingNode() && !v.asText("").isBlank()) {
                return intValue(v);
            }
        }
        return null;
    }

    private Short firstShort(JsonNode node, String... keys) {
        Integer i = firstInt(node, keys);
        return i == null ? null : i.shortValue();
    }

    private Integer intValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        String text = node.asText();
        try {
            return text.isBlank() ? null : Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String text = node.asText();
        try {
            return text.isBlank() ? null : Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Float floatValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return (float) node.asDouble();
        }
        String text = node.asText();
        try {
            return text.isBlank() ? null : Float.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer suffixNumber(String type, String prefix) {
        String suffix = type.substring(prefix.length());
        try {
            return Integer.valueOf(suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}