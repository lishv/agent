import { createApp } from 'vue'
import App from './App.vue'
import router from './router.js'
import './styles.css'

createApp(App).use(router).mount('#app')
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => { /* 未启用离线外壳时，在线页面仍可使用。 */ })
}
