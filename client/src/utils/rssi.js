// Fixed RSSI chart scale, in dBm.
export const RSSI_Y = { min: -100, max: -30 }

// Signal strength bands for RSSI charts, in dBm. Higher is better.
// The first band is open-ended upwards, the last one open-ended downwards.
export const RSSI_BANDS = [
  { from: -60, to: null, color: 'rgba(46, 204, 113, 0.18)' },
  { from: -70, to: -60, color: 'rgba(154, 205, 50, 0.18)' },
  { from: -80, to: -70, color: 'rgba(241, 196, 15, 0.18)' },
  { from: null, to: -80, color: 'rgba(230, 126, 34, 0.18)' },
]

export function rssiColor(value) {
  if (value == null) return null
  const v = Number(value)
  if (!Number.isFinite(v)) return null
  if (v >= -60) return '#2ecc71'
  if (v >= -70) return '#9acd32'
  if (v >= -80) return '#f1c40f'
  return '#e67e22'
}

/** Translucent background for an RSSI value tile. */
export function rssiBackground(value) {
  const color = rssiColor(value)
  return color ? `${color}44` : null
}
