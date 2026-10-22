<template>
  <div class="admin">
    <div class="header-row">
      <button @click="$router.push('/')" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>Пользователи</h2>
    </div>

    <!-- Админ заводит только других админов. Покупатели регистрируются сами и подтверждают
         свой блок ключом с комплектного листа, поэтому заводить их отсюда незачем. -->
    <form @submit.prevent="create" class="create-form">
      <input v-model="form.username" placeholder="Логин админа" required />
      <input v-model="form.password" placeholder="Пароль" type="password" required />
      <button type="submit" class="btn">Создать админа</button>
    </form>
    <p class="hint">
      Покупатель регистрируется сам: логин, пароль и пара «ID + ключ» с листа, идущего
      вместе с блоком. Права «только чтение» выдаёт владелец устройства — в карточке блока.
    </p>

    <!-- Срок по умолчанию для всех блоков без своего плана. Конкретный блок настраивается
         в его карточке: там же продление и отмена всех регистраций. -->
    <section class="card defaults">
      <div class="defaults-head">
        <h3>Срок оплаты по умолчанию</h3>
        <span class="defaults-current">{{ defaults.unlimited ? 'бессрочно' : `${defaults.days} дн.` }}</span>
      </div>
      <div class="defaults-row">
        <label>
          дней
          <input v-model="defaults.days" type="number" min="1" :disabled="defaults.unlimited" />
        </label>
        <label class="check">
          <input type="checkbox" v-model="defaults.unlimited" /> бессрочно
        </label>
        <button class="btn small save" @click="saveDefaults" :disabled="defaultsBusy">
          {{ defaultsBusy ? '...' : 'Сохранить' }}
        </button>
      </div>
      <p class="note">
        Применяется к блокам без собственного плана. Срок идёт с первого подтверждения
        «ID + ключ» и общий для всех, кто имеет доступ к блоку.
      </p>
    </section>

    <div class="user-list">
      <div class="user-card" v-for="u in users" :key="u.id">
        <div class="user-main">
          <div class="user-name">{{ u.username }}</div>
          <span class="role-badge" :class="effectiveRole(u).toLowerCase()">{{ effectiveRole(u) }}</span>
          <span v-if="u.enabled === false" class="disabled-badge">отключён</span>
          <button @click="editing = editing === u.id ? null : u.id" class="btn small arrow">
            {{ editing === u.id ? '▲' : '▼' }}
          </button>
        </div>

        <div class="user-devices">
          <div class="device-chip" v-for="d in devicesByUser[u.id] || []" :key="d.chipId">
            {{ d.chipId }} <span class="device-type-tag">{{ d.deviceType }}</span>
          </div>
          <span v-if="!(devicesByUser[u.id] || []).length" class="no-devices">нет устройств</span>
        </div>

        <div class="user-actions">
          <template v-if="u.id !== auth.user?.id">
            <button @click="toggleEnabled(u)" class="btn small">
              {{ u.enabled === false ? 'Включить' : 'Отключить' }}
            </button>
            <button v-if="u.role === 'ADMIN'" @click="changeRole(u)" class="btn small">
              {{ nextRole(u) }}
            </button>
            <button @click="remove(u)" class="btn small danger">Удалить</button>
          </template>
        </div>

        <div v-if="editing === u.id" class="device-panel">
          <form @submit.prevent="assign(u)" class="assign-form">
            <input v-model="assignForm.chipId" placeholder="chipId, напр. 13034338" required />
            <select v-model="assignForm.deviceType">
              <option>BLOCK</option>
              <option>INDIVIDUAL_SENSOR</option>
            </select>
            <select v-model="assignForm.accessRole">
              <option value="USER">управление</option>
              <option value="VIEWER">только чтение</option>
            </select>
            <button type="submit" class="btn small">Привязать</button>
          </form>
          <div class="assigned-list">
            <div class="assigned-row" v-for="d in devicesByUser[u.id] || []" :key="d.chipId">
              <span class="chip">{{ d.chipId }} (<span class="type">{{ d.deviceType }}</span>)</span>
              <button @click="setAccessRole(u, d.chipId)" class="btn small">
                {{ accessRoles[u.id + ':' + d.chipId] === 'VIEWER' ? 'дать управление' : 'только чтение' }}
              </button>
              <button @click="unassign(u, d.chipId)" class="btn small danger">Отвязать</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import {
  listUsers, getUserDevices, createAdmin, deleteUser, assignDevice, unassignDevice,
  updateUser, listDeviceAccess, getBlockPaymentDefaults, setBlockPaymentDefaults
} from '../api/auth'

