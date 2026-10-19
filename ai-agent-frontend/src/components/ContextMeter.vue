<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { LoaderCircle, Minimize2 } from 'lucide-vue-next'
const props = defineProps({ context: Object, offline: Boolean, hasMessages: Boolean })
const emit = defineEmits(['refresh', 'compact'])
const opened = ref(false)
const root = ref(null)
const processing = computed(() => props.context?.requesting || ['queued', 'compressing'].includes(props.context?.status))
const percent = computed(() => Math.round(props.context?.usagePercent || 0))
const arc = computed(() => 62.83 * (1 - Math.min(100, Math.max(0, percent.value)) / 100))
const label = computed(() => processing.value ? props.context?.status === 'queued' ? '等待压缩' : '压缩中' : props.context ? `约 ${percent.value}%` : props.hasMessages ? '上下文' : '0%')
const canCompact = computed(() => props.hasMessages && props.context?.enabled && !props.offline && !processing.value)
const count = value => value >= 1000 ? `${(value / 1000).toFixed(1)}k` : String(value || 0)
function toggle() { opened.value = !opened.value; if (opened.value) emit('refresh') }
function outside(event) { if (!root.value?.contains(event.target)) opened.value = false }
function escape(event) { if (event.key === 'Escape' && opened.value) { opened.value = false; root.value?.querySelector('button')?.focus(); event.stopPropagation() } }
watch(() => props.hasMessages, () => { opened.value = false })
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape) })
onBeforeUnmount(() => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape) })
</script>

<template>
  <div ref="root" class="context-meter">
    <button type="button" class="context-toggle" :class="{ 'context-high': percent >= 70, 'context-working': processing }"
      aria-label="查看上下文占用" :aria-expanded="opened" :title="context ? `上下文估算占用 ${percent}%` : '查看上下文占用与压缩'" @click="toggle">
      <LoaderCircle v-if="processing" :size="14" class="spin" />
      <svg v-else width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><circle class="context-ring-track" cx="12" cy="12" r="10" /><circle class="context-ring-value" cx="12" cy="12" r="10" stroke-dasharray="62.83" :stroke-dashoffset="arc" /></svg>
      <span>{{ label }}</span>
    </button>
    <section v-if="opened" class="context-panel" aria-label="上下文占用详情">
      <div class="context-panel-heading"><strong>上下文占用</strong><span>估算</span></div>
      <template v-if="context">
        <div class="context-usage"><strong>{{ count(context.estimatedTokens) }}</strong><span>/ {{ count(context.inputBudgetTokens) }} tokens</span><b>{{ percent }}%</b></div>
        <div class="context-bar" role="progressbar" aria-label="上下文预算占用" :aria-valuenow="Math.min(100, percent)" aria-valuemin="0" aria-valuemax="100"><span :style="{ width: `${Math.min(100, percent)}%` }"></span></div>
        <p>已为回复预留 {{ count(context.responseReserveTokens) }} tokens</p>
        <div class="context-policy">{{ context.autoEnabled ? `达到 ${context.thresholdPercent}% 自动压缩` : '自动压缩已关闭' }} · 尽量保留 {{ context.retainTurns }} 轮原文</div>
        <p v-if="context.compactionCount" class="context-last">上次压缩 {{ count(context.lastBeforeTokens) }} → {{ count(context.lastAfterTokens) }} tokens</p>
        <p v-if="context.message && !offline" class="context-message" :class="{ 'context-error': context.status === 'error' }" role="status">{{ context.message }}</p>
      </template>
      <p v-else>{{ hasMessages ? '暂时无法读取占用，请稍后再试' : '发送消息后显示当前会话的上下文占用' }}</p>
      <button type="button" class="context-compact" :disabled="!canCompact" @click="emit('compact')"><LoaderCircle v-if="processing" :size="14" class="spin" /><Minimize2 v-else :size="14" />{{ processing ? context?.status === 'queued' ? '已安排，等待安全位置' : '正在压缩上下文…' : '压缩上下文' }}</button>
      <small>{{ offline ? '当前离线，联网后可压缩' : '完整聊天记录始终保留' }}</small>
    </section>
  </div>
</template>
