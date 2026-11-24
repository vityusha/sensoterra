<template>
  <div v-if="!route.meta.measurementNotifications" class="sensor">
    <header v-if="measurement" class="page-header">
      <div class="header-top">
        <button type="button" class="btn small back-btn" aria-label="Назад к датчику" @click="backToSensor"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
        <div class="measurement-heading">
          <h2>{{ label(measurement.key) }}</h2>
          <p>{{ displayName }}</p>
        </div>
        <HeaderActions>
          <button v-if="pageReady && catalogEntry && isGradientField(measurement.key)" type="button" role="menuitem" @click="openNotifications">
            <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"/>
            </svg>
            Настройка уведомлений
          </button>
          <button v-if="pageReady && catalogEntry && measurement.key === 'v0' && canEditPrefs" type="button" role="menuitem" aria-haspopup="dialog" @click="zeroCalibrationOpen = true">
            <svg class="menu-icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3c-2 3-6 7-6 11a6 6 0 0 0 12 0c0-4-4-8-6-11Z"/>
              <path d="M9 14h6"/>
            </svg>
            Калибровка 0% влажности
          </button>
        </HeaderActions>
      </div>
    </header>
    <template v-else>
      <header class="page-header">
        <div class="header-top">
          <button @click="router.push('/')" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
          <h2>{{ displayName }}</h2>
          <HeaderActions>
            <button v-if="catalogEntry && canEditPrefs && isIndividual" role="menuitem" @click="openSettings">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="m13 10-8.5 8.5a1.4 1.4 0 0 0 2 2L15 12"/>
                <path d="m10 7 4-4 6 6-4 4z"/>
              </svg>
              Настройка датчика
            </button>
            <button v-if="catalogEntry && canEditPrefs && calibSectionVisible" role="menuitem" @click="openCalibSettings">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M12 2c-5.33 5.33-8 8.67-8 12 0 4.42 3.58 8 8 8s8-3.58 8-8c0-3.33-2.67-6.67-8-12zm0 18c-3.31 0-6-2.69-6-6 0-2.22 1.86-5.03 6-9.36 4.14 4.33 6 7.14 6 9.36 0 3.31-2.69 6-6 6z"/>
              </svg>
              Калибровка пределов
            </button>
            <button v-if="catalogEntry && canEditPrefs" role="menuitem" @click="openDisplaySettings">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zm0 12.5c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/>
              </svg>
              Показ измерений
            </button>
            <button v-if="pageReady && isIndividual && accessList.length" type="button" role="menuitem" aria-haspopup="dialog" @click="sharingDialog = 'access'">
              <DeviceSharingIcon kind="access" class="menu-icon" />
              Доступ
            </button>
            <button v-if="pageReady && canInvite" type="button" role="menuitem" aria-haspopup="dialog" @click="sharingDialog = 'invite'">
              <DeviceSharingIcon kind="invite" class="menu-icon" />
              Приглашение
            </button>
          </HeaderActions>
        </div>
        <div class="header-meta">
          <span class="type-tag">{{ typeLabel }}</span>
        </div>
      </header>
      <div class="device-id">ID: {{ chipId }}</div>

      <DeviceNameField v-if="pageReady && isIndividual && canEditPrefs" :name="editableName" @save="saveName" />
    </template>

    <PageLoadState v-if="!pageReady" :error="pageError" @retry="loadPage" />
    <template v-else>
    <section class="section" v-if="reading || measurement">
      <h3>{{ measurement ? 'Последнее значение' : 'Последние данные' }}</h3>
      <div class="last-received" v-if="reading?.time">
        Получено: <b>{{ fmtEventTimeWithZone(reading.time, tzOffset) }}</b>
        <span v-if="reading.numMessage != null" class="msg-num">[{{ reading.numMessage }}]</span>
        <span class="ago">({{ agoText }})</span>
      </div>
      <div class="immersion-warning" v-if="!measurement && immersionWarningShown">
        Проверьте уровень погружения датчика в почву
      </div>
      <div v-if="reading" class="latest-grid">
        <component
          :is="measurement ? 'div' : 'button'"
          v-for="che in visibleCells"
          :key="che.key"
          class="latest-cell"
          :class="{ 'measurement-link': !measurement }"
          :style="cellBackground(che)"
          :type="measurement ? undefined : 'button'"
          @click="!measurement && openMeasurement(che.key)"
        >
          <span class="l-label"><span>{{ labelParts(che.key).first }}<br v-if="labelParts(che.key).rest" />{{ labelParts(che.key).rest }}</span></span>
          <span v-if="thresholdNotificationFields.has(che.key)" class="threshold-indicator" title="Включены уведомления по порогу" aria-label="Включены уведомления по порогу"><svg class="threshold-bell" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.75a3 3 0 0 0-3 3v1.1c0 1.25-.38 2.48-1.1 3.5L3 10.75h10l-.9-1.4A6.5 6.5 0 0 1 11 5.85v-1.1a3 3 0 0 0-3-3ZM6.3 12a1.75 1.75 0 0 0 3.4 0" /></svg></span>
          <span class="l-value" :class="{ 'l-value-moisture': che.key === 'v0' && moistureCal }"><span class="l-reading" :class="{ 'alarm-active': openThresholdFields.has(che.key) }">{{ cellValue(che, reading) }}<span class="l-unit" v-if="cellUnit(che)">&nbsp;{{ cellUnit(che) }}</span></span></span>
          <span v-if="isGradientField(che.key)" class="pct-color" :style="cellGradientColor(che) ? { background: cellGradientColor(che) } : null" aria-hidden="true"></span>
        </component>
      </div>
      <div v-else class="empty small">
        <span v-if="loading" class="loading-label" role="status">Загрузка…</span>
        <template v-else>Нет данных</template>
      </div>
    </section>

    <template v-if="!measurement">
      <div class="battery-runtime">
        <span class="battery-label">Время работы батареи:</span>
        <b v-if="batteryRuntimeText">{{ batteryRuntimeText }}</b>
        <span v-else-if="!batteryStartLoaded" class="muted">…</span>
        <span v-else class="muted">информация недоступна</span>
      </div>

      <!-- Ключ для комплектного листа: его копирует админ и отдаёт покупателю вместе с датчиком. -->
      <section v-if="isAdmin && isIndividual" class="section">
        <div class="section-head">
          <h3>Ключ для комплектного листа</h3>
          <button class="btn small" @click="toggleKey" :disabled="keyBusy">{{ keyBusy ? '...' : (deviceKey ? 'Скрыть' : 'Показать') }}</button>
        </div>
        <div v-if="deviceKey" class="invite-box">
          <div class="invite-link">ID {{ chipId }} · ключ {{ deviceKey }}</div>
          <div class="invite-note">Распечатайте на листе, который едет в комплекте с датчиком.
            Покупатель введёт эту пару при регистрации.</div>
          <div class="invite-actions">
            <button class="btn small" @click="copyKey">Скопировать</button>
            <button class="btn small danger" @click="regenKey">Перевыпустить</button>
          </div>
        </div>
      </section>

      <template v-if="isIndividual">
        <DeviceSharingDialog v-if="canInvite" kind="invite" title="Приглашение" :open="sharingDialog === 'invite'" @close="sharingDialog = null">
          <InvitePanel :chip-id="chipId" eternal />
        </DeviceSharingDialog>
        <!-- Кто ещё имеет доступ к датчику и на каких условиях. -->
        <DeviceSharingDialog v-if="accessList.length" kind="access" title="Доступ" :open="sharingDialog === 'access'" @close="sharingDialog = null">
          <p v-if="adminLocked" class="muted admin-locked">
            У датчика есть владелец ({{ deviceInfo.ownerUsername }}): управлять им и доступом может
            только владелец. Админу остаются просмотр и сервисные операции.
          </p>
          <div class="relay-card access-card">
            <div class="access-entry" v-for="a in accessList" :key="a.userId">
              <span class="access-who">
                {{ a.username }} — {{ accessRoleLabel(a.accessRole) }}<span v-if="a.userId === auth.user?.id" class="access-self"> (вы)</span>
              </span>
              <div v-if="canManageAccess && a.userId !== auth.user?.id && a.accessRole !== 'OWNER'" class="access-actions">
                <button class="btn small" @click="swapAccessRole(a)">
                  {{ a.accessRole === 'VIEWER' ? 'дать управление' : 'оставить на чтение' }}
                </button>
                <button class="btn small danger" @click="revokeAccess(a)">Отозвать</button>
              </div>
            </div>
          </div>
        </DeviceSharingDialog>
      </template>
    </template>

    <section v-if="measurement || catalogEntry || !loading" class="section">
      <h3>
        <template v-if="measurement">Статистика измерений</template>
        <button v-else class="stats-toggle" type="button" :aria-expanded="showStats" @click="showStats = !showStats">
          Статистика измерений {{ showStats ? '▾' : '▸' }}
        </button>
      </h3>
      <template v-if="measurement || showStats">
        <div class="range-select">
          <button v-for="r in ranges" :key="r.label" class="btn small" :class="{ active: range === r.value }" @click="setRange(r.value)">{{ r.label }}</button>
          <button class="btn small" :class="{ active: range === 'custom' }" @click="showCustom = !showCustom">{{ showCustom ? 'Скрыть' : 'Свой период' }}</button>
        </div>
        <div v-if="showCustom" class="custom-range">
          <label>С <input v-model="customFrom" type="datetime-local" /></label>
          <label>По <input v-model="customTo" type="datetime-local" /></label>
          <span class="zone-label">{{ zoneLabel(tzOffset ?? undefined) }}</span>
          <button class="btn small" @click="applyCustom">Применить</button>
        </div>
        <RangeTimeline
          v-if="baseFromMs != null && baseToMs != null && baseToMs > baseFromMs"
          :from="baseFromMs"
          :to="baseToMs"
          :selection="selection"
          :zone-offset="tzOffset ?? undefined"
          @shift="shiftRange"
          @update:selection="applyTimelineSelection"
        />
        <div v-if="chartSections.length || !measurement" class="range-hint">Выделите период на графике мышью или пальцем</div>
        <div v-if="loading" class="empty small" role="status"><span class="loading-label">Загрузка…</span></div>
        <template v-else-if="measurement ? chartSections.length : rows.length">
          <div class="chart-section" v-for="che in chartSections" :key="che.key">
            <div class="chart-head">
              <h4>{{ label(che.key) }}<span class="unit" v-if="chartUnit(che)">, {{ chartUnit(che) }}</span></h4>
              <div class="y-edit" v-if="isYEditable(che.key) && canEditPrefs">
                <label>min <input type="number" step="any" v-model="yEdit(che.key).min" @keyup.enter="applyY(che.key)" /></label>
                <label>max <input type="number" step="any" v-model="yEdit(che.key).max" @keyup.enter="applyY(che.key)" /></label>
                <button class="btn small" @click="applyY(che.key)">ОК</button>
                <button class="btn small ghost" @click="resetY(che.key)">Авто</button>
              </div>
            </div>
            <LineChart :labels="timeLabels" :datasets="chartDatasets(che)" :unit="chartUnit(che)" :zone-offset="tzOffset ?? undefined" :x-min="statsXMin" :x-max="statsXMax" :y-min="yBounds(che.key).min" :y-max="yBounds(che.key).max" :y-bands="che.key === 'rssi' ? RSSI_BANDS : []" selectable @select-range="applySelectedRange" />
          </div>
          <div class="chart-section transmission-section" v-if="!measurement && (transmissionMarkers.length || isAdmin)">
            <div class="chart-head">
              <h4>Передача данных</h4>
              <div class="transmission-actions">
                <div v-if="isAdmin" class="transmission-toggle" role="group" aria-label="Вид передачи данных">
                  <button class="btn small" :class="{ active: transmissionView === 'graph' }" @click="transmissionView = 'graph'">График</button>
                  <button class="btn small" :class="{ active: transmissionView === 'errors' }" @click="showErrors">Ошибки</button>
                </div>
                <span class="transmission-count" v-if="transmissionView === 'graph' || !isAdmin">{{ transmissionMarkers.length }} сообщ.</span>
                <span class="transmission-count" v-else>{{ errorsExhausted ? `${errorRows.length} ошибок` : `Показано ${errorRows.length}` }}</span>
              </div>
            </div>
            <LineChart v-if="transmissionView === 'graph' || !isAdmin" :markers="transmissionMarkers" :x-min="statsXMin" :x-max="statsXMax" :zone-offset="tzOffset ?? undefined" timeline selectable :aria-label="`Передача данных: ${transmissionMarkers.length} сообщений`" @select-range="applySelectedRange" />
            <div v-else class="error-list-wrap">
              <div v-if="errorsLoading" class="empty small" role="status"><span class="loading-label">Загружаю ошибки…</span></div>
              <table v-if="errorRows.length" class="error-list">
                <thead><tr><th>Дата</th><th>№ сообщения</th><th>Ошибка</th></tr></thead>
                <tbody>
                  <tr v-for="(row, index) in errorRows" :key="`${row.time}-${index}`">
                    <td>{{ fmtEventTimeWithZone(row.time, tzOffset) }}</td>
                    <td>{{ row.numMessage ?? '—' }}</td>
                    <td>{{ row.error }}</td>
                  </tr>
                </tbody>
              </table>
              <div v-else-if="!errorsLoading" class="empty small">Ошибок в сохранённой истории нет</div>
              <button v-if="!errorsExhausted && !errorsLoading" class="btn small load-more-errors" @click="loadMoreErrors">Загрузить ещё</button>
            </div>
          </div>
          <div class="empty small" v-if="!chartSections.length && !transmissionMarkers.length">За выбранный период данных нет</div>
        </template>
        <div v-else class="empty small">Нет данных за выбранный период</div>
      </template>
    </section>

    <SensorCalibrate
      v-if="measurement?.key === 'v0' && canEditPrefs && zeroCalibrationOpen"
      modal
      :device-id="resolvedDeviceId"
      @close="zeroCalibrationOpen = false"
    />

    <div v-if="displaySettingsOpen" class="modal-overlay" @click.self="displaySettingsOpen = false">
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="display-settings-title">
        <div class="modal-head">
          <h3 id="display-settings-title" class="display-settings-title">
            <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zm0 12.5c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/>
            </svg>
            Показ измерений
          </h3>
          <button class="modal-close" aria-label="Закрыть" @click="displaySettingsOpen = false">✕</button>
        </div>
        <div class="field-toggles">
          <label class="field-check" v-for="key in fieldKeys" :key="'modal-tg-' + key">
            <input type="checkbox" :checked="!isFieldHidden(chipId, key)" @change="toggleField(chipId, key)" />
            {{ key.toUpperCase() }} — {{ label(key) }}
          </label>
        </div>
        <p class="hint">
          Скрытые измерения этого датчика не показываются ни в последних данных, ни на графиках.
          Выбор хранится в вашем аккаунте и переносится на другие устройства.
        </p>
      </div>
    </div>

    <div v-if="calibDialogOpen" class="modal-overlay" @click.self="calibDialogOpen = false">
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="calib-dialog-title">
        <div class="modal-head">
          <h3 id="calib-dialog-title">Калибровка пределов влажности</h3>
          <button class="modal-close" aria-label="Закрыть" @click="calibDialogOpen = false">✕</button>
        </div>
        <div class="calib-row">
          <label>Воздух <input type="number" v-model.number="calibForm.value1" /></label>
          <label>Сухая почва (0%) <input type="number" v-model.number="calibForm.value2" /></label>
          <label>Вода (100%) <input type="number" v-model.number="calibForm.value3" /></label>
        </div>
        <p class="hint">
          Опорные точки для этого датчика: текущие значения из БД, при их отсутствии —
          дефолтные. Правка калибровки — только админом.
        </p>
        <div class="modal-actions">
          <button class="btn small secondary close-btn" @click="calibDialogOpen = false">Закрыть</button>
          <button class="btn small save" :disabled="calibForm.busy" @click="saveCalib">
            {{ calibForm.busy ? '...' : 'Сохранить' }}
          </button>
        </div>
      </div>
    </div>
    </template>
  </div>
  <RouterView />
