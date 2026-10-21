/**
 * Yandex ID sign-in, authorization code flow with a full-page redirect.
 *
 * A popup would keep the page state, but Yandex pages may cut window.opener (COOP), so
 * the page context (where to return, device pair, invite token) is kept in sessionStorage
 * for the trip and read back by the /oauth/yandex callback view.
 */

const STORAGE_KEY = 'yandexOAuth'
const AUTHORIZE_URL = 'https://oauth.yandex.ru/authorize'

/** Callback route; must be registered as Redirect URI in the Yandex app settings. */
export const YANDEX_CALLBACK_PATH = '/oauth/yandex'

function randomString(bytes) {
  const data = new Uint8Array(bytes)
  crypto.getRandomValues(data)
  return base64url(data)
}

function base64url(bytes) {
  let binary = ''
  bytes.forEach(b => { binary += String.fromCharCode(b) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** PKCE S256 challenge; crypto.subtle exists only in a secure context, otherwise PKCE is skipped. */
async function pkceChallenge(verifier) {
  if (!window.isSecureContext || !crypto.subtle) return null
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64url(new Uint8Array(digest))
}

/**
 * Leaves for Yandex. context: { returnTo, chipId, key, inviteToken, intent } — all optional,
 * handed back by takeYandexContext() after the redirect.
 */
export async function startYandexLogin(clientId, context = {}) {
  const state = randomString(24)
  const verifier = randomString(48)
  const challenge = await pkceChallenge(verifier)
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify({
    state,
    verifier: challenge ? verifier : null,
    ...context
  }))
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: location.origin + YANDEX_CALLBACK_PATH,
    state
  })
  if (challenge) {
    params.set('code_challenge', challenge)
    params.set('code_challenge_method', 'S256')
  }
  location.assign(`${AUTHORIZE_URL}?${params}`)
}

/**
 * Context saved before the redirect, only if state matches (protects from a forged
 * callback link). Read once: a reload of the callback page must not reuse it.
 */
export function takeYandexContext(state) {
  let saved = null
  try {
    saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null')
  } catch {
    saved = null
  }
  sessionStorage.removeItem(STORAGE_KEY)
  if (!saved || !state || saved.state !== state) return null
  return saved
}
