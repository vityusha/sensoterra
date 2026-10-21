<template>
  <div class="settings">
    <div class="header-row">
      <button @click="$router.push('/')" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>Настройки</h2>
    </div>

    <section class="section">
      <h3>Внешний вид</h3>
      <label class="field-check">
        <input type="checkbox" :checked="isLight" @change="onThemeChange($event.target.checked)" />
        Светлая тема
      </label>
    </section>

    <section class="section">
      <h3>Уведомления</h3>
      <label class="field-check">
        <input type="checkbox" :checked="alertsEnabled" @change="onAlertsChange($event.target.checked)" />
        Показывать уведомления о событиях
      </label>
      <p class="hint">
        Отключает: онлайн/оффлайн блоков, события каналов, серверные предупреждения и сообщения
        «датчик не передаёт данные». Подтверждения действий и ошибки показываются всегда.
      </p>
    </section>

    <section class="section">
      <h3>Графики</h3>
      <select class="field-input" :value="tooltipMode" @change="onTooltipMode($event.target.value)">
        <option value="auto">Только при наведении мышью</option>
        <option value="always">Всегда, включая касание</option>
        <option value="off">Не показывать значения</option>
      </select>
      <p class="hint">
        На телефоне касание открывает значение в точке и оно держится, пока не ткнёшь в другое место —
        в режиме «только мышь» на сенсорных экранах подсказки не появляются совсем.
      </p>
    </section>

    <section class="section" v-if="!isAdmin()">
      <h3>Напряжение питания блока</h3>
      <div class="field-list">
        <div class="field-row" v-for="b in blocks" :key="b.chipId">
          <span class="field-input-text">{{ deviceLabel(b) }}</span>
          <label class="field-check">
            <input type="checkbox" :checked="!isVaccHidden(b.chipId)" @change="setVacc(b.chipId, $event.target.checked)" />
            показывать Vacc
          </label>
        </div>
      </div>
      <p class="hint">
        По умолчанию скрыто: у блоков на сетевом питании колонка пустая. Включите для блоков,
        питающихся от аккумулятора. Настройка — своя у каждого блока и у каждого пользователя.
      </p>
    </section>

    <section class="section" v-if="!isAdmin()">
      <h3>Каналы датчиков</h3>
      <p class="hint">
        У конкретного датчика аппаратно может не быть освещённости, а вместо влажности почвы он
        меряет влажность воздуха. Поэтому скрытие задаётся для каждого датчика отдельно.
      </p>
      <div v-for="sn in sensors" :key="sn.chipId" class="type-group">
        <h4>{{ sensorLabel(sn) }}</h4>
        <div class="field-list">
          <label class="field-check" v-for="key in fieldKeys" :key="key">
            <input type="checkbox" :checked="!isFieldHidden(sn.chipId, key)" :disabled="isSensorViewer(sn)" @change="toggle(sn.chipId, key)" />
            {{ key.toUpperCase() }} — {{ fieldTitle(sn, key) }}
          </label>
        </div>
      </div>
    </section>

    <section class="section">
      <h3>Названия полей и единицы</h3>
      <div v-for="type in types" :key="type" class="type-group">
        <h4>Датчик type={{ type }}</h4>
        <div class="field-list">
          <div class="field-row" v-for="key in fieldKeys" :key="key">
            <span class="field-tag">{{ key.toUpperCase() }}</span>
            <input class="field-input" v-model="labels[type][key]" @change="saveLabels(type)" :placeholder="defaultLabel(type, key)" />
            <input class="field-unit-input" v-model="units[type][key]" @change="saveUnits(type)" :placeholder="defaultUnit(type, key)" />
          </div>
        </div>
      </div>
      <p class="hint">
        Подписи и единицы — по типу датчика, одинаковые для всех его датчиков. Хранятся
        в вашем аккаунте, поэтому переносятся на телефон и компьютер. Подсказки под полями
        — значения по умолчанию, которые пришли с сервера: у типа 0 они есть, у остальных
        типов, пока для них не заданы значения по умолчанию, подсказка пустая.
      </p>

      <div v-if="isAdmin()" class="admin-defaults">
        <p class="hint">
          Админские значения по умолчанию. Их получают новые пользователи при регистрации;
          у кого подпись уже выбрана руками, она не меняется.
        </p>
        <button class="btn small secondary" @click="toggleDefaultsEditor()">
          {{ defaultsEditorOpen ? 'Скрыть значения по умолчанию' : 'Изменить значения по умолчанию' }}
        </button>

        <div v-if="defaultsEditorOpen" class="defaults-editor">
          <div v-for="type in types" :key="'d' + type" class="type-group">
            <h4>Значения по умолчанию для type={{ type }}</h4>
            <div v-for="key in fieldKeys" :key="key" class="field-row">
              <span class="field-tag">{{ key.toUpperCase() }}</span>
              <input
                class="field-input"
                v-model="defaults[String(type)][key].label"
                @change="saveTypeDefaults(type)"
              />
              <input
                class="field-unit-input"
                v-model="defaults[String(type)][key].unit"
                @change="saveTypeDefaults(type)"
              />
              <button
                class="btn small secondary"
                title="Обновить подпись у тех, кто её не менял руками"
                @click="applyOneDefault(type, key)"
              >
                Применить
              </button>
            </div>

            <div v-if="calibDefaults[String(type)]" class="calib-block">
              <h4 class="calib-title">Калибровка влажности (АЦП v0 → %)</h4>
              <div class="calib-row">
                <label>Воздух <input type="number" v-model.number="calibDefaults[String(type)].value1" /></label>
                <label>Сухая почва (0%) <input type="number" v-model.number="calibDefaults[String(type)].value2" /></label>
                <label>Вода (100%) <input type="number" v-model.number="calibDefaults[String(type)].value3" /></label>
                <button class="btn small save" :disabled="calibBusy" @click="saveCalibDefaults(type)">
                  {{ calibBusy ? '...' : 'Сохранить' }}
                </button>
              </div>
              <p class="calib-note">
                Исходные опорные точки для датчиков типа 0: копируются каждому новому датчику при
                первом сообщении. Конкретный датчик переопределяется на его странице, кнопкой
                «Калибровка влажности» (только админ).
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { settings, getChartTooltipMode, setChartTooltipMode, getTheme, setTheme, isVaccHidden, setVaccVisible, isFieldHidden, toggleField, getBlockName, getIndividualName, fieldKeys, fieldLabel, fieldUnit, defaultLabel, defaultUnit, setFields, saveDefaults, applyDefaults } from '../store/settings'
import { clearEventNotifications, refreshEventNotifications, pushNotification } from '../store/notifications'
import { isAdmin, auth, deviceAccess, rememberDeviceAccess } from '../store/auth'
import { getSensorTypes, listDevices, getSensorCatalog, getCalibrationDefaults, saveCalibrationDefaults, listDeviceAccess } from '../api/auth'
import { getFieldName, getFieldUnit } from '../store/settings'