</template>

<script setup>
import DeviceSharingDialog from '../components/DeviceSharingDialog.vue'
import DeviceSharingIcon from '../components/DeviceSharingIcon.vue'
import HeaderActions from '../components/HeaderActions.vue'
import PageLoadState from '../components/PageLoadState.vue'
import DeviceNameField from '../components/DeviceNameField.vue'
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSensorCatalog, getSensorTelemetry, getSensorErrors, getSensorFirstMessage, getDevice, listDeviceAccess, grantDeviceAccess, revokeDeviceAccess, saveSensorCalibration, getMoistureScale, getSensorScales, getSensorThreshold, getDeviceKey, regenerateDeviceKey, listAlarms } from '../api/auth'
import { auth, deviceAccess, rememberDeviceAccess, rememberDeviceOwner, isOwnerRole } from '../store/auth'
import { getIndividualName, setIndividualName, getBlockName, getFieldName, getFieldUnit, isFieldHidden, toggleField, getFieldY, setFieldY, fieldKeys } from '../store/settings'
import { pushNotification, connectWs } from '../store/notifications'
import { alarmState } from '../store/alarms'
import { calibrationFor, loadCalibration, setCalibration } from '../store/calibration'
import { adcToPercent, immersionWarning } from '../utils/moisture'
import { moistureScaleColor, sensorScaleColor } from '../utils/gradient'
import { RSSI_BANDS, rssiBounds, rssiBackground } from '../utils/rssi'
import { fmtEventTimeWithZone, fmtAgo, zoneLabel, msToZoneInput, zoneInputToMs } from '../utils/datetime'
import LineChart from '../components/LineChart.vue'
import RangeTimeline from '../components/RangeTimeline.vue'
import InvitePanel from '../components/InvitePanel.vue'
import SensorCalibrate from './SensorCalibrate.vue'

