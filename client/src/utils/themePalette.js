const clamp = (v, min, max) => Math.min(max, Math.max(min, v))

function hexToRgb(hex) {
  const m = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex || '')
  if (!m) return null
  const value = m[1].length === 3 ? m[1].split('').map(c => c + c).join('') : m[1]
  const n = parseInt(value, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

function rgbToHex(rgb) {
  const part = v => Math.round(clamp(v, 0, 255)).toString(16).padStart(2, '0')
  return `#${part(rgb[0])}${part(rgb[1])}${part(rgb[2])}`
}

function hexToHsl(hex) {
  const rgb = hexToRgb(hex)
  if (!rgb) return null
  const r = rgb[0] / 255
  const g = rgb[1] / 255
  const b = rgb[2] / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const d = max - min
  const l = (max + min) / 2
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1))
  let h = 0
  if (d !== 0) {
    if (max === r) h = 60 * (((g - b) / d) % 6)
    else if (max === g) h = 60 * ((b - r) / d + 2)
    else h = 60 * ((r - g) / d + 4)
  }
  return { h: (h + 360) % 360, s, l }
}

function hslToHex(h, s, l) {
  const hh = ((h % 360) + 360) % 360
  const c = (1 - Math.abs(2 * l - 1)) * s
  const x = c * (1 - Math.abs(((hh / 60) % 2) - 1))
  const m = l - c / 2
  let r = 0
  let g = 0
  let b = 0
  if (hh < 60) { r = c; g = x }
  else if (hh < 120) { r = x; g = c }
  else if (hh < 180) { g = c; b = x }
  else if (hh < 240) { g = x; b = c }
  else if (hh < 300) { r = x; b = c }
  else { r = c; b = x }
  return rgbToHex([(r + m) * 255, (g + m) * 255, (b + m) * 255])
}