/** Права пользователя на его устройства — одним запросом на устройство. */
async function listDeviceAccessChip(userId, devices) {
  const result = []
  for (const d of devices) {
    const entries = await listDeviceAccess(d.chipId).catch(() => [])
    const mine = entries.find(e => e.userId === userId)
    if (mine) result.push([d.chipId, mine.accessRole])
  }
  return result
}
import { pushNotification } from '../store/notifications'
import { auth } from '../store/auth'

const users = ref([])
const devicesByUser = reactive({})
const form = reactive({ username: '', password: '' })
const editing = ref(null)
const assignForm = reactive({ chipId: '', deviceType: 'BLOCK', accessRole: 'USER' })
const accessRoles = reactive({})

/** Роль аккаунта: либо админ, либо обычный пользователь. */
const ROLES = ['ADMIN', 'USER']

/**
 * Видимая роль в списке: у не-админа зависит от прав на его устройствах.
 * Если на всех устройствах доступ только на чтение — показываем VIEWER,
 * иначе USER. Роль аккаунта при этом всегда USER.
 */
function effectiveRole(u) {
  if (u.role === 'ADMIN') return 'ADMIN'
  const devs = devicesByUser[u.id] || []
  if (!devs.length) return 'USER'
  const roles = new Set(devs.map(d => accessRoles[`${u.id}:${d.chipId}`]))
  if (roles.has('VIEWER') && !roles.has('USER')) return 'VIEWER'
  return 'USER'
}

function fmtDate(value) {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('ru-RU')
}

const defaults = reactive({ days: 365, unlimited: false })
const defaultsBusy = ref(false)

async function saveDefaults() {
  defaultsBusy.value = true
  try {
    const days = Number(defaults.days) || 365
    const saved = await setBlockPaymentDefaults(days, defaults.unlimited)
    Object.assign(defaults, saved)
    pushNotification('Срок по умолчанию сохранён')
  } catch (e) {
    pushNotification(e.response?.data?.message || 'Не удалось сохранить срок')
  } finally {
    defaultsBusy.value = false
  }
}

async function load() {
  getBlockPaymentDefaults().then(d => Object.assign(defaults, d)).catch(() => { /* дефолт останется 365 */ })
  users.value = await listUsers()
  for (const u of users.value) {
    devicesByUser[u.id] = await getUserDevices(u.id)
    const entries = await listDeviceAccessChip(u.id, devicesByUser[u.id])
    for (const [chipId, role] of entries) {
      accessRoles[`${u.id}:${chipId}`] = role
    }
  }
}

onMounted(load)

async function create() {
  await createAdmin(form.username, form.password)
  form.username = ''
  form.password = ''
  pushNotification('Админ создан')
  await load()
}

function nextRole(u) {
  const i = ROLES.indexOf(u.role)
  return `Роль: ${ROLES[(i + 1) % ROLES.length]}`
}

async function changeRole(u) {
  const i = ROLES.indexOf(u.role)
  const role = ROLES[(i + 1) % ROLES.length]
  if (!confirm(`Сменить роль ${u.username} на ${role}?`)) return
  await updateUser(u.id, { role })
  await load()
}

async function toggleEnabled(u) {
  await updateUser(u.id, { enabled: u.enabled === false })
  await load()
}

async function remove(u) {
  if (!confirm(`Удалить пользователя ${u.username}?`)) return
  await deleteUser(u.id)
  await load()
}

async function assign(u) {
  await assignDevice(u.id, Number(assignForm.chipId), assignForm.deviceType, assignForm.accessRole)
  assignForm.chipId = ''
  devicesByUser[u.id] = await getUserDevices(u.id)
}

async function unassign(u, chipId) {
  await unassignDevice(u.id, chipId)
  devicesByUser[u.id] = await getUserDevices(u.id)
}

/** Права на устройство меняются на лету: владелец может оставить гостя только на чтение. */
async function setAccessRole(u, chipId) {
  const key = `${u.id}:${chipId}`
  const next = accessRoles[key] === 'VIEWER' ? 'USER' : 'VIEWER'
  await assignDevice(u.id, chipId, undefined, next)
  accessRoles[key] = next
  pushNotification(`${u.username}: ${next === 'VIEWER' ? 'только чтение' : 'управление'}`)
}
</script>

