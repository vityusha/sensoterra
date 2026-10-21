<template>
  <div class="device">
    <div class="header-row">
      <button @click="$router.push('/')" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>
        <template v-if="editingName && canControl">
          <input class="name-input" v-model="editNameVal" @click.stop @keyup.enter="saveName" @keyup.esc="editingName=false" />
        </template>
        <template v-else>
          <span :class="canControl ? 'editable' : ''" @click="canControl && startEditName()">{{ displayName }}</span>
        </template>
      </h2>
      <span v-if="device?.deviceType === 'BLOCK'" class="status-tag" :class="isOnline ? 'online' : 'offline'">
        {{ isOnline ? 'online' : 'offline' }}
      </span>
    </div>

    <!-- Оплата блока: срок один на всех, кто имеет доступ, и он общий, а не «мой». -->
    <div v-if="isBlock" class="pay-line" :class="payClass">{{ paymentText(payment) }}</div>

    <!-- Закрытый блок: состояние, графики и настройки недоступны до оплаты. -->
    <section v-if="isBlock && payment && !payment.paid" class="section paywall">
      <h3>Блок закрыт</h3>
      <p class="paywall-text">
        <template v-if="!payment.started">
          Срок оплаты ещё не запущен. Он начнётся автоматически, когда вы подтвердите
          владение блоком парой «ID + ключ» с комплектного листа.
        </template>
        <template v-else>
          {{ paymentText(payment) }}. Управление, настройки и статистика блока откроются
          после оплаты. Вопросы — продавцу.
        </template>
      </p>
    </section>

    <!-- Приглашение: доступ выдаётся по одноразовой ссылке, оплата для этого не нужна. -->
    <section v-if="canShare && device?.deviceType !== 'INDIVIDUAL_SENSOR' && !blockClosed" class="section">
      <div class="section-head">
        <h3>Приглашение</h3>
        <select v-model="inviteAccessRole" class="role-select">
          <option value="USER">с управлением</option>
          <option value="VIEWER">только чтение</option>
        </select>
        <button class="btn small" @click="makeInvite" :disabled="inviteBusy">
          {{ inviteBusy ? '...' : 'Создать ссылку' }}
        </button>
      </div>
      <div v-if="inviteError" class="invite-error">{{ inviteError }}</div>
      <div v-if="lastInvite" class="invite-box">
        <div class="invite-link">{{ inviteUrl(lastInvite.token) }}</div>
        <div class="invite-note">Ссылка одноразовая, действует до {{ fmtDate(lastInvite.expiresAt) }}.
          Отправьте её тому, кому нужен доступ{{ lastInvite.accessRole === 'VIEWER' ? ' только на чтение' : '' }}.</div>
        <button class="btn small" @click="copyInvite(lastInvite.token)">Скопировать</button>
      </div>
      <div v-if="invites.length" class="invite-list">
        <div class="invite-row" v-for="i in invites" :key="i.id">
          <code class="invite-token">{{ i.token.slice(0, 8) }}…</code>
          <span class="invite-state" :class="i.usable ? 'ok' : 'no'">
            {{ i.usable ? `до ${fmtDate(i.expiresAt)}` : (i.acceptedAt ? 'принято' : 'отозвано') }}
          </span>
          <span class="invite-role">{{ i.accessRole === 'VIEWER' ? 'чтение' : 'управление' }}</span>
          <button v-if="i.usable" class="btn small danger" @click="dropInvite(i)">Отозвать</button>
        </div>
        <div class="invite-cleanup">
          <button
            v-if="removableInvites.length"
            class="btn small secondary"
            :disabled="clearBusy"
            @click="clearFinished"
          >
            {{ clearBusy ? '...' : `Очистить завершённые (${removableInvites.length})` }}
          </button>
          <button
            v-if="invites.length"
            class="btn small danger"
            :disabled="clearBusy"
            title="Удалить все ссылки, включая те, что ещё работают"
            @click="clearEverything"
          >
            Удалить все ({{ invites.length }})
          </button>
        </div>
      </div>
    </section>

    <!-- Ключ для комплектного листа: его копирует админ и отдаёт покупателю вместе с блоком. -->
    <section v-if="auth.user?.role === 'ADMIN' && device?.deviceType !== 'INDIVIDUAL_SENSOR'" class="section">
      <div class="section-head">
        <h3>Ключ для комплектного листа</h3>
        <button class="btn small" @click="loadKey" :disabled="keyBusy">{{ keyBusy ? '...' : 'Показать' }}</button>
      </div>
      <div v-if="deviceKey" class="invite-box">
        <div class="invite-link">ID {{ device.chipId }} · ключ {{ deviceKey }}</div>
        <div class="invite-note">Распечатайте на листе, который едет в комплекте с блоком.
          Покупатель введёт эту пару при регистрации.</div>
        <div class="invite-actions">
          <button class="btn small" @click="copyKey">Скопировать</button>
          <button class="btn small danger" @click="regenKey">Перевыпустить</button>
        </div>
      </div>
    </section>

    <!-- Кто ещё имеет доступ к этому устройству и на каких условиях. -->
    <section v-if="accessList.length" class="section">
      <div class="section-head"><h3>Доступ</h3></div>
      <div class="invite-list">
        <div class="invite-row" v-for="a in accessList" :key="a.userId">
          <code class="invite-token">{{ a.username }}</code>
          <span class="invite-role">{{ a.accessRole === 'VIEWER' ? 'только чтение' : 'управление' }}</span>
          <button v-if="canControl && a.userId !== auth.user?.id" class="btn small" @click="swapAccessRole(a)">
            {{ a.accessRole === 'VIEWER' ? 'дать управление' : 'оставить на чтение' }}
          </button>
          <button v-if="canControl && a.userId !== auth.user?.id" class="btn small danger"
            @click="revokeAccess(a)">Отозвать</button>
        </div>
      </div>
    </section>

    <template v-if="device?.deviceType === 'BLOCK'">
      <section class="section">
        <h3>Телеметрия блока</h3>
        <div class="telemetry-row" v-if="blockTelemetry">
          <span class="t-item">RSSI роутера: <b>{{ blockTelemetry.rssiR }}</b> dBm</span>
          <span class="t-item">Vbat: <b>{{ blockTelemetry.vbat?.toFixed(2) }}</b> В</span>
          <span class="t-item" v-if="showVacc">Vacc: <b>{{ blockTelemetry.vacc?.toFixed(2) }}</b> В</span>
          <span class="t-item" v-if="blockTelemetry?.time">Время: <b>{{ fmtEventTimeWithZone(blockTelemetry.time, device?.tzOffsetMinutes) }}</b></span>
        </div>
        <div class="telemetry-row muted" v-else>нет данных</div>
        <!-- Выбор «что показывать» живёт здесь, а не в общем списке настроек: у блока он
             свой, и при сотнях блоков список на странице настроек был бы нечитаемым. -->
        <label v-if="!isAdmin" class="t-item vacc-toggle">
          <input type="checkbox" :checked="!isVaccHidden(chipId)" @change="onVaccChange($event.target.checked)" />
          показывать Vacc (напряжение аккумулятора)
        </label>
      </section>
    </template>

    <section class="section">
      <h3>Каналы управления</h3>
      <div class="relay-list">
        <div class="relay-card" v-for="r in relays" :key="r.id" @click="openRelaySettings(r)" title="Открыть настройки канала">
          <div class="relay-top">
            <span class="dot" :class="!r.activity ? 'inactive' : (r.state ? 'on' : 'off')"></span>
            <span class="relay-num">R{{ r.relayNumber }}</span>
            <div class="relay-id">
              <span class="relay-name">{{ r.name || '—' }}</span>
              <span class="relay-type">
                {{ relayTypeName(r) }}
                <span v-if="relayTypeComment(r)" class="relay-type-comment">{{ relayTypeComment(r) }}</span>
              </span>
            </div>
            <span class="relay-last-time" v-if="!r.activity">неактивен</span>
            <span class="relay-last-time" v-else-if="relayLastText(r)">{{ relayLastText(r) }}</span>
          </div>
          <div class="relay-actions">
            <button v-if="canControl" @click.stop="toggle(r, 1)" class="btn small" :disabled="busy===r.id || !r.activity">Вкл</button>
            <button v-if="canControl" @click.stop="toggle(r, 0)" class="btn small danger" :disabled="busy===r.id || !r.activity">Выкл</button>
            <button v-if="canControl" @click.stop="openRelaySettings(r)" class="btn small ghost" :disabled="!isOnline">Настройки</button>
          </div>
          <div class="relay-stats" v-if="r.activity">
            <button @click.stop="toggleRelayStats(r)" class="btn small ghost">
              {{ relayStatsOpen[r.relayNumber] ? 'Скрыть' : 'Статистика' }} {{ relayStatsOpen[r.relayNumber] ? '▾' : '▸' }}
            </button>
          </div>
          <div v-if="r.activity && relayStatsOpen[r.relayNumber]" class="relay-stats-panel">
            <div class="range-select">
              <button v-for="rg in ranges" :key="rg.label" class="btn small" :class="{ active: relayRangeVal[r.relayNumber] === rg.value }" @click.stop="selectRelayRange(r, rg.value)">{{ rg.label }}</button>
              <button class="btn small" :class="{ active: relayRangeVal[r.relayNumber] === 'custom' }" @click.stop="relayShowCustom[r.relayNumber] = !relayShowCustom[r.relayNumber]">{{ relayShowCustom[r.relayNumber] ? 'Скрыть' : 'Свой период' }}</button>
            </div>
            <div v-if="relayShowCustom[r.relayNumber]" class="custom-range">
              <label>С <input @click.stop v-model="relayCustomFrom[r.relayNumber]" type="datetime-local" /></label>
              <label>По <input @click.stop v-model="relayCustomTo[r.relayNumber]" type="datetime-local" /></label>
              <button class="btn small" @click.stop="applyRelayCustom(r)">Применить</button>
            </div>
            <div class="chart-section" v-if="relayStatsData[r.relayNumber]?.states?.length">
              <LineChart :labels="relayStatsData[r.relayNumber].labels" :datasets="[{ label: relayName(r), data: relayStatsData[r.relayNumber].states, color: '#f39c12', fill: true, stepped: true }]" :zone-offset="device?.tzOffsetMinutes" :downtimes="downtimes" :y-min="0" :y-max="1" :y-step="1" :y-tick-map="{ 0: 'выкл', 1: 'вкл' }" :x-min="relayStatsData[r.relayNumber].startMs" :x-max="relayStatsData[r.relayNumber].endMs" />
            </div>
            <div class="empty small" v-else>Нет переключений за выбранный период</div>
          </div>
        </div>
        <div v-if="!relays.length" class="empty small">Нет каналов</div>
      </div>
    </section>

    <section class="section">
      <h3>{{ device?.deviceType === 'BLOCK' ? 'Датчики блока' : 'Датчик' }}</h3>
      <div class="sensor-list">
        <div class="sensor-card" v-for="s in sensors" :key="s.id" @click="openSensor(s)">
          <div class="sensor-top">
            <span class="sensor-name">{{ s.name || `S${s.sensorNumber ?? ''}` }}{{ s.sensorChipId ? ` (${s.sensorChipId})` : '' }}</span>
            <span v-if="s.sensorNumber" class="type-tag">S{{ s.sensorNumber }}</span>
            <span v-if="s.sensorType" class="type-tag">type {{ s.sensorType }}</span>
            <button v-if="canControl && device?.deviceType === 'BLOCK' && s.sensorNumber" @click.stop="openSensorSettings(s)" class="btn small ghost" :disabled="!isOnline">Настройки</button>
            <button v-if="canControl && device?.deviceType === 'INDIVIDUAL_SENSOR'" @click.stop="openIndividualSensorSettings(s)" class="btn small ghost">Настройки</button>
          </div>
          <div class="sensor-readings">
            <span v-if="s.lastTime" class="reading muted">Получено: {{ fmtEventTimeWithZone(s.lastTime, device?.tzOffsetMinutes) }} <span v-if="s.numMessage != null" class="msg-num">[{{ s.numMessage }}]</span> <span class="ago">({{ fmtAgo(s.lastTime, nowTick) }})</span></span>
            <span v-if="showField(s, 'v0')" class="reading">{{ getLabel(s, 'v0') }}: <template v-if="moisturePct(s) != null">{{ moisturePct(s) }}% ({{ s.moistureAdc }}<span v-if="getUnit(s, 'v0') && getUnit(s, 'v0') !== '%'"> {{ getUnit(s, 'v0') }}</span>)</template><template v-else>{{ s.moistureAdc }}<span v-if="getUnit(s, 'v0')"> {{ getUnit(s, 'v0') }}</span></template><span v-if="moistureColor(s)" class="pct-color" :style="{ background: moistureColor(s) }"></span></span>
            <span v-if="moistureImmersion(s)" class="reading immersion-warning">Проверьте уровень погружения датчика в почву</span>
            <span v-if="showField(s, 'v1')" class="reading">{{ getLabel(s, 'v1') }}: {{ s.temperature }}<span v-if="getUnit(s, 'v1')"> {{ getUnit(s, 'v1') }}</span><span v-if="fieldColor(s, 'v1', s.temperature)" class="pct-color" :style="{ background: fieldColor(s, 'v1', s.temperature) }"></span></span>
            <span v-if="showField(s, 'v2')" class="reading">{{ getLabel(s, 'v2') }}: {{ s.humidity }}<span v-if="getUnit(s, 'v2')"> {{ getUnit(s, 'v2') }}</span><span v-if="fieldColor(s, 'v2', s.humidity)" class="pct-color" :style="{ background: fieldColor(s, 'v2', s.humidity) }"></span></span>
            <span v-if="showField(s, 'v3')" class="reading">{{ getLabel(s, 'v3') }}: {{ s.light }}<span v-if="getUnit(s, 'v3')"> {{ getUnit(s, 'v3') }}</span><span v-if="fieldColor(s, 'v3', s.light)" class="pct-color" :style="{ background: fieldColor(s, 'v3', s.light) }"></span></span>
            <span v-if="s.battery != null" class="reading">Батарея: {{ s.battery }}</span>
            <span v-if="s.rssi != null" class="reading">RSSI: {{ s.rssi }}</span>
            <span v-if="s.power != null" class="reading">Power: {{ s.power }}</span>
            <span v-if="!hasAnyData(s)" class="reading muted">нет данных</span>
          </div>
          <div class="sensor-config" v-if="sensorConfigText(s)">
            <span class="reading muted">{{ sensorConfigText(s) }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Оплата: админ назначает план конкретному блоку или отменяет все регистрации. -->
    <section v-if="isAdmin && isBlock" class="section">
      <div class="section-head">
        <h3>Оплата блока</h3>
      </div>
      <div class="pay-admin-row">
        <label>
          Срок, дней
          <input v-model="planDays" type="number" min="1" placeholder="напр. 365" />
        </label>
        <label class="opt">
          <input type="checkbox" v-model="planUnlimited" /> бессрочно
        </label>
        <button class="btn small" @click="savePlan" :disabled="planBusy">
          {{ planBusy ? '...' : 'Назначить план' }}
        </button>
      </div>
      <div class="pay-admin-row">
        <label>
          Продлить, дней
          <input v-model="extendDays" type="number" min="1" placeholder="напр. 30" />
        </label>
        <button class="btn small" @click="doExtend" :disabled="planBusy || !extendDays">
          {{ planBusy ? '...' : 'Продлить' }}
        </button>
        <button class="btn small danger" @click="doReset" :disabled="planBusy">Отменить все регистрации</button>
      </div>
      <div class="invite-note">
        Отмена регистраций снимает доступы и отзывает живые ссылки, а отсчёт обнуляет:
        срок снова пойдёт с первого подтверждения «ID + ключ» по этому же плану.
      </div>
      <div v-if="planError" class="invite-error">{{ planError }}</div>
    </section>

    <section class="section" v-if="registeredSensors.length && !blockClosed">
      <h3>Зарегистрированные датчики (для настройки блоков)</h3>
      <div class="sensor-list">
        <div class="sensor-card" v-for="r in registeredSensors" :key="r.id" :class="{ linked: isLinked(r) }" @click="openSensor(r)">
          <div class="sensor-top">
            <span class="sensor-name">{{ registeredName(r) }} ({{ r.sensorChipId }})</span>
            <span class="type-tag">зарегистрирован</span>
            <span v-if="isLinked(r)" class="type-tag">используется</span>
          </div>
        </div>
      </div>
    </section>

    <section class="section" v-if="device?.deviceType === 'BLOCK' && !blockClosed">
      <h3>
        <span class="editable" @click="showStats = !showStats">Статистика {{ showStats ? '▾' : '▸' }}</span>
      </h3>
      <template v-if="showStats">
        <div class="range-select">
          <button v-for="r in ranges" :key="r.label" class="btn small" :class="{ active: range === r.value }" @click="setRange(r.value)">{{ r.label }}</button>
          <button class="btn small" :class="{ active: range === 'custom' }" @click="showCustom = !showCustom">{{ showCustom ? 'Скрыть' : 'Свой период' }}</button>
        </div>
        <div v-if="showCustom" class="custom-range">
          <label>С <input v-model="customFrom" type="datetime-local" /></label>
          <label>По <input v-model="customTo" type="datetime-local" /></label>
          <button class="btn small" @click="applyCustom">Применить</button>
        </div>
        <div class="stats-summary" v-if="statusStatsData.length">
          <span>Отключений (&gt;60&nbsp;c): {{ statusSummary.outages }}</span>
          <span>Микросрывов (≤60&nbsp;c): {{ statusSummary.micro }}</span>
          <span v-if="statusSummary.longest">Самое длительное: {{ formatDuration(statusSummary.longest) }}</span>
          <span v-if="statusSummary.ongoing" class="ongoing">Связь не восстановлена</span>
        </div>
        <div class="chart-section" v-if="blockStatsData.length">
          <h4>RSSI_R</h4>
          <LineChart :labels="timeLabels" :datasets="[{ label: 'RSSI_R', data: blockStatsData.map(d => d.rssiR), color: '#3498db' }]" unit="dBm" :zone-offset="device?.tzOffsetMinutes" :downtimes="downtimes" :x-min="statsXMin" :x-max="statsXMax" />
        </div>
        <div class="chart-section" v-if="blockStatsData.length">
          <h4>Vbat</h4>
          <LineChart :labels="vbatStatsData.map(d => d.time)" :datasets="[{ label: 'Vbat', data: vbatStatsData.map(d => d.vbat), color: '#2ecc71' }]" unit="V" :zone-offset="device?.tzOffsetMinutes" :downtimes="downtimes" :y-min="0" :y-max="3.5" :y-step="0.5" :x-min="statsXMin" :x-max="statsXMax" />
        </div>
        <div class="chart-section" v-if="showVacc && blockStatsData.length">
          <h4>Vacc</h4>
          <LineChart :labels="timeLabels" :datasets="[{ label: 'Vacc', data: blockStatsData.map(d => d.vacc), color: '#f39c12' }]" unit="V" :zone-offset="device?.tzOffsetMinutes" :downtimes="downtimes" :y-min="0" :y-max="25" :y-step="5" :x-min="statsXMin" :x-max="statsXMax" />
        </div>
        <div class="chart-section" v-if="statusStatsData.length">
          <h4>Статус</h4>
          <LineChart :labels="statusTimeLabels" :datasets="[{ label: 'online', data: statusStatsData.map(d => d.status === 'online' ? 1 : 0), color: '#2ecc71', fill: true, stepped: true }]" :zone-offset="device?.tzOffsetMinutes" :downtimes="downtimes" :offline="offlineIntervals" :continuous="true" :y-min="0" :y-max="1" :y-step="1" :y-tick-map="{ 0: 'оффлайн', 1: 'онлайн' }" :x-min="statsXMin" :x-max="statsXMax" />
        </div>
        <div class="empty small" v-if="!blockStatsData.length && !statusStatsData.length">Нет данных за выбранный период</div>
      </template>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDevice, getDeviceRelays, getDeviceSensors, getDeviceRegisteredSensors, api, getDeviceStatuses, getBlockTelemetry, getDeviceStatusLog, getRelayTelemetry, getServerUptimeHistory, listInvites, createInvite, revokeInvite, clearFinishedInvites, clearAllInvites, listDeviceAccess, grantDeviceAccess, revokeDeviceAccess, getDeviceKey, regenerateDeviceKey, getDevicePayment, setDevicePayment, extendDevicePayment, resetDeviceRegistrations, getMoistureScale, getSensorScales } from '../api/auth'
