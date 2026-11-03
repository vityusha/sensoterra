import { reactive } from 'vue'
import { getAlarmSummary, getToken } from '../api/auth'
import { onWsMessage } from './notifications'

/**
 * Bell state. unseen > 0 lights the red dot: alarms started or finished since the user last
 * opened the alarms page. open — active alarms per type (tab counters).
 */
export const alarmState = reactive({
  unseen: 0,
  open: {},
  // Bumped on every alarm event so an open alarms page reloads its list.
  version: 0,
})

let loading = null

export function refreshAlarmSummary() {
  if (!getToken() || loading) return loading
  loading = getAlarmSummary()
    .then(data => {
      alarmState.unseen = data?.unseen || 0
      alarmState.open = data?.open || {}
    })
    .catch(() => { /* the dot just stays as it was */ })
    .finally(() => { loading = null })
  return loading
}

/** The alarms page is open: nothing is new for the user any more. */
export function clearUnseen() {
  alarmState.unseen = 0
}

onWsMessage(msg => {
  if (msg.type !== 'alarm') return
  alarmState.version++
  refreshAlarmSummary()
})
