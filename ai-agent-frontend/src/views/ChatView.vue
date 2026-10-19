<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, ArrowLeft, ArrowUp, ArrowUpRight, Bot, Check, Copy, Heart, MessageCircle, Plus, Search, Sparkles, Square, WandSparkles } from 'lucide-vue-next'
import { getApp } from '../data/apps.js'
import { useChat } from '../composables/useChat.js'
import MessageBubble from '../components/MessageBubble.vue'
import ContextMeter from '../components/ContextMeter.vue'
import { useAuth } from '../composables/useAuth.js'
import { MAX_MESSAGE_LENGTH } from '../constants/chat.js'

const route = useRoute()
const router = useRouter()
const { state, activeSession, newSession, selectSession, send, answer, stop, refreshContext, compact } = useChat()
const auth = useAuth()
const app = computed(() => getApp(route.params.appId))
const draft = ref('')
const textarea = ref(null)
const scrollArea = ref(null)
const followBottom = ref(true)
const copiedId = ref(false)
const copyIdError = ref(false)
const errorToast = ref(false)
let copyTimer
let errorTimer
const messages = computed(() => activeSession.value?.messages || [])
const inputPlaceholder = computed(() => state.pendingQuestion ? '输入你的回答，继续当前任务…' : app.value.placeholder)
const canSend = computed(() => !!draft.value.trim() && draft.value.length <= MAX_MESSAGE_LENGTH && !auth.state.offline && !auth.state.loading && !state.loading && !state.loadingMessages && !state.submittingAnswer && (!state.busy || !!state.pendingQuestion))

watch(() => state.failureVersion, () => {
  clearTimeout(errorTimer)
  errorToast.value = true
  errorTimer = setTimeout(() => { errorToast.value = false }, 2600)
})

watch(() => [route.params.appId, route.query.session, route.query.fresh, state.ownerId, state.loading], async ([appId, id]) => {
  if (state.loading) return
  const existing = state.sessions.find((session) => session.id === id && session.appId === appId)
  if (existing) {
    if (state.activeId !== existing.id) await selectSession(existing.id)
  } else {
    const session = newSession(appId)
    router.replace({ name: 'chat', params: { appId }, query: { session: session.id } })
  }
  draft.value = ''
  followBottom.value = true
  nextTick(() => {
    resizeInput()
    if (messages.value.length) jumpToBottom()
    else if (scrollArea.value) scrollArea.value.scrollTop = 0
    textarea.value?.focus({ preventScroll: true })
  })
}, { immediate: true })

watch(() => [messages.value.length, messages.value.at(-1)?.content, messages.value.at(-1)?.status, state.busy, state.pendingQuestion?.questionId], async () => {
  await nextTick()
  if (followBottom.value && messages.value.length) jumpToBottom()
})

watch(() => state.pendingQuestion?.questionId, async (id) => {
  if (!id) return
  followBottom.value = true
  await nextTick()
  jumpToBottom()
  textarea.value?.focus({ preventScroll: true })
})

function resizeInput() {
  if (!textarea.value) return
  textarea.value.style.height = 'auto'
  textarea.value.style.height = `${Math.min(textarea.value.scrollHeight, 160)}px`
}

function jumpToBottom() {
  if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
  followBottom.value = true
}

function handleScroll() {
  const element = scrollArea.value
  if (element) followBottom.value = element.scrollHeight - element.clientHeight - element.scrollTop < 100
}

async function submit() {
  if (!canSend.value) return
  if (!auth.state.user) { auth.open(); return }
  const text = draft.value
  if (state.pendingQuestion) {
    const accepted = await answer(text)
    if (accepted && draft.value === text) draft.value = ''
    await nextTick()
    resizeInput()
    textarea.value?.focus({ preventScroll: true })
    return
  }
  draft.value = ''
  followBottom.value = true
  await nextTick()
  resizeInput()
  textarea.value?.focus({ preventScroll: true })
  await send(text)
}

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    submit()
  }
}

function selectPrompt(prompt) {
  draft.value = prompt
  nextTick(() => { resizeInput(); textarea.value?.focus({ preventScroll: true }) })
}

function startNew() {
  const session = newSession(app.value.id)
  router.replace({ name: 'chat', params: { appId: app.value.id }, query: { session: session.id } })
}

function retry(index) {
  const question = messages.value.slice(0, index).reverse().find((message) => message.role === 'user')
  if (question && !state.busy) {
    followBottom.value = true
    send(question.content)
  }
}

async function copyId() {
  try { await navigator.clipboard.writeText(activeSession.value.id); copiedId.value = true; copyIdError.value = false }
  catch { copyIdError.value = true }
  clearTimeout(copyTimer)
  copyTimer = setTimeout(() => { copiedId.value = false; copyIdError.value = false }, 2200)
}

onBeforeUnmount(() => { clearTimeout(copyTimer); clearTimeout(errorTimer) })
</script>