import { getBlockName, setBlockName, getFieldName, getFieldUnit, isFieldHidden, isVaccHidden, setVaccVisible } from '../store/settings'
import { fmtEventTimeWithZone, fmtAgo } from '../utils/datetime'
import { computeDowntimes } from '../utils/uptime'
import { onWsMessage, pushNotification, connectWs } from '../store/notifications'
import { auth, rememberDeviceAccess, rememberPayment, isDevicePaid } from '../store/auth'
import { loadCalibration, calibrationFor } from '../store/calibration'
import { adcToPercent, immersionWarning } from '../utils/moisture'
import { moistureScaleColor, sensorScaleColor } from '../utils/gradient'
import { paymentText } from '../utils/payment'
import LineChart from '../components/LineChart.vue'

const route = useRoute()
const router = useRouter()
const chipId = route.params.chipId
const device = ref(null)
const relays = ref([])
const lastEvents = ref({})
const sensors = ref([])
/** Шкалы-градиенты влажности по chipId сенсора (для цветной индикации). */
const moistureScales = ref({})
/** Шкалы-градиенты полей v1/v2/v3 по chipId сенсора. */
const fieldScales = ref({})
const registeredSensors = ref([])
const busy = ref(null)
const editingName = ref(false)
const editNameVal = ref('')
const showStats = ref(false)
const blockTelemetry = ref(null)
const blockStatsData = ref([])
const statusStatsData = ref([])
const statusIntervals = ref([])
const timeLabels = ref([])
const statusTimeLabels = ref([])
const statsXMax = ref(null)
const statsXMin = ref(null)
const statusesMap = ref({})

