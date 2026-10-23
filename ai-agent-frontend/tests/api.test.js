import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { http, streamChat, errorMessage, submitHumanAnswer, cancelManusRun, answerErrorMessage, setCsrfToken, setAccountId } from '../src/services/api.js'
import { downloadGeneratedFile, accountError } from '../src/services/api.js'

let server
let requests = []
let posts = []
let requestHeaders = []

before(async () => {
  server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://localhost')
    requests.push(url)
    requestHeaders.push(request.headers)
    if (url.pathname.startsWith('/api/files/')) {
      if (url.searchParams.get('name') === 'expired.pdf') {
        response.writeHead(404, { 'Content-Type': 'application/json' })
        response.end(JSON.stringify({ code: 40400, message: '文件不存在或已过期，请重新生成后下载' })); return
      }
      response.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Disposition': "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87.pdf" })
      response.end('%PDF-test-content'); return
    }
    let value = {}
    if (request.method === 'POST') {
      let body = ''
      for await (const chunk of request) body += chunk
      value = JSON.parse(body)
      posts.push({ path: url.pathname, body: value })
      if (value.displayName === 'account-changed') {
        response.writeHead(409, { 'Content-Type': 'application/json' })
        response.end('{"code":40901,"message":"账号已切换"}'); return
      }
      if (url.pathname === '/api/auth/code-login' || url.pathname === '/api/auth/nickname' && value.displayName === 'unauthorized') {
        response.writeHead(401, { 'Content-Type': 'application/json' })
        response.end('{"message":"登录验证失败"}'); return
      }
      if (!url.pathname.endsWith('/chat') && !url.pathname.endsWith('/chat/sse')) {
        response.writeHead(value.answer === 'conflict' ? 409 : 200, { 'Content-Type': 'application/json' })
        response.end(JSON.stringify({ accepted: true, cancelled: true }))
        return
      }
    }
    const text = value.message || url.searchParams.get('message')
    if (text === 'http-error') {
      response.writeHead(503, { 'Content-Type': 'application/json' })
      response.end('{"error":"unavailable"}')
      return
    }
    if (text === 'html') {
      response.writeHead(200, { 'Content-Type': 'text/html' })
      response.end('<h1>wrong server</h1>')
      return
    }
    response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8' })
    if (text === 'final-answer') {
      response.end('data: Step 1: 已读取资料\n\nevent: final_answer\ndata: 第一段最终答案\ndata: 第二段最终答案\n\nevent: complete\ndata: {"reason":"finished"}\n\ndata: ignored\n\n'); return
    }
    if (text === 'slow') {
      response.write('data: 开始\n\n')
      const timer = setInterval(() => response.write(': keepalive\n\n'), 30)
      response.on('close', () => clearInterval(timer))
      return
    }
    if (text === 'error') { response.end('data: 执行错误：模型连接失败\n\n'); return }
    if (text === 'error-event') { response.end('data: 第一段\n\nevent: error\ndata: 服务暂时不可用，请稍后重试\n\n'); return }
    if (text === 'empty') { response.end(': ping\n\n'); return }
    if (text === 'done') { response.end('data: hello\n\ndata: [DONE]\n\ndata: ignored\n\n'); return }
    if (text === 'question' || text === 'wrong-question') {
      const runId = text === 'wrong-question' ? 'another-run' : value.runId
      response.end(`event: run_started\ndata: {"runId":"${runId}"}\n\nevent: human_question\ndata: ${JSON.stringify({ runId, questionId: 'question-123', question: '你的任务目标？' })}\n\ndata: Step 1: 收到回答\n\n`)
      return
    }
    if (url.pathname.endsWith('/manus/chat')) {
      response.end('data: Step 1: 工具 search 返回结果\n\ndata: Step 2: 工具 doTerminate 返回结果\n\n')
      return
    }
    // 特意在 UTF-8 字节中间切开中文，模拟真实网络分片。
    const bytes = Buffer.from('data: 你好 \r\n\r\ndata: 世界\n\n')
    response.write(bytes.subarray(0, 7))
    setTimeout(() => response.end(bytes.subarray(7)), 15)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  http.defaults.baseURL = `http://127.0.0.1:${server.address().port}/api`
})

after(async () => {
  server.closeAllConnections()
  await new Promise((resolve) => server.close(resolve))
})

const run = (message, options = {}) => streamChat({ appId: 'love', chatId: 'room-123', signal: new AbortController().signal, onMessage: () => {}, message, ...options })

