/**
 * Подпись оплаты блока. Срок общий для всех, кто имеет доступ, поэтому подпись
 * считается только из ответа сервера, а не из «моего» аккаунта.
 */

const DAY = 86400

/** «123 д 4 ч» — срок читается быстрее, чем «4 465 200 секунд». */
export function formatRemaining(seconds) {
  if (seconds == null) return ''
  const total = Math.max(0, Math.floor(seconds))
  const days = Math.floor(total / DAY)
  const hours = Math.floor((total % DAY) / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  if (days > 0) return hours > 0 ? `${days} д ${hours} ч` : `${days} д`
  if (hours > 0) return minutes > 0 ? `${hours} ч ${minutes} мин` : `${hours} ч`
  return `${minutes} мин`
}

/** Короткая строка для карточки в списке. */
export function paymentLabel(info) {
  if (!info || info.chargeable === false) return ''
  if (info.unlimited) return 'бессрочно'
  if (!info.started) return 'не подтверждён'
  if (info.paid) return `до ${formatDay(info.expiresAt)}`
  return 'срок истёк'
}

/** Полная строка для страницы блока: что с оплатой и сколько осталось. */
export function paymentText(info) {
  if (!info || info.chargeable === false) return ''
  if (info.unlimited) return 'Оплата: бессрочно'
  if (!info.started) return 'Оплата: срок ещё не запущен — подтвердите ID и ключ с листа'
  if (!info.paid) return `Оплата: срок истёк ${formatDay(info.expiresAt)}`
  return `Оплата: до ${formatDay(info.expiresAt)} (осталось ${formatRemaining(info.remainingSeconds)})`
}

function formatDay(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' })
}