// Строки телеметрии с реальным Vbat (нулевые заглушки в начале бурстов исключаем).
const vbatStatsData = computed(() => blockStatsData.value.filter(d => d.vbat > 0))

const range = ref('24h')
const showCustom = ref(false)
const customFrom = ref('')
const customTo = ref('')
const windowSince = ref(parseRange('24h'))
const windowTo = ref('')
const downtimes = ref([])

const rangeStartMs = computed(() => windowSince.value ? new Date(windowSince.value).getTime() : null)
const rangeEndMs = computed(() => windowTo.value ? new Date(windowTo.value).getTime() : null)

// Интервалы, когда устройство было офлайн (из статус-лога: offline → следующий online).
const offlineIntervals = computed(() => {
  const rows = statusStatsData.value
  if (!rows.length) return []
  const out = []
  let start = null
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i]
    if (r.status === 'offline') {
      if (start == null) start = r.time
    } else if (start != null) {
      out.push({ from: start, to: r.time })
      start = null
    }
  }
  if (start != null && rows[rows.length - 1].time !== start) out.push({ from: start, to: rows[rows.length - 1].time })
  else if (start != null) out.push({ from: start, to: rows[rows.length - 1].time })
  return out
})
const ranges = [
  { label: '3ч', value: '3h' },
  { label: '24ч', value: '24h' },
  { label: '3д', value: '3d' },
  { label: '7д', value: '7d' },
]
const relayTypeNames = [
  { value: 0, code: 'TIME', label: 'по времени' },
  { value: 1, code: 'S_ONE', label: 'от датчика по одному порогу', comment: 'по превышению одного порога' },
  { value: 2, code: 'S_LOW', label: 'от датчика по нижнему порогу', comment: 'включение по нижнему, выключение по верхнему' },
  { value: 3, code: 'S_HIGH', label: 'от датчика по верхнему порогу', comment: 'включение по верхнему, выключение по нижнему' },
]
const lastEventsByRelay = ref({})

