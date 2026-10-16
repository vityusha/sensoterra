<template>
  <section v-if="canShare" class="section">
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
      <div class="invite-note">
        <template v-if="eternal || !lastInvite.expiresAt">
          Ссылка одноразовая, без срока действия.
        </template>
        <template v-else>
          Ссылка одноразовая, действует до {{ fmtDate(lastInvite.expiresAt) }}.
        </template>
        Отправьте её тому, кому нужен доступ{{ lastInvite.accessRole === 'VIEWER' ? ' только на чтение' : '' }}.
      </div>
      <button class="btn small" @click="copyInvite(lastInvite.token)">Скопировать</button>
    </div>
    <div v-if="invites.length" class="invite-list">
      <div class="invite-row" v-for="i in invites" :key="i.id">
        <code class="invite-token">{{ i.token.slice(0, 8) }}…</code>
        <span class="invite-state" :class="i.usable ? 'ok' : 'no'">
          <template v-if="i.usable">
            <template v-if="i.expiresAt">до {{ fmtDate(i.expiresAt) }}</template>
            <template v-else>бессрочно</template>
          </template>
          <template v-else>{{ i.acceptedAt ? 'принято' : 'отозвано' }}</template>
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
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listInvites, createInvite, revokeInvite, clearFinishedInvites, clearAllInvites, listDeviceAccess } from '../api/auth'
import { pushNotification } from '../store/notifications'
import { auth, isAdmin } from '../store/auth'

const props = defineProps({
  chipId: { type: Number, required: true },
  /** Автономный датчик: у ссылки нет срока действия (expiresAt пустой). */
  eternal: { type: Boolean, default: false }
})

const accessList = ref([])
const invites = ref([])
const inviteAccessRole = ref('USER')
const lastInvite = ref(null)
const inviteBusy = ref(false)
const inviteError = ref('')
const clearBusy = ref(false)

const myAccessRole = computed(() => accessList.value.find(a => a.userId === auth.user?.id)?.accessRole)
// Сервер запрещает админу выдавать ссылки (403) — доступ он раздаёт напрямую.
const canShare = computed(() => !isAdmin() && myAccessRole.value === 'USER')

async function loadAccess() {
  try {
    accessList.value = await listDeviceAccess(props.chipId)
  } catch {
    accessList.value = []
  }
}

async function loadInvites() {
  if (!canShare.value) return
  try {
    invites.value = await listInvites(props.chipId)
  } catch {
    invites.value = []
  }
}

function fmtDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  return `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}`
}

function inviteUrl(token) {
  return `${location.origin}/invite/${token}`
}

async function makeInvite() {
  inviteBusy.value = true
  inviteError.value = ''
  try {
    lastInvite.value = await createInvite(props.chipId, inviteAccessRole.value)
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

const removableInvites = computed(() => invites.value.filter(i => !i.acceptedAt))

async function clearFinished() {
  if (!confirm('Убрать из списка отозванные и истёкшие ссылки? Принятые останутся.')) return
  clearBusy.value = true
  try {
    const res = await clearFinishedInvites(props.chipId)
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
    const res = await clearAllInvites(props.chipId)
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
  await revokeInvite(props.chipId, invite.id)
  if (lastInvite.value?.id === invite.id) lastInvite.value = null
  await loadInvites()
}

onMounted(async () => {
  await loadAccess()
  await loadInvites()
})
</script>

<style scoped>
.section { margin-bottom: 2rem; }
.section h3 { color: var(--text-muted); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 0.75rem; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.section-head h3 { margin: 0; }
.btn { padding: 0.4rem 0.8rem; border: 1px solid var(--btn-border); border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--btn-bg); color: var(--btn-text); }
.btn:hover:not(:disabled) { background: var(--btn-bg-hover); border-color: var(--btn-border); }
.btn.small { font-size: 0.75rem; padding: 0.3rem 0.6rem; }
.btn.secondary { background: var(--btn-bg-hover); }
.btn.danger { background: #7a2626; border-color: #a84040; color: var(--on-accent); }
.btn.danger:hover:not(:disabled) { background: #933030; border-color: #cb5654; }
.btn:disabled { cursor: default; background: var(--btn-disabled-bg); border-color: var(--btn-disabled-border); color: var(--btn-disabled-text); opacity: 0.7; }
.role-select { padding: 0.25rem 0.4rem; border-radius: 6px; border: 1px solid var(--border); background: var(--bg); color: var(--text); font-size: 0.75rem; }
.invite-error { color: var(--danger); font-size: 0.8rem; margin: 0.4rem 0; }
.invite-box { background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 0.6rem; margin-top: 0.6rem; }
.invite-link { color: var(--accent); font-family: monospace; font-size: 0.75rem; word-break: break-all; margin-bottom: 0.4rem; }
.invite-note { color: var(--text-dim); font-size: 0.75rem; line-height: 1.4; margin-bottom: 0.5rem; }
.invite-cleanup { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-top: 0.5rem; }
.invite-list { margin-top: 0.6rem; display: flex; flex-direction: column; gap: 0.35rem; }
.invite-row { display: flex; align-items: center; gap: 0.5rem; background: var(--bg); border-radius: 6px; padding: 0.35rem 0.5rem; }
.invite-role { color: var(--muted-fg); font-size: 0.7rem; }
.invite-token { color: var(--text-dim); font-size: 0.75rem; }
.invite-state { font-size: 0.7rem; flex: 1; }
.invite-state.ok { color: var(--success); }
.invite-state.no { color: var(--text-dim); }
</style>