<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bot, ChevronRight, Heart, House, Menu, MessageSquare, PanelLeftClose, PanelLeftOpen, PencilLine, Plus, RefreshCw, Sparkles, Trash2, X } from 'lucide-vue-next'
import { apps } from './data/apps.js'
import { useChat } from './composables/useChat.js'
import { http, accountError } from './services/api.js'
import { useAuth } from './composables/useAuth.js'
import AccountDialog from './components/AccountDialog.vue'

const route = useRoute()
const router = useRouter()
const { state, activateOwner, refreshHistory, removeSession, renameSession, newSession, isRunning } = useChat()
const auth = useAuth()
const drawerOpen = ref(false)
const mobileQuery = window.matchMedia('(max-width: 760px)')
const isMobile = ref(mobileQuery.matches)
const sidebar = ref(null)
const mobileMenu = ref(null)
const historyFilters = ref(null)
const sidebarCollapsed = ref(readSidebarPreference())
const compactSidebar = computed(() => sidebarCollapsed.value && !isMobile.value)
let previousOverflow
let accountFromDrawer = false

function readSidebarPreference() {
  try { return localStorage.getItem('huiling:sidebar-collapsed') === 'true' } catch { return false }
}
watch(sidebarCollapsed, (collapsed) => {
  try { localStorage.setItem('huiling:sidebar-collapsed', String(collapsed)) } catch { /* 布局偏好无法保存时，本次切换仍然可用。 */ }
})
function syncViewport() {
  isMobile.value = mobileQuery.matches
  if (!isMobile.value) drawerOpen.value = false
}
function closeDrawer(restoreFocus = true) {
  drawerOpen.value = false
  if (restoreFocus) nextTick(() => mobileMenu.value?.focus())
}
function sidebarKeys(event) {
  if (!isMobile.value || !drawerOpen.value) return
  if (event.key === 'Escape') { event.preventDefault(); closeDrawer(); return }
  if (event.key !== 'Tab') return
  const elements = [...sidebar.value.querySelectorAll('a, button:not(:disabled)')].filter(element => element.getClientRects().length)
  const first = elements[0], last = elements.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
function showHistory() {
  sidebarCollapsed.value = false
  nextTick(() => historyFilters.value?.querySelector('button')?.focus())
}
function openAccount() {
  accountFromDrawer = isMobile.value && drawerOpen.value
  closeDrawer(false)
  auth.open(auth.state.user ? 'account' : 'login')
}
watch(() => auth.state.dialog, (dialog) => {
  if (!dialog && accountFromDrawer) { accountFromDrawer = false; nextTick(() => mobileMenu.value?.focus()) }
})
watch([drawerOpen, isMobile], async ([open, mobile]) => {
  if (open && mobile) {
    if (previousOverflow === undefined) previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    await nextTick()
    sidebar.value?.querySelector('.drawer-close')?.focus()
  } else if (previousOverflow !== undefined) {
    document.body.style.overflow = previousOverflow
    previousOverflow = undefined
  }
})
watch(() => route.fullPath, () => closeDrawer(false))
onMounted(() => mobileQuery.addEventListener('change', syncViewport))
onBeforeUnmount(() => {
  mobileQuery.removeEventListener('change', syncViewport)
  if (previousOverflow !== undefined) document.body.style.overflow = previousOverflow
})
const connection = ref('checking')
const recent = computed(() => state.sessions.filter((session) => session.saved || session.messages.length))
const historyFilter = ref('all')
const deletion = ref(null)
const deleting = ref(false)
const deleteDialog = ref(null)
const renameTarget = ref(null)
const renameTitle = ref('')
const renameSaving = ref(false)
const renameError = ref('')
const renameDialog = ref(null)
let renameFocus
let deleteFocus
watch(deletion, async (item) => {
  if (item) { deleteFocus = document.activeElement; await nextTick(); deleteDialog.value?.querySelector('button')?.focus() }
  else { await nextTick(); deleteFocus?.focus({ preventScroll: true }) }
})
watch(renameTarget, async (item) => {
  if (item) {
    renameFocus = document.activeElement
    renameTitle.value = item.title
    renameError.value = ''
    await nextTick()
    const input = renameDialog.value?.querySelector('input')
    input?.focus({ preventScroll: true })
    input?.select()
  } else {
    // 等工作区移除 inert 后再恢复焦点，否则浏览器会忽略 focus()。
    await nextTick()
    renameFocus?.focus({ preventScroll: true })
  }
})
watch(() => state.ownerId, () => { deletion.value = null; renameTarget.value = null })
function renameKeys(event) {
  if (event.key === 'Escape' && !renameSaving.value) renameTarget.value = null
  if (event.key !== 'Tab') return
  const elements = [...renameDialog.value.querySelectorAll('input:not(:disabled), button:not(:disabled)')]
  if (event.shiftKey && document.activeElement === elements[0]) { event.preventDefault(); elements.at(-1)?.focus() }
  else if (!event.shiftKey && document.activeElement === elements.at(-1)) { event.preventDefault(); elements[0]?.focus() }
}
async function confirmRename() {
  if (!renameTarget.value || renameSaving.value) return
  const item = renameTarget.value
  renameSaving.value = true
  renameError.value = ''
  try {
    const saved = await renameSession(item.id, renameTitle.value)
    if (saved && renameTarget.value === item) renameTarget.value = null
  } catch (error) {
    if (renameTarget.value === item) renameError.value = accountError(error)
  } finally { renameSaving.value = false }
}
function deleteKeys(event) {
  if (event.key === 'Escape' && !deleting.value) deletion.value = null
  if (event.key !== 'Tab') return
  const buttons = [...deleteDialog.value.querySelectorAll('button:not(:disabled)')]
  if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons.at(-1)?.focus() }
  else if (!event.shiftKey && document.activeElement === buttons.at(-1)) { event.preventDefault(); buttons[0]?.focus() }
}
const filteredRecent = computed(() => recent.value.filter((session) => historyFilter.value === 'all' || historyFilter.value === session.appId))
const connectionLabel = computed(() => auth.state.offline && connection.value !== 'checking' ? '服务未连接' : ({ checking: '正在检查服务', online: '服务已连接', offline: '服务未连接' })[connection.value])