function relayTypeName(r) {
  const t = relayTypeNames.find(x => x.code === (r?.typeEnum || '').toUpperCase())
  return t ? t.label : (r?.typeEnum || 'НЕИЗВ')
}

function relayTypeComment(r) {
  const t = relayTypeNames.find(x => x.code === (r?.typeEnum || '').toUpperCase())
  return t?.comment || null
}

function lastEvent(r) {
  return lastEventsByRelay.value?.[String(r?.relayNumber)] || null
}

function relayLastState(r) {
  return lastEvent(r)?.state != null ? (lastEvent(r).state ? 'Вкл' : 'Выкл') : (r.state ? 'Вкл' : 'Выкл')
}

function relayLastText(r) {
  const ev = lastEvent(r)
  if (!ev?.time) return ''
  return `${ev.state ? 'вкл' : 'выкл'} в ${fmtEventTimeWithZone(ev.time, device.value?.tzOffsetMinutes)}`
}

async function loadLastEvents() {
  try {
    const events = await getRelayTelemetry(Number(chipId))
    const byRelay = {}
    for (const e of events) {
      const key = String(e.relayNumber)
      if (!byRelay[key]) byRelay[key] = []
      byRelay[key].push(e)
    }
    const map = {}
    for (const [key, list] of Object.entries(byRelay)) {
      list.sort((a, b) => new Date(a.time) - new Date(b.time))
      let lastSwitch = null
      for (let i = 1; i < list.length; i++) {
        if (list[i].state !== list[i - 1].state) {
          lastSwitch = { time: list[i].time, state: list[i].state }
        }
      }
      if (lastSwitch) map[key] = lastSwitch
    }
    lastEventsByRelay.value = map
  } catch { /* ignore */ }
}

const relayStatsOpen = ref({})
const relayShowCustom = ref({})
const relayRangeVal = ref({})
const relayCustomFrom = ref({})
const relayCustomTo = ref({})
const relayStatsData = ref({})

function relayName(r) {
  return r?.name ? `${r.name}` : `R${r.relayNumber}`
}

function openRelaySettings(r) {
  if (!canControl.value) return
  if (!isOnline.value) {
    pushNotification('Блок оффлайн — настройка канала недоступна', 'warn')
    return
  }
  router.push(`/device/${chipId}/relay/${r.relayNumber}`)
}

function relaySince(rn) {
  if (relayRangeVal.value[rn] === 'custom') {
    const cf = relayCustomFrom.value[rn]
    return cf ? new Date(cf).toISOString() : ''
  }
  return parseRange(relayRangeVal.value[rn] || '24h')
}

function relayTo(rn) {
  if (relayRangeVal.value[rn] === 'custom') {
    const ct = relayCustomTo.value[rn]
    return ct ? new Date(ct).toISOString() : ''
  }
  return ''
}

async function loadRelayStats(r) {
  const rn = r.relayNumber
  await ensureDowntimes()
  const since = relaySince(rn)
  const to = relayTo(rn)
  let rows = []
  try {
    rows = await getRelayTelemetry(Number(chipId), rn, since || undefined, to || undefined)
  } catch { /* ignore */ }
  rows.sort((a, b) => new Date(a.time) - new Date(b.time))
  const switches = []
  let prev = null
  for (const row of rows) {
    const s = row.state === 1 ? 1 : 0
    if (prev === s) continue
    switches.push({ time: row.time, state: s })
    prev = s
  }
  const startMs = since ? new Date(since).getTime() : (switches.length ? new Date(switches[0].time).getTime() : null)
  const endMs = to ? new Date(to).getTime() : (switches.length ? new Date(switches[switches.length - 1].time).getTime() : null)
  relayStatsData.value[rn] = {
    labels: switches.map(x => x.time),
    states: switches.map(x => x.state),
    startMs,
    endMs,
  }
}

async function toggleRelayStats(r) {
  const rn = r.relayNumber
  relayStatsOpen.value[rn] = !relayStatsOpen.value[rn]
  if (relayStatsOpen.value[rn]) {
    if (!relayRangeVal.value[rn]) relayRangeVal.value[rn] = '24h'
    await loadRelayStats(r)
  }
}

async function selectRelayRange(r, v) {
  relayRangeVal.value[r.relayNumber] = v
  await loadRelayStats(r)
}

async function applyRelayCustom(r) {
  relayRangeVal.value[r.relayNumber] = 'custom'
  await loadRelayStats(r)
}

async function ensureDowntimes() {
  if (downtimes.value.length) return
  const last = lastUptimeFetch
  if (last && Date.now() - last < 60000) return
  lastUptimeFetch = Date.now()
  try {
    downtimes.value = computeDowntimes(await getServerUptimeHistory())
  } catch { /* ignore */ }
}

let lastUptimeFetch = 0

const isOnline = computed(() => statusesMap.value[chipId]?.status === 'online')
const isAdmin = computed(() => auth.user?.role === 'ADMIN')

// Оплата блока: срок общий для всех, кто имеет доступ, поэтому гейт смотрит на блок.
const payment = ref(null)
const isBlock = computed(() => device.value?.deviceType === 'BLOCK')
const blockClosed = computed(() => isBlock.value && !!payment.value && !payment.value.paid)
const payClass = computed(() => {
  if (!payment.value || payment.value.chargeable === false) return 'free'
  return payment.value.paid ? 'ok' : 'no'
})

// План блока админ задаёт здесь же: срок по умолчанию живёт в настройках, а конкретный
// блок — отдельно, без похода в базу.
const planDays = ref('')
const planUnlimited = ref(false)
const extendDays = ref('')
const planBusy = ref(false)
const planError = ref('')

async function loadPayment() {
  try {
    rememberPayment(await getDevicePayment(Number(chipId)))
  } catch {
    payment.value = null
  }
}

async function savePlan() {
  planBusy.value = true
  planError.value = ''
  try {
    const days = Number(planDays.value)
    const body = planUnlimited.value
      ? { unlimited: true }
      : (Number.isFinite(days) && days > 0 ? { days } : { unlimited: false })
    rememberPayment(await setDevicePayment(Number(chipId), body))
    pushNotification('План оплаты блока обновлён')
  } catch (e) {
    planError.value = e.response?.data?.message || 'Не удалось сохранить план'
  } finally {
    planBusy.value = false
  }
}

async function doExtend() {
  planBusy.value = true
  planError.value = ''
  try {
    rememberPayment(await extendDevicePayment(Number(chipId), Number(extendDays.value)))
    extendDays.value = ''
    pushNotification('Срок продлён')
  } catch (e) {
    planError.value = e.response?.data?.message || 'Не удалось продлить'
  } finally {
    planBusy.value = false
  }
}

