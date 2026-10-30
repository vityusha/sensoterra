<template>
  <div class="dashboard">
    <div class="header-row">
      <h2>Устройства</h2>
      <div class="header-actions">
        <span class="connection-status" :class="connection.online ? 'online' : 'offline'">
          {{ connection.online ? 'Сервис OK' : 'Сервис недоступен' }}
        </span>
        <span class="user-info" v-if="auth.user">{{ auth.user.role }}</span>
        <button v-if="showClaimButton" @click="claimOpen = !claimOpen" class="btn small">+ Устройство</button>
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
            <button role="menuitem" @click="go('/settings')">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/>
              </svg>
              Настройки
            </button>
            <button role="menuitem" @click="go('/profile')">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
              </svg>
              Профиль
            </button>
            <button role="menuitem" @click="openApDialog">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z"/>
              </svg>
              Настройка в AP режиме
            </button>
            <button v-if="auth.user?.role === 'ADMIN'" role="menuitem" @click="go('/admin/users')">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"/>
              </svg>
              Пользователи
            </button>
            <div class="menu-sep"></div>
            <button role="menuitem" class="menu-danger" @click="handleLogout">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z"/>
              </svg>
              Выйти
            </button>
          </div>
        </div>
      </div>
    </div>


    <div v-if="needsClaim || claimOpen" class="activation-banner">
      <div class="activation-text">
        <template v-if="needsClaim">
          Устройство не привязано. Введите ID и ключ с комплектного листа, который шёл
          вместе с блоком.
        </template>
        <template v-else>
          Ещё один блок? Введите ID и ключ с его комплектного листа — он появится
          в списке. Повторный ввод того же ключа ничего не ломает.
        </template>
      </div>
      <form class="activation-form" @submit.prevent="submitClaim">
        <input v-model="claimForm.chipId" placeholder="ID устройства" inputmode="numeric" />
        <input v-model="claimForm.key" placeholder="XXXX-XXXX-XXXX" autocapitalize="characters"
          autocorrect="off" spellcheck="false" />
        <button type="submit" :disabled="claiming || !claimForm.chipId.trim() || !claimForm.key.trim()">
          {{ claiming ? '...' : 'Привязать' }}
        </button>
      </form>
      <div v-if="claimError" class="activation-error">{{ claimError }}</div>
    </div>

    <div v-if="viewedUserId" class="user-filter-banner">
      <span class="user-filter-text">
        Устройства пользователя <b>{{ viewedUser?.username ?? '...' }}</b>
      </span>
      <button class="btn small" @click="showAllDevices">Показать все</button>
    </div>

    <div v-if="loading || viewedLoading" class="loading">Загрузка...</div>
    <template v-else>
      <section class="section">
        <div class="section-head">
          <h3>Блоки управления</h3>
          <span v-if="blocks.length" class="section-count">{{ filteredBlocks.length }} из {{ blocks.length }}</span>
        </div>
        <div v-if="blocks.length === 0" class="empty small">Нет блоков</div>
        <template v-else>
          <div class="list-tools">
            <input v-model="blockSearch" class="search-input" type="search" inputmode="numeric"
              placeholder="Поиск по ID" aria-label="Поиск блоков по ID" />
            <button v-if="blockSearch" class="btn small" @click="blockSearch = ''">Сброс</button>
          </div>
          <div v-if="filteredBlocks.length === 0" class="empty small">По ID ничего не найдено</div>
          <div class="device-list" v-else>
            <div class="device-card" v-for="d in pagedBlocks" :key="d.chipId"
              :class="{ locked: isLocked(d.chipId) }" @click="$router.push(`/device/${d.chipId}`)">
              <div class="device-icon block">
                <span class="status-dot" :class="isOnline(d.chipId) ? 'online' : 'offline'"></span>
                B
              </div>
              <div class="device-info">
                <div class="device-name">
                  <template v-if="editing === d.chipId">
                    <input class="name-input" v-model="editName" @click.stop @keyup.enter="saveBlockName(d.chipId)" @keyup.esc="editing=null" />
                  </template>
                  <template v-else>
                    <span @click.stop="startEdit(d.chipId, 'block')">{{ getBlockName(d.chipId) || 'Имя блока' }}</span>
                    <span v-if="d.ownerUsername" class="device-owner"> ({{ d.ownerUsername }})</span>
                  </template>
                </div>
                <div class="device-type block"></div>
                <!-- Оплата у блока своя: у одного истёк, у другого впереди. -->
                <div class="pay-badge" :class="payClass(d.chipId)">{{ payLabel(d.chipId) }}</div>
              </div>
              <div class="device-chip">{{ d.chipId }}</div>
              <button
                v-if="auth.user?.role === 'ADMIN'"
                class="btn danger small"
                @click.stop="removeBlock(d)"
              >Удалить</button>
            </div>
          </div>
          <Paginator v-if="blockPages > 1" v-model:page="blockPage" :pages="blockPages" label="Страницы блоков" />
        </template>
      </section>

      <section class="section">
        <div class="section-head">
          <h3>Датчики блоков</h3>
          <span v-if="blockSensors.length" class="section-count">{{ filteredBlockSensors.length }} из {{ blockSensors.length }}</span>
        </div>
        <div v-if="blockSensors.length === 0" class="empty small">Нет датчиков блоков</div>
        <template v-else>
          <div class="list-tools">
            <input v-model="blockSensorSearch" class="search-input" type="search" inputmode="numeric"
              placeholder="Поиск по ID датчика" aria-label="Поиск датчиков блоков по ID" />
            <button v-if="blockSensorSearch" class="btn small" @click="blockSensorSearch = ''">Сброс</button>
          </div>
          <div v-if="filteredBlockSensors.length === 0" class="empty small">По ID ничего не найдено</div>
          <div class="device-list" v-else>
            <div
              class="device-card"
              v-for="s in pagedBlockSensors"
              :key="s.chipId"
              :class="{ error: s.conflict }"
              @click="openBlockSensor(s)"
            >
              <div class="device-icon" :class="s.conflict ? 'conflict' : 'block'">
                {{ s.conflict ? '!' : 'SB' }}
              </div>
              <div class="device-info">
                <div class="device-name">{{ blockSensorName(s) }}</div>
                <div class="device-tags">
                  <span class="tag block-tag" v-for="b in s.blockRoles" :key="b.blockId">
                    блок {{ getBlockName(b.blockId) ? getBlockName(b.blockId) + ' (' + b.blockId + ')' : b.blockId }} · S{{ b.slot }}
                  </span>
                </div>
                <div class="device-type" v-if="s.conflict">
                  Ошибка: зарегистрирован на {{ s.blockRoles.length }} блока. Выберите блок, где оставить регистрацию.
                </div>
                <div class="sensor-config" v-if="sensorConfigText(s)">
                  <span class="config-text">{{ sensorConfigText(s) }}</span>
                </div>
              </div>
              <div class="device-chip">{{ s.chipId }}</div>
            </div>
          </div>
          <Paginator v-if="blockSensorPages > 1" v-model:page="blockSensorPage" :pages="blockSensorPages" label="Страницы датчиков блоков" />
        </template>
      </section>

      <section class="section">
        <div class="section-head">
          <h3>Автономные датчики</h3>
          <span v-if="individualSensors.length" class="section-count">{{ filteredIndividualSensors.length }} из {{ individualSensors.length }}</span>
        </div>
        <div v-if="individualSensors.length === 0" class="empty small">Нет автономных датчиков</div>
        <template v-else>
          <div class="list-tools">
            <input v-model="individualSearch" class="search-input" type="search" inputmode="numeric"
              placeholder="Поиск по ID датчика" aria-label="Поиск автономных датчиков по ID" />
            <button v-if="individualSearch" class="btn small" @click="individualSearch = ''">Сброс</button>
          </div>
          <div v-if="filteredIndividualSensors.length === 0" class="empty small">По ID ничего не найдено</div>
          <div class="device-list" v-else>
            <div class="device-card" v-for="s in pagedIndividualSensors" :key="s.chipId" @click="router.push(`/sensor/${s.chipId}`)">
              <div class="device-icon sensor">SA</div>
              <div class="device-info">
                <div class="device-name">
                  <template v-if="editing === s.chipId">
                    <input class="name-input" v-model="editName" @click.stop @keyup.enter="saveIndividualName(s.chipId)" @keyup.esc="editing=null" />
                  </template>
                  <template v-else>
                    <span @click.stop="startEdit(s.chipId, 'individual')">{{ individualSensorName(s) }}</span>
                    <span v-if="ownerOf(s.chipId)" class="device-owner"> ({{ ownerOf(s.chipId) }})</span>
                  </template>
                </div>
                <div class="device-tags">
                  <span class="tag">автономный</span>
                </div>
                <div class="sensor-config" v-if="sensorConfigText(s)">
                  <span class="config-text">{{ sensorConfigText(s) }}</span>
                </div>
              </div>
              <div class="device-chip">{{ s.chipId }}</div>
              <button
                v-if="auth.user?.role === 'ADMIN'"
                class="btn danger small"
                @click.stop="removeSensor(s)"
              >Удалить</button>
            </div>
          </div>
          <Paginator v-if="individualPages > 1" v-model:page="individualPage" :pages="individualPages" label="Страницы автономных датчиков" />
        </template>
      </section>
    </template>

    <div v-if="apDialog" class="modal-overlay" @click.self="apDialog = false">
      <div class="modal">
        <h3>Настройка в AP режиме</h3>
        <p class="modal-sub">
          Переведите блок или автономный датчик в AP режим и подключитесь к его WiFi-сети.
          После этого нажмите «Открыть страницу настройки» — откроется страница устройства
          по адресу 192.168.4.1.
        </p>
        <div class="modal-actions ap-actions">
          <button class="btn small secondary" @click="apDialog = false">Отмена</button>
          <button class="btn small save" @click="openApPage">Открыть страницу настройки</button>
        </div>
      </div>
    </div>

    <div v-if="conflictDialog" class="modal-overlay" @click.self="conflictDialog=null">
      <div class="modal">
        <h3>Конфликт датчика</h3>
        <p class="modal-sub">
          {{ blockSensorName(conflictDialog) }} ({{ conflictDialog.chipId }})
          зарегистрирован на {{ dialogBlocks.length }} блоках.
          Выберите блок, где оставить регистрацию. Остальным будет отправлена команда
          UNREG; конфликт исчезнет, когда блоки переопубликуют SENSORS.
        </p>
        <label class="modal-option" v-for="b in dialogBlocks" :key="b.blockId">
          <input type="radio" :value="b.blockId" v-model="keepBlockId" />
          <span class="opt-label">блок {{ getBlockName(b.blockId) ? getBlockName(b.blockId) + ' (' + b.blockId + ')' : b.blockId }} · S{{ b.slot }}</span>
          <span v-if="!isOnline(b.blockId)" class="opt-warn">офлайн — команда применится после переподключения</span>
        </label>
        <div class="modal-actions">
          <button class="btn small secondary" @click="conflictDialog=null">Отмена</button>
          <button class="btn small" :disabled="!keepBlockId || resolving" @click="resolveConflict">Подтвердить</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Paginator from '../components/Paginator.vue'
