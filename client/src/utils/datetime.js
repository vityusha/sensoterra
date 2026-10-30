export const DEFAULT_ZONE_OFFSET_MIN = 180

export function zoneLabel(offsetMin) {
  const offset = offsetMin ?? DEFAULT_ZONE_OFFSET_MIN
  if (offset === 180) return 'MSK'
  const sign = offset >= 0 ? '+' : '−'
  const abs = Math.abs(offset)
  const hh = String(Math.floor(abs / 60)).padStart(2, '0')
  const mm = String(abs % 60).padStart(2, '0')
  return `UTC${sign}${hh}:${mm}`
}

// Время события в зоне устройства (локальное = UTC + offset).
export function fmtEventTime(iso, offsetMin) {
  if (!iso) return ''
  const offset = offsetMin ?? DEFAULT_ZONE_OFFSET_MIN
  const shifted = new Date(new Date(iso).getTime() + offset * 60000)
  const dd = String(shifted.getUTCDate()).padStart(2, '0')
  const mm = String(shifted.getUTCMonth() + 1).padStart(2, '0')
  const hh = String(shifted.getUTCHours()).padStart(2, '0')
  const mi = String(shifted.getUTCMinutes()).padStart(2, '0')
  return `${dd}-${mm}-${shifted.getUTCFullYear()} ${hh}:${mi}`
}

export function fmtEventTimeWithZone(iso, offsetMin) {
  if (!iso) return ''
  return `${fmtEventTime(iso, offsetMin)} ${zoneLabel(offsetMin)}`
}

// «Сколько назад» от текущего времени: дни / часы / минуты (без секунд).
export function fmtAgo(iso, nowMs = Date.now()) {
  if (!iso) return ''
  const ts = new Date(iso).getTime()
  if (!Number.isFinite(ts)) return ''
  const delta = Math.max(0, nowMs - ts)
  const min = Math.floor(delta / 60000)
  if (min < 1) return 'меньше минуты'
  if (min < 60) return `${min} мин назад`
  const h = Math.floor(min / 60)
  if (h < 24) {
    const m = min % 60
    return m ? `${h} ч ${m} мин назад` : `${h} ч назад`
  }
  const d = Math.floor(h / 24)
  const hh = h % 24
  return hh ? `${d} дн ${hh} ч назад` : `${d} дн назад`
}

export function fmtClock(iso, offsetMin) {
  if (!iso) return ''
  const offset = offsetMin ?? DEFAULT_ZONE_OFFSET_MIN
  const shifted = new Date(new Date(iso).getTime() + offset * 60000)
  const hh = String(shifted.getUTCHours()).padStart(2, '0')
  const mi = String(shifted.getUTCMinutes()).padStart(2, '0')
  return `${hh}:${mi}`
}

export function fmtShortDate(iso, offsetMin) {
  if (!iso) return ''
  const offset = offsetMin ?? DEFAULT_ZONE_OFFSET_MIN
  const shifted = new Date(new Date(iso).getTime() + offset * 60000)
  const dd = String(shifted.getUTCDate()).padStart(2, '0')
  const mm = String(shifted.getUTCMonth() + 1).padStart(2, '0')
  const yy = String(shifted.getUTCFullYear()).slice(-2)
  return `${dd}.${mm}.${yy}`
}

// Подпись оси графика: [дата, время] — ось выводит их в две строки.
export function fmtChartAxis(iso, offsetMin) {
  return [fmtShortDate(iso, offsetMin), fmtClock(iso, offsetMin)]
}

export function fmtDateTime(iso, offsetMin) {
  return `${fmtShortDate(iso, offsetMin)} ${fmtClock(iso, offsetMin)}`
}

export function msToZoneInput(ms, offsetMin) {
  if (!Number.isFinite(ms)) return ''
  const offset = offsetMin ?? DEFAULT_ZONE_OFFSET_MIN
  const d = new Date(ms + offset * 60000)
  const dd = String(d.getUTCDate()).padStart(2, '0')
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0')
  const hh = String(d.getUTCHours()).padStart(2, '0')
  const mi = String(d.getUTCMinutes()).padStart(2, '0')
  return `${d.getUTCFullYear()}-${mm}-${dd}T${hh}:${mi}`
}

export function zoneInputToMs(value, offsetMin) {
  if (!value) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value)
  if (!m) return null
  const offset = offsetMin ?? DEFAULT_ZONE_OFFSET_MIN
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]) - offset * 60000
}