const route = useRoute()
const pageReady = ref(false)
const pageError = ref('')
const router = useRouter()
const chipId = Number(route.params.chipId)
const queryDeviceId = route.query.deviceId ? Number(route.query.deviceId) : null
const rows = ref([])
const range = ref('24h')
const showCustom = ref(false)
const customFrom = ref('')
const customTo = ref('')
const baseFromMs = ref(null)
const baseToMs = ref(null)
const selection = ref(null)
const openThresholdFields = ref(new Set())
const thresholdNotificationFields = ref(new Set())
const baseLive = ref(true)
const loading = ref(true)
const catalogEntry = ref(null)
const tzOffset = ref(null)

const ranges = [
  { label: '3ч', value: '3h' },
  { label: '24ч', value: '24h' },
  { label: '3д', value: '3d' },
  { label: '7д', value: '7d' },
]

const isIndividual = computed(() => catalogEntry.value?.individual === true)
const blockRole = computed(() => catalogEntry.value?.blockRoles?.[0] || null)

/** Подпись блока-владельца: «Имя (id)» либо просто id, если имени нет. */
const blockLabel = computed(() => {
  if (isIndividual.value || !blockRole.value) return null
  const id = blockRole.value.blockId
  const name = getBlockName(id)
  return name ? `${name} (${id})` : String(id)
})

const displayName = computed(() => {
  const local = getIndividualName(chipId)
  if (local) return local
  return catalogEntry.value?.name || 'Датчик'
})

const editableName = computed(() => getIndividualName(chipId) || catalogEntry.value?.name || '')

function saveName(name) {
  if (!canEditPrefs.value) return
  setIndividualName(chipId, name)
}

const typeLabel = computed(() => {
  if (isIndividual.value) return 'автономный'
  const slot = blockRole.value?.slot
  return ['Блок', blockLabel.value, slot != null ? `S${slot}` : null].filter(Boolean).join(' ')
})

const THRESHOLD_FIELDS = ['v0', 'v1', 'v2', 'v3']

async function refreshOpenThresholds() {
  try {
    const results = await Promise.all(THRESHOLD_FIELDS.map(field => listAlarms(`THRESHOLD_${field.toUpperCase()}`, 200)))
    openThresholdFields.value = new Set(THRESHOLD_FIELDS.filter((field, i) =>
      results[i].some(a => Number(a.chipId) === chipId && !a.endedAt)))
  } catch {
    openThresholdFields.value = new Set()
  }
}

async function refreshThresholdConfig() {
  const configs = await Promise.all(THRESHOLD_FIELDS.map(field =>
    getSensorThreshold(chipId, field, resolvedDeviceId.value).catch(() => null)))
  thresholdNotificationFields.value = new Set(THRESHOLD_FIELDS.filter((field, i) => Number(configs[i]?.typeAlarm) > 0))
}

watch(() => alarmState.version, refreshOpenThresholds)

const sensorType = computed(() => {
  const last = rows.value.findLast(r => r.sensorType != null)
  return lastAny.value?.sensorType ?? last?.sensorType ?? catalogEntry.value?.sensorType ?? 0
})

const timeLabels = computed(() => rows.value.map(r => r.time))

const statsXMin = ref(null)
const statsXMax = ref(null)
const transmissionView = ref('graph')
const allErrorRows = ref([])
const errorsLoading = ref(false)
const errorsExhausted = ref(false)
const errorOffset = ref(0)

const errorRows = computed(() => allErrorRows.value
  .slice()
  .sort((a, b) => Date.parse(b.time) - Date.parse(a.time)))

async function showErrors() {
  transmissionView.value = 'errors'
  if (allErrorRows.value.length || errorsExhausted.value || errorsLoading.value) return
  await loadMoreErrors()
}

async function loadMoreErrors() {
  if (errorsExhausted.value || errorsLoading.value) return
  errorsLoading.value = true
  try {
    const pageSize = 100
    const page = await getSensorErrors(resolvedDeviceId.value, chipId, errorOffset.value, pageSize)
    allErrorRows.value = [...allErrorRows.value, ...page]
    errorOffset.value += page.length
    if (page.length < pageSize) errorsExhausted.value = true
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось загрузить список ошибок', 'error')
  } finally {
    errorsLoading.value = false
  }
}