async function checkConnection() {
  connection.value = 'checking'
  try {
    const { data } = await http.get('/health', { timeout: 5000 })
    connection.value = data === 'ok' ? 'online' : 'offline'
    if (connection.value === 'online' && auth.state.offline) await auth.refresh()
  } catch { connection.value = 'offline' }
}

function navigate(path) {
  closeDrawer(false)
  router.push(path)
}

function startChat() {
  navigate({ name: 'chat', params: { appId: route.params.appId || 'love' }, query: { fresh: Date.now() } })
}

watch(() => [auth.state.user?.id, auth.state.loading], ([id, loading]) => {
  if (loading) { if (!id && state.ownerId) activateOwner(null, false); return }
  if (state.ownerId !== (id || null)) activateOwner(id || null)
}, { immediate: true })
watch(() => auth.state.offline, (offline, wasOffline) => { if (!offline && wasOffline && state.ownerId) refreshHistory() })
async function confirmDelete() {
  if (!deletion.value || deleting.value) return
  deleting.value = true
  const item = deletion.value
  const ownerId = state.ownerId
  await removeSession(item.id)
  if (state.ownerId !== ownerId || deletion.value !== item) { deleting.value = false; return }
  if (route.query.session === item.id) {
    const session = newSession(item.appId)
    router.replace({ name: 'chat', params: { appId: item.appId }, query: { session: session.id } })
  }
  deletion.value = null; deleting.value = false
}
onMounted(async () => { await auth.init(); if (!state.ownerId && !auth.state.user) state.loading = false; checkConnection() })
</script>