import { usePager } from '../composables/usePager'
import { getUser, getUserDevices, listDevices, getSensorCatalog, getDeviceStatuses, deleteDevice, resolveSensorConflict, claimDevice, listPayments } from '../api/auth'
import { auth, logout, devicePayment, rememberPayment } from '../store/auth'
import { paymentLabel } from '../utils/payment'
import { getBlockName, setBlockName, getIndividualName, setIndividualName } from '../store/settings'
import { connection, pushNotification, onWsMessage, connectWs } from '../store/notifications'
import { initMissedTimings, prefetchRelayNames } from '../store/notifications'

const router = useRouter()
const route = useRoute()
const devices = ref([])
const catalog = ref([])
const statusMap = ref({})
const loading = ref(true)
const editing = ref(null)

// Оплата — свойство блока, поэтому смотрим её по каждому блоку отдельно.
function payInfo(chipId) {
  return devicePayment[chipId]
}
function isLocked(chipId) {
  const info = payInfo(chipId)
  return !!info && info.chargeable !== false && info.paid === false
}
function payLabel(chipId) {
  return paymentLabel(payInfo(chipId)) || '—'
}
function payClass(chipId) {
  const info = payInfo(chipId)
  if (!info || info.chargeable === false) return 'free'
  if (info.unlimited || info.paid) return 'ok'
  return 'no'
}