<template>
  <main class="chat-page" :class="app.id">
    <header class="chat-header">
      <div class="chat-header-left"><RouterLink to="/" class="icon-button chat-back" aria-label="返回应用主页"><ArrowLeft :size="18" /></RouterLink><span class="header-app-icon" :class="app.id"><Heart v-if="app.id === 'love'" :size="21" /><Bot v-else :size="22" /></span><div><h1>{{ app.name }}<span class="app-badge">{{ app.id === 'love' ? '情感伙伴' : '行动助手' }}</span></h1><p>{{ app.tagline }}</p></div></div>
      <button class="header-new-chat" @click="startNew"><Plus :size="16" /><span>新对话</span></button>
    </header>
    <div ref="scrollArea" class="chat-scroll-area" @scroll="handleScroll">
      <section v-if="!messages.length" class="chat-welcome">
        <div class="welcome-icon" :class="app.id"><Heart v-if="app.id === 'love'" :size="29" :stroke-width="1.6" /><Bot v-else :size="30" :stroke-width="1.6" /></div>
        <h2>{{ app.greeting }}</h2><p class="welcome-description">{{ app.intro }}</p>
        <div class="suggestion-heading"><Sparkles :size="14" /><span>不知道从哪里开始？试试这些</span></div>
        <div class="suggestion-grid"><button v-for="(suggestion, index) in app.suggestions" :key="suggestion.title" class="suggestion-card" @click="selectPrompt(suggestion.prompt)"><span class="suggestion-icon"><MessageCircle v-if="index === 0" :size="19" /><Heart v-else-if="app.id === 'love' && index === 1" :size="19" /><Search v-else-if="index === 1" :size="19" /><WandSparkles v-else :size="19" /></span><strong>{{ suggestion.title }}</strong><small>{{ suggestion.subtitle }}</small><ArrowUpRight :size="16" class="suggestion-arrow" /></button></div>
        <div v-if="app.id === 'manus'" class="agent-welcome-note"><span class="tiny-dot"></span>复杂任务可能需要一些时间，执行进展会实时显示</div>
      </section>
      <div v-else class="conversation-list" role="log" aria-label="聊天记录" :aria-busy="state.busy" aria-live="off">
        <MessageBubble v-for="(message, index) in messages" :key="message.id" :message="message" :app-id="app.id" :busy="state.busy" @retry="retry(index)" />
        <div v-if="app.id === 'manus' && state.busy" class="agent-activity typing-indicator" role="status" aria-live="polite">
          <MessageCircle v-if="state.pendingQuestion && !state.submittingAnswer" :size="14" />
          <span v-else aria-hidden="true"><i></i><i></i><i></i></span>
          <small>{{ state.submittingAnswer ? '正在继续处理…' : state.pendingQuestion ? '等待你的补充…' : '正在思考…' }}</small>
        </div>
      </div>
    </div>
    <div class="composer-region">
      <button v-if="!followBottom && messages.length" class="scroll-to-bottom" @click="jumpToBottom"><ArrowDown :size="15" />回到最新消息</button>
      <div class="composer-container">
        <Transition name="error-toast"><div v-if="errorToast" class="reply-error-toast" role="alert">回答出错~</div></Transition>
        <div v-if="auth.state.offline" class="offline-chat-note" role="status">当前离线，可查看已缓存的对话{{state.deletedIds.length?'；待删除对话将在联网后同步':''}}</div>
        <div v-else-if="state.loading || state.loadingMessages" class="offline-chat-note" role="status">正在读取你的对话…</div>
        <div v-else-if="state.historyError" class="offline-chat-note">{{ state.historyError }}</div>
        <div v-if="state.pendingQuestion" class="answer-banner"><span><MessageCircle :size="14" />智能体正在等待你的补充</span><button type="button" @click="stop">结束任务</button></div>
        <div v-if="state.answerError" class="answer-error" role="alert">{{ state.answerError }}</div>
        <form class="composer" :class="{ 'is-streaming': state.busy }" @submit.prevent="submit">
          <label for="chat-input" class="sr-only">{{ inputPlaceholder }}</label>
          <textarea id="chat-input" ref="textarea" v-model="draft" :maxlength="MAX_MESSAGE_LENGTH" :placeholder="inputPlaceholder" rows="1" @input="resizeInput" @keydown="handleKeydown"></textarea>
          <div class="composer-bottom"><span class="composer-hint"><Sparkles :size="14" /><span>{{ state.submittingAnswer ? '正在提交你的回答…' : state.pendingQuestion ? '补充回答后，继续当前任务' : state.busy ? app.id === 'love' ? '正在回复，请稍候…' : '正在执行，请稍候…' : 'Enter 发送 · Shift + Enter 换行' }}</span><span v-if="draft.length > MAX_MESSAGE_LENGTH * 0.9" class="character-count">{{ draft.length }}/{{ MAX_MESSAGE_LENGTH }}</span></span><button v-if="state.busy && !state.pendingQuestion" type="button" class="send-button stop-button" aria-label="停止接收" title="停止接收" @click="stop"><Square :size="14" fill="currentColor" /></button><button v-else type="submit" class="send-button" :disabled="!canSend" :aria-label="state.pendingQuestion ? '提交补充' : '发送消息'" :title="state.pendingQuestion ? '提交补充' : '发送消息'"><ArrowUp :size="20" /></button></div>
        </form>
        <div class="composer-footer"><div class="composer-footer-left"><ContextMeter :key="activeSession?.id" :context="activeSession?.context" :offline="auth.state.offline" :has-messages="!!messages.length" @refresh="refreshContext()" @compact="compact()" /><span>{{auth.state.user?'对话随账号保存':'登录后保存你的对话'}}</span></div><button :title="`会话 ID：${activeSession?.id || ''}`" class="session-id" @click="copyId"><Check v-if="copiedId" :size="11" /><Copy v-else :size="11" /><span>{{ copiedId ? '已复制会话 ID' : copyIdError ? '复制失败' : '复制会话 ID' }}</span></button></div>
        <div v-if="state.storageUnavailable" class="storage-notice">浏览器暂时无法保存记录，本次对话仍可继续。</div>
      </div>
    </div>
    <span class="sr-only" role="status">{{ state.pendingQuestion ? '等待你补充回答' : state.busy ? '正在接收回复' : '可以发送消息' }}</span>
  </main>
</template>