const transmissionMarkers = computed(() => {
  const min = statsXMin.value
  const max = statsXMax.value
  if (min == null || max == null || max <= min) return []
  return rows.value.flatMap(r => {
    const ms = Date.parse(r.time)
    if (!Number.isFinite(ms)) return []
    return [{
      x: ms,
      title: `${fmtEventTimeWithZone(r.time, tzOffset.value)}${r.numMessage != null ? ` [${r.numMessage}]` : ''}`,
      error: isAdmin.value && typeof r.error === 'string' && r.error.trim() ? r.error.trim() : '',
    }]
  })
})

/**
 * Права текущего пользователя на устройство датчика. Границы оси и скрытие
 * каналов (v0–v3) — «настройка», а не отображение: viewer их не меняет
 * (сервер тоже отклоняет запись).
 */
const accessRole = ref(null)
const accessList = ref([])
const sharingDialog = ref(null)
watch(() => route.fullPath, () => { sharingDialog.value = null })
const showStats = ref(false)
const deviceInfo = ref(null)
const resolvedDeviceId = ref(null)

const canEditPrefs = computed(() => {
  if (auth.user?.role === 'ADMIN') return true
  if (accessRole.value) return accessRole.value !== 'VIEWER'
  // Роль ещё не загружена — сверяемся с общим кэшем, иначе не блокируем.
  return deviceAccess[resolvedDeviceId.value ?? chipId] !== 'VIEWER'
})

async function loadAccess(deviceId) {
  // Автономный датчик — сам себе устройство: его доступ ведётся здесь, а не на блоке.
  if (isIndividual.value && deviceId === chipId) {
    try {
      accessList.value = await listDeviceAccess(deviceId)
      const mine = accessList.value.find(a => a.userId === auth.user?.id)
      accessRole.value = mine?.accessRole ?? null
      if (mine?.accessRole) rememberDeviceAccess(deviceId, mine.accessRole)
    } catch {
      accessList.value = []
      accessRole.value = auth.user?.role === 'ADMIN' ? null : accessRole.value
    }
    return
  }
  if (auth.user?.role === 'ADMIN') {
    accessRole.value = null
    return
  }
  if (accessRole.value) return
  const cached = deviceAccess[deviceId]
  if (cached) {
    accessRole.value = cached
    return
  }
  try {
    const list = await listDeviceAccess(deviceId)
    const mine = list.find(a => a.userId === auth.user?.id)
    if (mine?.accessRole) {
      accessRole.value = mine.accessRole
      rememberDeviceAccess(deviceId, mine.accessRole)
    }
  } catch {
    accessRole.value = null
  }
}

// --- Калибровка влажности этого датчика (только админ; PUT /api/sensors/{id}/calibration) ---

const isAdmin = computed(() => auth.user?.role === 'ADMIN')

const myAccessRole = computed(() => accessList.value.find(a => a.userId === auth.user?.id)?.accessRole)
const canInvite = computed(() => isIndividual.value && !isAdmin.value && isOwnerRole(myAccessRole.value))
const othersDevice = computed(() => !!deviceInfo.value?.ownerUsername
  && deviceInfo.value.ownerUsername !== auth.user?.username)
const adminLocked = computed(() => isAdmin.value && othersDevice.value && !deviceInfo.value?.ownerAdminAccess)
const canManageAccess = computed(() => (isAdmin.value && !adminLocked.value) || isOwnerRole(myAccessRole.value))

function accessRoleLabel(role) {
  if (role === 'OWNER') return 'владелец'
  return role === 'VIEWER' ? 'только чтение' : 'управление'
}

async function swapAccessRole(a) {
  const next = a.accessRole === 'VIEWER' ? 'USER' : 'VIEWER'
  try {
    await grantDeviceAccess(chipId, a.userId, next)
    await loadAccess(chipId)
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось изменить доступ', 'error')
  }
}

async function revokeAccess(a) {
  if (!confirm(`Отозвать доступ у ${a.username}?`)) return
  try {
    await revokeDeviceAccess(chipId, a.userId)
    await loadAccess(chipId)
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось отозвать доступ', 'error')
  }
}

const displaySettingsOpen = ref(false)
const calibDialogOpen = ref(false)
const zeroCalibrationOpen = ref(false)

function closeDialogsOnEscape(e) {
  if (e.key === 'Escape') {
    displaySettingsOpen.value = false
    calibDialogOpen.value = false
  }
}

function openDisplaySettings() {
  displaySettingsOpen.value = true
}

function openCalibSettings() {
  initCalibForm()
  calibDialogOpen.value = true
}

const deviceKey = ref(null)
const keyBusy = ref(false)

async function toggleKey() {
  if (deviceKey.value) {
    deviceKey.value = null
    return
  }
  keyBusy.value = true
  try {
    deviceKey.value = (await getDeviceKey(chipId)).key
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось получить ключ', 'error')
  } finally {
    keyBusy.value = false
  }
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(`ID ${chipId} · ключ ${deviceKey.value}`)
    pushNotification('Ключ скопирован')
  } catch {
    pushNotification('Скопируйте вручную', 'error')
  }
}

async function regenKey() {
  if (!confirm('Перевыпустить ключ? Прежний комплектный лист перестанет подходить.')) return
  keyBusy.value = true
  try {
    deviceKey.value = (await regenerateDeviceKey(chipId)).key
    pushNotification('Ключ перевыпущен')
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось перевыпустить ключ', 'error')
  } finally {
    keyBusy.value = false
  }
}

/** Секция только для админов и датчиков типа 0 (влажность). */
const calibSectionVisible = computed(() =>
  isAdmin.value && (!catalogEntry.value || Number(catalogEntry.value.sensorType ?? 0) === 0))

const calibForm = reactive({ value1: null, value2: null, value3: null, busy: false })
let calibFormReady = false

/** Заполняет форму один раз при первой загрузке страницы (кэш store-калибровки). */
async function initCalibForm() {
  if (calibFormReady || !calibSectionVisible.value) return
  const cal = await loadCalibration(chipId)
  if (cal) Object.assign(calibForm, { value1: cal.value1, value2: cal.value2, value3: cal.value3 })
  calibFormReady = true
}

async function saveCalib() {
  if (calibForm.busy) return
  if (calibForm.value1 == null || calibForm.value2 == null || calibForm.value3 == null) {
    pushNotification('Заполните все три опорных значения', 'error')
    return
  }
  calibForm.busy = true
  try {
    const v = await saveSensorCalibration(chipId, {
      value1: calibForm.value1, value2: calibForm.value2, value3: calibForm.value3,
    })
    if (v) {
      Object.assign(calibForm, { value1: v.value1, value2: v.value2, value3: v.value3 })
      // Обновляем кеш — график v0 в процентах сразу пересчитается.
      setCalibration(chipId, { value1: v.value1, value2: v.value2, value3: v.value3 })
    }
    calibDialogOpen.value = false
    pushNotification('Калибровка сохранена', 'success')
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось сохранить калибровку', 'error')
  } finally {
    calibForm.busy = false
  }
}

/** Настройки автономного датчика: только не-viewer, только индивидуальный. */
function openSettings() {
  router.push(`/sensor/${chipId}/settings`)
}

/** Поля с редактируемым градиентом: v0 (влажность) и v1/v2/v3 (sensor_scale). */
function isGradientField(key) {
  return ['v0', 'v1', 'v2', 'v3'].includes(key)
}