// Устройств нет вовсе — новый покупатель без блока: нужен ID + ключ с листа.
const needsClaim = computed(() => !!auth.user && auth.user.role === 'USER' && devices.value.length === 0)
// Кнопка нужна ровно тогда, когда баннер уже не показывается, но привязать ещё есть что.
const showClaimButton = computed(
  () => !!auth.user && auth.user.role === 'USER' && devices.value.length > 0)
const claimForm = reactive({ chipId: '', key: '' })
const claimOpen = ref(false)
const claimError = ref('')
const claiming = ref(false)

async function submitClaim() {
  claiming.value = true
  claimError.value = ''
  try {
    try {
      await claimDevice(Number(claimForm.chipId), claimForm.key.trim())
      claimForm.chipId = ''
      claimForm.key = ''
      claimOpen.value = false
      pushNotification('Устройство привязано к вашему аккаунту')
    } catch (e) {
      claimError.value = e.response?.data?.message || 'ID или ключ не подошли'
      return
    }
    // Список перечитывается отдельным шагом: его ошибка не должна выглядеть как «не тот ключ».
    try {
      await refreshAll()
    } catch {
      pushNotification('Не удалось обновить список — обновите страницу')
    }
  } finally {
    claiming.value = false
  }
}

const editName = ref('')
const conflictDialog = ref(null)
const keepBlockId = ref(null)
const resolving = ref(false)
const apDialog = ref(false)
const menuOpen = ref(false)
const menuRef = ref(null)