test('文件下载携带账号身份并通过 API 客户端获取 Blob，过期错误仍可读', async () => {
  const previousAdapter = http.defaults.adapter
  http.defaults.adapter = 'fetch'
  setAccountId('owner-123')
  try {
    const result = await downloadGeneratedFile('/custom/api/files/pdf/download-run-1?name=report.pdf')
    assert.equal(result.filename, '中文.pdf')
    assert.equal(await result.blob.text(), '%PDF-test-content')
    assert.equal(requests.at(-1).pathname, '/api/files/pdf/download-run-1')
    assert.equal(requestHeaders.at(-1)['x-account-id'], 'owner-123')
    await assert.rejects(downloadGeneratedFile('/api/files/pdf/download-run-1?name=expired.pdf'), error => {
      assert.equal(error.response.status, 404)
      assert.match(accountError(error), /文件不存在或已过期/)
      return true
    })
  } finally {
    http.defaults.adapter = previousAdapter
    setAccountId('')
  }
})

test('最终回复的同站完整链接去除用户目录后，仍携带当前账号身份下载', async () => {
  const previousAdapter = http.defaults.adapter
  const previousLocation = globalThis.location
  http.defaults.adapter = 'fetch'
  globalThis.location = { origin: 'http://agent.huilingyun.asia' }
  setAccountId('owner-123')
  try {
    const result = await downloadGeneratedFile('http://agent.huilingyun.asia/api/files/pdf/2110254732141473793/mvkzh3tn-5ouslefw8x8-rsn5s7hsimo?name=hangzhou-trip-plan-v2.pdf')
    assert.equal(await result.blob.text(), '%PDF-test-content')
    assert.equal(requests.at(-1).pathname, '/api/files/pdf/mvkzh3tn-5ouslefw8x8-rsn5s7hsimo')
    assert.equal(requests.at(-1).search, '?name=hangzhou-trip-plan-v2.pdf')
    assert.equal(requestHeaders.at(-1)['x-account-id'], 'owner-123')
    assert.equal(result.filename, '中文.pdf')
  } finally {
    http.defaults.adapter = previousAdapter
    setAccountId('')
    if (previousLocation === undefined) delete globalThis.location
    else globalThis.location = previousLocation
  }
})

test('最终答案事件与进展分别处理，完成事件之后不继续输出', async () => {
  const progress = [], final = []
  await run('final-answer', { appId: 'manus', onMessage: text => progress.push(text), onFinalAnswer: text => final.push(text) })
  assert.deepEqual(progress, ['Step 1: 已读取资料'])
  assert.deepEqual(final, ['第一段最终答案\n第二段最终答案'])
})

test('图片预览可取消附件请求，不影响原有下载调用', async () => {
  const controller = new AbortController()
  controller.abort()
  const before = requests.length
  await assert.rejects(downloadGeneratedFile('/api/files/download/download-run-1?name=cat.png', { signal: controller.signal }), error => error.code === 'ERR_CANCELED')
  assert.equal(requests.length, before)
})

test('昵称修改携带账号归属与 CSRF，验证码登录失败不触发会话过期', async () => {
  const previousWindow = globalThis.window, events = []
  globalThis.window = { dispatchEvent: event => events.push(event.type) }
  setCsrfToken('csrf-test'); setAccountId('101')
  try {
    await http.post('/auth/nickname', { displayName: '绘灵小李' })
    assert.equal(requestHeaders.at(-1)['x-account-id'], '101')
    assert.equal(requestHeaders.at(-1)['x-csrf-token'], 'csrf-test')
    await assert.rejects(http.post('/auth/code-login', { email: 'missing@example.test', code: '345678' }), error => error.response.status === 401)
    assert.deepEqual(events, [])
    await assert.rejects(http.post('/auth/nickname', { displayName: 'unauthorized' }), error => error.response.status === 401)
    assert.deepEqual(events, ['huiling:session-expired'])
  } finally {
    setCsrfToken(''); setAccountId('')
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
  }
})

test('统一数字错误码触发账号切换后的登录状态刷新', async () => {
  const previousWindow = globalThis.window, events = []
  globalThis.window = { dispatchEvent: event => events.push(event.type) }
  try {
    await assert.rejects(http.post('/auth/nickname', { displayName: 'account-changed' }), error => error.response.data.code === 40901)
    assert.deepEqual(events, ['huiling:session-expired'])
  } finally {
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
  }
})

