<script setup>
import { useRouter } from 'vue-router'
import { ArrowUpRight, Bot, Heart, MessageCircle, Search, Sparkles, FileText } from 'lucide-vue-next'
import { apps } from '../data/apps.js'

const router = useRouter()
const enterApp = (id) => router.push(`/chat/${id}`)
</script>

<template>
  <main class="home-page">
    <header class="page-topline"><span>工作空间</span><span class="topline-right">绘灵 · HUILING</span></header>
    <div class="home-content">
      <section class="home-hero">
        <span class="home-mark"><Sparkles :size="28" :stroke-width="1.6" /></span>
        <h1>让想法，向前一步。</h1>
        <p>聊聊心事，探索问题，或把一件事交给 AI。</p>
        <div class="home-capabilities" aria-label="工作空间能力"><span><MessageCircle :size="14" />自然对话</span><span><Search :size="14" />信息搜索</span><span><FileText :size="14" />文档生成</span></div>
      </section>
      <section id="applications" class="applications-section" aria-labelledby="applications-title">
        <div class="section-heading"><h2 id="applications-title">选择你的助手</h2><span>从这里开始</span></div>
        <div class="application-grid">
          <button v-for="app in apps" :key="app.id" class="application-card" :class="app.id" @click="enterApp(app.id)">
            <div class="app-card-top"><span class="application-icon"><Heart v-if="app.id === 'love'" :size="24" :stroke-width="1.7" /><Bot v-else :size="24" :stroke-width="1.7" /></span><ArrowUpRight class="card-arrow" :size="20" /></div>
            <div class="app-card-body"><h3>{{ app.name }}</h3><p>{{ app.description }}</p></div>
            <div class="app-card-bottom"><div class="capability-tags"><span v-for="capability in app.capabilities" :key="capability">{{ capability }}</span></div><span class="enter-label">开始对话</span></div>
          </button>
        </div>
      </section>
      <p class="home-footer">从一个问题开始，把更多时间留给重要的事。</p>
    </div>
  </main>
</template>
