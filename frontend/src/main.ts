import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { initializeMobileRuntime } from './mobile/runtime'
import { initializeHttpStorage } from './utils/http'
import { consumeNotificationLaunchRoute } from './mobile/localAlerts'
import './assets/styles/main.scss'

function renderStartupFailure(error: unknown) {
  console.error('LeanTPM startup failed', error)
  const root = document.querySelector<HTMLDivElement>('#app')
  if (!root) return
  const panel = document.createElement('main')
  panel.className = 'startup-failure'
  const title = document.createElement('h1')
  title.textContent = 'LeanTPM 启动失败'
  const message = document.createElement('p')
  message.textContent = '移动端安全组件初始化失败，请关闭应用后重新打开；如仍失败，请重新安装最新版本。'
  panel.append(title, message)
  root.replaceChildren(panel)
}

async function bootstrap() {
  try {
    await initializeHttpStorage()
    await initializeMobileRuntime()

    const app = createApp(App)
    for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
      app.component(key, component)
    }

    app.use(createPinia())
    app.use(router)
    app.use(ElementPlus, { size: 'default' })
    app.mount('#app')
    await router.isReady()
    const launchRoute = await consumeNotificationLaunchRoute()
    if (launchRoute) await router.push(launchRoute)
    window.addEventListener('leantpm-notification-open', (event) => {
      const route = (event as CustomEvent<{ route?: string }>).detail?.route
      if (route) void router.push(route)
    })
  } catch (error) {
    renderStartupFailure(error)
  }
}

void bootstrap()