const types = ref([])
const labels = reactive({})
const units = reactive({})
const blocks = ref([])
const sensors = ref([])
const alertsEnabled = ref(true)
const tooltipMode = ref('auto')
const isLight = ref(false)

function deviceLabel(d) {
  return `${getBlockName(d.chipId) || d.name || d.chipId} (${d.chipId})`
}

function sensorLabel(sn) {
  const slot = sn.blockRoles?.[0]?.slot
  const own = getIndividualName(sn.chipId)
  const nm = own || sn.name
  return slot ? `Датчик S${slot}${nm ? ` «${nm}»` : ''} (${sn.chipId})` : `Датчик${nm ? ` «${nm}»` : ''} (${sn.chipId})`
}

function fieldTitle(sn, key) {
  const t = sn.sensorType || 0
  return `${getFieldName(t, key)} ${getFieldUnit(t, key)}`.trim()
}

function setVacc(chipId, visible) {
  setVaccVisible(chipId, visible)
}

function toggle(chipId, key) {
  toggleField(chipId, key)
}

/** Устройство, которому принадлежит датчик: блок его слота либо он сам. */
function sensorDeviceId(sn) {
  return sn.blockRoles?.[0]?.blockId ?? sn.chipId
}

/** VIEWER: чекбоксы каналов выключены — сервер запись всё равно отклонит. */
function isSensorViewer(sn) {
  return deviceAccess[sensorDeviceId(sn)] === 'VIEWER'
}

/** Роли текущего пользователя на устройства датчиков — для гейта «только чтение». */
async function loadSensorAccess() {
  const ids = new Set()
  for (const b of blocks.value) ids.add(Number(b.chipId))
  for (const s of sensors.value) ids.add(Number(sensorDeviceId(s)))
  await Promise.allSettled([...ids].filter(Boolean).map(async id => {
    if (deviceAccess[id]) return
    try {
      const list = await listDeviceAccess(id)
      const mine = list.find(a => a.userId === auth.user?.id)
      if (mine?.accessRole) rememberDeviceAccess(id, mine.accessRole)
    } catch { /* роль неизвестна — сервер отклонит запись */ }
  }))
}