function relativeLuminance(hex) {
  const rgb = hexToRgb(hex)
  if (!rgb) return 0
  const [r, g, b] = rgb.map(v => {
    const c = v / 255
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

export function contrastRatio(a, b) {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

function mixHex(a, b, t) {
  const ca = hexToRgb(a)
  const cb = hexToRgb(b)
  if (!ca || !cb) return a
  return rgbToHex(ca.map((v, i) => v + (cb[i] - v) * t))
}

function minContrast(hex, backgrounds) {
  const values = backgrounds.filter(Boolean).map(bg => contrastRatio(hex, bg))
  return values.length ? Math.min(...values) : Infinity
}

function ensureContrast(hex, backgrounds, minimum) {
  if (!hex || !backgrounds.length) return hex
  if (minContrast(hex, backgrounds) >= minimum) return hex
  const hsl = hexToHsl(hex)
  if (!hsl) return hex

  function search(up) {
    let low = up ? hsl.l : 0
    let high = up ? 1 : hsl.l
    const edge = hslToHex(hsl.h, hsl.s, up ? high : low)
    if (minContrast(edge, backgrounds) < minimum) return edge
    for (let i = 0; i < 24; i++) {
      const mid = (low + high) / 2
      const candidate = hslToHex(hsl.h, hsl.s, mid)
      const ok = minContrast(candidate, backgrounds) >= minimum
      if (up) {
        if (ok) high = mid
        else low = mid
      } else if (ok) {
        low = mid
      } else {
        high = mid
      }
    }
    return hslToHex(hsl.h, hsl.s, up ? high : low)
  }

  const candidates = [hex, search(true), search(false)].filter(Boolean)
  const passing = candidates.filter(c => minContrast(c, backgrounds) >= minimum)
  if (passing.length) {
    return passing.sort((a, b) => Math.abs(hexToHsl(a).l - hsl.l) - Math.abs(hexToHsl(b).l - hsl.l))[0]
  }
  return candidates.sort((a, b) => minContrast(b, backgrounds) - minContrast(a, backgrounds))[0]
}

function findCardColor(bgHex, bgHsl, textHex, mode) {
  const sCap = mode === 'light' ? Math.min(bgHsl.s, 0.26) : Math.min(bgHsl.s, 0.38)
  const preferred = mode === 'light'
    ? clamp(bgHsl.l - 0.08, 0.48, 0.96)
    : clamp(bgHsl.l + 0.08, 0.04, 0.5)
  let best = null
  let bestScore = -Infinity
  for (let step = 0; step <= 0.5; step += 0.01) {
    for (const offset of step === 0 ? [0] : [step, -step]) {
      const l = clamp(preferred + offset, 0.02, 0.98)
      const candidate = hslToHex(bgHsl.h, sCap, l)
      const textContrast = contrastRatio(candidate, textHex)
      const bgContrast = contrastRatio(candidate, bgHex)
      const score = Math.min(textContrast / 4.5, bgContrast / 1.18)
      if (score > bestScore) {
        best = candidate
        bestScore = score
      }
      if (textContrast >= 4.5 && bgContrast >= 1.18) return candidate
    }
  }
  return best || hslToHex(bgHsl.h, sCap, preferred)
}

export const THEME_VAR_NAMES = [
  '--bg-soft', '--bg-card', '--bg-primary',
  '--text', '--text-strong', '--text-sub', '--text-muted', '--text-dim', '--text-faint',
  '--on-accent', '--link', '--danger', '--success', '--accent', '--warn',
  '--border', '--border-soft', '--divider', '--chart-grid',
  '--tag-bg', '--tag-text', '--tag-bg-ok', '--tag-text-ok',
  '--banner-bg', '--banner-text', '--banner-border',
  '--muted-fg', '--card-shadow', '--bg-accent', '--bg-active',
  '--btn-bg', '--btn-bg-hover', '--btn-border', '--btn-text',
  '--btn-disabled-bg', '--btn-disabled-border', '--btn-disabled-text',
  '--save-bg', '--save-bg-hover', '--save-text', '--save-border',
]

export function pickPaletteMode(bgHex) {
  const lightText = contrastRatio(bgHex, '#eeeeee')
  const darkText = contrastRatio(bgHex, '#1a1a1a')
  return darkText > lightText ? 'light' : 'dark'
}

export function buildThemePalette(bgHex, mode, base = {}) {
  const bgHsl = hexToHsl(bgHex)
  if (!bgHsl) return {}
  const text = base['--text'] || (mode === 'light' ? '#1a1a1a' : '#eeeeee')
  const card = findCardColor(bgHex, bgHsl, text, mode)
  let primary = mixHex(card, text, 0.08)
  if (contrastRatio(primary, text) < 4.5) primary = mixHex(card, mode === 'light' ? '#ffffff' : '#000000', 0.08)
  if (contrastRatio(primary, text) < 4.5) primary = card
  const border = mixHex(card, text, 0.28)
  const borderSoft = mixHex(card, text, 0.16)
  const chartGrid = mixHex(card, text, 0.11)
  const bgSoft = mixHex(bgHex, card, 0.5)
  const disabledBg = mixHex(card, bgHex, 0.55)
  const disabledText = ensureContrast(base['--text-faint'] || text, [disabledBg], 2.5)

  const vars = {}
  for (const [name, value] of Object.entries(base)) {
    if (value) vars[name] = value
  }
  if (!vars['--text']) vars['--text'] = text
  vars['--bg'] = bgHex
  vars['--bg-soft'] = bgSoft
  vars['--bg-card'] = card
  vars['--bg-primary'] = primary
  vars['--border'] = border
  vars['--border-soft'] = borderSoft
  vars['--chart-grid'] = chartGrid
  vars['--divider'] = borderSoft
  vars['--btn-bg'] = card
  vars['--btn-bg-hover'] = primary
  vars['--btn-border'] = border
  vars['--btn-disabled-bg'] = disabledBg
  vars['--btn-disabled-border'] = borderSoft
  vars['--btn-disabled-text'] = disabledText

  const textTargets = {
    '--text': 4.5,
    '--text-strong': 4.5,
    '--text-sub': 4.5,
    '--text-muted': 3.8,
    '--text-dim': 3.2,
    '--text-faint': 2.6,
    '--muted-fg': 3.5,
    '--link': 3.5,
    '--danger': 3.5,
    '--success': 3.5,
    '--warn': 3.5,
  }
  for (const [name, target] of Object.entries(textTargets)) {
    if (vars[name]) vars[name] = ensureContrast(vars[name], [bgHex, card], target)
  }
  vars['--btn-text'] = ensureContrast(vars['--btn-text'] || text, [card, primary], 4.5)
  return vars
}