function go(path) {
  menuOpen.value = false
  router.push(path)
}

function openApDialog() {
  menuOpen.value = false
  apDialog.value = true
}

function closeMenuOnOutsideClick(e) {
  if (menuOpen.value && !menuRef.value?.contains(e.target)) menuOpen.value = false
}

function closeMenuOnEscape(e) {
  if (e.key === 'Escape') menuOpen.value = false
}

function openApPage() {
  window.open('http://192.168.4.1', '_blank')
  apDialog.value = false
}

// Admin opened one user's devices from the users page (/?user=<id>); the admin's own
// list and catalog already hold everything, so this only narrows what is shown.
const viewedUserId = computed(() => Number(route.query.user) || null)
const viewedUser = ref(null)
const viewedChips = ref(null)
const viewedLoading = ref(false)

watch(viewedUserId, async (id) => {
  viewedUser.value = null
  viewedChips.value = null
  viewedLoading.value = !!id
  if (!id) return
  try {
    const [user, userDevices] = await Promise.all([getUser(id), getUserDevices(id)])
    if (id !== viewedUserId.value) return
    viewedUser.value = user
    viewedChips.value = new Set(userDevices.map(d => d.chipId))
  } catch {
    if (id !== viewedUserId.value) return
    pushNotification('Не удалось загрузить устройства пользователя', 'error')
    showAllDevices()
  } finally {
    if (id === viewedUserId.value) viewedLoading.value = false
  }
}, { immediate: true })

function showAllDevices() {
  router.replace({ path: '/' })
}

const visibleDevices = computed(() => {
  const chips = viewedChips.value
  return chips ? devices.value.filter(d => chips.has(d.chipId)) : devices.value
})
// A block sensor belongs to the user through the block it is registered on.
const visibleCatalog = computed(() => {
  const chips = viewedChips.value
  if (!chips) return catalog.value
  return catalog.value.filter(c => chips.has(c.chipId) || c.blockRoles?.some(r => chips.has(r.blockId)))
})

const blocks = computed(() => visibleDevices.value.filter(d => d.deviceType === 'BLOCK'))
const individualSensors = computed(() => visibleCatalog.value.filter(c => c.individual))
const blockSensors = computed(() => visibleCatalog.value.filter(c => !c.individual))

function useDevicePager(source, query) {
  return usePager(source, query, (item, q) => String(item.chipId).toLowerCase().includes(q))
}

const blockSearch = ref('')
const {
  page: blockPage,
  filtered: filteredBlocks,
  pages: blockPages,
  paged: pagedBlocks,
} = useDevicePager(blocks, blockSearch)

const blockSensorSearch = ref('')
const {
  page: blockSensorPage,
  filtered: filteredBlockSensors,
  pages: blockSensorPages,
  paged: pagedBlockSensors,
} = useDevicePager(blockSensors, blockSensorSearch)

const individualSearch = ref('')
const {
  page: individualPage,
  filtered: filteredIndividualSensors,
  pages: individualPages,
  paged: pagedIndividualSensors,
} = useDevicePager(individualSensors, individualSearch)

const ownerByChip = computed(() => {
  const map = {}
  for (const d of devices.value) {
    if (d.ownerUsername) map[String(d.chipId)] = d.ownerUsername
  }
  return map
})

function ownerOf(chipId) {
  return ownerByChip.value[String(chipId)] || ''
}

const dialogBlocks = computed(() => {
  const seen = new Set()
  const out = []
  for (const b of conflictDialog.value?.blockRoles || []) {
    if (seen.has(b.blockId)) continue
    seen.add(b.blockId)
    out.push(b)
  }
  return out
})

