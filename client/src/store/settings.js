import { reactive, watch } from 'vue'
import { getPrefs, updatePrefs, saveFieldDefaults, applyFieldDefaults } from '../api/auth'
import { pushNotification } from './notifications'
import { THEME_VAR_NAMES, buildThemePalette, pickPaletteMode } from '../utils/themePalette'

const STORAGE_KEY = 'sensoterra-settings'
const MIGRATED_KEY = 'sensoterra-settings-migrated'

function load() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY)) || {}
  } catch {
    return {}
  }
}

/**
 * Только то, что не зависит от устройств: уведомления и поведение графиков. Всё
 * привязанное к блокам и датчикам живёт в БД (см. prefs ниже), чтобы настройка
 * не приходилось повторять на каждом браузере и телефоне.
 */
function normalizeBgColor(v) {
  return typeof v === 'string' && /^#[0-9a-f]{6}$/i.test(v) ? v.toLowerCase() : null
}

const DEFAULT_BG = { dark: '#1a1a2e', light: '#f7f7f7' }

function clearPaletteVars(root) {
  for (const name of THEME_VAR_NAMES) root.style.removeProperty(name)
  root.style.removeProperty('--bg')
  root.style.removeProperty('color-scheme')
}

function readThemeVars(root, theme) {
  const previous = root.getAttribute('data-theme')
  root.setAttribute('data-theme', theme)
  const style = getComputedStyle(root)
  const base = {}
  for (const name of THEME_VAR_NAMES) {
    const value = style.getPropertyValue(name).trim()
    if (value) base[name] = value
  }
  root.setAttribute('data-theme', previous || settings.theme)
  return base
}

function saveLocal(state) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    alertsEnabled: state.alertsEnabled,
    chartTooltips: state.chartTooltips,
    theme: state.theme,
    bgColors: state.bgColors,
  }))
}

const persisted = load()

export const settings = reactive({
  // локальная часть
  alertsEnabled: persisted.alertsEnabled !== false,
  chartTooltips: ['auto', 'always', 'off'].includes(persisted.chartTooltips) ? persisted.chartTooltips : 'auto',
  theme: (persisted.theme === 'light' || persisted.theme === 'dark') ? persisted.theme : 'dark',
  bgColors: {
    dark: normalizeBgColor(persisted.bgColors?.dark ?? persisted.bgColor),
    light: normalizeBgColor(persisted.bgColors?.light),
  },
  moistureScales: persisted.moistureScales || {},
  // серверная часть: devices[chipId], sensors[chipId], fields[type][field],
  // defaults[type][field] — значения по умолчанию из кода сервера, не чьи-то настройки
  devices: {},
  sensors: {},
  fields: {},
  defaults: {},
  loaded: false,
})

watch(() => [settings.alertsEnabled, settings.chartTooltips, settings.theme, settings.bgColors, settings.moistureScales], () => saveLocal(settings), { deep: true })

const fieldKeys = ['v0', 'v1', 'v2', 'v3']

function devicePref(chipId) {
  const key = String(chipId)
  if (!settings.devices[key]) settings.devices[key] = { hideVacc: true, name: null }
  return settings.devices[key]
}

function sensorPref(chipId) {
  const key = String(chipId)
  if (!settings.sensors[key]) settings.sensors[key] = { hiddenFields: [], yRange: {} }
  // Сервер может отдать объект без этих ключей (например, {}) — тогда запись
  // в yRange падала бы с TypeError и настройка «молча» не сохранялась.
  if (!settings.sensors[key].hiddenFields) settings.sensors[key].hiddenFields = []
  if (!settings.sensors[key].yRange) settings.sensors[key].yRange = {}
  return settings.sensors[key]
}

function applyPrefs(data) {
  if (!data) return
  settings.devices = data.devices || {}
  settings.sensors = data.sensors || {}
  settings.fields = data.fields || {}
  settings.defaults = data.defaults || {}
  settings.loaded = true
}

/**
 * Загрузка настроек при входе. При первом запуске после переезда на БД отправляем
 * накопленное в браузере: иначе у пользователя пропали бы блоки, названные вручную.
 */
export async function loadPrefs() {
  try {
    const data = await getPrefs()
    applyPrefs(data)
  } catch {
    settings.loaded = true
  }
  if (!localStorage.getItem(MIGRATED_KEY) && hasLegacyData()) {
    try {
      await updatePrefs(legacyPatch())
      localStorage.setItem(MIGRATED_KEY, '1')
      const data = await getPrefs()
      applyPrefs(data)
    } catch { /* перенос не удался — работаем на локальных значениях, флаг не ставим */ }
  }
}

