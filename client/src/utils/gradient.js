// Градиент влажности между двумя цветами — по принципу прошивки (таблица gragRGB):
// переход идёт по кругу hue через зелёный («норма»), например
// красный → жёлтый → зелёный → голубой → синий.
// Если любой из цветов ахроматический (чёрный/белый/серый) — обычный
// линейный RGB-переход, как в классическом CSS-градиенте.

function hexToRgb(hex) {
  const m = /^#?([0-9a-fA-F]{6})$/.exec(hex || '')
  if (!m) return [0, 0, 0]
  const n = parseInt(m[1], 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

function rgbToHsv([r, g, b]) {
  const rn = r / 255
  const gn = g / 255
  const bn = b / 255
  const max = Math.max(rn, gn, bn)
  const min = Math.min(rn, gn, bn)
  const d = max - min
  let h = 0
  if (d > 0) {
    if (max === rn) h = 60 * (((gn - bn) / d) % 6)
    else if (max === gn) h = 60 * ((bn - rn) / d + 2)
    else h = 60 * ((rn - gn) / d + 4)
  }
  if (h < 0) h += 360
  const s = max === 0 ? 0 : d / max
  return [h, s, max]
}

function hsvToRgb(h, s, v) {
  h = ((h % 360) + 360) % 360
  const c = v * s
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1))
  const m = v - c
  let r = 0
  let g = 0
  let b = 0
  if (h < 60) { r = c; g = x }
  else if (h < 120) { r = x; g = c }
  else if (h < 180) { g = c; b = x }
  else if (h < 240) { g = x; b = c }
  else if (h < 300) { r = x; b = c }
  else { r = c; b = x }
  return [(r + m) * 255, (g + m) * 255, (b + m) * 255]
}

function rgbToHex(rgb) {
  const h = (v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')
  const [r, g, b] = rgb
  return `#${h(r)}${h(g)}${h(b)}`
}

const GREEN_HUE = 120

/** Цвет в точке t∈[0,1] градиента от a к b: HSV-дуга через зелёный либо линейный RGB. */
function colorAt(a, b, h0, dh, s0, ds, v0, dv, achromatic, t) {
  if (achromatic) {
    return [
      a[0] + (b[0] - a[0]) * t,
      a[1] + (b[1] - a[1]) * t,
      a[2] + (b[2] - a[2]) * t,
    ]
  }
  return hsvToRgb(h0 + dh * t, s0 + ds * t, v0 + dv * t)
}

function endpoints(minColor, maxColor) {
  const a = hexToRgb(minColor)
  const b = hexToRgb(maxColor)
  const [h0, s0, v0] = rgbToHsv(a)
  const [h1, s1, v1] = rgbToHsv(b)
  const achromatic = s0 < 0.08 || s1 < 0.08
  const dh = achromatic ? 0 : hueDelta(h0, h1)
  return { a, b, h0, dh, s0, ds: s1 - s0, v0, dv: v1 - v0, achromatic }
}

/** Выбор дуги hue от h0 к h1: если цвета смежные (кратчайшая дуга < 120°),
 * идём кратчайшим путём; иначе — дугой, проходящей через зелёный («норма»),
 * как в прошивке. Возвращает дельту в градусах (может быть отрицательной). */
function hueDelta(h0, h1) {
  const cw = ((h1 - h0) % 360 + 360) % 360 // длина пути по часовой
  const ccw = 360 - cw
  const shortest = Math.min(cw, ccw)
  if (shortest < GREEN_HUE) return cw <= ccw ? cw : cw - 360
  // Дуга, содержащая зелёный: проверяем, лежит ли 120° на пути по часовой от h0 длиной cw.
  const rel = ((GREEN_HUE - h0) % 360 + 360) % 360
  if (rel <= cw) return cw
  return cw - 360
}

/** CSS-градиент влажности от minColor (0%, сухо/предел) к maxColor (100%, влага).
 * HSV-интерполяция по дуге через зелёный; для чёрного/белого/серого — линейный RGB. */
export function moistureGradientCss(minColor, maxColor, steps = 16) {
  const e = endpoints(minColor, maxColor)
  const stops = []
  for (let i = 0; i <= steps; i++) {
    const t = i / steps
    stops.push(`${rgbToHex(colorAt(e.a, e.b, e.h0, e.dh, e.s0, e.ds, e.v0, e.dv, e.achromatic, t))} ${(t * 100).toFixed(1)}%`)
  }
  return `linear-gradient(90deg, ${stops.join(', ')})`
}

/** Цвет градиента в точке t∈[0,1] — тот же алгоритм, что и у полосы превью. */
export function moistureGradientColor(minColor, maxColor, t) {
  const tt = Math.max(0, Math.min(1, t))
  const e = endpoints(minColor, maxColor)
  return rgbToHex(colorAt(e.a, e.b, e.h0, e.dh, e.s0, e.ds, e.v0, e.dv, e.achromatic, tt))
}

/**
 * Цвет влажности по сохранённой шкале датчика: pct за пределами minPct..maxPct
 * окрашивается цветом ближайшей границы. null — если шкала не задана/не включена
 * или процента нет.
 */
export function moistureScaleColor(scale, pct) {
  if (!scale?.enabled || scale.minPct == null || scale.maxPct == null) return null
  const p = Number(pct)
  if (pct == null || !Number.isFinite(p)) return null
  if (scale.maxPct === scale.minPct) return scale.minColor || null
  const t = (p - scale.minPct) / (scale.maxPct - scale.minPct)
  return moistureGradientColor(scale.minColor, scale.maxColor, t)
}

/**
 * Цвет значения поля (v1/v2/v3) по сохранённой шкале датчика (sensor_scale).
 * Значения за пределами minValue..maxValue окрашиваются цветом ближайшей границы.
 * null — если шкала не задана/не включена или значения нет.
 */
export function sensorScaleColor(scale, value) {
  if (!scale?.enabled || scale.minValue == null || scale.maxValue == null) return null
  const v = Number(value)
  if (value == null || !Number.isFinite(v)) return null
  if (scale.maxValue === scale.minValue) return scale.minColor || null
  const t = (v - scale.minValue) / (scale.maxValue - scale.minValue)
  return moistureGradientColor(scale.minColor, scale.maxColor, t)
}
