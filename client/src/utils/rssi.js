// RSSI charts span up to 40 dBm; the top edge follows the strongest sample
// and the bottom edge never goes below -100 dBm.
export const RSSI_SPAN = 40

export function rssiBounds(values) {
  const nums = (values ?? [])
    .filter(v => v != null)
    .map(v => Number(v))
    .filter(v => Number.isFinite(v) && v < 0)
  const rounded = nums.length ? Math.ceil(Math.max(...nums) / 10) * 10 : -30
  const max = Math.max(rounded, -90)
  return { min: Math.max(-100, max - RSSI_SPAN), max }
}

// Signal strength bands for RSSI charts, in dBm. Higher is better.
// The first band is open-ended upwards, the last one open-ended downwards.
export const RSSI_BANDS = [
  { from: -60, to: null, color: 'rgba(126, 217, 87, 0.5)' },
  { from: -70, to: -60, color: 'rgba(154, 205, 50, 0.5)' },
  { from: -80, to: -70, color: 'rgba(253, 199, 9, 0.5)' },
  { from: null, to: -80, color: 'rgba(230, 126, 34, 0.5)' },
]

export function rssiColor(value) {
  if (value == null) return null
  const v = Number(value)
  if (!Number.isFinite(v)) return null
  if (v >= -60) return '#7ed957'
  if (v >= -70) return '#9acd32'
  if (v >= -80) return '#fdc709'
  return '#e67e22'
}

/** Translucent background for an RSSI value tile. */
export function rssiBackground(value) {
  const color = rssiColor(value)
  return color ? `${color}44` : null
}