onMounted(async () => {
  alertsEnabled.value = settings.alertsEnabled !== false
  tooltipMode.value = getChartTooltipMode()
  isLight.value = getTheme() === 'light'
  // следим за сменой темы в другой вкладке
  window.addEventListener('storage', () => {
    isLight.value = getTheme() === 'light'
  })
  let list = [0]
  try {
    const resp = await getSensorTypes()
    if (Array.isArray(resp) && resp.length) list = resp.map(Number)
  } catch { /* бэкенд недоступен — показываем тип 0 */ }
  // Админу эти списки не показываем: при сотнях блоков и тысячах датчиков страница
  // превращается в простыню. Свой выбор он делает там же, где смотрит устройство.
  if (!isAdmin()) {
    try {
      const dev = await listDevices()
      blocks.value = (Array.isArray(dev) ? dev : []).filter(d => d.deviceType === 'BLOCK')
    } catch { /* список блоков не загрузился — раздел останется пустым */ }
    try {
      const cat = await getSensorCatalog()
      sensors.value = Array.isArray(cat) ? cat : []
    } catch { /* каталог датчиков не загрузился */ }
    await loadSensorAccess()
  }
  types.value = list
  for (const t of types.value) {
    if (!labels[String(t)]) labels[String(t)] = {}
    if (!units[String(t)]) units[String(t)] = {}
    hydrate(t)
  }
})

const defaultsEditorOpen = ref(false)

const calibDefaults = reactive({})
const calibBusy = ref(false)

async function loadCalibDefaults() {
  try {
    const data = await getCalibrationDefaults()
    for (const [t, v] of Object.entries(data || {})) {
      calibDefaults[t] = { value1: v.value1, value2: v.value2, value3: v.value3 }
    }
  } catch { /* дефолты недоступны — секция не покажется */ }
}

async function saveCalibDefaults(type) {
  const c = calibDefaults[String(type)]
  if (!c || c.value1 == null || c.value2 == null || c.value3 == null) {
    pushNotification('Заполните все три опорных значения', 'error')
    return
  }
  calibBusy.value = true
  try {
    const data = await saveCalibrationDefaults({
      [String(type)]: { value1: c.value1, value2: c.value2, value3: c.value3 },
    })
    for (const [t, v] of Object.entries(data || {})) {
      calibDefaults[t] = { value1: v.value1, value2: v.value2, value3: v.value3 }
    }
    pushNotification('Значения по умолчанию калибровки сохранены', 'success')
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось сохранить калибровку', 'error')
  } finally {
    calibBusy.value = false
  }
}

/**
 * Черновик дефолтов для админских полей. Копия settings.defaults, а не он сам: пока
 * админ правит поля, серверные значения должны остаться прежними — иначе «от чего
 * раскатывать» посчиталось бы от несохранённого и тихо разъехалось бы с базой.
 */
const defaults = reactive({})
/** Что было в дефолте до правки — с этого значения раскатываем. */
const defaultsOriginal = reactive({})

function toggleDefaultsEditor() {
  defaultsEditorOpen.value = !defaultsEditorOpen.value
  // Снимок «до правки» берём только при открытии: после сохранения он обязан остаться
  // прежним, иначе «Применить» раскатает уже новое значение на новое же и ничего не
  // изменит у тех, кто до сих пор на старом.
  if (defaultsEditorOpen.value) {
    resetDefaultsOriginal()
    syncDefaultsDraft()
    loadCalibDefaults()
  }
}

function resetDefaultsOriginal() {
  for (const key of Object.keys(defaultsOriginal)) delete defaultsOriginal[key]
  for (const [type, byField] of Object.entries(settings.defaults || {})) {
    defaultsOriginal[type] = {}
    for (const k of fieldKeys) {
      defaultsOriginal[type][k] = { label: byField[k]?.label ?? '', unit: byField[k]?.unit ?? '' }
    }
  }
  for (const t of types.value) {
    if (defaultsOriginal[String(t)]) continue
    defaultsOriginal[String(t)] = {}
    for (const k of fieldKeys) defaultsOriginal[String(t)][k] = { label: '', unit: '' }
  }
}

function syncDefaultsDraft() {
  for (const key of Object.keys(defaults)) delete defaults[key]
  for (const [type, byField] of Object.entries(settings.defaults || {})) {
    defaults[type] = {}
    for (const k of fieldKeys) {
      defaults[type][k] = { label: byField[k]?.label ?? '', unit: byField[k]?.unit ?? '' }
    }
  }
  for (const t of types.value) {
    if (defaults[String(t)]) continue
    defaults[String(t)] = {}
    for (const k of fieldKeys) defaults[String(t)][k] = { label: '', unit: '' }
  }
}