<style scoped>
.admin { max-width: 620px; margin: 0 auto; padding: 1rem; }
h2 { color: var(--text-strong); margin-bottom: 1rem; }
.header-row { display: flex; align-items: center; gap: 0.75rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1rem; padding: 1rem; background: var(--bg); }
.header-row h2 { color: var(--text-strong); flex: 1; margin: 0; }
.create-form { display: flex; gap: 0.5rem; margin-bottom: 1.5rem; flex-wrap: wrap; }
.create-form input, .create-form select { padding: 0.5rem; border-radius: 6px; border: 1px solid var(--border); background: var(--bg); color: var(--text); font-size: 0.85rem; }
.card { background: var(--bg-card); border-radius: 12px; padding: 1rem; }
.defaults { margin-bottom: 1rem; }
.defaults-head { display: flex; align-items: baseline; justify-content: space-between; gap: 0.5rem; margin-bottom: 0.7rem; }
.defaults-head h3 { margin: 0; font-size: 0.9rem; font-weight: 500; color: var(--text-strong); }
.defaults-current { font-size: 0.8rem; color: var(--success); white-space: nowrap; }
.defaults-row { display: flex; gap: 0.6rem; align-items: center; flex-wrap: wrap; }
.defaults-row label { display: flex; align-items: center; gap: 0.4rem; color: var(--text-muted); font-size: 0.8rem; }
.defaults-row input[type='number'] { width: 5rem; padding: 0.3rem 0.4rem; border-radius: 6px; border: 1px solid var(--border); background: var(--bg); color: var(--text); font-size: 0.8rem; }
.note { color: var(--text-dim); font-size: 0.75rem; line-height: 1.45; margin: 0.7rem 0 0; }
.user-list { display: flex; flex-direction: column; gap: 0.75rem; }
.user-card { background: var(--bg-card); border-radius: 12px; padding: 1rem; }
.user-main { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem; }
.user-name { color: var(--text); font-weight: 500; flex: 1; }
.role-badge { font-size: 0.7rem; padding: 0.2rem 0.5rem; border-radius: 4px; }
.role-badge.admin { background: var(--bg-accent); color: var(--text); }
.role-badge.user { background: var(--tag-bg-ok); color: var(--text); }
.role-badge.viewer { background: #2a2a3e; color: var(--text-muted); }
.role-badge.on { background: var(--tag-bg-ok); color: var(--tag-text-ok); }
.role-badge.off { background: var(--bg-accent); color: var(--danger); }
.user-paid { display: flex; align-items: center; gap: 0.4rem; margin-bottom: 0.5rem; }
.hint { color: var(--text-dim); font-size: 0.75rem; line-height: 1.45; margin: -0.75rem 0 1.25rem; }
.days-input { width: 4.5rem; padding: 0.3rem 0.4rem; border-radius: 6px; border: 1px solid var(--border); background: var(--bg); color: var(--text); font-size: 0.75rem; }
.key-label { color: var(--text-faint); font-size: 0.7rem; }
.key-code { background: var(--bg); color: var(--warn); padding: 0.15rem 0.4rem; border-radius: 4px; font-size: 0.8rem; letter-spacing: 0.05em; }
.disabled-badge { font-size: 0.65rem; color: var(--danger); border: 1px solid var(--danger); border-radius: 4px; padding: 0.1rem 0.35rem; }
.user-devices { display: flex; gap: 0.4rem; flex-wrap: wrap; margin-bottom: 0.5rem; }
.device-chip { font-family: monospace; font-size: 0.75rem; background: var(--bg); padding: 0.2rem 0.5rem; border-radius: 4px; color: var(--text-muted); }
.device-type-tag { color: var(--text-faint); font-size: 0.65rem; }
.no-devices { color: var(--text-faint); font-size: 0.8rem; }
.user-actions { display: flex; gap: 0.5rem; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.small { font-size: 0.75rem; padding: 0.3rem 0.6rem; }
.btn.secondary { background: var(--bg-accent); }
.btn.danger { background: var(--bg-accent); }
.btn.arrow { background: transparent; border: 1px solid var(--border); }
.device-panel { margin-top: 0.75rem; border-top: 1px solid rgba(255,255,255,0.08); padding-top: 0.75rem; }
.assign-form { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.assign-form input, .assign-form select { padding: 0.4rem; border-radius: 6px; border: 1px solid var(--border); background: var(--bg); color: var(--text); font-size: 0.8rem; }
.assigned-list { display: flex; flex-direction: column; gap: 0.4rem; }
.assigned-row { display: flex; justify-content: space-between; align-items: center; background: var(--bg); padding: 0.4rem 0.6rem; border-radius: 6px; }
.chip { font-family: monospace; font-size: 0.8rem; color: var(--text-muted); }
.type { color: var(--text-faint); }
</style>