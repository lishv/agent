<script setup>
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { Download, ExternalLink, ImageOff, RefreshCw } from 'lucide-vue-next'
import { accountError, downloadGeneratedFile } from '../services/api.js'
import { useAuth } from '../composables/useAuth.js'

const props = defineProps({ image: { type: Object, required: true } })
const emit = defineEmits(['download'])
const auth = useAuth()
const container = ref(null)
const picture = ref(null)
const source = ref('')
const status = ref('idle')
const error = ref('')
let visible = false
let observer
let controller
let objectUrl = ''

/** 中断未完成的预览，并释放浏览器中的附件副本；不影响服务端文件和下载链接。 */
function release() {
  controller?.abort()
  controller = null
  source.value = ''
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = ''
}

/** 本站图片通过已有鉴权接口读取，外部图片使用浏览器图片加载；过期和网络错误均可重试。 */
async function load() {
  release()
  status.value = 'loading'
  error.value = ''
  const request = new AbortController()
  controller = request
  try {
    // 先移除上一张图片，避免重试时旧元素的事件覆盖新请求状态。
    await nextTick()
    if (request.signal.aborted) return
    if (!props.image.generated) {
      source.value = props.image.href
      return
    }
    if (!auth.state.user?.id) throw new Error('请登录后查看图片')
    const { blob } = await downloadGeneratedFile(props.image.href, { signal: request.signal })
    if (request.signal.aborted) return
    objectUrl = URL.createObjectURL(blob)
    source.value = objectUrl
  } catch (cause) {
    if (request.signal.aborted) return
    status.value = 'error'
    error.value = accountError(cause)
  }
}

/** 图片完成解码后展示；旧请求产生的事件不影响当前图片。 */
function loaded(event) {
  if (event.target === picture.value && event.target.getAttribute('src') === source.value) status.value = 'loaded'
}

/** 无效图片或外站拒绝访问时显示占位说明，仍保留查看和下载入口。 */
function failed(event) {
  if (event.target !== picture.value || event.target.getAttribute('src') !== source.value) return
  release()
  status.value = 'error'
  error.value = '图片暂时无法预览，可重试或使用下方链接。'
}

watch([() => props.image.href, () => auth.state.user?.id], () => {
  release()
  status.value = 'idle'
  error.value = ''
  if (visible) load()
})
onMounted(() => {
  observer = new IntersectionObserver(entries => {
    if (!entries.some(entry => entry.isIntersecting)) return
    visible = true
    observer.disconnect()
    load()
  }, { rootMargin: '160px' })
  observer.observe(container.value)
})
onUnmounted(() => { observer?.disconnect(); release() })
</script>

<template>
  <figure ref="container" class="message-image" :data-image-href="image.href">
    <div class="message-image-frame" :aria-busy="status === 'loading'">
      <img v-if="source" ref="picture" :src="source" :alt="image.label" :class="{ 'is-loaded': status === 'loaded' }" decoding="async" referrerpolicy="no-referrer" @load="loaded" @error="failed" />
      <div v-if="status !== 'loaded'" class="message-image-placeholder" :class="{ 'is-error': status === 'error' }" role="status">
        <template v-if="status === 'error'"><ImageOff :size="22" /><span>{{ error }}</span><button type="button" @click="load"><RefreshCw :size="13" />重新加载</button></template>
        <template v-else><span class="message-image-loading"></span><span>{{ status === 'idle' ? '图片预览' : '正在加载图片…' }}</span></template>
      </div>
    </div>
    <figcaption>
      <span class="message-image-label" :title="image.label">{{ image.label }}</span>
      <button v-if="image.generated" type="button" @click="emit('download', image.href)"><Download :size="13" />下载图片</button>
      <a v-else :href="image.href" target="_blank" rel="noopener noreferrer"><ExternalLink :size="13" />打开原图</a>
    </figcaption>
  </figure>
</template>
