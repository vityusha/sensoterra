<template>
  <div class="alarms">
    <div class="header-row">
      <button @click="goBack" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>Уведомления</h2>
      <!-- Acts on the current tab only. -->
      <div class="menu-wrap" ref="menuRef">
        <button
          class="menu-toggle"
          :aria-expanded="menuOpen"
          aria-haspopup="menu"
          aria-label="Меню"
          @click="menuOpen = !menuOpen"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
            <circle cx="12" cy="5" r="2" />
            <circle cx="12" cy="12" r="2" />
            <circle cx="12" cy="19" r="2" />
          </svg>
        </button>
        <div v-if="menuOpen" class="menu-dropdown" role="menu">
          <button role="menuitem" :disabled="!hasUnreadFinished || busy" @click="readAll">
            <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M18 7l-1.41-1.41-6.34 6.34 1.41 1.41L18 7zm4.24-1.41L11.66 16.17 7.48 12l-1.41 1.41L11.66 19l12-12-1.42-1.41zM.41 13.41L6 19l1.41-1.41L1.83 12 .41 13.41z"/>
            </svg>
            Прочитать все
          </button>
          <button role="menuitem" class="menu-danger" :disabled="!hasDeletableRead || busy" @click="removeRead">
            <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/>
            </svg>
            Удалить все прочитанные
          </button>
        </div>
      </div>
    </div>

    <div class="tabs" role="tablist">
      <button
        v-for="t in TABS"
        :key="t.type"
        role="tab"
        class="tab"
        :class="{ active: t.type === activeType }"
        :aria-selected="t.type === activeType"
        @click="selectTab(t.type)"
      >
        {{ t.label }}
        <span v-if="alarmState.open[t.type]" class="tab-count">{{ alarmState.open[t.type] }}</span>
      </button>
    </div>

    <p class="hint">Жирным — активные и непросмотренные завершённые. Нажмите на завершённое, чтобы отметить его прочитанным.</p>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="loading && !rows.length" class="empty">Загрузка...</p>
    <p v-else-if="!rows.length" class="empty">
      {{ activeTab.tracked ? 'Уведомлений нет' : 'Этот тип уведомлений пока не отслеживается' }}
    </p>
    <template v-else>
    <!-- The table header is hidden on a phone: the event name goes above the cards there. -->
    <p class="mobile-caption">{{ eventTitle }}</p>
    <table class="alarm-table">
      <thead>
        <tr>
          <th>{{ eventTitle }}</th>
          <th v-if="valueTitle" class="col-value">{{ valueTitle }}</th>
          <th>Начало</th>
          <th>Окончание</th>
          <th>Длительность</th>
          <th class="col-actions"><span class="sr-only">Удаление</span></th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="a in rows"
          :key="a.id"
          :class="{ unread: isBold(a), active: !a.endedAt, clickable: a.endedAt && !a.read }"
          @click="onRowClick(a)"
        >
          <td class="col-event">
            <span class="subject">{{ subjectKind(a) }} {{ subjectLabel(a) }}</span>
          </td>
          <td v-if="valueTitle" class="col-value" :data-label="valueTitle">{{ fmtValue(a) }}</td>
          <td data-label="Начало">{{ fmtEventTime(a.startedAt, a.tzOffsetMinutes) }}</td>
          <td data-label="Окончание">{{ a.endedAt ? fmtEventTime(a.endedAt, a.tzOffsetMinutes) : 'продолжается' }}</td>
          <td data-label="Длительность">{{ duration(a) }}</td>
          <td class="col-actions">
            <button
              v-if="a.canDelete"
              class="delete-btn"
              aria-label="Удалить уведомление"
              title="Удалить"
              :disabled="busy"
              @click.stop="remove(a)"
            >
              <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
                <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/>
              </svg>
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listAlarms, markAlarmsSeen, markAlarmRead, markAllAlarmsRead, deleteAlarm, deleteReadAlarms } from '../api/auth'
import { alarmState, clearUnseen, refreshAlarmSummary } from '../store/alarms'
import { connectWs, pushNotification } from '../store/notifications'
import { getIndividualName } from '../store/settings'
import { fmtEventTime } from '../utils/datetime'

