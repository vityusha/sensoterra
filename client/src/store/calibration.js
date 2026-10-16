import { reactive } from 'vue'
import { getSensorCalibration } from '../api/auth'

/**
 * Кеш опорных точек калибровки влажности (АЦП → %) по чипу датчика.
 *
 * Каждая страница (датчик, карточка на блоке, порог канала) одинаково переводит
 * v0 в проценты, поэтому калибровка читается один раз на чип и переиспользуется.
 * Запрос не админский (чтение калибровки открыто всем, кто видит датчик).
 */
const calibrations = reactive({}) // chipId -> { value1, value2, value3 } | null
const loading = reactive({}) // chipId -> true, пока идёт запрос
const failed = reactive({}) // chipId -> true, если читать не удалось (не повторяем)

export function calibrationFor(chipId) {
  if (chipId == null) return null
  return calibrations[String(chipId)] || null
}

export async function loadCalibration(chipId) {
  if (chipId == null) return null
  const key = String(chipId)
  if (calibrations[key] || loading[key] || failed[key]) return calibrations[key] || null
  loading[key] = true
  try {
    const data = await getSensorCalibration(chipId)
    calibrations[key] = data
      ? { value1: data.value1, value2: data.value2, value3: data.value3 }
      : null
    return calibrations[key]
  } catch {
    failed[key] = true
    return null
  } finally {
    loading[key] = false
  }
}

/** После сохранения калибровки на странице датчика обновляем кеш. */
export function setCalibration(chipId, cal) {
  if (chipId == null) return
  const key = String(chipId)
  calibrations[key] = cal
  failed[key] = false
}