function defaultBefore(type, key) {
  return defaultsOriginal[String(type)]?.[key] || { label: '', unit: '' }
}

async function saveTypeDefaults(type) {
  const perType = {}
  for (const k of fieldKeys) {
    perType[k] = { label: defaults[String(type)][k].label, unit: defaults[String(type)][k].unit }
  }
  try {
    await saveDefaults({ [String(type)]: perType })
    syncDefaultsDraft()
  } catch { /* уведомление уже показано в сторе */ }
}

async function applyOneDefault(type, key) {
  const was = defaultBefore(type, key)
  try {
    const res = await applyDefaults(type, key, was)
    pushNotification(res.message)
  } catch { /* уведомление уже показано в сторе */ }
}

function hydrate(t) {
  for (const k of fieldKeys) {
    labels[String(t)][k] = fieldLabel(t, k)
    units[String(t)][k] = fieldUnit(t, k)
  }
}

function saveLabels(type) {
  const entries = {}
  for (const key of fieldKeys) {
    entries[key] = { label: labels[String(type)][key] || '' }
  }
  setFields(type, entries)
}

function saveUnits(type) {
  const entries = {}
  for (const key of fieldKeys) {
    entries[key] = { unit: units[String(type)][key] || '' }
  }
  setFields(type, entries)
}

function onAlertsChange(v) {
  alertsEnabled.value = v
  settings.alertsEnabled = v
  if (v) refreshEventNotifications()
  else clearEventNotifications()
}

function onThemeChange(v) {
  isLight.value = !!v
  setTheme(v ? 'light' : 'dark')
}

function onTooltipMode(v) {
  tooltipMode.value = v
  setChartTooltipMode(v)
}
</script>

<style scoped>
.settings { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; align-items: center; gap: 0.75rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1.5rem; padding: 1rem; background: var(--bg); }
.header-row h2 { color: var(--text-strong); flex: 1; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.secondary { background: var(--bg-accent); }
.section { margin-bottom: 2rem; }
.section h3 { color: var(--text-muted); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 0.75rem; }
.field-list { display: flex; flex-direction: column; gap: 0.6rem; }
.type-group { margin-bottom: 1rem; }
.type-group h4 { color: var(--tag-text); font-size: 0.85rem; margin: 0 0 0.5rem; }
.field-row { display: flex; align-items: center; gap: 0.7rem; background: var(--bg-card); padding: 0.7rem 1rem; border-radius: 8px; }
.field-tag { font-family: monospace; color: var(--tag-text); width: 28px; }
.field-input { flex: 1; background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; padding: 0.4rem 0.6rem; color: var(--text); font-size: 0.85rem; }
.field-input:focus { border-color: var(--accent); outline: none; }
.field-unit-input { width: 60px; background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; padding: 0.4rem 0.5rem; color: var(--text); font-size: 0.85rem; text-align: center; }
.field-unit-input:focus { border-color: var(--accent); outline: none; }
.field-check { display: flex; align-items: center; gap: 0.3rem; color: var(--text-muted); font-size: 0.8rem; cursor: pointer; white-space: nowrap; }
.field-check input { cursor: pointer; }
.field-input-text { flex: 1; color: var(--text-sub); font-size: 0.85rem; }
.hint { color: var(--muted-fg); font-size: 0.78rem; line-height: 1.4; margin: 0.5rem 0 0; }
.admin-defaults { margin-top: 0.9rem; padding-top: 0.9rem; border-top: 1px dashed var(--divider); }
.admin-defaults .hint { margin: 0 0 0.6rem; }
.defaults-editor { margin-top: 0.8rem; }
.defaults-editor .field-row { flex-wrap: wrap; }
.defaults-editor .btn { margin-left: auto; }
.calib-block { margin-top: 1rem; padding-top: 1rem; border-top: 1px dashed var(--divider); }
.calib-title { color: var(--tag-text); font-size: 0.82rem; margin: 0 0 0.5rem; }
.calib-row { display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: center; }
.calib-row label { display: flex; align-items: center; gap: 0.4rem; color: var(--text-muted); font-size: 0.78rem; background: var(--bg-card); border-radius: 8px; padding: 0.4rem 0.6rem; }
.calib-row input { width: 4.5rem; background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.85rem; padding: 0.3rem 0.5rem; }
.calib-note { color: var(--text-faint); font-size: 0.72rem; line-height: 1.4; margin: 0.5rem 0 0; }
</style>