test('正确调用恋爱接口、编码中文和特殊字符，并保留完整流式文本', async () => {
  const chunks = []
  await run('你好 & #? + 世界', { onMessage: (chunk) => chunks.push(chunk) })
  const url = requests.at(-1)
  assert.equal(url.pathname, '/api/ai/love_app/chat/sse')
  assert.equal(url.search, '')
  assert.deepEqual(posts.at(-1).body, { message: '你好 & #? + 世界', chatId: 'room-123' })
  assert.equal(chunks.join(''), '你好 世界')
})

test('智能体按独立步骤返回，并发送用于文件记忆的 chatId', async () => {
  const chunks = []
  await run('任务', { appId: 'manus', onMessage: (chunk) => chunks.push(chunk) })
  assert.equal(requests.at(-1).pathname, '/api/ai/manus/chat')
  assert.equal(posts.at(-1).body.chatId, 'room-123')
  assert.equal(chunks.length, 2)
  assert.match(chunks[1], /^Step 2:/)
})

test('正常 EOF 不自动重发请求', async () => {
  const start = requests.length
  await run('normal')
  await new Promise((resolve) => setTimeout(resolve, 70))
  assert.equal(requests.length - start, 1)
})

test('DONE 之后不追加额外消息', async () => {
  const chunks = []
  await run('done', { onMessage: (chunk) => chunks.push(chunk) })
  assert.deepEqual(chunks, ['hello'])
})

test('AbortController 在接收中取消，停止后不继续回调', async () => {
  const controller = new AbortController()
  const chunks = []
  await assert.rejects(run('slow', {
    signal: controller.signal,
    onMessage(chunk) { chunks.push(chunk); controller.abort() },
  }), /已停止接收|abort|cancel/i)
  assert.deepEqual(chunks, ['开始'])
})

test('后端文本错误、空响应及非 SSE 内容明确报错', async () => {
  await assert.rejects(run('error'), /模型连接失败/)
  await assert.rejects(run('empty'), /没有收到回复/)
  await assert.rejects(run('html'), /没有返回流式内容/)
})

test('恋爱回答中途出现命名错误事件时保留已接收内容并停止', async () => {
  const messages = [], previous = requests.length
  await assert.rejects(run('error-event', { onMessage: text => messages.push(text) }), /服务暂时不可用/)
  assert.deepEqual(messages, ['第一段'])
  assert.equal(requests.length - previous, 1)
})

test('HTTP 错误只显示友好提示', async () => {
  try { await run('http-error'); assert.fail('should reject') }
  catch (error) { assert.match(errorMessage(error), /503/) }
})

test('提问事件单独交给交互回调，元数据不会污染执行步骤', async () => {
  const questions = []
  const messages = []
  await run('question', { appId: 'manus', runId: 'run-local-123', onQuestion: (question) => questions.push(question), onMessage: (message) => messages.push(message) })
  assert.equal(posts.at(-1).body.runId, 'run-local-123')
  assert.deepEqual(questions, [{ runId: 'run-local-123', questionId: 'question-123', question: '你的任务目标？' }])
  assert.deepEqual(messages, ['Step 1: 收到回答'])
})

test('拒绝不属于当前运行的问题', async () => {
  await assert.rejects(run('wrong-question', { appId: 'manus', runId: 'run-local-123', onQuestion: () => assert.fail('unexpected question') }), /不属于当前任务/)
})

test('回答带运行和问题标识通过 POST 提交，重复或过期问题给出明确错误', async () => {
  assert.match(answerErrorMessage({ response: { status: 400 } }), /20000/)
  await submitHumanAnswer({ runId: 'run-local-123', questionId: 'question-123', answer: '学习 Java' })
  assert.deepEqual(posts.at(-1), { path: '/api/ai/manus/answer', body: { runId: 'run-local-123', questionId: 'question-123', answer: '学习 Java' } })
  await assert.rejects(submitHumanAnswer({ runId: 'run-local-123', questionId: 'question-123', answer: 'conflict' }), (error) => {
    assert.match(answerErrorMessage(error), /已回答或已过期/)
    return true
  })
})

test('取消接口只取消指定运行', async () => {
  await cancelManusRun('run-local-123')
  assert.deepEqual(posts.at(-1), { path: '/api/ai/manus/cancel', body: { runId: 'run-local-123' } })
})

test('长中文文本在正文中完整传递，URL 不包含聊天内容', async () => {
  const message = '中'.repeat(20000)
  await run(message)
  assert.equal(posts.at(-1).body.message, message)
  assert.equal(requests.at(-1).search, '')
})