<template>
  <div class="workspace" :class="{ 'sidebar-collapsed': compactSidebar }" :inert="auth.state.dialog || !!deletion || !!renameTarget">
    <button v-if="drawerOpen" class="sidebar-scrim" tabindex="-1" aria-label="关闭导航" @click="closeDrawer()"></button>
    <aside id="app-sidebar" ref="sidebar" class="sidebar" :class="{ 'is-open': drawerOpen }" :role="isMobile && drawerOpen ? 'dialog' : undefined" :aria-modal="isMobile && drawerOpen ? true : undefined" aria-label="绘灵导航" @keydown="sidebarKeys">
      <div class="brand-row">
        <button class="brand" aria-label="绘灵首页" @click="navigate('/')">
          <span class="brand-symbol"><Sparkles :size="23" :stroke-width="1.8" /></span>
          <span class="brand-copy">绘灵<span class="brand-en">AI 工作空间</span></span>
        </button>
        <button class="icon-button sidebar-toggle" :aria-label="compactSidebar ? '展开侧边栏' : '收起侧边栏'" :title="compactSidebar ? '展开侧边栏' : '收起侧边栏'" aria-controls="app-sidebar" :aria-expanded="!compactSidebar" @click="sidebarCollapsed = !sidebarCollapsed"><PanelLeftOpen v-if="compactSidebar" :size="19" /><PanelLeftClose v-else :size="19" /></button>
        <button class="icon-button drawer-close" aria-label="关闭导航" @click="closeDrawer()"><X :size="20" /></button>
      </div>
      <button class="new-chat-button" aria-label="开启新对话" :title="compactSidebar ? '开启新对话' : undefined" @click="startChat"><Plus :size="18" /><span class="new-chat-label">开启新对话</span><span class="new-chat-shortcut">↗</span></button>
      <div class="nav-label">工作空间</div>
      <nav aria-label="应用导航" class="main-nav">
        <RouterLink to="/" class="nav-item" aria-label="探索应用" :title="compactSidebar ? '探索应用' : undefined" :class="{ active: route.name === 'home' }" @click="closeDrawer(false)"><House :size="18" /><span>探索应用</span></RouterLink>
        <RouterLink v-for="app in apps" :key="app.id" :to="`/chat/${app.id}`" class="nav-item" :aria-label="app.name" :title="compactSidebar ? app.name : undefined" :class="{ active: route.params.appId === app.id }" @click="closeDrawer(false)">
          <Heart v-if="app.id === 'love'" :size="18" /><Bot v-else :size="18" /><span>{{ app.name }}</span><ChevronRight class="nav-chevron" :size="14" />
        </RouterLink>
      </nav>
      <button v-if="compactSidebar" class="collapsed-history-button icon-button" aria-label="展开最近对话" title="最近对话" @click="showHistory"><MessageSquare :size="19" /></button>
      <div class="history-heading"><span class="nav-label">最近对话</span><MessageSquare :size="13" /></div>
      <div ref="historyFilters" class="history-filters" role="group" aria-label="筛选对话应用"><button v-for="filter in [{id:'all',label:'全部'},{id:'love',label:'恋爱'},{id:'manus',label:'智能体'}]" :key="filter.id" :class="{selected:historyFilter===filter.id}" :aria-pressed="historyFilter===filter.id" @click="historyFilter=filter.id">{{ filter.label }}</button></div>
      <div v-if="!recent.length" class="history-empty">每个好故事，<br />都从一句「你好」开始。</div>
      <nav v-else class="history-list" aria-label="最近对话">
        <div v-for="session in filteredRecent" :key="session.id" class="history-row">
          <button class="history-item" :class="{ selected: route.query.session === session.id }" :title="session.title" @click="navigate({ name: 'chat', params: { appId: session.appId }, query: { session: session.id } })"><Heart v-if="session.appId==='love'" :size="14" /><Bot v-else :size="14" /><span>{{ session.title }}</span><small v-if="isRunning(session.id)" class="history-running" aria-label="正在执行" title="正在执行"></small></button>
          <button class="history-action history-rename" :aria-label="`修改对话名：${session.title}`" title="修改对话名" @click="renameTarget=session"><PencilLine :size="14" /></button>
          <button class="history-action history-delete" :aria-label="`删除对话：${session.title}`" title="删除对话" @click="deletion=session"><Trash2 :size="14" /></button>
        </div>
        <p v-if="!filteredRecent.length" class="history-empty">这里还没有对话</p>
      </nav>
      <div class="sidebar-bottom">
        <button class="connection-status" :class="auth.state.offline ? 'offline' : connection" :title="connection === 'offline' || auth.state.offline ? '点击重新检查后端连接' : '点击刷新连接状态'" @click="checkConnection" :disabled="connection === 'checking'">
          <span class="status-dot"></span><span class="connection-label">{{ connectionLabel }}</span><RefreshCw :size="14" :class="{ spin: connection === 'checking' }" /><span v-if="compactSidebar" class="sr-only">{{ connectionLabel }}</span>
        </button>
        <button class="profile profile-button" :aria-label="auth.state.user ? `账号设置：${auth.state.user.displayName}` : '登录 / 注册'" :title="compactSidebar ? auth.state.user ? `账号设置：${auth.state.user.displayName}` : '登录 / 注册' : undefined" @click="openAccount"><span class="profile-avatar">{{ auth.state.user?.displayName?.slice(0,1)||'你' }}</span><div><strong>{{ auth.state.user?.displayName||'登录 / 注册' }}</strong><span>{{ auth.state.user ? '你的对话，随账号保存' : '登录后保存每一段对话' }}</span></div></button>
      </div>
    </aside>
    <div class="main-shell" :inert="isMobile && drawerOpen">
      <header class="mobile-header"><button ref="mobileMenu" class="icon-button" aria-label="打开导航" aria-controls="app-sidebar" :aria-expanded="drawerOpen" @click="drawerOpen = true"><Menu :size="21" /></button><span>绘灵 <span class="mobile-brand-en">AI 工作空间</span></span><span class="brand-symbol small"><Sparkles :size="18" /></span></header>
      <RouterView />
    </div>
  </div>
  <AccountDialog />
  <Teleport to="body">
    <div v-if="renameTarget" class="account-overlay" @click.self="!renameSaving && (renameTarget=null)" @keydown="renameKeys">
      <section ref="renameDialog" class="account-dialog" role="dialog" aria-modal="true" aria-labelledby="rename-title">
        <h2 id="rename-title">修改对话名</h2>
        <p class="account-intro">为这段对话起一个方便查找的名字。</p>
        <form class="account-form" @submit.prevent="confirmRename">
          <label for="conversation-title">对话名<input id="conversation-title" v-model="renameTitle" maxlength="100" autocomplete="off" :disabled="renameSaving" aria-describedby="rename-hint" @input="renameError=''" /></label>
          <span id="rename-hint" class="account-field-hint">最多 100 个字符</span>
          <p v-if="renameError" class="account-error" role="alert">{{ renameError }}</p>
          <div class="delete-dialog-actions"><button type="button" class="account-secondary" :disabled="renameSaving" @click="renameTarget=null">取消</button><button type="submit" class="account-primary" :disabled="renameSaving">{{renameSaving?'正在保存…':'保存名称'}}</button></div>
        </form>
      </section>
    </div>
    <div v-if="deletion" class="account-overlay" @click.self="!deleting && (deletion=null)" @keydown="deleteKeys"><section ref="deleteDialog" class="account-dialog delete-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-title"><h2 id="delete-title">删除这段对话？</h2><p>“{{deletion.title}}”将从列表中移除，相关聊天记录、记忆文件和压缩备份也会清理，无法恢复。</p><div class="delete-dialog-actions"><button class="account-secondary" :disabled="deleting" @click="deletion=null">取消</button><button class="account-primary" :disabled="deleting" @click="confirmDelete">{{deleting?'正在删除…':'删除对话'}}</button></div></section></div>
  </Teleport>
</template>