function openMeasurement(fieldKey) {
  router.push({
    name: 'sensor-measurement',
    params: { chipId, fieldKey },
    query: route.query,
    state: { fromSensorPage: true },
  })
}

function backToSensor() {
  if (window.history.state?.fromSensorPage) router.back()
  else router.replace({ name: 'sensor', params: { chipId }, query: route.query })
}

function openNotifications() {
  const fieldKey = measurement.value.key
  router.push({
    name: fieldKey === 'v0' ? 'sensor-moisture-notifications' : 'sensor-field-notifications',
    params: { chipId, fieldKey },
    query: { ...route.query, deviceId: resolvedDeviceId.value },
    state: { fromMeasurementPage: true },
  })
}

/**
 * Последняя запись датчика в целом — грузится отдельно с limit=1, чтобы раздел
 * «Последние данные» показывал её, даже когда она старше выбранного периода.
 */
const lastAny = ref(null)

const latest = computed(() => lastAny.value || (rows.value.length ? rows.value[rows.value.length - 1] : null))

/** Текущее время для подписи «сколько назад» — обновляется вместе с таймером освежения. */
const nowTick = ref(Date.now())
const agoText = computed(() => fmtAgo(reading.value?.time, nowTick.value))

const batteryStart = ref(null)
const batteryStartLoaded = ref(false)
const BATTERY_START_TTL_MS = 5 * 60 * 1000
let batteryStartFetchedAt = 0

async function loadBatteryStart(deviceId) {
  if (Date.now() - batteryStartFetchedAt < BATTERY_START_TTL_MS) return
  try {
    const row = await getSensorFirstMessage(deviceId, chipId)
    batteryStart.value = row?.time ?? null
    batteryStartFetchedAt = Date.now()
  } catch {
    batteryStartFetchedAt = 0
  } finally {
    batteryStartLoaded.value = true
  }
}

function fmtBatteryRuntime(ms) {
  const totalMin = Math.max(0, Math.floor(ms / 60000))
  const days = Math.floor(totalMin / 1440)
  const hours = Math.floor((totalMin % 1440) / 60)
  const minutes = totalMin % 60
  if (days) return `${days} дн. ${hours} ч. ${minutes} мин.`
  if (hours) return `${hours} ч. ${minutes} мин.`
  return `${minutes} мин.`
}

const batteryRuntimeText = computed(() => {
  if (!batteryStart.value) return null
  const startMs = new Date(batteryStart.value).getTime()
  if (!Number.isFinite(startMs)) return null
  const ms = nowTick.value - startMs
  return ms >= 0 ? fmtBatteryRuntime(ms) : null
})

const moistureCal = computed(() => {
  if (sensorType.value !== 0) return null
  return calibrationFor(chipId)
})

/** Сохранённая шкала-градиент датчика (для цветной индикации влажности). */
const moistureScale = ref(null)

/** Шкалы-градиенты полей v1/v2/v3 (sensor_scale) — для цветной индикации показаний. */
const fieldScales = ref({})

const latestPct = computed(() => {
  const cal = moistureCal.value
  if (!cal || reading.value?.moistureAdc == null) return null
  return adcToPercent(reading.value.moistureAdc, cal)
})

const pctColor = computed(() => moistureScaleColor(moistureScale.value, latestPct.value))

function fieldColor(che, row) {
  if (che.key === 'v0') return null // влажность — отдельная логика (moistureScaleColor)
  const v = che.value(row)
  const scale = fieldScales.value[che.key]
  return sensorScaleColor(scale, v)
}

/** Цветовой прямоугольник поля: null — градиент не настроен, рисуем пустую рамку. */
function cellGradientColor(che) {
  return che.key === 'v0' ? pctColor.value : fieldColor(che, reading.value)
}

/** RSSI tile: a translucent signal-quality background behind the value. */
function cellBackground(che) {
  if (che.key !== 'rssi') return null
  const bg = rssiBackground(reading.value?.rssi)
  return bg ? { background: `linear-gradient(${bg}, ${bg}), var(--bg-card)` } : null
}

const immersionWarningShown = computed(() => {
  if (!latest.value || latest.value.moistureAdc == null) return false
  const cal = moistureCal.value
  return cal ? immersionWarning(latest.value.moistureAdc, cal) : false
})

const charts = computed(() => [
  { key: 'v0', value: r => r.moistureAdc, color: '#3498db' },
  { key: 'v1', value: r => r.temperature, color: '#e74c3c' },
  { key: 'v2', value: r => r.humidity, color: '#2ecc71' },
  { key: 'v3', value: r => r.light, color: '#f39c12' },
  { key: 'battery', value: r => r.battery, color: '#9b59b6' },
  // BatLoad — батарея под нагрузкой (автономные датчики): своя ячейка в
  // последних данных + вторая линия на графике батареи, не отдельный график.
  { key: 'batload', value: r => r.batload, color: '#e67e22', onBattery: true },
  { key: 'rssi', value: r => r.rssi, color: '#1abc9c' },
])

const measurement = computed(() => charts.value.find(chart => chart.key === route.params.fieldKey))
const lastFieldReading = ref(null)

watch([measurement, lastAny, resolvedDeviceId], async ([chart, newest, deviceId], previous, onCleanup) => {
  if (chart !== previous[0]) {
    lastFieldReading.value = null
    zeroCalibrationOpen.value = false
  }
  if (!chart || !deviceId || hasValue(chart, newest)) return

  let current = true
  onCleanup(() => { current = false })
  try {
    const [row] = await getSensorTelemetry(deviceId, chipId, undefined, undefined, 1, chart.key)
    if (current && hasValue(chart, row)) lastFieldReading.value = row
  } catch {
    // Keep the most recent available sample while the next refresh retries.
  }
})

const reading = computed(() => {
  const chart = measurement.value
  if (!chart) return latest.value
  if (hasValue(chart, lastAny.value)) return lastAny.value
  return lastFieldReading.value || rows.value.findLast(row => hasValue(chart, row))
})

function hasValue(chart, row) {
  const value = row && chart.value(row)
  return value != null && Number.isFinite(Number(value))
}

const visibleCells = computed(() => measurement.value ? [measurement.value] : cellCharts.value)

const availableCharts = computed(() => charts.value.filter(c => {
  if (isFieldHidden(chipId, c.key)) return false
  return rows.value.some(r => c.value(r) != null && !isNaN(c.value(r)))
}))

/**
 * Ячейки «Последних данных» — по последней записи датчика, даже если она старше
 * выбранного периода (иначе при пустом периоде показания не отображаются вовсе).
 */
const cellCharts = computed(() => charts.value.filter(c => {
  if (isFieldHidden(chipId, c.key)) return false
  const r = latest.value
  return r != null && c.value(r) != null && !isNaN(c.value(r))
}))

/** Графики-секции: BatLoad не рисуется отдельно, а входит в график батареи. */
const chartSections = computed(() => {
  const chart = measurement.value
  if (chart) return rows.value.some(row => hasValue(chart, row)) ? [chart] : []
  return availableCharts.value.filter(c => !c.onBattery)
})

/** Подпись канала — из настроек (страница «Общая настройка»); дефолты для батареи/RSSI. */
function label(key) {
  const name = getFieldName(sensorType.value, key)
  if (name !== key) return name
  if (key === 'battery') return 'Батарея'
  if (key === 'batload') return 'Батарея под нагрузкой'
  if (key === 'rssi') return isIndividual.value ? 'Уровень сигнала RSSI роутера' : 'Уровень сигнала RSSI блока'
  return name
}

