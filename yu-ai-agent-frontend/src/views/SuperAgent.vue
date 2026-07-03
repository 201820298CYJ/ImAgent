<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="header-inner">
        <div class="brand-block">
          <div class="logo-halo">
            <img :src="logo" alt="南京大学" class="logo-img" />
          </div>
          <div class="title-block">
            <div class="title-eyebrow">南京大学 · 信息管理学院</div>
            <h1 class="title-main">信管 AI 智能体<span class="title-badge">Beta</span></h1>
          </div>
        </div>
        <div class="status-block">
          <div class="status-pill" :class="statusClass">
            <span class="status-dot"></span>
            <span class="status-text">{{ statusText }}</span>
          </div>
          <div class="session-id"><span class="session-hash">#</span>{{ chatId }}</div>
        </div>
      </div>
    </header>

    <main class="app-main">
      <div class="chat-shell">
        <ChatRoom
          :messages="messages"
          :connection-status="connectionStatus"
          :quick-prompts="quickPrompts"
          ai-type="super"
          @send-message="sendMessage"
        />
      </div>
    </main>

    <AppFooter />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { chatWithManus } from '../api'

useHead({
  title: '南京大学信息管理学院 · AI 智能体',
  meta: [
    { name: 'description', content: '南大信管 AI 智能体，融合 RAG、Agent、MCP，提供院系资讯、学术辅导与任务协作。' },
    { name: 'keywords', content: '南京大学,信息管理学院,AI智能体,RAG,Agent,智能问答' }
  ]
})

const logo = new URL('../assets/nju-logo.png', import.meta.url).href

const generateChatId = () => 'imagent_' + Math.random().toString(36).substring(2, 10)

const messages = ref([])
const chatId = ref(generateChatId())
const connectionStatus = ref('disconnected')
let eventSource = null

const quickPrompts = [
  '信管学院有哪些本科专业？',
  '飞跃手册里有关于港中深商业分析的经验吗？',
  '帮我搜索最新的南大信管招生政策',
  '介绍一下学院的师资力量'
]

const statusClass = computed(() => ({
  'is-ready': connectionStatus.value === 'disconnected',
  'is-busy': connectionStatus.value === 'connecting',
  'is-error': connectionStatus.value === 'error'
}))

const statusText = computed(() => {
  switch (connectionStatus.value) {
    case 'connecting': return '正在思考'
    case 'error': return '连接异常'
    default: return '就绪'
  }
})

const addMessage = (content, isUser, type = '') => {
  messages.value.push({
    content,
    isUser,
    type,
    time: new Date().getTime()
  })
}

const sendMessage = (message) => {
  addMessage(message, true, 'user-question')

  if (eventSource) eventSource.close()
  connectionStatus.value = 'connecting'

  const aiIndex = messages.value.length
  addMessage('', false, 'ai-answer')

  eventSource = chatWithManus(message, chatId.value)

  eventSource.onmessage = (event) => {
    const data = event.data
    if (data === '[DONE]') {
      connectionStatus.value = 'disconnected'
      eventSource.close()
      return
    }
    if (data === undefined || data === null) return
    const target = messages.value[aiIndex]
    if (target) target.content += data
  }

  eventSource.onerror = () => {
    const target = messages.value[aiIndex]
    if (!target || !target.content) {
      addMessage('抱歉，连接出现异常，请稍后重试。', false, 'ai-error')
    }
    connectionStatus.value = 'disconnected'
    eventSource.close()
  }
}

onMounted(() => {
  addMessage(
    '你好，我是南京大学信息管理学院 AI 智能体。可以回答学院资讯、飞跃手册相关问题，也能帮你完成搜索、文档撰写等任务，随时向我提问。',
    false
  )
})

onBeforeUnmount(() => {
  if (eventSource) eventSource.close()
})
</script>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

/* Header */
.app-header {
  flex: 0 0 auto;
  z-index: 20;
  padding: 18px 32px 16px;
  background:
    radial-gradient(600px 200px at 15% 0%, rgba(123, 178, 255, 0.28), transparent 60%),
    radial-gradient(500px 200px at 100% 0%, rgba(201, 168, 106, 0.20), transparent 60%),
    linear-gradient(180deg, var(--nju-navy) 0%, var(--nju-blue) 100%);
  color: #fff;
  box-shadow: 0 8px 28px -18px rgba(10, 37, 64, 0.55);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  position: relative;
  overflow: hidden;
}

.app-header::after {
  content: '';
  position: absolute;
  bottom: 0; left: 0; right: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(201, 168, 106, 0.7), transparent);
}

.header-inner {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}

.brand-block {
  display: flex;
  align-items: center;
  gap: 18px;
}

.logo-halo {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  background: linear-gradient(135deg, #ffffff 0%, #f4f0f8 100%);
  border: 1px solid rgba(255, 255, 255, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 6px;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.9),
    0 10px 24px -12px rgba(0, 0, 0, 0.55),
    0 0 0 4px rgba(255, 255, 255, 0.06);
}

.logo-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.title-block {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.15;
  gap: 6px;
  text-align: left;
}

.title-eyebrow {
  font-size: 11.5px;
  letter-spacing: 0.32em;
  color: rgba(255, 255, 255, 0.65);
  text-transform: uppercase;
}

.title-main {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0.06em;
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 2px 0;
}

.title-badge {
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.12em;
  padding: 2px 8px;
  color: var(--nju-navy);
  background: linear-gradient(135deg, #f7d68b, var(--nju-gold));
  border-radius: 999px;
  text-transform: uppercase;
}

.status-block {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px 6px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.14);
  font-size: 12.5px;
  letter-spacing: 0.05em;
  backdrop-filter: blur(6px);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #7ce0a5;
  box-shadow: 0 0 0 4px rgba(124, 224, 165, 0.18);
}

.is-busy .status-dot {
  background: #ffcc66;
  box-shadow: 0 0 0 4px rgba(255, 204, 102, 0.18);
  animation: pulseDot 1.4s ease-in-out infinite;
}

.is-error .status-dot {
  background: #ff7a7a;
  box-shadow: 0 0 0 4px rgba(255, 122, 122, 0.18);
}

@keyframes pulseDot {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.6; transform: scale(1.2); }
}

.session-id {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.55);
  letter-spacing: 0.08em;
  font-variant-numeric: tabular-nums;
  font-family: 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
}

.session-hash {
  color: var(--nju-gold);
  opacity: 0.85;
  margin-right: 2px;
}

/* Main —— 占满剩余高度，内部不允许自身滚动，把滚动交给聊天区 */
.app-main {
  flex: 1 1 auto;
  min-height: 0;
  padding: 20px 32px 12px;
  display: flex;
  overflow: hidden;
}

.chat-shell {
  max-width: 1080px;
  width: 100%;
  margin: 0 auto;
  display: flex;
  min-height: 0;
  flex: 1;
}

/* Responsive */
@media (max-width: 768px) {
  .app-header { padding: 16px 18px 14px; }
  .brand-block { gap: 12px; }
  .logo-halo { width: 52px; height: 52px; border-radius: 12px; padding: 4px; }
  .title-main { font-size: 19px; }
  .title-eyebrow { font-size: 10.5px; letter-spacing: 0.2em; }
  .status-block { align-items: flex-start; }
  .app-main { padding: 18px 12px 8px; }
}

@media (max-width: 480px) {
  .header-inner { gap: 14px; }
  .title-main { font-size: 17px; gap: 6px; }
  .title-badge { font-size: 9px; padding: 1px 6px; }
  .session-id { display: none; }
}
</style>