async function doReset() {
  if (!window.confirm('Снять все доступы к блоку и отозвать все живые ссылки? '
    + 'Отсчёт оплаты обнулится и снова пойдёт с первого подтверждения ID и ключа.')) return
  planBusy.value = true
  planError.value = ''
  try {
    await resetDeviceRegistrations(Number(chipId))
    pushNotification('Все регистрации блока отменены')
    await loadAccess()
    await load()
  } catch (e) {
    planError.value = e.response?.data?.message || 'Не удалось отменить регистрации'
  } finally {
    planBusy.value = false
  }
}
// Права живут на устройстве: один и тот же человек на своём блоке — хозяин,
// на чужом — наблюдатель. Роль аккаунта (ADMIN/USER) тут ни при чём.
const myAccessRole = computed(() => accessList.value.find(a => a.userId === auth.user?.id)?.accessRole)
const canControl = computed(() =>
  isAdmin.value || (myAccessRole.value === 'USER' && isDevicePaid(chipId)))
// Поделиться устройством может обычный пользователь, который им управляет; оплата для
// этого не нужна — приглашение открывает доступ, а не возможность управлять. Админу
// приглашение не показываем: у него другие механизмы (ключ комплектного листа, доступ).
const canShare = computed(() => !isAdmin.value && myAccessRole.value === 'USER')

const invites = ref([])
const inviteAccessRole = ref('USER')
const accessList = ref([])
const deviceKey = ref(null)
const keyBusy = ref(false)

async function loadAccess() {
  try {
    accessList.value = await listDeviceAccess(chipId)
    // Кэш нужен роутеру: настройки блока открываются отдельным маршрутом.
    rememberDeviceAccess(chipId, accessList.value.find(a => a.userId === auth.user?.id)?.accessRole)
  } catch {
    accessList.value = []
  }
}

async function loadKey() {
  keyBusy.value = true
  try {
    deviceKey.value = (await getDeviceKey(chipId)).key
  } catch (e) {
    inviteError.value = e.response?.data?.message || 'Не удалось получить ключ'
  } finally {
    keyBusy.value = false
  }
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(`ID ${device.value.chipId} · ключ ${deviceKey.value}`)
    pushNotification('Ключ скопирован')
  } catch {
    inviteError.value = 'Скопируйте вручную'
  }
}

async function regenKey() {
  if (!confirm('Перевыпустить ключ? Прежний комплектный лист перестанет подходить.')) return
  keyBusy.value = true
  try {
    deviceKey.value = (await regenerateDeviceKey(chipId)).key
    pushNotification('Ключ перевыпущен')
  } finally {
    keyBusy.value = false
  }
}

async function swapAccessRole(a) {
  const next = a.accessRole === 'VIEWER' ? 'USER' : 'VIEWER'
  await grantDeviceAccess(chipId, a.userId, next)
  await loadAccess()
}

async function revokeAccess(a) {
  if (!confirm(`Отозвать доступ у ${a.username}?`)) return
  await revokeDeviceAccess(chipId, a.userId)
  await loadAccess()
}
const lastInvite = ref(null)
const inviteBusy = ref(false)
const inviteError = ref('')

function fmtDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  return `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}`
}

function roundHalf(v) {
  return Math.round(v * 2) / 2
}

function fmtSend(seconds) {
  if (seconds == null) return null
  if (seconds >= 3600) {
    const h = Math.floor(seconds / 3600)
    const m = Math.round((seconds % 3600) / 60)
    return m ? `${h} ч ${m} мин` : `${h} ч`
  }
  if (seconds >= 60) return `${Math.round(seconds / 60)} мин`
  return `${seconds} с`
}

function sensorConfigText(s) {
  const parts = []
  if (s.sleep != null) parts.push(`сон ${s.sleep < 60 ? s.sleep + ' с' : roundHalf(s.sleep / 60) + ' мин'}`)
  if (s.send != null) parts.push(`отпр. ${fmtSend(s.send)}`)
  if (s.percentN != null || s.percentS != null) parts.push(`пределы ${s.percentN ?? 0}/${s.percentS ?? 0} %`)
  if (s.minVoltage != null) parts.push(`мин. напр. ${Math.round(s.minVoltage) / 1000} В`)
  return parts.join(' · ')
}

function inviteUrl(token) {
  return `${location.origin}/invite/${token}`
}

async function loadInvites() {
  // Админ раздаёт доступ напрямую, а приглашения ему сервер не отдаёт (403) — запроса нет.
  if (isAdmin.value) return
  if (!canShare.value) return
  try {
    invites.value = await listInvites(chipId)
  } catch {
    invites.value = []
  }
}

async function makeInvite() {
  inviteBusy.value = true
  inviteError.value = ''
  try {
    lastInvite.value = await createInvite(chipId, inviteAccessRole.value)
    await loadInvites()
  } catch (e) {
    inviteError.value = e.response?.data?.message || 'Не удалось создать ссылку'
  } finally {
    inviteBusy.value = false
  }
}

async function copyInvite(token) {
  const url = inviteUrl(token)
  try {
    await navigator.clipboard.writeText(url)
    pushNotification('Ссылка скопирована')
  } catch {
    inviteError.value = 'Скопируйте ссылку вручную'
  }
}

/**
 * Отозванные и истёкшие ссылки: они больше никому ничего не дают и только мешают в
 * списке. Принятые сюда не попадают — они объясняют, кому выдавался доступ.
 */
const removableInvites = computed(() => invites.value.filter(i => !i.acceptedAt))
const clearBusy = ref(false)

async function clearFinished() {
  if (!confirm('Убрать из списка отозванные и истёкшие ссылки? Принятые останутся.')) return
  clearBusy.value = true
  try {
    const res = await clearFinishedInvites(chipId)
    if (lastInvite.value && removableInvites.value.some(i => i.id === lastInvite.value.id)) {
      lastInvite.value = null
    }
    await loadInvites()
    pushNotification(`Убрано ссылок: ${res.removed}`)
  } catch (e) {
    pushNotification(e.response?.data?.message || 'Не удалось очистить список')
  } finally {
    clearBusy.value = false
  }
}

/**
 * Полная очистка: в отличие от очистки завершённых перестают работать и живые ссылки,
 * которые уже разосланы. Поэтому спрашиваем отдельно и прямо говорим, что именно
 * пропадёт.
 */
async function clearEverything() {
  const accepted = invites.value.filter(i => i.acceptedAt).length
  const live = invites.value.length - accepted
  const warning =
    `Удалить все ${invites.value.length} ссылок на это устройство?\n\n`
    + `Принятые: ${accepted}. Доступ у этих людей останется.\n`
    + `Ещё живые: ${live}.${live ? ' Они перестанут работать — если их уже разослали, их придётся выдать заново.' : ''}`
  if (!confirm(warning)) return
  clearBusy.value = true
  try {
    const res = await clearAllInvites(chipId)
    lastInvite.value = null
    await loadInvites()
    pushNotification(`Удалено ссылок: ${res.removed}`)
  } catch (e) {
    pushNotification(e.response?.data?.message || 'Не удалось удалить ссылки')
  } finally {
    clearBusy.value = false
  }
}

async function dropInvite(invite) {
  await revokeInvite(chipId, invite.id)
  if (lastInvite.value?.id === invite.id) lastInvite.value = null
  await loadInvites()
}

const displayName = computed(() => {
  if (device.value?.deviceType === 'BLOCK') {
    const base = getBlockName(chipId) || device.value?.name
    return base ? `${base} (${chipId})` : chipId
  }
  return device.value?.name || chipId
})

onMounted(async () => {
  await load()
  await loadAccess()
  await loadInvites()
})

let wsUnsub = null
let refreshTimer = null
let liveTimer = null
let liveBusy = false
/** Текущее время для подписей «сколько назад» рядом с «Получено». */
const nowTick = ref(Date.now())
let agoTimer = null

function isThisDevice(value) {
  return value !== undefined && value !== null && String(value) === String(chipId)
}

/**
 * Точечное обновление без полного load(): WS может умереть при уходе телефона
 * в фон, а спящий датчик reconnect не инициирует. Опрос страховит live-обновления.
 */
