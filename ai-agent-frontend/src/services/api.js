import axios from 'axios'
import { createSseParser } from './sse.js'
import { generatedFileRequest, downloadFilename } from './fileDownloads.js'
import { MAX_MESSAGE_LENGTH } from '../constants/chat.js'

export const http = axios.create({ baseURL: import.meta.env?.VITE_API_BASE_URL || '/api', withCredentials: true })
let csrfToken = ''
let accountId = ''
export const setCsrfToken = (token) => { csrfToken = token || '' }
export const setAccountId = (id) => { accountId = id || '' }
http.interceptors.request.use((config) => {
  if (csrfToken && !['get', 'head', 'options'].includes(config.method)) config.headers.set('X-CSRF-TOKEN', csrfToken)
  if (accountId && /^\/(ai|chats|files)(\/|$)|^\/auth\/(password|nickname)$/.test(config.url || '')) config.headers.set('X-Account-ID', accountId)
  return config
})
http.interceptors.response.use((response) => response, (error) => {
  const isLogin = ['/auth/login', '/auth/code-login'].includes(error.config?.url)
  if ((error.response?.status === 401 && !isLogin || error.response?.data?.code === 40901) && typeof window !== 'undefined')
    window.dispatchEvent(new Event('huiling:session-expired'))
  return Promise.reject(error)
})

export const listConversations = async () => (await http.get('/chats', { timeout: 10000 })).data
export const getConversation = async (id) => (await http.get(`/chats/${encodeURIComponent(id)}`, { timeout: 10000 })).data
export const deleteConversation = (id) => http.delete(`/chats/${encodeURIComponent(id)}`, { timeout: 10000 })
export const renameConversation = async (id, title) => (await http.put(`/chats/${encodeURIComponent(id)}/title`, { title }, { timeout: 10000 })).data
export const cancelConversation = (id) => http.post(`/chats/${encodeURIComponent(id)}/cancel`, {}, { timeout: 5000 })
export const getContextUsage = async (id) => (await http.get(`/chats/${encodeURIComponent(id)}/context`, { timeout: 10000 })).data
export const compactContext = async (id) => (await http.post(`/chats/${encodeURIComponent(id)}/context/compact`, {}, { timeout: 10000 })).data

/** 使用已配置的后端地址与登录态获取附件；过期等失败响应仍按业务错误展示。 */
export async function downloadGeneratedFile(href) {
  const path = generatedFileRequest(href)
  if (!path) throw new Error('下载链接无效，请重新生成文件。')
  try {
    const response = await http.get(path, { responseType: 'blob', timeout: 120000 })
    return { blob: response.data, filename: downloadFilename(response.headers.get('content-disposition'), href) }
  } catch (error) {
    if (error.response?.data instanceof Blob) {
      try { error.response.data = JSON.parse(await error.response.data.text()) } catch { /* 沿用通用下载错误。 */ }
      if (error.response.data?.code === 40901 && typeof window !== 'undefined') window.dispatchEvent(new Event('huiling:session-expired'))
    }
    throw error
  }
}

export function accountError(error) {
  const message = error.response?.data?.message
  return typeof message === 'string' ? message : error.response ? '操作失败，请稍后重试' : !axios.isAxiosError(error) && error.message ? error.message : '暂时无法连接服务，请检查网络'
}

const endpoints = {
  love: '/ai/love_app/chat/sse',
  manus: '/ai/manus/chat',
}

/** Axios fetch adapter 在浏览器里返回 ReadableStream，正常 EOF 直接结束，不自动重发任务。 */
export async function streamChat({ appId, message, chatId, runId, signal, onMessage, onQuestion, onFinalAnswer }) {
  let reader
  let onAbort
  let finished = false
  let received = false
  try {
    const response = await http.post(endpoints[appId], {
      message, chatId, ...(appId === 'manus' && runId ? { runId } : {}),
    }, {
      adapter: 'fetch',
      responseType: 'stream',
      headers: { Accept: 'text/event-stream' },
      signal,
      timeout: 0,
    })
    if (!response.headers.get('content-type')?.includes('text/event-stream')) {
      await response.data?.cancel?.()
      throw new Error('接口没有返回流式内容，请检查后端是否正常运行。')
    }
    reader = response.data.getReader()
    onAbort = () => { reader.cancel().catch(() => {}) }
    signal.addEventListener('abort', onAbort, { once: true })
    if (signal.aborted) throw new DOMException('已停止接收', 'AbortError')
    const decoder = new TextDecoder('utf-8')
    const parser = createSseParser(({ event, data }) => {
      if (finished) return
      if (event === 'run_started') return
      if (event === 'final_answer') {
        if (!data.trim()) throw new Error('未收到最终回答，请重试。')
        received = true
        if (onFinalAnswer) onFinalAnswer(data)
        else onMessage(data)
        return
      }
      if (event === 'human_question') {
        let question
        try { question = JSON.parse(data) } catch { throw new Error('收到的问题格式不正确。') }
        if (typeof question.question !== 'string' || !question.question.trim()
          || typeof question.runId !== 'string' || typeof question.questionId !== 'string'
          || (runId && question.runId !== runId)) throw new Error('收到的问题不属于当前任务或格式不正确。')
        if (!onQuestion) throw new Error('当前客户端不支持提交用户回答。')
        received = true
        onQuestion(question)
        return
      }
      if (event === 'error' || /^(执行错误|错误[：:]|Step\s+\d+:\s*步骤执行失败)/.test(data)) {
        throw new Error(data || '处理失败，请稍后重试。')
      }
      if (data === '[DONE]' || event === 'done' || event === 'complete') {
        finished = true
        return
      }
      if (data) {
        received = true
        onMessage(data)
      }
    })
    while (!signal.aborted && !finished) {
      const { value, done } = await reader.read()
      if (signal.aborted) break
      if (done) {
        parser.feed(decoder.decode())
        parser.finish()
        break
      }
      parser.feed(decoder.decode(value, { stream: true }))
    }
    if (signal.aborted) throw new DOMException('已停止接收', 'AbortError')
    if (!received) throw new Error('暂时没有收到回复，请稍后再试。')
  } finally {
    if (onAbort) signal.removeEventListener('abort', onAbort)
    if (reader) {
      await reader.cancel().catch(() => {})
      reader.releaseLock()
    }
  }
}

export async function submitHumanAnswer({ runId, questionId, answer, signal }) {
  const { data } = await http.post('/ai/manus/answer', { runId, questionId, answer }, { signal, timeout: 15000 })
  if (!data?.accepted) throw new Error('回答未被接收，请重试。')
}

export function cancelManusRun(runId) {
  return http.post('/ai/manus/cancel', { runId }, { timeout: 5000 })
}

export function answerErrorMessage(error) {
  if (error.response?.status === 409) return '这个问题已回答或已过期，请等待任务的最新进展。'
  if (error.response?.status === 410) return '任务已结束，请重新发起任务。'
  if (error.response?.status === 400) return `回答不能为空，且不能超过 ${MAX_MESSAGE_LENGTH} 字符。`
  return '补充回答提交失败，请再试一次。'
}

export function errorMessage(error) {
  if (error.response?.status) {
    const status = error.response.status
    if (status === 404) return '没有找到聊天接口，请检查后端地址。'
    if (status === 429) return '请求有些频繁，请稍后再试。'
    return `服务暂时无法处理请求（${status}），请稍后再试。`
  }
  if (axios.isAxiosError(error)) return '连接失败，请确认后端服务已在 8124 端口启动。'
  return '回答未完成，请稍后再试。'
}