// Переносим имена и подписи каналов. hideVacc и fieldYRange в старом виде относились
// «ко всем», привязать их к устройствам однозначно нельзя: hideVacc теперь скрыт по
// умолчанию, границы оси при необходимости задаются заново.
function hasLegacyData() {
  return Object.keys(persisted.blockNames || {}).length > 0
    || Object.keys(persisted.individualNames || {}).length > 0
    || Object.keys(persisted.fieldLabels || {}).length > 0
    || Object.keys(persisted.fieldUnits || {}).length > 0
}

function legacyFields() {
  const fields = {}
  for (const [type, labels] of Object.entries(persisted.fieldLabels || {})) {
    for (const [field, label] of Object.entries(labels || {})) {
      if (!label) continue
      fields[type] = fields[type] || {}
      fields[type][field] = { label, unit: persisted.fieldUnits?.[type]?.[field] ?? '' }
    }
  }
  for (const [type, units] of Object.entries(persisted.fieldUnits || {})) {
    for (const [field, unit] of Object.entries(units || {})) {
      if (!unit) continue
      fields[type] = fields[type] || {}
      fields[type][field] = { label: persisted.fieldLabels?.[type]?.[field] ?? '', unit }
    }
  }
  return fields
}

function legacyPatch() {
  const devices = {}
  for (const [chipId, name] of Object.entries(persisted.blockNames || {})) {
    devices[chipId] = { name: name || '' }
  }
  for (const [chipId, name] of Object.entries(persisted.individualNames || {})) {
    devices[chipId] = { name: name || '' }
  }
  return { devices, sensors: {}, fields: legacyFields() }
}

export function getBlockName(chipId) {
  return settings.devices[String(chipId)]?.name || null
}

export function setBlockName(chipId, name) {
  const pref = devicePref(chipId)
  pref.name = name || null
  patch('devices', chipId, { name: name || '' })
}

export function getIndividualName(chipId) {
  return settings.devices[String(chipId)]?.name || null
}

export function setIndividualName(chipId, name) {
  setBlockName(chipId, name)
}

/**
 * Vacc скрыт по умолчанию: у блоков на сетевом питании колонка пустая. Блоки на
 * аккумуляторе пользователь включает обратно — настройка принадлежит блоку.
 */
export function isVaccHidden(chipId) {
  const pref = settings.devices[String(chipId)]
  return pref ? pref.hideVacc !== false : true
}

export function setHideVacc(chipId, v) {
  const pref = devicePref(chipId)
  pref.hideVacc = !!v
  patch('devices', chipId, { hideVacc: !!v })
}

export function setVaccVisible(chipId, v) {
  setHideVacc(chipId, !v)
}

/**
 * Подпись канала. Подписи лежат в БД по типу датчика; если пользователь ничего не
 * задал, у типа 0 показывается то, что было по умолчанию в браузере, у остальных
 * типов — сам ключ канала.
 */
/**
 * Подпись канала для показа. Своя строка важнее дефолта, очищенная своя строка важнее
 * дефолта тоже: пользователь снял подпись сам, и тихо вернуть ему «Влажность почвы» из
 * дефолта означало бы врать про то, что он настроил. Дефолта нет — показываем сырой
 * ключ канала (v0, v1, ...), он узнаваем и его видно, что пора подписать.
 */
export function getFieldName(sensorType, field) {
  const own = ownField(sensorType, field)
  if (own) return own.label != null ? own.label : field
  const label = defaultField(sensorType, field).label
  return label != null ? label : field
}

export function getFieldUnit(sensorType, field) {
  const own = ownField(sensorType, field)
  if (own) return own.unit != null ? own.unit : ''
  const unit = defaultField(sensorType, field).unit
  return unit != null ? unit : ''
}

/** Подпись по умолчанию для подсказки под полем; нет дефолта — пусто, а не чужое имя. */
export function defaultLabel(sensorType, field) {
  return defaultField(sensorType, field).label ?? ''
}

/** Единица по умолчанию для подсказки под полем. */
export function defaultUnit(sensorType, field) {
  return defaultField(sensorType, field).unit ?? ''
}

