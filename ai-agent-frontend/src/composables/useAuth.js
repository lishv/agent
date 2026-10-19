import { reactive } from 'vue'
import { http, setCsrfToken, setAccountId } from '../services/api.js'

const PROFILE_KEY = 'huiling.account.v1'
const state = reactive({ user: null, loading: true, offline: false, dialog: false, mode: 'login' })
let initialization
let revision = 0
function cacheProfile() {
  try {
    if (state.user) localStorage.setItem(PROFILE_KEY, JSON.stringify({ user: state.user, expiresAt: Date.now() + 30 * 86400000 }))
    else localStorage.removeItem(PROFILE_KEY)
  } catch { /* 缓存失败不影响在线登录。 */ }
}
function open(mode = 'login') { state.mode = mode; state.dialog = true }
async function refresh() {
  const requestRevision = ++revision
  state.loading = true
  try {
    const { data } = await http.get('/auth/session', { timeout: 7000 })
    if (requestRevision !== revision) return
    state.user = data.user; state.offline = false; setCsrfToken(data.csrfToken); setAccountId(data.user?.id); cacheProfile()
  } catch (error) {
    if (requestRevision !== revision) return
    if (!error.response || error.response.status >= 500) {
      state.offline = true
      try {
        const cached = JSON.parse(localStorage.getItem(PROFILE_KEY) || 'null')
        if (cached?.user?.id && cached.expiresAt > Date.now()) state.user = cached.user
      } catch { }
    } else { state.user = null; state.offline = false; setCsrfToken(''); setAccountId('') }
  } finally { if (requestRevision === revision) state.loading = false }
}
const init = () => initialization ||= refresh()
async function authenticate(mode, body) {
  const { data } = await http.post(`/auth/${mode}`, body, { timeout: 15000 })
  revision++; state.loading = false
  state.user = data.user; state.offline = false; setCsrfToken(data.csrfToken); setAccountId(data.user?.id); cacheProfile(); state.dialog = false
}
async function updateNickname(displayName) {
  const owner = state.user?.id
  const requestRevision = ++revision
  try {
    const { data } = await http.post('/auth/nickname', { displayName }, { timeout: 15000 })
    if (requestRevision !== revision || state.user?.id !== owner || data.id !== owner) throw new Error('账号状态已改变，请重新打开账号设置')
    state.user = data; cacheProfile()
  } finally { if (requestRevision === revision) state.loading = false }
}
async function logout() {
  if (!state.offline) await http.post('/auth/logout', {}, { timeout: 7000 })
  revision++; state.user = null; cacheProfile(); setCsrfToken(''); setAccountId(''); state.dialog = false
  if (!state.offline) await refresh()
}
if (typeof window !== 'undefined') {
  window.addEventListener('huiling:login-requested', () => open())
  window.addEventListener('huiling:session-expired', () => { state.user = null; cacheProfile(); setCsrfToken(''); setAccountId(''); open(); refresh() })
  window.addEventListener('storage', (event) => {
    if (event.key !== PROFILE_KEY) return
    let profile
    try { profile = JSON.parse(event.newValue || 'null')?.user } catch { }
    if ((profile?.id || null) === (state.user?.id || null)) {
      if (state.user && typeof profile?.displayName === 'string') state.user.displayName = profile.displayName
      return
    }
    state.user = null; setCsrfToken(''); setAccountId(''); refresh()
  })
  window.addEventListener('offline', () => { state.offline = true })
  window.addEventListener('online', refresh)
}
export function useAuth() { return { state, init, refresh, open, authenticate, updateNickname, logout } }