function labelParts(key) {
  const text = label(key)
  const splitAt = text.indexOf(' ')
  return splitAt < 0
    ? { first: text, rest: '' }
    : { first: text.slice(0, splitAt), rest: text.slice(splitAt + 1) }
}

/** Единица величины — из настроек; дефолты для батареи/RSSI. */
function fieldUnit(key) {
  const u = getFieldUnit(sensorType.value, key)
  if (u) return u
  if (key === 'battery') return 'В'
  if (key === 'batload') return 'В'
  if (key === 'rssi') return 'дБм'
  return ''
}

/** Влажность почвы в процентах (как на странице блока): "74.2% (512)". */
function moistureDisplay(adc) {
  const cal = moistureCal.value
  const pct = cal ? adcToPercent(adc, cal) : null
  return pct != null ? `${pct}% (${adc})` : fmtValue(adc)
}

function cellValue(che, row) {
  const v = che.value(row)
  if (che.key === 'v0') return moistureDisplay(v)
  return fmtValue(v)
}

function cellUnit(che) {
  // Процент уже внутри значения «74.2% (1554)» — единица не дублируется.
  if (che.key === 'v0') return moistureCal.value ? '' : fieldUnit('v0')
  return fieldUnit(che.key)
}

function chartData(che) {
  if (che.key === 'v0' && moistureCal.value) {
    return rows.value.map(r => r.moistureAdc != null ? adcToPercent(r.moistureAdc, moistureCal.value) : null)
  }
  return rows.value.map(r => che.value(r))
}

/** На графике батареи второй линией — BatLoad (нагрузка), если по датчику есть. */
function chartDatasets(che) {
  const ds = [{ label: label(che.key), data: chartData(che), color: che.color }]
  if (che.key === 'battery' && !measurement.value) {
    const bl = availableCharts.value.find(c => c.key === 'batload')
    if (bl) ds.push({ label: label('batload'), data: chartData(bl), color: bl.color })
  }
  return ds
}

function chartUnit(che) {
  if (che.key === 'v0') {
    if (moistureCal.value) return getFieldUnit(sensorType.value, 'v0') || '%'
    return fieldUnit('v0')
  }
  return fieldUnit(che.key)
}

// --- Выбор границ вертикальной шкалы (v0–v3), запоминается в настройках пользователя ---

const Y_EDITABLE = ['v0', 'v1', 'v2', 'v3']

function isYEditable(key) {
  return Y_EDITABLE.includes(key)
}

const yEdits = reactive({})

function yEdit(key) {
  if (!yEdits[key]) {
    const r = getFieldY(chipId, key)
    yEdits[key] = { min: r.min ?? '', max: r.max ?? '' }
  }
  return yEdits[key]
}

/** График батареи всегда в фиксированном диапазоне 1,5–3,5 В (для всех датчиков). */
const BATTERY_Y = { min: 1.5, max: 3.5 }

const rssiY = computed(() => rssiBounds(rows.value.map(r => r.rssi)))

function yBounds(key) {
  if (key === 'battery') return BATTERY_Y
  if (key === 'rssi') return rssiY.value
  if (!isYEditable(key)) return { min: null, max: null }
  return getFieldY(chipId, key)
}

function parseBound(v) {
  if (v === '' || v == null) return null
  const n = Number(String(v).replace(',', '.'))
  return Number.isFinite(n) ? n : NaN
}

function applyY(key) {
  if (!canEditPrefs.value) return
  const e = yEdit(key)
  const min = parseBound(e.min)
  const max = parseBound(e.max)
  if ((min != null && !Number.isFinite(min)) || (max != null && !Number.isFinite(max))) {
    pushNotification('Граница шкалы — число (допустима запятая)', 'error')
    return
  }
  if (min != null && max != null && max <= min) {
    pushNotification('max должен быть больше min', 'error')
    return
  }
  setFieldY(chipId, key, min, max)
  pushNotification('Шкала сохранена', 'success')
}

function resetY(key) {
  if (!canEditPrefs.value) return
  const e = yEdit(key)
  e.min = ''
  e.max = ''
  setFieldY(chipId, key, null, null)
}

function fmtValue(v) {
  if (v == null || isNaN(v)) return '—'
  return typeof v === 'number' ? v.toFixed(1).replace(/\.0$/, '') : String(v)
}

function rangeDurationMs(r) {
  const num = parseInt(r)
  const unit = r.slice(-1)
  return unit === 'h' ? num * 3600000 : num * 86400000
}

function presetWindowMs(r) {
  const to = Date.now()
  return { from: to - rangeDurationMs(r), to }
}

function ensureBaseWindow() {
  if (selection.value || !baseLive.value) return
  const w = presetWindowMs(range.value === 'custom' ? '24h' : range.value)
  baseFromMs.value = w.from
  baseToMs.value = w.to
}

const activeFromMs = computed(() => selection.value?.from ?? baseFromMs.value)
const activeToMs = computed(() => selection.value?.to ?? baseToMs.value)

async function setRange(r) {
  range.value = r
  selection.value = null
  baseLive.value = true
  const w = presetWindowMs(r)
  baseFromMs.value = w.from
  baseToMs.value = w.to
  await loadData()
}

/** Начало периода: выделенный участок важнее базового периода. */
function currentSince() {
  return activeFromMs.value != null ? new Date(activeFromMs.value).toISOString() : ''
}

/** Конец периода: выделенный участок важнее базового периода. */
function currentTo() {
  return activeToMs.value != null ? new Date(activeToMs.value).toISOString() : ''
}

function applyCustom() {
  const fromMs = zoneInputToMs(customFrom.value, tzOffset.value ?? undefined)
  const toMs = zoneInputToMs(customTo.value, tzOffset.value ?? undefined)
  if (fromMs == null || toMs == null || toMs <= fromMs) return
  range.value = 'custom'
  selection.value = null
  baseLive.value = false
  baseFromMs.value = fromMs
  baseToMs.value = toMs
  loadData()
}

function applySelectedRange({ from, to }) {
  if (loading.value) return
  const fromMs = Math.floor(from / 60000) * 60000
  const toMs = Math.ceil(to / 60000) * 60000
  const baseFrom = activeFromMs.value
  const baseTo = activeToMs.value
  if (baseFrom == null || baseTo == null || !(toMs > fromMs)) return
  const f = Math.max(baseFrom, fromMs)
  const t = Math.min(baseTo, toMs)
  if (!(t > f)) return
  selection.value = { from: f, to: t }
  baseLive.value = false
  loadData()
}

function applyTimelineSelection({ from, to }) {
  const fromMs = Math.floor(from / 60000) * 60000
  const toMs = Math.ceil(to / 60000) * 60000
  const baseFrom = baseFromMs.value
  const baseTo = baseToMs.value
  if (baseFrom == null || baseTo == null || !(toMs > fromMs)) return
  const f = Math.max(baseFrom, fromMs)
  const t = Math.min(baseTo, toMs)
  if (!(t > f)) return
  selection.value = { from: f, to: t }
  baseLive.value = false
  loadData()
}

