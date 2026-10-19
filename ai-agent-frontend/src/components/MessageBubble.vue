<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { Bot, Check, ChevronDown, ChevronUp, Copy, Heart, MessageCircle, RefreshCw, Wrench } from 'lucide-vue-next'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { presentMessage } from '../services/messagePresentation.js'
import { generatedFileRequest } from '../services/fileDownloads.js'
import { accountError, downloadGeneratedFile } from '../services/api.js'

const props = defineProps({ message: { type: Object, required: true }, appId: { type: String, required: true }, busy: Boolean })
const emit = defineEmits(['retry'])
const copied = ref(false)
const downloadStatus = ref('')
const pendingDownloads = new Set()
const copyError = ref(false)
let timer
const codeCopyTimers = new Map()
const expanded = ref(false)
const canExpand = ref(false)
const contentElement = ref(null)
const messageColumn = ref(null)
const questionElements = new Map()
const questionExpanded = ref({})
const questionOverflow = ref({})
let resizeObserver
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true })
const defaultLink = markdown.renderer.rules.link_open || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
markdown.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  if (generatedFileRequest(tokens[idx].attrGet('href'))) tokens[idx].attrJoin('class', 'generated-file-link')
  else tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLink(tokens, idx, options, env, self)
}
function renderCodeBlock(tokens, index) {
  const token = tokens[index]
  const language = markdown.utils.escapeHtml(token.info?.trim().split(/\s+/)[0] || '代码')
  const code = markdown.utils.escapeHtml(token.content)
  return `<div class="code-block"><div class="code-toolbar"><span class="code-language">${language}</span><button type="button" class="code-copy-button" aria-label="复制代码" title="复制代码"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/></svg><span class="code-copy-label" aria-live="polite">复制代码</span></button></div><pre><code>${code}</code></pre></div>\n`
}
markdown.renderer.rules.fence = renderCodeBlock
markdown.renderer.rules.code_block = renderCodeBlock
const render = (text) => DOMPurify.sanitize(markdown.render(text || ''), { ADD_ATTR: ['target'] })
const displayContent = computed(() => presentMessage(props.message.content, props.appId, props.message.role))
// 只给完整工具回执添加视觉分组；混合说明或最终回答继续按正文展示。
const isToolResult = computed(() => props.message.role === 'assistant' && /^\*\*(?:已(?:搜索|读取|下载|运行|生成|保存|请求|完成|查询|计算|规划|获取)[^*]*|操作结果|[^*]*未完成)\*\*[：:]/.test(displayContent.value.trim()))
const isFinalAnswer = computed(() => props.message.role === 'assistant' && (props.message.kind === 'final' || props.appId === 'love'))
const html = computed(() => render(displayContent.value))
const time = computed(() => new Date(props.message.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }))

async function copy() {
  try {
    await navigator.clipboard.writeText(displayContent.value)
    copied.value = true
    copyError.value = false
  } catch { copyError.value = true }
  clearTimeout(timer)
  timer = setTimeout(() => { copied.value = false; copyError.value = false }, 2200)
}
function codeCopyState(button, label) {
  button.querySelector('.code-copy-label').textContent = label
  button.setAttribute('aria-label', label)
  button.title = label
  button.classList.toggle('is-copied', label === '已复制')
  button.classList.toggle('is-error', label === '复制失败')
}
// v-html 内的代码按钮使用事件委托；每次流式重绘都能复制当前代码，无需重复绑定监听。
async function copyCode(event) {
  const button = event.target?.closest?.('.code-copy-button')
  if (!button || !contentElement.value?.contains(button) || button.disabled) return
  const code = button.closest('.code-block')?.querySelector('pre code')
  if (!code) return
  clearTimeout(codeCopyTimers.get(button))
  button.disabled = true
  try {
    await navigator.clipboard.writeText(code.textContent)
    codeCopyState(button, '已复制')
  } catch { codeCopyState(button, '复制失败') }
  finally { button.disabled = false }
  if (!button.isConnected) { codeCopyTimers.delete(button); return }
  codeCopyTimers.set(button, setTimeout(() => {
    codeCopyState(button, '复制代码')
    codeCopyTimers.delete(button)
  }, 2200))
}

