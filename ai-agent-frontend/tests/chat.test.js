import { before, after, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'

const legacy = [{
  id: 'old-room', appId: 'manus', title: '旧对话', createdAt: 1,
  messages: [{
    id: 'old-reply', role: 'assistant', content: '分组的旧步骤', createdAt: 2, status: 'done',
    steps: [{ number: 1, text: '搜索完成' }, { number: 2, text: '读取完成' }],
  }],
}]
let saved = JSON.stringify({ sessions: legacy, deletedIds: [] })
globalThis.localStorage = { getItem: () => saved, setItem: (key, value) => { saved = value } }
const { useChat } = await import('../src/composables/useChat.js')
const { http } = await import('../src/services/api.js')
const chat = useChat()
const { useAuth } = await import('../src/composables/useAuth.js')
useAuth().state.user = { id: '101', email: 'test@example.test', displayName: '测试' }
useAuth().state.loading = false
await chat.activateOwner('101', false)
let server
let lastQuery
let lastBody
const backgroundResponses = new Map()
let remoteHistory = []
const remoteDetails = new Map()
const contextUsages = new Map()
const compactionRequests = []
let holdHistory = false
let heldHistory
let renameFailure = false
let holdRename = false
let heldRename
async function until(check) {
  const deadline = Date.now() + 3000
  while (!check()) {
    if (Date.now() > deadline) assert.fail('未收到预期的异步更新')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

before(async () => {
  server = createServer(async (request, response) => {
    lastQuery = new URL(request.url, 'http://localhost')
    if (request.method === 'GET') {
      if (lastQuery.pathname.endsWith('/chats') && holdHistory) {
        const snapshot = JSON.stringify(remoteHistory)
        heldHistory = () => { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(snapshot) }
        return
      }
      if (lastQuery.pathname.endsWith('/context')) {
        const usage = contextUsages.get(lastQuery.pathname.split('/').at(-2))
        response.writeHead(usage ? 200 : 404, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(usage || {})); return
      }
      const detail = remoteDetails.get(lastQuery.pathname.split('/').at(-1))
      response.writeHead(lastQuery.pathname.endsWith('/chats') || detail ? 200 : 404, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify(lastQuery.pathname.endsWith('/chats') ? remoteHistory : detail || {}))
      return
    }
    let body = ''
    for await (const chunk of request) body += chunk
    lastBody = body ? JSON.parse(body) : {}
    if (request.method === 'PUT' && lastQuery.pathname.endsWith('/title')) {
      const item = remoteHistory.find(item => item.id === lastQuery.pathname.split('/').at(-2))
      if (!item || renameFailure) {
        response.writeHead(item ? 503 : 404, { 'Content-Type': 'application/json' }); response.end('{"message":"名称未保存"}'); return
      }
      Object.assign(item, { title: lastBody.title, updatedAt: Date.now() })
      const finish = () => { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(item)) }
      if (holdRename) heldRename = finish
      else finish()
      return
    }
    if (lastQuery.pathname.endsWith('/context/compact')) {
      const id = lastQuery.pathname.split('/').at(-3)
      compactionRequests.push(id)
      const usage = { ...contextUsages.get(id), status: 'queued', message: '等待安全位置' }
      contextUsages.set(id, usage)
      response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(usage)); return
    }
    if (lastQuery.pathname.endsWith('/cancel')) {
      response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{"cancelled":true}'); return
    }
    if (lastQuery.pathname.endsWith('/manus/answer')) {
      const detail = remoteDetails.get('remote-task')
      if (detail) {
        detail.messages[0].questions[0].answer = lastBody.answer
        detail.messages[0].status = 'done'
        detail.running = false
      }
      response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{"accepted":true}'); return
    }
    response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8' })
    if (lastBody.message === '后台执行') {
      backgroundResponses.set(lastBody.chatId, response)
      response.write(lastQuery.pathname.endsWith('/manus/chat') ? 'data: Step 1: 已开始\n\n' : 'data: 已开始\n\n')
      return
    }
    if (lastBody.message === '带最终答案的任务') {
      response.end('data: Step 1: 正在检索资料\n\nevent: final_answer\ndata: 这是完整最终答案。\n\nevent: complete\ndata: {"reason":"finished"}\n\n'); return
    }
    response.end(lastQuery.pathname.endsWith('/manus/chat')
      ? 'data: Step 1: 搜索完成\n\ndata: Step 2: 读取完成\n\n'
      : 'data: 第一段\n\ndata: 第二段\n\n')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  http.defaults.baseURL = `http://127.0.0.1:${server.address().port}/api`
})
after(async () => {
  server.closeAllConnections()
  await new Promise((resolve) => server.close(resolve))
})

test('旧版分组历史也恢复为独立步骤气泡', () => {
  const restored = chat.state.sessions.find((session) => session.id === 'old-room')
  assert.equal(restored.messages.length, 2)
  assert.deepEqual(restored.messages.map((message) => message.stepNumber), [1, 2])
  assert.deepEqual(restored.messages.map((message) => message.content), ['搜索完成', '读取完成'])
})

test('每条智能体 SSE 是独立消息，使用同一会话 ID，不留下空气泡', async () => {
  const session = chat.newSession('manus')
  await chat.send('执行任务')
  assert.equal(session.messages.length, 3)
  assert.deepEqual(session.messages.map((message) => message.role), ['user', 'assistant', 'assistant'])
  assert.deepEqual(session.messages.slice(1).map((message) => message.stepNumber), [1, 2])
  assert.equal(lastBody.chatId, session.id)
  assert.equal(chat.state.busy, false)
  assert.equal(JSON.parse(saved).sessions[0].messages.length, 3)
})

test('恋爱大师的流式片段仍合并为同一个回复', async () => {
  const session = chat.newSession('love')
  await chat.send('你好')
  assert.equal(session.messages.length, 2)
  assert.equal(session.messages[1].content, '第一段第二段')
})

test('进展和最终答案分别保存，刷新本机缓存后仍能识别最终答案', async () => {
  const session = chat.newSession('manus')
  await chat.send('带最终答案的任务')
  assert.deepEqual(session.messages.slice(1).map(message => message.kind), ['progress', 'final'])
  assert.equal(session.messages.at(-1).content, '这是完整最终答案。')
  const cached = JSON.parse(saved).sessions.find(item => item.id === session.id)
  assert.equal(cached.messages.at(-1).kind, 'final')
  await chat.activateOwner('101', false)
  assert.equal(chat.state.sessions.find(item => item.id === session.id).messages.at(-1).kind, 'final')
})

test('创建新对话保留尚未加载消息的服务器历史', () => {
  chat.state.sessions.push({ id:'server-history',appId:'love',title:'服务器历史',createdAt:Date.now(),messages:[],saved:true })
  chat.newSession('manus')
  assert.ok(chat.state.sessions.some((session)=>session.id==='server-history'))
})

for (const appId of ['love', 'manus']) {
  test(`${appId} 切换对话后继续执行，两个会话的进度和停止操作彼此独立`, async () => {
    const first = chat.newSession(appId)
    const firstSend = chat.send('后台执行')
    let secondSend
    try {
      await until(() => first.messages.at(-1)?.content === '已开始')
      const second = chat.newSession(appId === 'love' ? 'manus' : 'love')
      assert.notEqual(first.messages.at(-1).status, 'stopped')
      assert.equal(chat.state.busy, false)
      secondSend = chat.send('后台执行')
      await until(() => second.messages.at(-1)?.content === '已开始')
      await chat.selectSession(first.id)
      assert.equal(chat.state.busy, true)
      backgroundResponses.get(first.id).end(appId === 'manus'
        ? 'event: final_answer\ndata: 原会话已完成\n\nevent: complete\ndata: {}\n\n'
        : 'data: 原会话已完成\n\n')
      await firstSend
      assert.equal(chat.state.busy, false)
      assert.ok(first.messages.some(message => message.content.includes('原会话已完成')))
      await chat.selectSession(second.id)
      assert.equal(chat.state.busy, true)
      chat.stop()
      await secondSend
      assert.equal(second.messages.at(-1).status, 'stopped')
    } finally {
      chat.stop()
      backgroundResponses.forEach(response => response.end())
      backgroundResponses.clear()
      await Promise.allSettled([firstSend, secondSend])
    }
  })
}

test('页面重新加载后恢复服务端正在运行的任务、有效追问与完成状态，不重新发送任务', async () => {
  const summary = { id: 'remote-task', appId: 'manus', title: '服务端任务', createdAt: Date.now(), running: true }
  remoteHistory = [summary]
  remoteDetails.set(summary.id, { ...summary, messages: [{ id: 'remote-question', role: 'assistant', content: '', createdAt: Date.now(), status: 'waiting', questions: [{ runId: 'remote-run', questionId: 'remote-question-id', question: '继续做什么？', answer: '', expired: false }] }] })
  try {
    await chat.activateOwner('101')
    await chat.selectSession(summary.id)
    assert.equal(chat.state.busy, true)
    assert.equal(chat.state.pendingQuestion.expired, false)
    chat.newSession('love')
    assert.equal(chat.state.busy, false)
    assert.equal(chat.state.pendingQuestion, null)
    await chat.selectSession(summary.id)
    assert.equal(chat.state.pendingQuestion.questionId, 'remote-question-id')
    assert.equal(await chat.answer('完成原任务'), true)
    assert.equal(chat.state.busy, false)
    assert.equal(chat.state.pendingQuestion, null)
    assert.equal(lastBody.answer, '完成原任务')
  } finally {
    remoteHistory = []; remoteDetails.clear()
    await chat.activateOwner('101', false)
  }
})

test('后台 SSE 在刷新历史列表后仍写入同一个会话，不被摘要替换丢失', async () => {
  const session = chat.newSession('love')
  const sending = chat.send('后台执行')
  try {
    await until(() => session.messages.at(-1)?.content === '已开始')
    remoteHistory = [{ id: session.id, appId: 'love', title: session.title, createdAt: session.createdAt, running: true }]
    await chat.refreshHistory()
    chat.newSession('manus')
    backgroundResponses.get(session.id).end('data: 后续完整回复\n\n')
    await sending
    await chat.selectSession(session.id)
    assert.equal(chat.activeSession.value.messages.at(-1).content, '已开始后续完整回复')
  } finally { remoteHistory = []; backgroundResponses.forEach(response => response.end()); backgroundResponses.clear(); await sending }
})

test('上下文占用、手动压缩状态和缓存按会话隔离，切换对话不会显示另一会话的进度', async () => {
  const first = chat.newSession('love')
  first.messages.push({id:'context-user',role:'user',content:'已有对话',createdAt:Date.now()})
  const second = chat.newSession('manus')
  second.messages.push({id:'context-user-2',role:'user',content:'其他对话',createdAt:Date.now()})
  const usage = { enabled:true,estimated:true,estimatedTokens:10000,inputBudgetTokens:28672,usagePercent:34.9,status:'idle' }
  contextUsages.set(first.id, usage); contextUsages.set(second.id, {...usage,usagePercent:55,estimatedTokens:15769})
  try {
    await chat.refreshContext(first); await chat.refreshContext(second)
    assert.ok(second.context, JSON.stringify({offline:useAuth().state.offline,loading:useAuth().state.loading,owner:chat.state.ownerId,url:lastQuery?.pathname,present:chat.state.sessions.some(item=>item.id===second.id)}))
    await chat.compact(first)
    assert.equal(chat.activeSession.value.id, second.id)
    assert.equal(chat.activeSession.value.context.usagePercent, 55)
    assert.equal(chat.activeSession.value.context.status, 'idle')
    assert.equal(first.context.status, 'queued')
    assert.equal(compactionRequests.at(-1), first.id)
    contextUsages.set(first.id, {...usage,status:'completed',estimatedTokens:3000,usagePercent:10.5,compactionCount:1})
    await chat.refreshContext(first)
    assert.equal(JSON.parse(saved).sessions.find(item => item.id===first.id).context.compactionCount, 1)
    assert.equal(second.context.usagePercent,55)
  } finally { contextUsages.clear(); await chat.activateOwner('101',false) }
})

test('改名保存到服务器和本机缓存，迟到的历史列表不能覆盖名称', async () => {
  const session = chat.newSession('manus')
  session.messages.push({ id: 'rename-user', role: 'user', content: '原始问题', createdAt: Date.now() })
  session.saved = true
  remoteHistory = [{ id: session.id, appId: session.appId, title: '原始问题', createdAt: session.createdAt, running: false }]
  try {
    holdHistory = true
    const refreshing = chat.refreshHistory()
    await until(() => heldHistory)
    assert.equal(await chat.renameSession(session.id, '  新的任务名称  '), true)
    assert.equal(lastBody.title, '新的任务名称')
    heldHistory(); heldHistory = null; holdHistory = false
    await refreshing
    assert.equal(chat.state.sessions.find(item => item.id === session.id).title, '新的任务名称')
    assert.equal(session.messages[0].content, '原始问题')
    assert.equal(JSON.parse(saved).sessions.find(item => item.id === session.id).title, '新的任务名称')
    await chat.activateOwner('101', false)
    assert.equal(chat.state.sessions.find(item => item.id === session.id).title, '新的任务名称')
  } finally { holdHistory = false; heldHistory?.(); heldHistory = null; remoteHistory = [] }
})

test('改名失败或离线时保留原标题，空名称和超长名称无法提交', async () => {
  const session = chat.newSession('love')
  session.title = '原来的名称'; session.saved = true
  remoteHistory = [{ id: session.id, appId: session.appId, title: session.title, createdAt: session.createdAt }]
  try {
    await assert.rejects(chat.renameSession(session.id, '   '), /1 至 100/)
    await assert.rejects(chat.renameSession(session.id, '字'.repeat(101)), /1 至 100/)
    useAuth().state.offline = true
    await assert.rejects(chat.renameSession(session.id, '离线名称'), /当前离线/)
    useAuth().state.offline = false; renameFailure = true
    await assert.rejects(chat.renameSession(session.id, '失败的名称'))
    assert.equal(session.title, '原来的名称')
  } finally { useAuth().state.offline = false; renameFailure = false; remoteHistory = [] }
})

test('改名等待期间切换账号，返回结果不会污染新账号的历史', async () => {
  const session = chat.newSession('love')
  session.title = '旧账号名称'; session.saved = true
  remoteHistory = [{ id: session.id, appId: session.appId, title: session.title, createdAt: session.createdAt }]
  let renaming
  try {
    holdRename = true
    renaming = chat.renameSession(session.id, '迟到的名称')
    await until(() => heldRename)
    await chat.activateOwner('102', false)
    const titles = chat.state.sessions.map(item => item.title)
    heldRename(); heldRename = null; holdRename = false
    assert.equal(await renaming, false)
    assert.deepEqual(chat.state.sessions.map(item => item.title), titles)
  } finally { holdRename = false; heldRename?.(); heldRename = null; await renaming; remoteHistory = []; await chat.activateOwner('101', false) }
})
