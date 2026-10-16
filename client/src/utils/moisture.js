/**
 * Пересчёт показаний АЦП влажности (v0) в относительную влажность % и обратно.
 *
 * Опорные точки калибровки: value1 — воздух, value2 — сухая почва (0%),
 * value3 — вода (100%).
 */
export function adcToPercent(adc, cal) {
  if (adc == null || isNaN(adc) || !cal) return null
  const dry = cal.value2
  const water = cal.value3
  if (dry == null || water == null || dry === water) return null
  if (adc > dry) return 0
  if (adc < water) return 100
  const pct = ((dry - adc) / (dry - water)) * 100
  return Math.round(pct * 10) / 10
}

export function percentToAdc(pct, cal) {
  if (pct == null || isNaN(pct) || !cal) return null
  const dry = cal.value2
  const water = cal.value3
  if (dry == null || water == null) return null
  const p = Math.min(100, Math.max(0, Number(pct)))
  return Math.round(dry - (p * (dry - water)) / 100)
}

/**
 * Датчик висит выше точки погружения: значение выше середины между воздухом
 * и сухой почвой (value1 - deltaV/2, deltaV = value1 - value2).
 */
export function immersionWarning(adc, cal) {
  if (adc == null || isNaN(adc) || !cal) return false
  const air = cal.value1
  const dry = cal.value2
  if (air == null || dry == null) return false
  return adc > air - (air - dry) / 2
}

export function hexToRgb(hex) {
  if (!hex) return { r: 71, g: 71, b: 255 }
  const h = hex.replace('#', '')
  const num = parseInt(h.length === 3 ? h.split('').map(c => c + c).join('') : h, 16)
  return {
    r: (num >> 16) & 255,
    g: (num >> 8) & 255,
    b: num & 255,
  }
}

export function rgbToHex(r, g, b) {
  const toHex = (v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')
  return '#' + toHex(r) + toHex(g) + toHex(b)
}

/**
 * Linear interpolation between two colors
 */
export function interpolateColor(pct, colorMin, colorMax) {
  const c1 = hexToRgb(colorMin)
  const c2 = hexToRgb(colorMax)
  const t = Math.max(0, Math.min(1, pct / 100))
  const r = Math.round(c1.r + (c2.r - c1.r) * t)
  const g = Math.round(c1.g + (c2.g - c1.g) * t)
  const b = Math.round(c1.b + (c2.b - c1.b) * t)
  return rgbToHex(r, g, b)
}