const TABS = [
  { type: 'GAP', label: 'Пропуски', tracked: true },
  { type: 'LEVEL', label: 'Уровень', tracked: true },
  { type: 'BATTERY', label: 'Батарейка', tracked: false },
  { type: 'OFFLINE', label: 'Офлайн', tracked: true },
  { type: 'THRESHOLD_V0', label: 'Порог V0', tracked: false },
  { type: 'THRESHOLD_V1', label: 'Порог V1', tracked: false },
  { type: 'THRESHOLD_V2', label: 'Порог V2', tracked: false },
  { type: 'THRESHOLD_V3', label: 'Порог V3', tracked: false },
]

const EVENT_TEXT = {
  GAP: 'не передает данные',
  LEVEL: 'нет погружения в почву',
  BATTERY: 'низкое напряжение батарейки',
  OFFLINE: 'нет связи с блоком',
}

const route = useRoute()
const router = useRouter()
const rows = ref([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const now = ref(Date.now())
let tickTimer = null

const activeType = computed(() =>
  TABS.some(t => t.type === route.query.tab) ? route.query.tab : TABS[0].type)
const activeTab = computed(() => TABS.find(t => t.type === activeType.value))
const hasDeletableRead = computed(() => rows.value.some(a => a.endedAt && a.read && a.canDelete))
const hasUnreadFinished = computed(() => rows.value.some(a => a.endedAt && !a.read))

const menuOpen = ref(false)
const menuRef = ref(null)

function closeMenuOnOutsideClick(e) {
  if (menuOpen.value && !menuRef.value?.contains(e.target)) menuOpen.value = false
}

function closeMenuOnEscape(e) {
  if (e.key === 'Escape') menuOpen.value = false
}

function goBack() {
  if (window.history.state?.back) router.back()
  else router.push('/')
}

function selectTab(type) {
  router.replace({ query: { ...route.query, tab: type } })
}

function isBold(a) {
  return !a.endedAt || !a.read
}

function subjectKind(a) {
  return a.type === 'OFFLINE' ? 'Блок' : 'Датчик'
}

// Firmware default names ("Sens1", "moisture sensor") say nothing: show only the ID then.
function meaningfulName(name) {
  if (!name) return null
  if (/^Sens\d*$/i.test(name) || /moisture sensor/i.test(name)) return null
  return name
}

function subjectLabel(a) {
  const name = getIndividualName(a.chipId) || meaningfulName(a.name)
  return name && String(name) !== String(a.chipId) ? `${name} (${a.chipId})` : String(a.chipId)
}

function eventText(a) {
  return typeEventText(a.type)
}

function typeEventText(type) {
  if (EVENT_TEXT[type]) return EVENT_TEXT[type]
  const m = /^THRESHOLD_(V\d)$/.exec(type)
  return m ? `сработал порог ${m[1]}` : type
}

// Types that carry a reading get their own column; GAP and OFFLINE have none.
function typeValueTitle(type) {
  return type === 'LEVEL' || type === 'BATTERY' || type.startsWith('THRESHOLD_') ? 'Показания' : null
}

const valueTitle = computed(() => typeValueTitle(activeType.value))

function fmtValue(a) {
  if (a.value == null) return '—'
  return a.type === 'LEVEL' ? String(Math.round(a.value)) : String(Math.round(a.value * 100) / 100)
}

// Every row of a tab is the same event, so it names the first column instead of each row.
const eventTitle = computed(() => {
  const text = typeEventText(activeType.value)
  return text.charAt(0).toUpperCase() + text.slice(1)
})

function duration(a) {
  const start = new Date(a.startedAt).getTime()
  const end = a.endedAt ? new Date(a.endedAt).getTime() : now.value
  if (!Number.isFinite(start) || !Number.isFinite(end)) return ''
  const min = Math.floor(Math.max(0, end - start) / 60000)
  if (min < 1) return 'меньше минуты'
  if (min < 60) return `${min} мин`
  const h = Math.floor(min / 60)
  if (h < 24) return min % 60 ? `${h} ч ${min % 60} мин` : `${h} ч`
  const d = Math.floor(h / 24)
  return h % 24 ? `${d} дн ${h % 24} ч` : `${d} дн`
}

function errorText(err) {
  return err?.response?.data?.message || 'Не удалось загрузить уведомления'
}

async function load() {
  const type = activeType.value
  loading.value = true
  error.value = ''
  try {
    const data = await listAlarms(type)
    // The user switched tabs while this was loading: keep the newer tab's list.
    if (type === activeType.value) rows.value = data
  } catch (err) {
    if (type === activeType.value) error.value = errorText(err)
  } finally {
    loading.value = false
  }
}

// Everything shown on this page counts as seen: the bell's dot goes out.
async function markSeen() {
  clearUnseen()
  try {
    await markAlarmsSeen()
  } catch { /* the dot comes back on the next summary; nothing else breaks */ }
}

async function onRowClick(a) {
  if (!a.endedAt || a.read) return
  a.read = true
  try {
    await markAlarmRead(a.id)
  } catch {
    a.read = false
  }
}

async function remove(a) {
  if (!confirm(`Удалить уведомление «${subjectKind(a)} ${subjectLabel(a)} ${eventText(a)}»?`)) return
  busy.value = true
  try {
    await deleteAlarm(a.id)
    rows.value = rows.value.filter(r => r.id !== a.id)
  } catch {
    pushNotification('Не удалось удалить уведомление', 'error')
  } finally {
    busy.value = false
  }
}

async function readAll() {
  menuOpen.value = false
  busy.value = true
  try {
    await markAllAlarmsRead(activeType.value)
    for (const a of rows.value) {
      if (a.endedAt) a.read = true
    }
  } catch {
    pushNotification('Не удалось отметить уведомления прочитанными', 'error')
  } finally {
    busy.value = false
  }
}

async function removeRead() {
  menuOpen.value = false
  if (!confirm(`Удалить все прочитанные уведомления на вкладке «${activeTab.value.label}»?`)) return
  busy.value = true
  try {
    const { deleted } = await deleteReadAlarms(activeType.value)
    pushNotification(`Удалено уведомлений: ${deleted}`)
    await load()
  } catch {
    pushNotification('Не удалось удалить уведомления', 'error')
  } finally {
    busy.value = false
  }
}

watch(activeType, () => {
  menuOpen.value = false
  rows.value = []
  load()
})

// A new or finished alarm while the page is open: show it right away; it is seen as well.
watch(() => alarmState.version, () => {
  load()
  markSeen()
})

onMounted(() => {
  connectWs()
  load()
  markSeen()
  refreshAlarmSummary()
  tickTimer = setInterval(() => { now.value = Date.now() }, 30000)
  document.addEventListener('click', closeMenuOnOutsideClick)
  document.addEventListener('keydown', closeMenuOnEscape)
})

onBeforeUnmount(() => {
  clearInterval(tickTimer)
  document.removeEventListener('click', closeMenuOnOutsideClick)
  document.removeEventListener('keydown', closeMenuOnEscape)
})
</script>

<style scoped>
.alarms { max-width: 900px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; align-items: center; gap: 0.75rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1rem; padding: 1rem; background: var(--bg); }
.header-row h2 { color: var(--text-strong); flex: 1; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.small { font-size: 0.75rem; padding: 0.3rem 0.6rem; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* Chips that wrap onto more lines on a narrow screen: no horizontal scroll, every tab visible. */
.tabs { display: flex; flex-wrap: wrap; gap: 0.4rem; margin-bottom: 0.9rem; }
.tab { display: inline-flex; align-items: center; gap: 0.35rem; padding: 0.4rem 0.75rem; border: 1px solid var(--btn-border); border-radius: 999px; background: var(--btn-bg); color: var(--text-muted); font-size: 0.82rem; cursor: pointer; white-space: nowrap; }
.tab:hover { color: var(--text); background: var(--btn-bg-hover); }
.tab.active { color: var(--text-strong); background: var(--bg-active); border-color: var(--accent); }
.tab:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.tab-count { min-width: 1.2rem; padding: 0 0.3rem; border-radius: 0.6rem; background: var(--danger); color: #fff; font-size: 0.7rem; line-height: 1.2rem; text-align: center; }

.hint { color: var(--text-dim); font-size: 0.72rem; line-height: 1.4; margin-bottom: 0.75rem; }

.menu-wrap { position: relative; flex: none; }
.menu-toggle { width: 2.2rem; height: 2.2rem; padding: 0; border: none; border-radius: 50%; display: flex; align-items: center; justify-content: center; background: transparent; color: var(--text-strong); cursor: pointer; }
.menu-toggle:hover, .menu-toggle[aria-expanded='true'] { background: var(--bg-primary); }
.menu-toggle:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.menu-dropdown { position: absolute; top: calc(100% + 0.4rem); right: 0; z-index: 60; min-width: 220px; padding: 0.35rem; display: flex; flex-direction: column; background: var(--bg-card); border: 1px solid var(--border-soft); border-radius: 10px; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.45); }
.menu-dropdown button { width: 100%; padding: 0.55rem 0.7rem; border: none; border-radius: 6px; display: flex; align-items: center; gap: 0.55rem; background: transparent; color: var(--text); font-size: 0.85rem; text-align: left; white-space: nowrap; cursor: pointer; }
.menu-dropdown button:hover:not(:disabled) { background: var(--bg-primary); }
.menu-dropdown button:disabled { color: var(--btn-disabled-text); cursor: not-allowed; }
.menu-dropdown .menu-danger:not(:disabled) { color: var(--danger); }
.menu-icon { width: 1rem; height: 1rem; flex: none; opacity: 0.9; }
.empty { color: var(--text-dim); font-size: 0.9rem; padding: 1.5rem 0; text-align: center; }
.error { color: var(--danger); font-size: 0.85rem; }

.alarm-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.alarm-table th { text-align: left; color: var(--text-muted); font-weight: normal; font-size: 0.75rem; padding: 0.4rem 0.5rem; border-bottom: 1px solid var(--border-soft); }
.alarm-table td { padding: 0.55rem 0.5rem; border-bottom: 1px solid var(--divider); color: var(--text-sub); vertical-align: top; }
.alarm-table tr.unread td { font-weight: 700; color: var(--text-strong); }
.alarm-table tr.active td:first-child { box-shadow: inset 3px 0 0 var(--danger); }
.alarm-table tr.clickable { cursor: pointer; }
.alarm-table tr.clickable:hover td { background: var(--bg-soft); }
.col-value { white-space: nowrap; }
.mobile-caption { display: none; }
.col-actions { width: 2.5rem; text-align: right; }
.delete-btn { display: inline-flex; padding: 0.25rem; border: none; border-radius: 6px; background: transparent; color: var(--text-dim); cursor: pointer; }
.delete-btn:hover:not(:disabled) { color: var(--danger); background: var(--bg-soft); }
.delete-btn:focus-visible { outline: 2px solid var(--accent); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

/* Phone: each alarm becomes a small card instead of a five-column row. */
@media (max-width: 600px) {
  .alarm-table thead { display: none; }
  .mobile-caption { display: block; color: var(--text-muted); font-size: 0.8rem; padding-bottom: 0.4rem; border-bottom: 1px solid var(--border-soft); }
  .alarm-table, .alarm-table tbody, .alarm-table tr, .alarm-table td { display: block; width: 100%; }
  .alarm-table tr { position: relative; padding: 0.55rem 2.5rem 0.55rem 0.6rem; border-bottom: 1px solid var(--divider); }
  .alarm-table tr.active { box-shadow: inset 3px 0 0 var(--danger); }
  .alarm-table tr.active td:first-child { box-shadow: none; }
  .alarm-table td { padding: 0.1rem 0; border: none; }
  .alarm-table td[data-label]::before { content: attr(data-label) ': '; color: var(--text-dim); font-weight: normal; }
  .alarm-table td.col-event { margin-bottom: 0.2rem; }
  .alarm-table td.col-actions { position: absolute; top: 0.45rem; right: 0.3rem; width: auto; }
}
</style>
