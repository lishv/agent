import { computed, reactive } from 'vue'
import { streamChat, errorMessage, submitHumanAnswer, cancelConversation, answerErrorMessage, listConversations, getConversation, deleteConversation, renameConversation, getContextUsage, compactContext } from '../services/api.js'
import { useAuth } from './useAuth.js'
import { presentMessage } from '../services/messagePresentation.js'

const STORAGE_KEY = 'huiling.chat.v2.'
const MAX_SESSIONS = 100
const auth = useAuth()
export const createId = () => globalThis.crypto?.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`

function restoreMessages(message, appId, running = false) {
  const restored = {
    ...message,
    status: !running && ['streaming', 'waiting'].includes(message.status) ? 'stopped' : message.status,
    questions: message.questions?.map((question) => ({ ...question, expired: !running && !question.answer || question.expired })) || [],
  }
  if (appId !== 'manus' || !message.steps?.length) return [restored]
  // 已保存的旧版分组消息也拆开显示，保留提问、错误和停止状态。
  const result = message.steps.map((step, index) => ({
    ...restored, id: `${message.id}-step-${index}`, content: step.text,
    stepNumber: step.number, steps: [], questions: [], status: 'done', error: '',
  }))
  if (restored.questions.length || ['error', 'stopped'].includes(restored.status)) {
    result.push({ ...restored, id: `${message.id}-status`, content: '', steps: [], stepNumber: null })
  }
  return result
}

function loadSessions(ownerId) {
  try {
    const cached = JSON.parse(localStorage.getItem(STORAGE_KEY + ownerId) || '{}')
    state.deletedIds = Array.isArray(cached.deletedIds) ? cached.deletedIds : []
    const value = cached.sessions || []
    if (!Array.isArray(value)) return []
    return value.filter((session) => typeof session.id === 'string' && ['love', 'manus'].includes(session.appId)
      && typeof session.title === 'string' && typeof session.createdAt === 'number'
      && Array.isArray(session.messages) && session.messages.every((message) => typeof message.id === 'string'
        && ['user', 'assistant'].includes(message.role) && typeof message.content === 'string'
        && typeof message.createdAt === 'number' && (!message.steps || Array.isArray(message.steps)
          && message.steps.every((step) => typeof step.text === 'string'))
        && (!message.questions || Array.isArray(message.questions)
          && message.questions.every((question) => typeof question.question === 'string'))))
      .slice(0, MAX_SESSIONS).map((session) => ({
        ...session,
        running: false,
        context: session.context ? { ...session.context, status: 'idle', requesting: false } : null,
        messages: session.messages.flatMap((message) => restoreMessages(message, session.appId)),
      }))
  } catch { return [] }
}

const state = reactive({
  sessions: [], activeId: null, storageUnavailable: false, activities: {},
  get busy() { return !!this.activities[this.activeId]?.busy },
  get pendingQuestion() { return this.activities[this.activeId]?.pendingQuestion || null },
  get submittingAnswer() { return !!this.activities[this.activeId]?.submittingAnswer },
  get answerError() { return this.activities[this.activeId]?.answerError || '' },
  failureVersion: 0,
  ownerId: null, loading: true, loadingMessages: false, historyError: '', deletedIds: [],
})
const requests = new Map()
let pollTimer
let polling = false
let selectionVersion = 0
let ownerVersion = 0
const contextRevisions = new Map()
// 历史加载与改名可能同时返回，使用版本号防止旧标题覆盖刚保存的新名称。
const titleRevisions = new Map()

const isRunning = (id) => !!state.activities[id]?.busy
const isCompacting = (session) => ['queued', 'compressing'].includes(session.context?.status)

function syncRemoteActivity(session, running) {
  if (requests.has(session.id)) return
  session.running = !!running
  if (!running) { delete state.activities[session.id]; return }
  const pendingQuestion = session.messages.flatMap(item => item.questions || []).find(question => !question.answer && !question.expired) || null
  const activity = state.activities[session.id] ||= { busy: true, remote: true, submittingAnswer: false, answerError: '' }
  activity.pendingQuestion = pendingQuestion
  schedulePoll()
}

function schedulePoll() {
  if (pollTimer || polling || auth.state.offline || !state.ownerId || !state.sessions.some(session => isRunning(session.id) || isCompacting(session))) return
  const ownerId = state.ownerId
  pollTimer = setTimeout(async () => {
    pollTimer = null
    polling = true
    try {
      for (const session of state.sessions.filter(item => state.activities[item.id]?.remote)) {
        if (state.ownerId !== ownerId || auth.state.offline) return
        await refreshSession(session)
      }
      for (const session of state.sessions.filter(item => isCompacting(item) || isRunning(item.id) && item.id === state.activeId)) {
        if (state.ownerId !== ownerId || auth.state.offline) return
        await refreshContext(session)
      }
    } finally { polling = false; schedulePoll() }
  }, 1500)
  pollTimer.unref?.()
}

async function refreshContext(session = activeSession.value) {
  session = state.sessions.find(item => item.id === session?.id)
  const ownerId = state.ownerId
  if (!session || !ownerId || auth.state.offline || auth.state.loading || session.context?.requesting || !session.saved && !session.messages.length) return
  const epoch = ownerVersion, revision = (contextRevisions.get(session.id) || 0) + 1
  contextRevisions.set(session.id, revision)
  try {
    const data = await getContextUsage(session.id)
    if (ownerVersion !== epoch || contextRevisions.get(session.id) !== revision || state.ownerId !== ownerId || state.deletedIds.includes(session.id) || !state.sessions.includes(session)) return
    if (!Number.isFinite(data.estimatedTokens) || !Number.isFinite(data.usagePercent) || data.inputBudgetTokens <= 0) return
    session.context = data
    persist(); schedulePoll()
  } catch {
    // 显示记录与回复不依赖状态接口；读取失败保留最后一次估算。
    if (state.ownerId === ownerId && !session.context) session.contextUnavailable = true
  }
}

async function compact(session = activeSession.value) {
  session = state.sessions.find(item => item.id === session?.id)
  const ownerId = state.ownerId
  if (!session || !ownerId || auth.state.offline || !session.context?.enabled || session.context.requesting || isCompacting(session)) return
  const epoch = ownerVersion, revision = (contextRevisions.get(session.id) || 0) + 1
  contextRevisions.set(session.id, revision)
  session.context.requesting = true
  try {
    const data = await compactContext(session.id)
    if (ownerVersion !== epoch || contextRevisions.get(session.id) !== revision || state.ownerId !== ownerId || state.deletedIds.includes(session.id) || !state.sessions.includes(session)) return
    session.context = data
    persist(); schedulePoll()
  } catch {
    if (state.ownerId === ownerId && state.sessions.includes(session)) {
      session.context = { ...session.context, status: 'error', message: '压缩请求未提交，可稍后重试；原记忆仍保留', requesting: false }
      persist()
    }
  } finally { if (session.context) session.context.requesting = false }
}

function persist() {
  if (!state.ownerId) return
  try {
    const sessions = state.sessions.filter((session) => session.saved || session.messages.length).slice(0, MAX_SESSIONS)
      .map((session) => ({ ...session, messages: session.messages.map((message) => ({ ...message, content: presentMessage(message.content, session.appId, message.role) })) }))
    localStorage.setItem(STORAGE_KEY + state.ownerId, JSON.stringify({ sessions, deletedIds: state.deletedIds }))
    state.storageUnavailable = false
  } catch { state.storageUnavailable = true }
}

async function activateOwner(ownerId, sync = true) {
  ownerVersion++; contextRevisions.clear(); titleRevisions.clear()
  Object.keys(state.activities).forEach(id => stop(id))
  clearTimeout(pollTimer); pollTimer = null; selectionVersion++
  state.activities = {}; state.loading = true; state.ownerId = ownerId; state.activeId = null; state.sessions = []; state.deletedIds = []; state.historyError = ''
  if (ownerId) state.sessions = loadSessions(ownerId)
  if (ownerId && sync && !auth.state.offline) await refreshHistory()
  if (state.ownerId === ownerId) state.loading = false
}

async function refreshHistory() {
  const ownerId = state.ownerId
  const epoch = ownerVersion
  const revisions = new Map(titleRevisions)
  if (!ownerId || auth.state.offline || auth.state.loading) return
  try {
    for (const id of [...state.deletedIds]) {
      try { await deleteConversation(id) } catch (error) { if (error.response?.status !== 404) throw error }
      if (state.ownerId !== ownerId || ownerVersion !== epoch) return
      state.deletedIds = state.deletedIds.filter((value) => value !== id); persist()
    }
    const summaries = await listConversations()
    if (state.ownerId !== ownerId || ownerVersion !== epoch) return
    const old = new Map(state.sessions.map((session) => [session.id, session]))
    const unsaved = state.sessions.filter((session) => (!session.saved || isRunning(session.id)) && !summaries.some((item) => item.id === session.id))
    state.sessions = [...unsaved, ...summaries.filter((item) => !state.deletedIds.includes(item.id)).map((item) => {
      const session = old.get(item.id) || { messages: [] }
      const title = session.title && titleRevisions.get(item.id) !== revisions.get(item.id) ? session.title : item.title
      Object.assign(session, item, { title, saved: true })
      syncRemoteActivity(session, item.running)
      return session
    })].slice(0, MAX_SESSIONS)
    state.historyError = ''; persist()
    schedulePoll()
  } catch (error) {
    if (state.ownerId !== ownerId || ownerVersion !== epoch) return
    state.historyError = '历史暂时无法同步，已保留当前设备的记录'
    if (!error.response || error.response.status >= 500) auth.state.offline = true
  }
}

async function refreshSession(session) {
  const ownerId = state.ownerId
  if (!ownerId || auth.state.offline || auth.state.loading || requests.has(session.id)) return
  try {
    const data = await getConversation(session.id)
    if (state.ownerId !== ownerId || state.deletedIds.includes(session.id)) return
    if (requests.has(session.id)) return
    session.messages = data.messages.flatMap((message) => restoreMessages(message, session.appId, data.running)); session.saved = true
    syncRemoteActivity(session, data.running)
    session.updatedAt = data.updatedAt; persist()
  } catch (error) {
    if (error.response?.status !== 404 && state.ownerId === ownerId) state.historyError = '暂时无法读取完整历史，已保留缓存内容'
  }
}

async function removeSession(id) {
  if (!state.ownerId) return false
  const session = state.sessions.find((item) => item.id === id)
  if (!session) return false
  stop(id)
  // 本地先隐藏；服务不可达时按账号保留待删除标记，联网后清理服务器记录和记忆文件。
  state.deletedIds = [...new Set([...state.deletedIds, id])]
  state.sessions = state.sessions.filter((item) => item.id !== id)
  if (state.activeId === id) state.activeId = null
  persist()
  if (!auth.state.offline) await refreshHistory()
  return true
}

async function renameSession(id, title) {
  const session = state.sessions.find(item => item.id === id)
  if (!session || !state.ownerId) return false
  const value = title.trim()
  if (!value || value.length > 100) throw new Error('对话名需为 1 至 100 个字符')
  if (auth.state.offline) throw new Error('当前离线，请连接网络后修改对话名')
  const epoch = ownerVersion
  const revision = (titleRevisions.get(id) || 0) + 1
  titleRevisions.set(id, revision)
  const data = await renameConversation(id, value)
  if (ownerVersion !== epoch || titleRevisions.get(id) !== revision || !state.sessions.includes(session) || state.deletedIds.includes(id)) return false
  titleRevisions.set(id, revision + 1)
  session.title = data.title
  session.updatedAt = data.updatedAt
  session.saved = true
  persist()
  return true
}

function stop(id = state.activeId) {
  if (typeof id !== 'string') id = state.activeId
  const activity = state.activities[id]
  if (!activity?.busy) return
  cancelConversation(id).catch(() => {})
  const request = requests.get(id)
  if (request) {
    if (request.session.appId === 'manus' && request.reply.status === 'done') nextReply(request)
    request.controller.abort()
    request.reply.status = 'stopped'
    requests.delete(id)
  }
  const session = state.sessions.find(item => item.id === id)
  session?.messages.forEach(message => {
    if (['streaming', 'waiting'].includes(message.status)) message.status = 'stopped'
    message.questions?.forEach(question => { if (!question.answer) question.expired = true })
  })
  if (session) session.running = false
  delete state.activities[id]
  persist()
}

function newSession(appId) {
  selectionVersion++; state.loadingMessages = false
  state.sessions = state.sessions.filter((session) => session.saved || session.messages.length)
  const session = { id: createId(), appId, title: '新的对话', createdAt: Date.now(), messages: [], saved: false }
  state.sessions.unshift(session)
  state.sessions = state.sessions.slice(0, MAX_SESSIONS)
  state.activeId = session.id
  return session
}

async function selectSession(id) {
  const version = ++selectionVersion
  state.activeId = id
  state.loadingMessages = false
  const session = state.sessions.find((item) => item.id === id)
  if (session?.saved && !auth.state.offline) {
    state.loadingMessages = true
    await refreshSession(session)
    if (version === selectionVersion) state.loadingMessages = false
  }
  refreshContext(session)
}

const activeSession = computed(() => state.sessions.find((session) => session.id === state.activeId))
const createReply = (kind = 'progress') => reactive({
  id: createId(), role: 'assistant', content: '', steps: [], questions: [], stepNumber: null,
  status: 'streaming', error: '', createdAt: Date.now(), kind,
})

function nextReply(request) {
  if (!request.reply.content && !request.reply.questions.length && request.reply.status === 'streaming') return request.reply
  request.reply = createReply()
  request.session.messages.push(request.reply)
  return request.reply
}

function receive(request, type, data) {
  if (request.controller.signal.aborted || request.ownerId !== state.ownerId) return
  if (request.submitting) {
    request.buffer.push({ type, data })
    return
  }
  if (type === 'question') {
    const reply = nextReply(request)
    const question = { ...data, answer: '', expired: false }
    reply.questions.push(question)
    reply.status = 'waiting'
    request.activity.pendingQuestion = question
    request.activity.answerError = ''
    persist()
    return
  }
  if (type === 'final') {
    const reply = nextReply(request)
    reply.kind = 'final'; reply.content = data; reply.status = 'done'
    persist()
    return
  }
  if (request.session.appId === 'love') request.reply.content += data
  else {
    const reply = nextReply(request)
    const match = data.match(/^Step\s+(\d+):\s*([\s\S]*)$/)
    reply.stepNumber = match ? Number(match[1]) : null
    reply.content = match ? match[2] : data
    reply.status = 'done'
    persist()
  }
}

async function send(text) {
  const session = activeSession.value
  const message = text.trim()
  if (!message || !session || state.busy) return
  if (!auth.state.user) { if (typeof window !== 'undefined') window.dispatchEvent(new Event('huiling:login-requested')); return }
  if (auth.state.offline || state.loading) return
  session.messages.push({ id: createId(), role: 'user', content: message, createdAt: Date.now() })
  if (session.messages.length === 1) session.title = message.slice(0, 24)
  const reply = createReply(session.appId === 'love' ? 'final' : 'progress')
  session.messages.push(reply)
  const request = {
    controller: new AbortController(), reply, session, runId: session.appId === 'manus' ? createId() : null,
    submitting: false, buffer: [], answerPromise: null, ownerId: state.ownerId,
    activity: reactive({ busy: true, remote: false, pendingQuestion: null, submittingAnswer: false, answerError: '' }),
  }
  requests.set(session.id, request)
  state.activities[session.id] = request.activity
  session.running = true
  schedulePoll()
  persist()
  try {
    await streamChat({
      appId: session.appId, message, chatId: session.id, runId: request.runId,
      signal: request.controller.signal,
      onMessage: (chunk) => receive(request, 'message', chunk),
      onQuestion: (question) => receive(request, 'question', question),
      onFinalAnswer: (content) => receive(request, 'final', content),
    })
    if (request.answerPromise) await request.answerPromise
    if (!request.controller.signal.aborted) request.reply.status = 'done'
  } catch (error) {
    if (request.answerPromise) await request.answerPromise
    if (request.controller.signal.aborted) request.reply.status = 'stopped'
    else {
      if (session.appId === 'manus') nextReply(request)
      request.reply.status = 'error'
      request.reply.error = errorMessage(error)
      if (state.activeId === session.id && state.ownerId === request.ownerId) state.failureVersion++
    }
  } finally {
    session.messages.forEach((item) => item.questions?.forEach((question) => {
      if (question.runId === request.runId && !question.answer) {
        question.expired = true
        if (item.status === 'waiting') item.status = 'stopped'
      }
    }))
    if (request.reply.status === 'done' && !request.reply.content && !request.reply.questions.length) {
      session.messages = session.messages.filter((item) => item.id !== request.reply.id)
    }
    if (requests.get(session.id) === request) {
      requests.delete(session.id)
      delete state.activities[session.id]
      session.running = false
    }
    persist()
    if (!request.controller.signal.aborted && state.sessions.includes(session)) {
      await refreshSession(session)
      await refreshHistory()
      await refreshContext(session)
    }
  }
}

async function answer(text) {
  const session = activeSession.value
  const activity = state.activities[session?.id]
  const request = requests.get(session?.id)
  const question = state.pendingQuestion
  const value = text.trim()
  if (!activity || !question || !value || activity.submittingAnswer) return false
  if (!request) return answerRemote(session, activity, question, value)
  activity.submittingAnswer = true
  activity.answerError = ''
  request.submitting = true
  // 缓存提交期间的 SSE，确保回答先出现在右侧，后续进展再出现在左侧。
  request.answerPromise = (async () => {
    let accepted = false
    try {
      await submitHumanAnswer({ runId: question.runId, questionId: question.questionId, answer: value, signal: request.controller.signal })
      if (request.controller.signal.aborted) return false
      accepted = true
      question.answer = value
      request.reply.status = 'done'
      request.session.messages.push({ id: createId(), role: 'user', content: value, humanAnswer: true, createdAt: Date.now() })
      request.reply = createReply()
      request.session.messages.push(request.reply)
      if (activity.pendingQuestion?.questionId === question.questionId) activity.pendingQuestion = null
    } catch (error) {
      if (!request.controller.signal.aborted && requests.get(session.id) === request) {
        activity.answerError = answerErrorMessage(error)
        if (state.activeId === session.id) state.failureVersion++
      }
    } finally {
      request.submitting = false
      const events = request.buffer.splice(0)
      events.forEach((event) => receive(request, event.type, event.data))
      activity.submittingAnswer = false
      persist()
    }
    return accepted
  })()
  return request.answerPromise
}

async function answerRemote(session, activity, question, value) {
  const ownerId = state.ownerId
  activity.submittingAnswer = true; activity.answerError = ''
  try {
    await submitHumanAnswer({ runId: question.runId, questionId: question.questionId, answer: value })
    if (state.ownerId !== ownerId) return false
    activity.pendingQuestion = null
    await refreshSession(session)
    return true
  } catch (error) {
    if (state.ownerId === ownerId) {
      activity.answerError = answerErrorMessage(error)
      if (state.activeId === session.id) state.failureVersion++
    }
    return false
  } finally { activity.submittingAnswer = false }
}

export function useChat() {
  return { state, activeSession, newSession, selectSession, send, answer, stop, activateOwner, refreshHistory, removeSession, renameSession, isRunning, refreshContext, compact }
}