/** 下载链接通过 API 获取附件，其他点击仍交给代码复制或正常链接行为。 */
async function handleContentClick(event) {
  const anchor = event.target?.closest?.('a')
  const href = anchor?.getAttribute('href')
  if (!anchor || !contentElement.value?.contains(anchor) || !generatedFileRequest(href)) return copyCode(event)
  event.preventDefault()
  if (pendingDownloads.has(href)) return
  pendingDownloads.add(href)
  downloadStatus.value = '正在下载…'
  try {
    const { blob, filename } = await downloadGeneratedFile(href)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    downloadStatus.value = ''
  } catch (error) {
    downloadStatus.value = accountError(error)
  } finally { pendingDownloads.delete(href) }
}
function measureContent() {
  const element = contentElement.value
  canExpand.value = !isFinalAnswer.value && !!element && element.scrollHeight > Number.parseFloat(getComputedStyle(element).lineHeight) * 2 + 2
  questionElements.forEach((paragraph, id) => {
    questionOverflow.value[id] = paragraph.scrollHeight > Number.parseFloat(getComputedStyle(paragraph).lineHeight) * 2 + 2
  })
}
function setQuestionElement(id, element) {
  if (element) questionElements.set(id, element)
  else questionElements.delete(id)
}
watch([displayContent, () => props.message.kind], async () => {
  await nextTick(); measureContent()
  if (messageColumn.value) resizeObserver?.observe(messageColumn.value)
})
watch(() => props.message.questions, async () => {
  await nextTick(); measureContent()
  if (messageColumn.value) resizeObserver?.observe(messageColumn.value)
}, { deep: true })
onMounted(() => {
  measureContent()
  resizeObserver = new ResizeObserver(measureContent)
  if (messageColumn.value) resizeObserver.observe(messageColumn.value)
})
onUnmounted(() => { clearTimeout(timer); codeCopyTimers.forEach(clearTimeout); codeCopyTimers.clear(); resizeObserver?.disconnect() })
</script>

<template>
  <article v-if="appId !== 'manus' || message.role !== 'assistant' || message.content || message.questions?.length || message.status !== 'streaming'" class="message-row" :class="[message.role, { 'step-message': !!message.stepNumber, 'tool-message': isToolResult }]" :data-role="message.role" :data-kind="message.role === 'assistant' ? isFinalAnswer ? 'final' : 'progress' : undefined">
    <span v-if="message.role === 'assistant'" class="message-avatar" :class="appId"><Wrench v-if="isToolResult" :size="15" /><Heart v-else-if="appId === 'love'" :size="18" /><Bot v-else :size="19" /></span>
    <div ref="messageColumn" class="message-column">
      <div class="message-meta"><strong>{{ message.role === 'user' ? '你' : appId === 'love' ? 'AI 恋爱大师' : 'AI 超级智能体' }}</strong><span v-if="message.humanAnswer">补充回答</span><span>{{ time }}</span></div>
      <div v-if="message.role === 'user'" class="message-bubble user-bubble"><div ref="contentElement" class="message-text" :class="{ 'is-collapsed': !expanded }">{{ displayContent }}</div><button v-if="canExpand" class="message-expand" :aria-expanded="expanded" @click="expanded = !expanded"><ChevronUp v-if="expanded" :size="13" /><ChevronDown v-else :size="13" />{{ expanded ? '收起' : '展开' }}</button></div>
      <div v-else class="message-bubble ai-bubble">
        <div v-if="message.content" ref="contentElement" class="markdown-body message-text" :class="{ 'is-collapsed': !isFinalAnswer && !expanded }" @click="handleContentClick" v-html="html"></div>
        <p v-if="downloadStatus" class="message-error" role="status">{{ downloadStatus }}</p>
        <button v-if="message.content && canExpand" class="message-expand" :aria-expanded="expanded" @click="expanded = !expanded"><ChevronUp v-if="expanded" :size="13" /><ChevronDown v-else :size="13" />{{ expanded ? '收起' : '展开' }}</button>
        <div v-for="question in message.questions || []" :key="question.questionId" class="human-question">
          <div class="human-question-heading"><MessageCircle :size="15" /><strong>需要你补充信息</strong></div>
          <p :ref="element => setQuestionElement(question.questionId, element)" class="message-text" :class="{ 'is-collapsed': !questionExpanded[question.questionId] }">{{ question.question }}</p>
          <button v-if="questionOverflow[question.questionId]" class="message-expand question-expand" :aria-expanded="!!questionExpanded[question.questionId]" @click="questionExpanded[question.questionId] = !questionExpanded[question.questionId]"><ChevronUp v-if="questionExpanded[question.questionId]" :size="13" /><ChevronDown v-else :size="13" />{{ questionExpanded[question.questionId] ? '收起' : '展开' }}</button>
          <small v-if="question.answer"><Check :size="12" />已收到你的补充</small>
          <small v-else-if="question.expired">本次提问已结束</small>
          <small v-else>请在下方输入框回答，智能体会继续当前任务。</small>
        </div>
        <div v-if="message.status === 'streaming' && appId === 'love'" class="typing-indicator" :class="{ compact: message.content }"><span><i></i><i></i><i></i></span><small>{{ message.content ? '正在继续回复' : '正在认真听你说…' }}</small></div>
        <div v-if="message.status === 'error'" class="message-error"><span>这次回答未完成</span><button :disabled="busy" @click="emit('retry')"><RefreshCw :size="13" />再次尝试</button></div>
        <div v-if="message.status === 'stopped'" class="stopped-label">已停止接收{{ !message.content ? '，暂无回复内容' : '' }}</div>
      </div>
      <div v-if="message.role === 'assistant' && message.content && message.status !== 'streaming'" class="message-actions"><button @click="copy"><Check v-if="copied" :size="13" /><Copy v-else :size="13" />{{ copied ? '已复制' : copyError ? '复制失败' : '复制回复' }}</button></div>
    </div>
    <span v-if="message.role === 'user'" class="message-avatar user">你</span>
  </article>
</template>