/**
 * Сохраняет новые дефолты целиком и подменяет ими локальные, чтобы поля на странице
 * показали ровно то, что теперь в базе. При отказе возвращает прежние значения и
 * сообщает об этом — как и остальные правки здесь.
 */
export async function saveDefaults(fieldsByType) {
  const previous = settings.defaults
  settings.defaults = fieldsByType
  try {
    settings.defaults = await saveFieldDefaults(fieldsByType)
  } catch {
    settings.defaults = previous
    pushNotification('Значения по умолчанию не сохранены')
    throw new Error('не сохранено')
  }
}

/**
 * Раскатывает дефолт тем, у кого подпись этого канала ещё равна старому дефолту.
 * fromValues — то, что было на экране: сервер не помнит, что админ видел, и не должен
 * гадать, у кого подпись «стояла из дефолта».
 */
export async function applyDefaults(sensorType, field, fromValues) {
  const to = defaultField(sensorType, field)
  try {
    return await applyFieldDefaults({
      sensorType: Number(sensorType) || 0,
      field,
      oldLabel: fromValues.label ?? '',
      oldUnit: fromValues.unit ?? '',
      newLabel: to.label ?? '',
      newUnit: to.unit ?? '',
    })
  } catch {
    pushNotification('Не удалось применить значение по умолчанию')
    throw new Error('не применено')
  }
}

function fieldTypeKey(sensorType) {
  return String(Number(sensorType) || 0)
}

/**
 * Личная ячейка типа-канала или null, если её у пользователя нет. null и {label: null} —
 * разные вещи: первое значит «подпись не задана, показываем дефолт», второе «пользователь
 * сам очистил подпись, дефолт тут ни при чём».
 */
function ownField(sensorType, field) {
  const row = settings.fields[fieldTypeKey(sensorType)]?.[field]
  return row === undefined ? null : row
}

function defaultField(sensorType, field) {
  return settings.defaults[fieldTypeKey(sensorType)]?.[field] || {}
}

/** Личная подпись канала — ровно то, что лежит в строке пользователя, без дефолтов. */
export function fieldLabel(sensorType, field) {
  return ownField(sensorType, field)?.label ?? ''
}

/** Личная единица канала — ровно то, что лежит в строке пользователя, без дефолтов. */
export function fieldUnit(sensorType, field) {
  return ownField(sensorType, field)?.unit ?? ''
}

export function setFieldName(sensorType, field, label) {
  patchField(sensorType, field, { label: label || '' })
}

export function setFieldUnit(sensorType, field, unit) {
  patchField(sensorType, field, { unit: unit || '' })
}

/** Один запрос на все каналы типа: иначе правка подписи слала бы четыре PUT подряд. */
export function setFields(sensorType, entries) {
  const type = String(Number(sensorType) || 0)
  settings.fields[type] = settings.fields[type] || {}
  for (const [field, body] of Object.entries(entries)) {
    settings.fields[type][field] = { ...settings.fields[type][field], ...body }
  }
  patch('fields', type, entries)
}

function patchField(sensorType, field, body) {
  const type = String(Number(sensorType) || 0)
  settings.fields[type] = settings.fields[type] || {}
  settings.fields[type][field] = { ...settings.fields[type][field], ...body }
  patch('fields', type, { [field]: body })
}

/**
 * Отправляет изменение на сервер. Значение применяется сразу, оптимистично, поэтому при
 * ошибке его надо вернуть обратно И сказать об этом: молчаливая подмена «чекбокс снят, а
 * настройка не сохранена» выглядит как сбой в самом интерфейсе и очень долго ищется.
 */
function patch(kind, key, body, undo) {
  const body_ = { devices: {}, sensors: {}, fields: {} }
  body_[kind][key] = body
  updatePrefs(body_).then(applyPrefs).catch(async () => {
    if (undo) undo()
    pushNotification('Настройка не сохранена на сервере')
  })
}

/** Скрытие каналов — свойство конкретного датчика, а не его типа. */
export function isFieldHidden(sensorChipId, field) {
  const hidden = settings.sensors[String(sensorChipId)]?.hiddenFields || []
  return hidden.includes(field)
}

export function hiddenFields(sensorChipId) {
  return settings.sensors[String(sensorChipId)]?.hiddenFields || []
}