async function shiftRange(direction) {
  const from = baseFromMs.value
  const to = baseToMs.value
  if (from == null || to == null) return
  const width = to - from
  let nextFrom = from + direction * width
  let nextTo = to + direction * width
  if (direction > 0 && nextTo > Date.now()) {
    nextTo = Date.now()
    nextFrom = nextTo - width
  }
  if (nextFrom === from && nextTo === to) return
  baseFromMs.value = nextFrom
  baseToMs.value = nextTo
  selection.value = null
  baseLive.value = false
  if (range.value === 'custom') {
    const offset = tzOffset.value ?? undefined
    customFrom.value = msToZoneInput(nextFrom, offset)
    customTo.value = msToZoneInput(nextTo, offset)
  }
  await loadData()
}

async function loadData(silent = false) {
  if (!silent) loading.value = true
  ensureBaseWindow()
  try {
    const catalog = await getSensorCatalog()
    catalogEntry.value = catalog.find(c => Number(c.chipId) === chipId) || null
    // Форма калибровки (админ): один раз, не блокирует загрузку данных.
    initCalibForm()
    let deviceId = queryDeviceId
    if (!deviceId) {
      const entry = catalogEntry.value
      if (entry && !entry.individual && entry.blockRoles?.length) {
        deviceId = entry.blockRoles[0].blockId
      } else {
        deviceId = chipId
      }
    }
    resolvedDeviceId.value = deviceId
    await loadAccess(deviceId)
    if (isIndividual.value && deviceId === chipId && !deviceInfo.value) {
      try {
        const device = await getDevice(deviceId)
        deviceInfo.value = device
        rememberDeviceOwner(device)
      } catch { /* список доступа покажется без данных о владельце */ }
    }
    if (tzOffset.value == null) {
      try {
        const device = deviceInfo.value || await getDevice(deviceId)
        tzOffset.value = device?.tzOffsetMinutes ?? null
      } catch { /* зона остаётся по умолчанию */ }
    }
    const since = currentSince()
    const to = currentTo()
    const batteryPromise = loadBatteryStart(deviceId)
    const [periodRows, fresh] = await Promise.all([
      getSensorTelemetry(deviceId, chipId, since || undefined, to || undefined),
      // Последняя запись без привязки к периоду; ошибка не обнуляет прежнее значение.
      getSensorTelemetry(deviceId, chipId, undefined, undefined, 1).catch(() => null),
    ])
    await batteryPromise
    rows.value = periodRows
    rows.value.reverse()
    if (Array.isArray(fresh)) lastAny.value = fresh.length ? fresh[0] : null
    statsXMin.value = activeFromMs.value ?? (rows.value.length ? Date.parse(rows.value[0].time) : Date.now() - 3600000)
    statsXMax.value = activeToMs.value ?? (rows.value.length ? Date.parse(rows.value[rows.value.length - 1].time) : statsXMin.value + 1)
    if (!(statsXMax.value > statsXMin.value)) statsXMax.value = statsXMin.value + 1
    if (sensorType.value === 0) await loadCalibration(chipId)
  } finally {
    loading.value = false
  }
}

let refreshTimer = null

function refreshScales() {
  getMoistureScale(chipId).then(s => { moistureScale.value = s }).catch(() => {})
  getSensorScales(chipId).then(list => {
    const map = {}
    for (const sc of list || []) {
      if (sc.enabled && sc.minValue != null && sc.maxValue != null) map[sc.fieldKey] = sc
    }
    fieldScales.value = map
  }).catch(() => { fieldScales.value = {} })
}

watch(() => route.meta.measurementNotifications, (open, wasOpen) => {
  if (!open && wasOpen) {
    refreshScales()
    refreshThresholdConfig()
    refreshOpenThresholds()
  }
})

async function loadPage() {
  pageError.value = ''
  try {
    await loadData()
    pageReady.value = true
    refreshThresholdConfig()
  } catch {
    pageError.value = 'Не удалось загрузить данные датчика'
  }
}

onMounted(() => {
  connectWs()
  refreshOpenThresholds()
  loadPage()
  refreshScales()
  refreshTimer = setInterval(() => {
    if (!pageReady.value || loading.value) return
    nowTick.value = Date.now()
    loadData(true).catch(() => {})
  }, 30000)
  document.addEventListener('keydown', closeDialogsOnEscape)
})
onUnmounted(() => {
  clearInterval(refreshTimer)
  document.removeEventListener('keydown', closeDialogsOnEscape)
})
</script>