async function refreshLive() {
  if (liveBusy || blockClosed.value) return
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
  liveBusy = true
  try {
    const before = sensors.value.map(s => String(s.sensorChipId ?? s.chipId)).sort().join(',')
    const [list, relayList, statuses] = await Promise.all([
      getDeviceSensors(Number(chipId)),
      getDeviceRelays(Number(chipId)),
      getDeviceStatuses(),
    ])
    const after = list.map(s => String(s.sensorChipId ?? s.chipId)).sort().join(',')
    sensors.value = list
    relays.value = relayList
    const map = {}
    for (const s of statuses) map[s.chipId] = s
    statusesMap.value = map
    if (isBlock.value) {
      const bt = await getBlockTelemetry(Number(chipId))
      blockTelemetry.value = bt[0] || null
    }
    nowTick.value = Date.now()
    // Появился или пропал датчик — догружаем калибровки и шкалы полным load().
    if (before !== after) await load()
  } catch { /* оплата/закрытый блок/сеть — молча ждём следующий тик */ } finally {
    liveBusy = false
  }
}

function onVisibilityChange() {
  if (document.visibilityState !== 'visible') return
  connectWs()
  refreshLive()
}

function onOnline() {
  connectWs()
  refreshLive()
}

onMounted(() => {
  agoTimer = setInterval(() => { nowTick.value = Date.now() }, 30000)
  liveTimer = setInterval(refreshLive, 15000)
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('online', onOnline)
  wsUnsub = onWsMessage((msg) => {
    if ((msg.deviceId != null || msg.chipId != null) && !isThisDevice(msg.deviceId) && !isThisDevice(msg.chipId)) return
    // Служебный статус MQTT-подключения (без deviceId) — не состояние этого устройства.
    if (msg.type === 'status' && msg.deviceId == null && msg.chipId == null) return
    if (msg.type === 'status' && typeof msg.status !== 'undefined') {
      statusesMap.value = {
        ...statusesMap.value,
        [chipId]: { chipId, status: msg.status, lastSeen: msg.timestamp ?? statusesMap.value[chipId]?.lastSeen }
      }
    }
    // EVENT_R — событие канала (таймер, датчик, ручное): блок сам сменил состояние,
    // применяем сразу, а не ждём перечитывания (STATE_R приходит только на реконнекте).
    // STATE_R — полный снапшот из прошивки, тут достаточно освежить страницу.
    if (msg.type === 'relayEvent' && typeof msg.relay !== 'undefined' && typeof msg.state !== 'undefined') {
      const relay = relays.value.find(r => Number(r.relayNumber) === Number(msg.relay))
      if (relay) relay.state = msg.state
    }
    const liveTypes = ['status', 'sensor', 'sensors', 'relayState']
    if (!liveTypes.includes(msg.type)) return
    if (refreshTimer) return
    refreshTimer = setTimeout(async () => {
      refreshTimer = null
      try { await load() } catch { /* ignore */ }
    }, 1500)
  })
})

onBeforeUnmount(() => {
  if (refreshTimer) { clearTimeout(refreshTimer); refreshTimer = null }
  if (liveTimer) { clearInterval(liveTimer); liveTimer = null }
  if (agoTimer) { clearInterval(agoTimer); agoTimer = null }
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('online', onOnline)
  if (wsUnsub) wsUnsub()
})

async function load() {
  // Оплата спрашивается всегда: 402 на остальные запросы — это ожидаемое состояние
  // закрытого блока, а не ошибка страницы.
  await loadPayment()
  if (isBlock.value && payment.value && !payment.value.paid) {
    if (!device.value) {
      device.value = { chipId: Number(chipId), deviceType: 'BLOCK' }
    }
    return
  }
  device.value = await getDevice(Number(chipId))
  relays.value = await getDeviceRelays(Number(chipId))
  sensors.value = await getDeviceSensors(Number(chipId))
  // Калибровки влажности: нужны для показа % и предупреждения о погружении.
  await Promise.all(sensors.value
    .filter(s => (s.sensorType ?? 0) === 0 && s.moistureAdc != null)
    .map(s => loadCalibration(Number(s.sensorChipId ?? s.chipId))))
  // Шкалы-градиенты: для цветной индикации влажности рядом с показаниями.
  await Promise.all(sensors.value
    .filter(s => (s.sensorType ?? 0) === 0 && s.sensorChipId)
    .map(async s => {
      const chip = Number(s.sensorChipId)
      try {
        const sc = await getMoistureScale(chip)
        moistureScales.value = { ...moistureScales.value, [chip]: sc }
      } catch { /* без шкалы — просто без цвета */ }
    }))
  // Шкалы-градиенты v1/v2/v3 — цветные индикации температуры/влажности/света.
  await Promise.all(sensors.value
    .filter(s => s.sensorChipId)
    .map(async s => {
      const chip = Number(s.sensorChipId)
      try {
        const list = await getSensorScales(chip)
        const map = {}
        for (const sc of list || []) {
          if (sc.enabled && sc.minValue != null && sc.maxValue != null) map[sc.fieldKey] = sc
        }
        fieldScales.value = { ...fieldScales.value, [chip]: map }
      } catch { /* без шкал — просто без цвета */ }
    }))
  await loadLastEvents()
  if (device.value?.deviceType === 'BLOCK') {
    registeredSensors.value = await getDeviceRegisteredSensors(Number(chipId))
    const bt = await getBlockTelemetry(Number(chipId))
    blockTelemetry.value = bt[0] || null
  }
  const statuses = await getDeviceStatuses()
  const map = {}
  for (const s of statuses) map[s.chipId] = s
  statusesMap.value = map
}

function isLinked(r) {
  return relays.value.some(x => x.sensorId && String(x.sensorId) === String(r.sensorChipId))
}

function registeredName(r) {
  const s = sensors.value.find(x => String(x.sensorChipId) === String(r.sensorChipId))
  return s?.name || `S${r.slot}`
}

function openSensor(s) {
  const chip = s.sensorChipId ?? s.chipId
  if (!chip) return
  router.push(`/sensor/${chip}?deviceId=${chipId}`)
}

function openSensorSettings(s) {
  if (!canControl.value) return
  if (!isOnline.value) {
    pushNotification('Блок оффлайн — настройка датчика недоступна', 'warn')
    return
  }
  if (!s.sensorNumber) {
    pushNotification('У датчика нет слота — настройка через блок невозможна', 'warn')
    return
  }
  router.push(`/device/${chipId}/sensor/${s.sensorNumber}`)
}

function openIndividualSensorSettings(s) {
  if (!canControl.value) return
  const chip = s.sensorChipId ?? s.chipId ?? chipId
  if (!chip) return
  router.push(`/sensor/${chip}/settings`)
}

async function toggle(r, state) {
  if (!canControl.value) return
  busy.value = r.id
  try {
    await api.post(`/relays/${r.id}/command`, { state })
    await load()
  } finally {
    busy.value = null
  }
}

function startEditName() {
  if (!canControl.value) return
  editingName.value = true
  editNameVal.value = device.value?.deviceType === 'BLOCK' ? (getBlockName(chipId) || '') : (device.value?.name || '')
}

function saveName() {
  if (!canControl.value) return
  if (device.value?.deviceType === 'BLOCK') setBlockName(chipId, editNameVal.value)
  else device.value.name = editNameVal.value || null
  editingName.value = false
}

function getLabel(sensor, field) {
  return getFieldName(sensor.sensorType, field)
}

function getUnit(sensor, field) {
  return getFieldUnit(sensor.sensorType, field)
}

/**
 * Админ не выбирает, что показывать (настройки отображения у него убраны), поэтому у него
 * Vacc всегда виден: иначе колонка пропала бы у него без возможности её вернуть.
 */
const showVacc = computed(() => isAdmin.value || !isVaccHidden(chipId))

function onVaccChange(visible) {
  setVaccVisible(chipId, visible)
}

