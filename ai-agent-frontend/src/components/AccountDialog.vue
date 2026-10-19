<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Eye, EyeOff, Mail, Sparkles, X } from 'lucide-vue-next'
import { useAuth } from '../composables/useAuth.js'
import { accountError, http } from '../services/api.js'
const auth = useAuth()
const email = ref(''), password = ref(''), confirmPassword = ref(''), currentPassword = ref(''), code = ref(''), displayName = ref('')
const error = ref(''), notice = ref(''), busy = ref(false), sendingCode = ref(false), showPassword = ref(false)
const cooldowns = reactive(new Map()), now = ref(Date.now())
const dialog = ref(null)
let countdown, previousFocus
const mode = computed(() => auth.state.mode)
const isLogin = computed(() => ['login', 'code-login'].includes(mode.value))
const needsPassword = computed(() => ['login', 'register', 'reset', 'change'].includes(mode.value))
const codePurpose = computed(() => ({ register: 'register', reset: 'reset_password', 'code-login': 'login' })[mode.value])
const codeKey = computed(() => `${codePurpose.value}:${email.value.trim().toLowerCase()}`)
const cooldown = computed(() => Math.max(0, Math.ceil(((cooldowns.get(codeKey.value) || 0) - now.value) / 1000)))
const titles = { login: '欢迎回到绘灵', 'code-login': '欢迎回到绘灵', register: '开始你的绘灵之旅', reset: '找回密码', change: '修改密码', nickname: '修改昵称', account: '你的账号' }
watch(() => auth.state.dialog, async (opened) => {
  if (opened) { previousFocus = document.activeElement; await nextTick(); dialog.value?.querySelector('input,button')?.focus() }
  else { password.value = ''; confirmPassword.value = ''; currentPassword.value = ''; code.value = ''; showPassword.value = false; error.value = ''; notice.value = ''; previousFocus?.focus() }
})
watch(mode, () => {
  error.value = ''; notice.value = ''; password.value = ''; confirmPassword.value = ''; currentPassword.value = ''; code.value = ''; showPassword.value = false
  if (mode.value === 'nickname') displayName.value = auth.state.user?.displayName || ''
})
function close() { if (!busy.value && !sendingCode.value) auth.state.dialog = false }
function trapFocus(event) {
  if (event.key === 'Escape') { close(); return }
  if (event.key !== 'Tab') return
  const buttons = [...dialog.value.querySelectorAll('input:not(:disabled),button:not(:disabled)')]
  const first = buttons[0], last = buttons.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
async function sendCode() {
  if (busy.value || sendingCode.value || cooldown.value) return
  if (!dialog.value?.querySelector('#account-email')?.reportValidity()) return
  const key = codeKey.value
  sendingCode.value = true; error.value = ''; notice.value = ''
  try {
    await http.post('/auth/email-code', { email: email.value, purpose: codePurpose.value }, { timeout: 15000 })
    notice.value = '验证码已发送，5 分钟内有效'; now.value = Date.now(); cooldowns.set(key, now.value + 60000)
    clearInterval(countdown); countdown = setInterval(() => { now.value = Date.now(); if (![...cooldowns.values()].some(time => time > now.value)) clearInterval(countdown) }, 1000)
  } catch (e) { error.value = accountError(e) }
  finally { sendingCode.value = false }
}
async function submit() {
  error.value = ''; notice.value = ''
  if (['register', 'reset', 'change'].includes(mode.value) && password.value !== confirmPassword.value) { error.value = '两次输入的密码不一致'; return }
  if (['register', 'nickname'].includes(mode.value) && !displayName.value.trim()) { error.value = '请输入昵称'; return }
  busy.value = true
  try {
    const body = { email: email.value, password: password.value, confirmPassword: confirmPassword.value, code: code.value, displayName: displayName.value }
    if (mode.value === 'login') await auth.authenticate('login', { email: email.value, password: password.value })
    else if (mode.value === 'code-login') await auth.authenticate('code-login', { email: email.value, code: code.value })
    else if (mode.value === 'register') await auth.authenticate('register', body)
    else if (mode.value === 'nickname') {
      await auth.updateNickname(displayName.value.trim()); auth.state.mode = 'account'; await nextTick(); notice.value = '昵称已更新'
    }
    else {
      await http.post(mode.value === 'change' ? '/auth/password' : '/auth/reset-password', { ...body, currentPassword: currentPassword.value }, { timeout: 15000 })
      await auth.refresh(); auth.state.mode = 'login'; await nextTick(); notice.value = '密码已更新，请使用新密码登录'
    }
  } catch (e) { error.value = accountError(e) }
  finally { busy.value = false }
}
async function signOut() {
  busy.value = true; error.value = ''
  try { await auth.logout() } catch (e) { error.value = accountError(e) }
  finally { busy.value = false }
}
onBeforeUnmount(() => clearInterval(countdown))
</script>

<template>
  <Teleport to="body"><div v-if="auth.state.dialog" class="account-overlay" @click.self="close" @keydown="trapFocus">
    <section ref="dialog" class="account-dialog" role="dialog" aria-modal="true" aria-labelledby="account-title">
      <button class="account-close icon-button" aria-label="关闭账号窗口" :disabled="busy || sendingCode" @click="close"><X :size="18" /></button>
      <span class="account-mark"><Sparkles :size="26" /></span><h2 id="account-title">{{ titles[mode] }}</h2>
      <p class="account-intro">{{ mode === 'account' ? '账号连接你的每一段对话' : '让每一段对话，都有记忆' }}</p>
      <template v-if="mode === 'account'">
        <div class="account-identity"><Mail :size="18" /><div><strong>{{ auth.state.user?.displayName }}</strong><span>{{ auth.state.user?.email }}</span></div></div>
        <div class="account-actions">
          <button class="account-secondary" :disabled="busy || auth.state.offline || auth.state.loading" @click="auth.state.mode = 'nickname'">修改昵称</button>
          <button class="account-secondary" :disabled="busy || auth.state.offline || auth.state.loading" @click="auth.state.mode = 'change'">修改密码</button>
        </div>
        <button class="account-secondary" :disabled="busy" @click="signOut">{{ busy ? '正在退出…' : '退出登录' }}</button>
      </template>
      <div v-if="isLogin" class="account-login-methods" role="group" aria-label="登录方式">
        <button type="button" :aria-pressed="mode === 'login'" :disabled="busy || sendingCode" @click="auth.state.mode = 'login'">密码登录</button>
        <button type="button" :aria-pressed="mode === 'code-login'" :disabled="busy || sendingCode" @click="auth.state.mode = 'code-login'">验证码登录</button>
      </div>
      <form v-if="mode !== 'account'" class="account-form" @submit.prevent="submit">
        <div v-if="!['change','nickname'].includes(mode)" class="account-field"><label for="account-email">邮箱</label><input id="account-email" v-model="email" type="email" autocomplete="email" maxlength="254" placeholder="输入你的邮箱" required /></div>
        <div v-if="['register','nickname'].includes(mode)" class="account-field"><label for="account-name">昵称</label><input id="account-name" v-model="displayName" autocomplete="nickname" maxlength="40" placeholder="希望我们怎么称呼你" required /><span v-if="mode === 'nickname'" class="account-field-hint">1 至 40 个字符，保存后立即生效</span></div>
        <div v-if="codePurpose" class="account-field"><label for="account-code">邮箱验证码</label><div class="code-input"><input id="account-code" v-model="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" placeholder="六位验证码" required /><button type="button" :disabled="busy || sendingCode || cooldown > 0 || auth.state.offline || auth.state.loading" @click="sendCode">{{ sendingCode ? '发送中…' : cooldown ? `${cooldown} 秒` : '获取验证码' }}</button></div></div>
        <div v-if="mode === 'change'" class="account-field"><label for="account-current-password">当前密码</label><input id="account-current-password" v-model="currentPassword" type="password" autocomplete="current-password" maxlength="64" required /></div>
        <div v-if="needsPassword" class="account-field"><label for="account-password">{{ mode === 'login' ? '密码' : '新密码' }}</label><div class="password-input"><input id="account-password" v-model="password" :type="showPassword ? 'text' : 'password'" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :minlength="mode === 'login' ? undefined : 8" maxlength="64" :placeholder="mode === 'login' ? '输入你的密码' : '8 至 64 位密码'" required /><button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword"><EyeOff v-if="showPassword" :size="17" /><Eye v-else :size="17" /></button></div></div>
        <div v-if="needsPassword && mode !== 'login'" class="account-field"><label for="account-confirm-password">确认密码</label><input id="account-confirm-password" v-model="confirmPassword" type="password" autocomplete="new-password" maxlength="64" placeholder="再次输入密码" required /></div>
        <p v-if="error" class="account-error" role="alert">{{ error }}</p><p v-if="notice" class="account-notice" role="status">{{ notice }}</p>
        <button class="account-primary" :disabled="busy || sendingCode || auth.state.offline || auth.state.loading" type="submit">{{ busy ? '请稍候…' : { login:'登录', 'code-login':'登录', register:'注册并登录', reset:'重置密码', change:'保存新密码', nickname:'保存昵称' }[mode] }}</button>
      </form>
      <p v-if="mode === 'account' && error" class="account-error" role="alert">{{ error }}</p>
      <p v-if="mode === 'account' && notice" class="account-notice" role="status">{{ notice }}</p>
      <div class="account-links" v-if="isLogin"><button :disabled="busy || sendingCode" @click="auth.state.mode='register'">注册账号</button><button :disabled="busy || sendingCode" @click="auth.state.mode='reset'">忘记密码</button></div>
      <div class="account-links" v-else-if="['register','reset'].includes(mode)"><button :disabled="busy || sendingCode" @click="auth.state.mode='login'">返回登录</button></div>
      <div class="account-links" v-else-if="['change','nickname'].includes(mode)"><button :disabled="busy || sendingCode" @click="auth.state.mode='account'">返回账号设置</button></div>
      <p v-if="auth.state.offline" class="account-notice">当前离线，恢复连接后即可登录</p>
    </section>
  </div></Teleport>
</template>