export function toggleField(sensorChipId, field) {
  const pref = sensorPref(sensorChipId)
  const arr = pref.hiddenFields
  const idx = arr.indexOf(field)
  if (idx >= 0) arr.splice(idx, 1)
  else arr.push(field)
  patch('sensors', sensorChipId, { hiddenFields: [...arr] }, () => {
    const at = pref.hiddenFields.indexOf(field)
    if (at >= 0) pref.hiddenFields.splice(at, 1)
    else pref.hiddenFields.push(field)
  })
}

export function areAlertsEnabled() {
  return settings.alertsEnabled !== false
}

export function setAlertsEnabled(v) {
  settings.alertsEnabled = !!v
}

// 'auto' — подсказки только при наведении мышью: на телефоне касание открывало
// значение и оно залипало, пока не ткнёшь в другое место.
// 'always' — показывать и по касанию, 'off' — не показывать никогда.
export function getChartTooltipMode() {
  return settings.chartTooltips
}

export function setChartTooltipMode(v) {
  settings.chartTooltips = ['auto', 'always', 'off'].includes(v) ? v : 'auto'
}

export function getTheme() {
  return settings.theme === 'light' ? 'light' : 'dark'
}

export function getBgColor() {
  return settings.bgColors[getTheme()] || null
}

export function setBgColor(v) {
  settings.bgColors[getTheme()] = normalizeBgColor(v)
  applyTheme()
}

export function setTheme(v) {
  settings.theme = (v === 'light' || v === 'dark') ? v : 'dark'
  applyTheme()
}

export function toggleTheme() {
  setTheme(getTheme() === 'dark' ? 'light' : 'dark')
}

export function applyTheme() {
  try {
    const root = document.documentElement
    root.setAttribute('data-theme', settings.theme)
    const bgColor = getBgColor()
    clearPaletteVars(root)
    if (bgColor) {
      const mode = pickPaletteMode(bgColor)
      const base = readThemeVars(root, mode)
      const vars = buildThemePalette(bgColor, mode, base)
      for (const [name, value] of Object.entries(vars)) root.style.setProperty(name, value)
      root.style.setProperty('color-scheme', mode)
    }
    const themeColor = bgColor || DEFAULT_BG[settings.theme]
    let meta = document.querySelector('meta[name="theme-color"]')
    if (!meta) {
      meta = document.createElement('meta')
      meta.setAttribute('name', 'theme-color')
      document.head.appendChild(meta)
    }
    meta.setAttribute('content', themeColor)
    let manifestLink = document.querySelector('link[rel="manifest"]')
    if (manifestLink) {
      try {
        fetch(manifestLink.getAttribute('href'))
          .then(r => r.json())
          .then(m => {
            m.theme_color = themeColor
            m.background_color = themeColor
          })
          .catch(() => {})
      } catch {}
    }
  } catch {}
}

export function chartTooltipsEnabled() {
  const mode = settings.chartTooltips
  if (mode === 'always') return true
  if (mode === 'off') return false
  try {
    return window.matchMedia('(hover: hover)').matches
  } catch {
    return true
  }
}

export function getFieldY(sensorChipId, field) {
  const range = settings.sensors[String(sensorChipId)]?.yRange?.[field]
  return { min: range?.min != null ? range.min : null, max: range?.max != null ? range.max : null }
}

/**
 * Границы вертикальной шкалы канала. min/max: число — задать, null — сбросить в
 * «авто», undefined — не трогать текущее значение.
 */
export function setFieldY(sensorChipId, field, min, max) {
  const pref = sensorPref(sensorChipId)
  const cur = pref.yRange[field] || {}
  const next = {
    min: min === undefined ? (cur.min ?? null) : (min == null ? null : Number(min)),
    max: max === undefined ? (cur.max ?? null) : (max == null ? null : Number(max)),
  }
  pref.yRange[field] = next
  patch('sensors', sensorChipId, { yRange: { [field]: next } })
}

export { fieldKeys }
export function getMoistureScale(chipId) {
  const key = String(chipId)
  const sc = settings.moistureScales[key] || {}
  return {
    minPct: sc.minPct ?? null,
    maxPct: sc.maxPct ?? null,
    minColor: sc.minColor || '#3b82f6',
    maxColor: sc.maxColor || '#22c55e',
    enabled: sc.enabled === true && sc.minPct != null && sc.maxPct != null,
    ...sc,
  }
}

export function setMoistureScale(chipId, scale) {
  const key = String(chipId)
  settings.moistureScales[key] = { ...getMoistureScale(chipId), ...scale }
}

export function clearMoistureScale(chipId) {
  const key = String(chipId)
  delete settings.moistureScales[key]
}