function showField(sensor, field) {
  // Скрытие — свойство датчика, а не его типа: аппаратные каналы у разных датчиков разные
  if (isFieldHidden(sensor.sensorChipId ?? chipId, field)) return false
  if (field === 'v0') return sensor.moistureAdc != null
  if (field === 'v1') return sensor.temperature != null
  if (field === 'v2') return sensor.humidity != null
  if (field === 'v3') return sensor.light != null
  return true
}

function hasAnyData(s) {
  return s.moistureAdc != null || s.temperature != null || s.humidity != null || s.light != null || s.battery != null
}

/** Калибровка влажности этого датчика (null — не тип 0 или не загружена). */
function moistureCal(s) {
  if ((s.sensorType ?? 0) !== 0) return null
  return calibrationFor(Number(s.sensorChipId ?? s.chipId))
}

function moisturePct(s) {
  const cal = moistureCal(s)
  return cal ? adcToPercent(s.moistureAdc, cal) : null
}

/** Цвет влажности по сохранённой шкале-градиенту датчика (null — если её нет). */
function moistureColor(s) {
  const chip = Number(s.sensorChipId ?? s.chipId)
  return moistureScaleColor(moistureScales.value[chip], moisturePct(s))
}

/** Цвет поля v1/v2/v3 по сохранённой шкале-градиенту (null — если её нет). */
function fieldColor(s, key, value) {
  const chip = Number(s.sensorChipId ?? s.chipId)
  return sensorScaleColor(fieldScales.value[chip]?.[key], value)
}

function moistureImmersion(s) {
  const cal = moistureCal(s)
  return cal ? immersionWarning(s.moistureAdc, cal) : false
}

function parseRange(r) {
  const num = parseInt(r)
  const unit = r.slice(-1)
  const ms = unit === 'h' ? num * 3600000 : num * 86400000
  return new Date(Date.now() - ms).toISOString()
}

const BREAK_THRESHOLD_MS = 60000

function classifyIntervals(log) {
  const intervals = []
  let start = null
  for (const e of log) {
    const t = new Date(e.time).getTime()
    if (e.status === 'offline') {
      if (start === null) start = t
    } else if (start !== null) {
      const durationMs = t - start
      intervals.push({ start, end: t, durationMs, type: durationMs > BREAK_THRESHOLD_MS ? 'outage' : 'micro' })
      start = null
    }
  }
  if (start !== null) intervals.push({ start, end: null, durationMs: null, type: 'ongoing' })
  return intervals
}

const statusSummary = computed(() => {
  const outages = statusIntervals.value.filter(i => i.type === 'outage')
  return {
    outages: outages.length,
    micro: statusIntervals.value.filter(i => i.type === 'micro').length,
    longest: outages.reduce((m, i) => Math.max(m, i.durationMs), 0),
    ongoing: statusIntervals.value.some(i => i.type === 'ongoing'),
  }
})

function formatDuration(ms) {
  const s = Math.round(ms / 1000)
  if (s >= 86400) return `${Math.floor(s / 86400)} д ${Math.floor((s % 86400) / 3600)} ч`
  if (s >= 3600) return `${Math.floor(s / 3600)} ч ${Math.floor((s % 3600) / 60)} мин`
  if (s >= 60) return `${Math.floor(s / 60)} мин`
  return `${s} c`
}

async function loadStats() {
  await ensureDowntimes()
  const since = windowSince.value || undefined
  const to = windowTo.value || undefined
  // Последнее статус-событие до начала окна: нужно, чтобы растянуть ряд влево
  // только при реально известном значении. Без него линия «выдумывала» онлайн
  // за период до запуска сервиса, когда данных ещё не было (как у RSSI/Vbat).
  const [block, statusLog, statusBefore] = await Promise.all([
    getBlockTelemetry(chipId, since, to),
    getDeviceStatusLog(chipId, since, to),
    since ? getDeviceStatusLog(chipId, undefined, since).catch(() => []) : Promise.resolve([]),
  ])
  blockStatsData.value = block.reverse()
  statusStatsData.value = statusLog.reverse()
  timeLabels.value = blockStatsData.value.map(d => d.time)
  statusTimeLabels.value = statusStatsData.value.map(d => d.time)

  // Общие границы времени для всех графиков статистики (единый масштаб).
  const toMs = arr => arr.map(t => new Date(t).getTime()).filter(n => !isNaN(n))
  const bt = toMs(timeLabels.value)
  const st = toMs(statusTimeLabels.value)
  const all = bt.concat(st)
  const dataMax = all.length ? Math.max(...all) : null
  const dataMin = all.length ? Math.min(...all) : null
  statsXMin.value = rangeStartMs.value != null ? rangeStartMs.value : dataMin
  statsXMax.value = rangeEndMs.value != null ? rangeEndMs.value : dataMax

  // Растягиваем статусный ряд до границ окна: влево — только если статус на
  // левом крае известен (последнее событие до окна), иначе линия начинается
  // от первой записи — период неопределённости остаётся пустым, как на
  // графиках телеметрии. Вправо — по последнему известному значению.
  const rows = statusStatsData.value
  const xMin = statsXMin.value
  const xMax = statsXMax.value
  const knownBefore = statusBefore.length ? statusBefore[0] : null
  if (xMin != null) {
    const firstMs = rows.length ? new Date(rows[0].time).getTime() : null
    if (firstMs == null && knownBefore) {
      rows.push({ ...knownBefore, time: new Date(xMin).toISOString() })
    } else if (firstMs != null && firstMs > xMin && knownBefore) {
      rows.unshift({ ...knownBefore, time: new Date(xMin).toISOString() })
    }
  }
  if (xMax != null && rows.length && new Date(rows[rows.length - 1].time).getTime() < xMax) {
    rows.push({ ...rows[rows.length - 1], time: new Date(xMax).toISOString() })
  }
  statusTimeLabels.value = rows.map(d => d.time)
  statusIntervals.value = classifyIntervals(rows)
}

async function setRange(r) {
  range.value = r
  showStats.value = true
  windowSince.value = parseRange(r)
  windowTo.value = ''
  await loadStats()
}

function applyCustom() {
  range.value = 'custom'
  showStats.value = true
  windowSince.value = customFrom.value ? new Date(customFrom.value).toISOString() : ''
  windowTo.value = customTo.value ? new Date(customTo.value).toISOString() : ''
  loadStats()
}
</script>