let wsUnsub = null
let liveTimer = null
let liveBusy = false
let catalogRefreshTimer = null
/**
 * Полное перечитывание списка. Вынесено из onMounted, потому что нужно и после привязки
 * блока по ключу: новый блок появляется только с сервера, локально его неоткуда взять.
 */
async function refreshAll() {
  const [devs, cats, statuses] = await Promise.all([listDevices(), getSensorCatalog(), getDeviceStatuses()])
  devices.value = devs
  // Оплата каждого блока: без неё нельзя отличить «срок истёк» от «ещё не подтверждён».
  // Список не тарифицируется и при закрытой оплате, поэтому запрос всегда успешен.
  listPayments().then(list => {
    for (const info of list) rememberPayment(info)
  }).catch(() => { /* счётчик просто не покажем */ })
  catalog.value = cats
  initMissedTimings(cats)
  prefetchRelayNames(devs)
  const map = {}
  for (const s of statuses) map[s.chipId] = s
  statusMap.value = map
}

onMounted(async () => {
  connectWs()
  try {
    await refreshAll()
  } finally {
    loading.value = false
  }
  wsUnsub = onWsMessage((msg) => {
    if (msg.type === 'status') {
      const wid = typeof msg.deviceId !== 'undefined' ? msg.deviceId : msg.chipId
      if (!statusMap.value[wid] && !devices.value.some(d => String(d.chipId) === String(wid))) return
      const cur = statusMap.value[wid] || { chipId: wid }
      cur.status = msg.status
      cur.timestamp = msg.timestamp
      statusMap.value[wid] = { ...cur }
    }
    if (msg.type === 'sensor') scheduleCatalogRefresh()
    if (msg.type === 'sensors') {
      refreshCatalog()
      refreshStatuses()
    }
  })
  liveTimer = setInterval(refreshLive, 10000)
  document.addEventListener('visibilitychange', onVisibilityChange)
  document.addEventListener('click', closeMenuOnOutsideClick)
  document.addEventListener('keydown', closeMenuOnEscape)
  window.addEventListener('online', onOnline)
})

