// Username and password rules, mirrored from the server
// (server/src/main/java/ru/sensoterra/server/security/CredentialsPolicy.java) so the user sees
// the reason before submitting. Keep both in sync; the server stays the source of truth.

export const MIN_USERNAME_LENGTH = 3
export const MAX_USERNAME_LENGTH = 50
export const MIN_PASSWORD_LENGTH = 8
export const MAX_PASSWORD_BYTES = 72

export const PASSWORD_HINT = `Не короче ${MIN_PASSWORD_LENGTH} символов, не совпадает с логином и не из самых простых.`

const USERNAME_RE = /^[\p{L}\p{N}._@+-]+$/u

const COMMON = new Set([
  '12345678', '123456789', '1234567890', '87654321', '11111111', '00000000',
  'password', 'password1', 'passw0rd', 'qwertyui', 'qwerty123', 'qwertyuiop',
  '1q2w3e4r', '1qaz2wsx', 'zaq12wsx', 'asdfghjk', 'iloveyou', 'sunshine',
  'admin123', 'administrator', 'welcome1', 'letmein1', 'abc12345', 'abcd1234',
  'sensoterra', 'йцукенгш',
])

/** Russian reason why the username is not accepted, or null. */
export function usernameProblem(raw) {
  const username = (raw || '').trim()
  if (!username) return 'Введите логин'
  if (username.length < MIN_USERNAME_LENGTH || username.length > MAX_USERNAME_LENGTH) {
    return `Логин — от ${MIN_USERNAME_LENGTH} до ${MAX_USERNAME_LENGTH} символов`
  }
  if (!USERNAME_RE.test(username)) return 'В логине можно использовать только буквы, цифры и . _ - @ +'
  return null
}

/** Russian reason why the password is not accepted, or null. */
export function passwordProblem(password, username) {
  if (!password || !password.trim()) return 'Введите пароль'
  if (password.length < MIN_PASSWORD_LENGTH) return `Пароль должен быть не короче ${MIN_PASSWORD_LENGTH} символов`
  if (new TextEncoder().encode(password).length > MAX_PASSWORD_BYTES) return 'Пароль слишком длинный'
  const lower = password.toLowerCase()
  if (username && lower === username.trim().toLowerCase()) return 'Пароль не должен совпадать с логином'
  if (new Set(password).size === 1 || COMMON.has(lower)) return 'Пароль слишком простой'
  return null
}

/** Micronaut puts the reason into _embedded.errors[0].message; message is just "Bad Request". */
export function apiErrorMessage(err) {
  const data = err?.response?.data
  return data?._embedded?.errors?.[0]?.message || data?.message || ''
}

/** Reason of a failed request in Russian for CredentialsPolicy messages; others pass through. */
export function credentialsErrorText(err) {
  const message = apiErrorMessage(err)
  if (!message) return message
  if (message.startsWith('Password must be at least')) return `Пароль должен быть не короче ${MIN_PASSWORD_LENGTH} символов`
  if (message.startsWith('Password must be at most')) return 'Пароль слишком длинный'
  if (message.startsWith('Password must not be the same')) return 'Пароль не должен совпадать с логином'
  if (message === 'Password is too common') return 'Пароль слишком простой'
  if (message.startsWith('Username must be')) return `Логин — от ${MIN_USERNAME_LENGTH} до ${MAX_USERNAME_LENGTH} символов`
  if (message.startsWith('Username may contain')) return 'В логине можно использовать только буквы, цифры и . _ - @ +'
  if (message === 'Username already exists') return 'Такой логин уже занят'
  return message
}