<style scoped>
.sensor { max-width: 600px; margin: 0 auto; padding: 1rem; }
.page-header { position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1.5rem; padding: 0.75rem 1rem 1rem; background: var(--bg); }
.header-top { display: flex; align-items: center; gap: 0.75rem; }
.header-top h2 { color: var(--text-strong); flex: 1; min-width: 0; margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.measurement-heading { flex: 1; min-width: 0; }
.measurement-heading h2 { white-space: normal; overflow-wrap: anywhere; }
.measurement-heading p { margin-top: 0.25rem; color: var(--text-muted); overflow-wrap: anywhere; }
.header-meta { display: flex; justify-content: flex-start; margin-top: 0.35rem; }
.type-tag { font-size: 0.7rem; padding: 0.2rem 0.5rem; border-radius: 4px; background: var(--tag-bg); color: var(--tag-text); }
/* The bell sits right next to the menu button. */
.ago { color: var(--text-dim); font-size: 0.85em; margin-left: 0.35rem; }
.msg-num { color: var(--text-dim); font-size: 0.85em; margin-left: 0.25rem; }
.battery-runtime { display: flex; align-items: baseline; flex-wrap: wrap; gap: 0.4rem; background: var(--bg); border-left: 3px solid var(--accent); border-radius: 10px; padding: 0.7rem 1rem; font-size: 0.85rem; color: var(--text-muted); margin-bottom: 1.5rem; }
.battery-runtime b { color: var(--text-strong); }
.battery-label { color: var(--text-dim); }
.battery-runtime .muted { color: var(--text-dim); }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.section-head h3 { margin: 0; }
.stats-toggle { font: inherit; letter-spacing: inherit; text-transform: inherit; color: inherit; background: none; border: 0; border-bottom: 1px dashed var(--border); cursor: pointer; }
.stats-toggle:hover { color: var(--accent); }
.invite-box { background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 0.6rem; margin-top: 0.6rem; }
.invite-link { color: var(--accent); font-family: monospace; font-size: 0.75rem; word-break: break-all; margin-bottom: 0.4rem; }
.invite-note { color: var(--text-dim); font-size: 0.75rem; line-height: 1.4; margin-bottom: 0.5rem; }
.invite-actions { display: flex; flex-wrap: wrap; gap: 0.6rem; margin-top: 0.2rem; }
.relay-card { background: var(--bg-card); border-radius: 12px; padding: 0.9rem 1rem; box-shadow: var(--card-shadow); }
.access-card { padding: 0.25rem 0.8rem; }
.access-entry { display: flex; align-items: center; flex-wrap: wrap; gap: 0.5rem; padding: 0.45rem 0; }
.access-entry + .access-entry { border-top: 1px solid var(--border-soft); }
.access-who { flex: 1 1 auto; min-width: 0; color: var(--text); font-size: 0.85rem; overflow-wrap: anywhere; }
.access-self { color: var(--text-faint); font-size: 0.75rem; }
.access-actions { display: flex; flex: 1 0 100%; flex-wrap: wrap; gap: 0.4rem; }
.admin-locked { font-size: 0.78rem; line-height: 1.45; color: var(--text-dim); margin: 0 0 0.6rem; }
.btn.danger { background: var(--bg-accent); border-color: var(--bg-accent); color: var(--on-accent); }
.btn.danger:hover:not(:disabled) { background: #7a2222; border-color: #7a2222; color: #fff; }
.threshold-indicator { position: absolute; top: 0.45rem; right: 1.125rem; width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; }
.threshold-bell { width: 16px; height: 16px; flex: none; fill: none; stroke: #ffe066; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
:global(html[data-theme="light"] .threshold-bell) { stroke: #2563eb !important; }
.latest-cell { position: relative; display: grid; grid-template-rows: 2.25rem 1.5rem; row-gap: 0.25rem; }
.latest-cell.measurement-link { border: 1px solid var(--border-soft); padding: calc(0.7rem - 1px) calc(0.9rem - 1px); text-align: left; font: inherit; cursor: pointer; transition: border-color 0.15s, box-shadow 0.15s, background-color 0.15s; }
.latest-cell.measurement-link:hover { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent); }
.latest-cell.measurement-link:active { border-color: var(--accent); background: var(--bg-soft); box-shadow: 0 0 0 1px var(--accent); }
.latest-cell.measurement-link:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.small { font-size: 0.75rem; padding: 0.3rem 0.6rem; }
.btn.secondary { background: var(--bg-accent); }
.btn.active { background: var(--bg-active); border: 1px solid var(--accent); }
.section { margin-bottom: 2rem; }
.section h3 { color: var(--text-muted); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 0.75rem; }
.range-select { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.range-select > button { flex: 1 1 auto; }
.custom-range { display: flex; gap: 0.6rem; align-items: center; flex-wrap: wrap; margin-bottom: 1rem; padding: 0.6rem 0.8rem; background: var(--bg-card); border-radius: 10px; }
.custom-range label { display: flex; align-items: center; gap: 0.4rem; color: var(--text-muted); font-size: 0.78rem; }
.custom-range input { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.8rem; padding: 0.3rem 0.5rem; }
.zone-label { color: var(--text-faint); font-size: 0.72rem; }
.range-hint { color: var(--text-faint); font-size: 0.72rem; margin: -0.35rem 0 0.8rem; }
.last-received { color: var(--text-muted); font-size: 0.85rem; margin-bottom: 0.6rem; }
.last-received b { color: var(--text-strong); }
.immersion-warning { color: var(--danger); background: #3a1518; font-weight: 600; font-size: 0.85rem; padding: 0.5rem 0.7rem; border-radius: 8px; margin-bottom: 0.7rem; }
.latest-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 0.6rem; }
.latest-cell { background: var(--bg-card); border-radius: 10px; padding: 0.7rem 0.9rem; }
.latest-cell:has(> .pct-color) { padding-inline-end: 1.15rem; }
.latest-cell:has(> .pct-color) .threshold-indicator { right: 1.5rem; }
.l-label { display: flex; align-items: flex-start; gap: 0.4rem; color: var(--text-sub); font-size: 0.8rem; line-height: 1.05rem; margin: 0; padding-right: 1.8rem; overflow: hidden; white-space: normal; }
.latest-cell:has(> .threshold-indicator) .l-label { padding-right: 2.175rem; }
.latest-cell:has(> .pct-color):has(> .threshold-indicator) .l-label { padding-right: 2.3rem; }
.l-label > span { display: -webkit-box; flex: 1; min-width: 0; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
.l-value { color: var(--text); font-size: 1rem; font-weight: 500; display: flex; align-items: center; align-self: start; width: 100%; min-width: 0; min-height: 1.5rem; }
.l-reading { display: inline-flex; align-items: center; min-height: 1.5rem; box-sizing: border-box; padding: 0.15rem 0.35rem; border: 1px solid transparent; border-radius: 5px; }
.l-reading.alarm-active { border-color: var(--danger); }
.l-value-moisture .l-reading { white-space: nowrap; }
@media (max-width: 480px) {
  .l-value-moisture .l-reading { font-size: 0.875rem; padding-inline: 0.15rem; }
}

.l-unit { color: var(--text); font-size: 1rem; font-weight: 500; }
.chart-section { margin-bottom: 1.5rem; }
.chart-head { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 0.3rem; }
.chart-section h4 { color: var(--text-dim); font-size: 0.8rem; margin-bottom: 0; }
.transmission-section { margin-bottom: 1.5rem; }
.transmission-count { color: var(--text-faint); font-size: 0.72rem; }
.transmission-actions, .transmission-toggle { display: flex; align-items: center; gap: 0.45rem; }
.error-list-wrap { max-width: 100%; overflow-x: auto; }
.error-list { width: 100%; border-collapse: collapse; color: var(--text); font-size: 0.8rem; }
.error-list th, .error-list td { padding: 0.55rem 0.65rem; border-bottom: 1px solid var(--border-soft); text-align: left; vertical-align: top; }
.error-list th { color: var(--text-muted); font-weight: 600; white-space: nowrap; }
.error-list td:first-child, .error-list td:nth-child(2) { white-space: nowrap; }
@media (max-width: 560px) {
  .transmission-section .chart-head { align-items: flex-start; flex-direction: column; }
  .error-list { font-size: 0.74rem; }
  .error-list th, .error-list td { padding: 0.45rem 0.35rem; }
}
.y-edit { display: flex; align-items: center; gap: 0.4rem; flex-wrap: wrap; }
.y-edit label { display: flex; align-items: center; gap: 0.3rem; color: var(--text-dim); font-size: 0.7rem; }
.y-edit input { width: 5.5rem; background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.75rem; padding: 0.2rem 0.4rem; }
.btn.ghost { background: transparent; border: 1px solid var(--border-soft); color: var(--text-muted); }
.field-toggles { display: flex; flex-direction: column; gap: 0.5rem; }
.field-check { display: flex; align-items: center; gap: 0.35rem; color: var(--text-sub); font-size: 0.8rem; cursor: pointer; }
.hint { color: var(--text-dim); font-size: 0.75rem; margin-top: 0.5rem; }
.calib-row { display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: center; }
.calib-row label { display: flex; align-items: center; gap: 0.4rem; color: var(--text-muted); font-size: 0.78rem; background: var(--bg-card); border-radius: 8px; padding: 0.4rem 0.6rem; }
.calib-row input { width: 4.5rem; background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.85rem; padding: 0.3rem 0.5rem; }
.unit { color: var(--text-faint); font-weight: 400; }
.modal-overlay { position: fixed; inset: 0; z-index: 100; display: flex; align-items: center; justify-content: center; padding: 1rem; background: rgba(0, 0, 0, 0.6); }
.modal { width: 100%; max-width: 420px; max-height: 85vh; overflow-y: auto; padding: 1.25rem; background: var(--bg-card); border-radius: 12px; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.5); }
.modal-head { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; margin-bottom: 0.9rem; }
.modal h3 { color: var(--text-strong); font-size: 1rem; text-transform: none; letter-spacing: 0; margin: 0; }
.display-settings-title { display: flex; align-items: center; gap: 0.75rem; }
.display-settings-title svg { width: 1.5rem; height: 1.5rem; flex: none; }
.modal-actions { display: flex; justify-content: center; gap: 0.6rem; margin-top: 1rem; }
.modal-actions .btn { display: inline-flex; align-items: center; justify-content: center; width: 8rem; height: 2rem; padding: 0 0.6rem; }
.modal-actions .close-btn { font-weight: 700; }
.empty.small { color: var(--text-faint); text-align: center; padding: 1rem; font-size: 0.85rem; }
</style>