onBeforeUnmount(() => {
  if (wsUnsub) wsUnsub()
  if (liveTimer) clearInterval(liveTimer)
  if (catalogRefreshTimer) clearTimeout(catalogRefreshTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  document.removeEventListener('click', closeMenuOnOutsideClick)
  document.removeEventListener('keydown', closeMenuOnEscape)
  window.removeEventListener('online', onOnline)
})

function isOnline(chipId) {
  return statusMap.value[chipId]?.status === 'online'
}

function openBlockSensor(s) {
  if (s.conflict) {
    conflictDialog.value = s
    keepBlockId.value = null
    return
  }
  router.push(`/sensor/${s.chipId}?deviceId=${s.blockRoles?.[0]?.blockId ?? s.chipId}`)
}

async function resolveConflict() {
  if (!conflictDialog.value || !keepBlockId.value) return
  resolving.value = true
  try {
    const res = await resolveSensorConflict(conflictDialog.value.chipId, keepBlockId.value)
    const unregistered = res?.unregisteredBlocks || []
    const failed = res?.failedBlocks || []
    const offline = res?.offlineBlocks || []
    let text = `Датчик ${conflictDialog.value.chipId}: команда UNREG отправлена${unregistered.length ? ' блокам ' + unregistered.join(', ') : ' не отправлена ни одному блоку'}.`
    if (failed.length) text += ` Не удалось отправить на ${failed.join(', ')}.`
    if (offline.length) text += ` Блоки офлайн, применят после переподключения: ${offline.join(', ')}.`
    pushNotification(text, failed.length ? 'warn' : 'success')
    conflictDialog.value = null
    await Promise.all([refreshCatalog(), refreshStatuses()])
  } catch (e) {
    pushNotification(deleteErrorMessage(e, 'Не удалось разрешить конфликт'), 'error')
  } finally {
    resolving.value = false
  }
}


function blockSensorName(s) {
  if (s.name && !/^Sens\d*$/i.test(s.name) && !/moisture sensor/i.test(s.name)) return s.name
  const slot = s.blockRoles?.[0]?.slot
  return slot != null ? `Датчик S${slot}` : (s.name || s.chipId)
}

function individualSensorName(s) {
  const custom = getIndividualName(s.chipId)
  if (custom) return custom
  if (s.name && !/^Sens\d*$/i.test(s.name) && !/moisture sensor/i.test(s.name)) return s.name
  return 'Имя датчика'
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

function startEdit(chipId, type) {
  editing.value = chipId
  if (type === 'block') editName.value = getBlockName(chipId) || ''
  else editName.value = getIndividualName(chipId) || ''
}

function saveBlockName(chipId) {
  setBlockName(chipId, editName.value)
  editing.value = null
}

function saveIndividualName(chipId) {
  setIndividualName(chipId, editName.value)
  editing.value = null
}

function handleLogout() {
  menuOpen.value = false
  logout()
  router.push('/login')
}

function scheduleCatalogRefresh() {
  if (catalogRefreshTimer) return
  catalogRefreshTimer = setTimeout(() => {
    catalogRefreshTimer = null
    refreshCatalog().catch(() => {})
  }, 500)
}

async function refreshCatalog() {
  const cats = await getSensorCatalog()
  catalog.value = cats
  initMissedTimings(cats)
}

async function refreshStatuses() {
  const statuses = await getDeviceStatuses()
  const map = {}
  for (const s of statuses) map[s.chipId] = s
  statusMap.value = map
}

async function refreshLive() {
  if (document.visibilityState === 'hidden' || liveBusy) return
  liveBusy = true
  try {
    await Promise.all([refreshCatalog(), refreshStatuses()])
  } catch {
  } finally {
    liveBusy = false
  }
}

function onVisibilityChange() {
  if (document.visibilityState !== 'visible') return
  connectWs()
  refreshAll().catch(() => {})
}

function onOnline() {
  connectWs()
  refreshAll().catch(() => {})
}

function deleteErrorMessage(e, fallback) {
  const status = e?.response?.status
  if (status === 401) return 'Сессия истекла или не передана — войдите заново'
  if (status === 403) return 'Недостаточно прав'
  return e?.response?.data?.message || fallback
}

async function removeSensor(s) {
  const name = individualSensorName(s)
  const message = `Удалить датчик ${name} (${s.chipId})?\n\n`
    + 'Будут стёрты все его данные: телеметрия, история и настройки. '
    + 'Также будет сброшено retain на MQTT-топиках датчика, чтобы он не появился снова. '
    + 'Если датчик продолжит передавать данные, он появится заново как новое устройство.'
  if (!confirm(message)) return
  try {
    const result = await deleteDevice(s.chipId)
    const cleared = result.retainedTopicsCleared ?? 0
    const total = result.retainedTopicsTotal ?? 0
    pushNotification(`Датчик ${s.chipId} удалён. Retain сброшен на ${cleared} из ${total} топиков.`, 'success')
    await Promise.all([refreshCatalog(), refreshStatuses(), listDevices().then(d => { devices.value = d })])
  } catch (e) {
    pushNotification(deleteErrorMessage(e, 'Не удалось удалить датчик'), 'error')
  }
}

async function removeBlock(d) {
  const name = getBlockName(d.chipId) || d.chipId
  const attached = catalog.value
    .filter(c => !c.individual && c.blockRoles?.some(r => String(r.blockId) === String(d.chipId)))
  const label = c => `${c.chipId}${blockSensorName(c) ? ' (' + blockSensorName(c) + ')' : ''}`
  const exclusive = attached.filter(c => (c.blockRoles?.length ?? 0) <= 1)
  const shared = attached.filter(c => (c.blockRoles?.length ?? 0) > 1)
  const message = `УДАЛИТЬ БЛОК ${name} (${d.chipId})?\n\n`
    + 'Действие необратимо. Из базы будет стёрто:\n'
    + (exclusive.length ? `• датчики этого блока целиком: ${exclusive.map(label).join(', ')}\n` : '• датчики этого блока (если есть)\n')
    + (shared.length
      ? `• слоты этого блока у датчиков, занятых и на других блоках: ${shared.map(label).join(', ')} — сами датчики и их данные сохранятся\n`
      : '')
    + '• каналы реле и вся история их переключений;\n'
    + '• телеметрия блока, журнал статусов и сценарии автоматизации;\n'
    + '• retain на MQTT-топиках блока и его эксклюзивных датчиков.\n\n'
    + 'Если блок физически подключён, он появится заново как новое устройство.'
  if (!confirm(message)) return
  try {
    const result = await deleteDevice(d.chipId)
    const cleared = result.retainedTopicsCleared ?? 0
    const total = result.retainedTopicsTotal ?? 0
    const kept = result.sharedSensorChipIds?.length ?? 0
    const removed = (result.sensorChipIds?.length ?? 0) - kept
    pushNotification(`Блок ${d.chipId} удалён. Датчиков удалено: ${removed}, сохранено: ${kept}. Retain сброшен на ${cleared} из ${total} топиков.`, 'success')
    await Promise.all([refreshCatalog(), refreshStatuses(), listDevices().then(d2 => { devices.value = d2 })])
  } catch (e) {
    pushNotification(deleteErrorMessage(e, 'Не удалось удалить блок'), 'error')
  }
}
</script>

<style scoped>
.dashboard { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1.5rem; padding: 1rem; background: var(--bg); }
h2 { color: var(--text-strong); }
.header-actions { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
.connection-status { font-size: 0.7rem; padding: 0.15rem 0.5rem; border-radius: 4px; }
.connection-status.online { background: var(--tag-bg-ok); color: var(--success); }
.connection-status.offline { background: var(--bg-accent); color: var(--danger); }
.user-info { color: var(--text-dim); font-size: 0.8rem; }
.menu-wrap { position: relative; margin-left: auto; }
.menu-toggle { width: 2.2rem; height: 2.2rem; padding: 0; border: none; border-radius: 50%; display: flex; align-items: center; justify-content: center; background: transparent; color: var(--text-strong); cursor: pointer; }
.menu-toggle:hover, .menu-toggle[aria-expanded='true'] { background: var(--bg-primary); color: var(--text-strong); }
.menu-dropdown { position: absolute; top: calc(100% + 0.4rem); right: 0; z-index: 60; min-width: 220px; padding: 0.35rem; display: flex; flex-direction: column; background: var(--bg-card); border: 1px solid var(--border-soft); border-radius: 10px; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.45); }
.menu-dropdown button { width: 100%; padding: 0.55rem 0.7rem; border: none; border-radius: 6px; display: flex; align-items: center; gap: 0.55rem; background: transparent; color: var(--text); font-size: 0.85rem; text-align: left; white-space: nowrap; cursor: pointer; }
.menu-dropdown button:hover { background: var(--bg-primary); }
.menu-dropdown .menu-danger { color: var(--danger); }
.menu-icon { width: 1rem; height: 1rem; flex: none; opacity: 0.9; }
.menu-sep { height: 1px; margin: 0.3rem 0; background: var(--border-soft); }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.secondary { background: var(--bg-accent); }
.btn.danger { background: var(--bg-accent); color: var(--on-accent); flex-shrink: 0; }
.btn.danger:hover { background: #7a2222; color: #fff; }
.device-card:not(.locked) > .btn.danger { opacity: 0.75; }
.btn.small { padding: 0.25rem 0.5rem; font-size: 0.7rem; }
.loading, .empty { color: var(--text-dim); text-align: center; padding: 2rem; }

.pay-badge { font-size: 0.75rem; margin-top: 2px; }
.pay-badge.ok { color: var(--success); }
.pay-badge.no { color: var(--danger); }
.pay-badge.free { color: var(--text-faint); }
.device-card.locked { opacity: 0.75; }
.device-card.locked .device-name { color: var(--warn); }
.empty.small { padding: 1rem; font-size: 0.85rem; }
.section { margin-bottom: 2rem; }
.section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 0.75rem; margin-bottom: 0.75rem; }
.section h3 { color: var(--text-muted); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 0; }
.section-count { color: var(--text-faint); font-size: 0.72rem; white-space: nowrap; }
.list-tools { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.75rem; }
.search-input { flex: 1; min-width: 0; padding: 0.42rem 0.6rem; border: 1px solid var(--border-soft); border-radius: 8px; background: var(--bg); color: var(--text); font-size: 0.8rem; }
.search-input:focus { outline: none; border-color: var(--accent); }
.search-input::placeholder { color: var(--text-faint); }
.device-list { display: flex; flex-direction: column; gap: 0.75rem; }
.device-card { display: flex; align-items: center; gap: 1rem; padding: 1rem; background: var(--bg-card); border-radius: 12px; cursor: pointer; box-shadow: var(--card-shadow); transition: background 0.2s; }
.device-card:hover { background: var(--bg-primary); }
.device-card.error { border: 1px solid var(--danger); }
.device-card.error:hover { background: var(--bg-accent); }
.device-icon { width: 40px; height: 40px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 1.1rem; color: #fff; flex-shrink: 0; position: relative; }
.device-icon.block { background: var(--tag-bg-ok); }
.device-icon.sensor { background: var(--tag-bg); }
.device-icon.conflict { background: var(--bg-accent); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; position: absolute; top: 4px; right: 4px; }
.status-dot.online { background: var(--success); }
.status-dot.offline { background: var(--danger); }
.device-info { flex: 1; min-width: 0; }
.device-name { color: var(--text); font-weight: 500; }
.device-name span { cursor: pointer; }
.device-name span:hover { color: var(--accent); }
.device-name .device-owner { color: var(--text-dim); font-weight: 400; font-size: 0.8rem; cursor: default; }
.device-name .device-owner:hover { color: var(--text-dim); }
.name-input { background: var(--bg); border: 1px solid var(--accent); border-radius: 4px; padding: 0.2rem 0.5rem; color: var(--text); font-size: 0.9rem; width: 100%; }
.device-tags { display: flex; flex-wrap: wrap; gap: 0.3rem; margin-top: 0.3rem; }
.sensor-config { margin-top: 0.35rem; }
.config-text { font-size: 0.7rem; color: var(--tag-text); font-family: inherit; }
.tag { font-size: 0.7rem; padding: 0.15rem 0.45rem; border-radius: 4px; background: var(--tag-bg); color: var(--tag-text); }
.tag.block-tag { background: var(--tag-bg-ok); color: var(--tag-text-ok); }
.device-type { color: var(--danger); font-size: 0.75rem; margin-top: 0.3rem; }
.device-chip { color: var(--text-faint); font-size: 0.75rem; font-family: monospace; }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 100; padding: 1rem; }
.modal { background: var(--bg-card); border-radius: 12px; padding: 1.25rem; max-width: 420px; width: 100%; box-shadow: 0 8px 30px rgba(0,0,0,0.5); }
.modal h3 { color: var(--text-strong); margin: 0 0 0.5rem; }
.modal-sub { color: var(--text-dim); font-size: 0.8rem; margin: 0 0 0.5rem; line-height: 1.4; }
.modal-option { display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem; border-radius: 8px; cursor: pointer; }
.modal-option:hover { background: var(--bg-primary); }
.modal-option input { accent-color: var(--accent); flex-shrink: 0; }
.opt-label { color: var(--text); font-size: 0.85rem; }
.opt-warn { color: var(--danger); font-size: 0.7rem; margin-left: auto; text-align: right; }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.ap-actions .btn { min-height: 2.5rem; padding: 0.45rem 0.9rem; }
.user-filter-banner { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; background: var(--banner-bg); border: 1px solid var(--banner-border); border-radius: 12px; padding: 0.6rem 1rem; margin-bottom: 1rem; }
.user-filter-text { color: var(--banner-text); font-size: 0.85rem; min-width: 0; overflow-wrap: anywhere; }
.activation-banner { background: var(--banner-bg); border: 1px solid var(--banner-border); border-radius: 12px; padding: 0.9rem 1rem; margin-bottom: 1rem; }
.activation-text { color: var(--banner-text); font-size: 0.85rem; line-height: 1.4; margin-bottom: 0.6rem; }
.activation-form { display: flex; gap: 0.5rem; flex-wrap: wrap; }
.activation-form input { flex: 1; min-width: 160px; padding: 0.45rem 0.6rem; border-radius: 6px; border: 1px solid var(--banner-border); background: var(--bg); color: var(--text); font-size: 0.85rem; font-family: monospace; text-transform: uppercase; }
.activation-form button { padding: 0.45rem 0.9rem; border: none; border-radius: 6px; background: var(--warn); color: var(--bg-card); font-size: 0.85rem; font-weight: 600; cursor: pointer; }
.activation-form button:disabled { opacity: 0.6; }
.activation-error { color: var(--on-accent); font-size: 0.8rem; margin-top: 0.5rem; }

/* Узкий экран: карточка устройства в две строки, иначе иконка/имя/chipId/кнопка слипаются. */
@media (max-width: 480px) {
  .device-card { flex-wrap: wrap; row-gap: 0.5rem; }
  .device-card > .btn { margin-left: auto; }
  .device-icon { width: 34px; height: 34px; font-size: 0.95rem; }
  .header-row { flex-wrap: wrap; row-gap: 0.5rem; }
  .header-actions { width: 100%; }
}
</style>