<style scoped>
.device { max-width: 600px; margin: 0 auto; padding: 1rem; }
.pay-line { font-size: 0.85rem; margin-bottom: 0.5rem; color: var(--text-dim); }
.pay-line.ok { color: var(--success); }
.pay-line.no { color: var(--danger); }
.pay-line.free { color: var(--text-faint); }
.paywall-text { color: var(--text-muted); font-size: 0.9rem; line-height: 1.5; margin: 0.25rem 0 0; }
.pay-admin-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
.pay-admin-row input[type='number'] { width: 90px; }
.header-row { display: flex; align-items: center; gap: 0.75rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1.5rem; padding: 1rem; background: var(--bg); }
.header-row h2 { color: var(--text-strong); flex: 1; }
.editable { cursor: pointer; border-bottom: 1px dashed var(--border); }
.editable:hover { color: var(--accent); }
.name-input { background: var(--bg); border: 1px solid var(--accent); border-radius: 4px; padding: 0.2rem 0.5rem; color: var(--text); font-size: 1rem; width: 100%; }
.type-tag { font-size: 0.7rem; padding: 0.2rem 0.5rem; border-radius: 4px; background: var(--tag-bg-ok); color: var(--text); }
.status-tag { font-size: 0.7rem; padding: 0.2rem 0.5rem; border-radius: 4px; }
.status-tag.online { background: var(--tag-bg-ok); color: var(--success); }
.status-tag.offline { background: var(--bg-accent); color: var(--danger); }
.btn { padding: 0.4rem 0.8rem; border: 1px solid var(--btn-border); border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--btn-bg); color: var(--btn-text); }
.btn:hover:not(:disabled) { background: var(--btn-bg-hover); border-color: var(--btn-border); }
.btn.small { font-size: 0.75rem; padding: 0.3rem 0.6rem; }
.btn.secondary { background: var(--btn-bg-hover); }
.btn.danger { background: #7a2626; border-color: #a84040; color: var(--on-accent); }
.btn.danger:hover:not(:disabled) { background: #933030; border-color: #cb5654; }
.btn.active { background: var(--bg-active); border-color: var(--accent); color: var(--on-accent); }
/* Раньше при 0.5 opacity кнопки «Вкл/Выкл» на карточке канала (var(--bg-card))
   и в hover-режиме карточки (var(--bg-primary)) визуально совпадали с фоном. */
.btn:disabled { cursor: default; background: var(--btn-disabled-bg); border-color: var(--btn-disabled-border); color: var(--btn-disabled-text); opacity: 0.7; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.section-head h3 { margin: 0; }
.invite-error { color: var(--danger); font-size: 0.8rem; margin: 0.4rem 0; }
.invite-box { background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 0.6rem; margin-top: 0.6rem; }
.invite-actions { display: flex; flex-wrap: wrap; gap: 0.6rem; margin-top: 0.2rem; }
.invite-link { color: var(--accent); font-family: monospace; font-size: 0.75rem; word-break: break-all; margin-bottom: 0.4rem; }
.invite-note { color: var(--text-dim); font-size: 0.75rem; line-height: 1.4; margin-bottom: 0.5rem; }
.invite-cleanup { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-top: 0.5rem; }
.invite-list { margin-top: 0.6rem; display: flex; flex-direction: column; gap: 0.35rem; }
.invite-row { display: flex; align-items: center; gap: 0.5rem; background: var(--bg); border-radius: 6px; padding: 0.35rem 0.5rem; }
.role-select { padding: 0.25rem 0.4rem; border-radius: 6px; border: 1px solid var(--border); background: var(--bg); color: var(--text); font-size: 0.75rem; }
.invite-role { color: var(--muted-fg); font-size: 0.7rem; }
.invite-token { color: var(--text-dim); font-size: 0.75rem; }
.invite-state { font-size: 0.7rem; flex: 1; }
.invite-state.ok { color: var(--success); }
.invite-state.no { color: var(--text-dim); }
.section { margin-bottom: 2rem; }
.section h3 { color: var(--text-muted); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 0.75rem; }
.relay-list, .sensor-list { display: flex; flex-direction: column; gap: 0.6rem; }
.relay-card { background: var(--bg-card); border-radius: 12px; padding: 0.9rem 1rem; box-shadow: var(--card-shadow); }
.relay-card { cursor: pointer; transition: background 0.2s; }
.relay-card:hover { background: var(--bg-primary); }
.relay-top { display: flex; align-items: center; gap: 0.6rem; margin-bottom: 0.6rem; }
.relay-num { font-family: monospace; color: var(--text-muted); font-size: 0.9rem; flex-shrink: 0; }
.relay-id { display: flex; flex-direction: column; flex: 1; min-width: 0; gap: 0.2rem; }
.relay-name { color: var(--text); font-size: 0.9rem; }
.state { font-size: 0.75rem; padding: 0.2rem 0.5rem; border-radius: 4px; }
.state.on { background: var(--tag-bg-ok); color: var(--success); }
.state.off { background: var(--bg-accent); color: var(--danger); }
.relay-actions { display: flex; gap: 0.5rem; }
.relay-actions .btn.small { flex: 1; }
.relay-stats { margin-top: 0.6rem; }
/* Раньше ghost был с прозрачным фоном и рамкой того же тона, что карточка — сливался. */
.btn.ghost { background: var(--btn-bg); border: 1px solid var(--btn-border); color: var(--btn-text); }
.btn.ghost:hover:not(:disabled) { background: var(--btn-bg-hover); border-color: var(--btn-border); color: var(--btn-text); }
.relay-stats-panel { margin-top: 0.6rem; padding: 0.6rem 0.8rem; background: var(--bg); border-radius: 10px; }
.relay-top { display: flex; align-items: center; gap: 0.6rem; }
.dot { width: 0.7rem; height: 0.7rem; border-radius: 50%; flex-shrink: 0; }
.dot.on { background: var(--success); box-shadow: 0 0 6px rgba(46,204,113,0.5); }
  .dot.off { background: var(--danger); box-shadow: 0 0 6px rgba(231,76,60,0.5); }
  .dot.inactive { background: var(--text-muted); box-shadow: none; }
.relay-type { font-size: 0.72rem; color: var(--banner-text); background: var(--banner-bg); border: 1px solid var(--banner-border); padding: 0.15rem 0.45rem; border-radius: 999px; align-self: flex-start; white-space: nowrap; }
.relay-type-comment { display: block; font-weight: 400; color: var(--banner-text); margin-top: 0.15rem; white-space: normal; }
.relay-type.unk { background: var(--banner-bg); border-color: var(--banner-border); color: var(--warn); }
.relay-last-time { margin-left: auto; font-size: 0.72rem; color: var(--text-dim); white-space: nowrap; flex-shrink: 0; }
.sensor-card { background: var(--bg-card); border-radius: 12px; padding: 0.9rem 1rem; box-shadow: var(--card-shadow); }
.sensor-card { cursor: pointer; transition: background 0.2s; }
.sensor-card:hover { background: var(--bg-primary); }
.sensor-top { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem; }
.sensor-top .btn { margin-left: auto; }
.sensor-name { color: var(--text); flex: 1; }
.sensor-readings { display: flex; flex-wrap: wrap; gap: 0.4rem; }
.reading { font-size: 0.8rem; color: var(--text-muted); background: var(--bg); padding: 0.2rem 0.5rem; border-radius: 4px; }
.pct-color { display: inline-block; width: 2.55em; height: 0.85em; margin-top: 0.2em; flex: none; border: 1px solid #fff; border-radius: 2px; }
.reading.muted { color: var(--text-faint); }
.ago { color: var(--text-faint); font-size: 0.92em; margin-left: 0.3rem; }
.msg-num { color: var(--text-faint); font-size: 0.92em; }
.reading .pct-color { margin: 0 0 0 0.4rem; vertical-align: baseline; }
.reading.immersion-warning { color: var(--danger); background: #3a1518; font-weight: 600; }
.sensor-config { margin-top: 0.5rem; }
.sensor-card.linked { border: 1px solid var(--success); }
.telemetry-row { display: flex; gap: 1rem; flex-wrap: wrap; }
.telemetry-row.muted { color: var(--text-faint); }
.t-item { font-size: 0.85rem; color: var(--text-muted); }
.t-item b { color: var(--text); }
.empty.small { color: var(--text-faint); text-align: center; padding: 1rem; font-size: 0.85rem; }
.range-select { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.custom-range { display: flex; gap: 0.6rem; align-items: center; flex-wrap: wrap; margin-bottom: 1rem; padding: 0.6rem 0.8rem; background: var(--bg-card); border-radius: 10px; }
.custom-range label { display: flex; align-items: center; gap: 0.4rem; color: var(--text-muted); font-size: 0.78rem; }
.custom-range input { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.8rem; padding: 0.3rem 0.5rem; }
.stats-summary { display: flex; flex-wrap: wrap; column-gap: 1.8rem; row-gap: 0.3rem; margin-bottom: 1rem; font-size: 0.85rem; color: var(--text-muted); }
.stats-summary .ongoing { color: var(--danger); font-weight: 600; }
.chart-section { margin-bottom: 1.5rem; }
.chart-section h4 { color: var(--text-dim); font-size: 0.8rem; margin-bottom: 0.3rem; }
/* Палец вместо курсора: кнопки каналов крупнее и с чёткой рамкой. */
@media (hover: none) {
  .relay-actions { gap: 0.6rem; }
  .relay-actions .btn.small { min-height: 2.6rem; font-size: 0.85rem; }
}
</style>
