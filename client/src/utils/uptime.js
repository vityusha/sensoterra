// Расчёт интервалов простоя сервиса из истории uptime-логов.
// Записи: { startedAt, stoppedAt } (UTC ISO). Закрытая запись (остановлен сервис) даёт
// простой от её stoppedAt до старта следующей записи. Незакрытая (текущая/обрыв) — пропускается.
export function computeDowntimes(history) {
  const entries = (history || [])
    .map(h => ({
      start: h.startedAt ? new Date(h.startedAt).getTime() : null,
      end: h.stoppedAt ? new Date(h.stoppedAt).getTime() : null,
    }))
    .filter(e => e.start != null && !isNaN(e.start))
    .sort((a, b) => a.start - b.start)

  const res = []
  for (let i = 0; i < entries.length - 1; i++) {
    const end = entries[i].end
    if (end == null || isNaN(end)) continue
    const nextStart = entries[i + 1].start
    if (nextStart > end) {
      res.push({ from: new Date(end).toISOString(), to: new Date(nextStart).toISOString() })
    }
  }
  return res
}