<template>
  <div class="nju-im-assistant-container">
    <div class="header">
      <div class="left-section">
        <div class="back-button" @click="goBack">返回</div>
        <img :src="logo" alt="南京大学信息管理学院徽章" class="logo" />
        <h1 class="title">南京大学信息管理学院 AI 助手</h1>
      </div>
      <div class="chat-id">会话ID: {{ chatId }}</div>
    </div>

    <div class="content-wrapper">
      <div class="chat-area">
        <ChatRoom
          :messages="messages"
          :connection-status="connectionStatus"
          ai-type="nju-im"
          @send-message="sendMessage"
        />
      </div>
    </div>

    <div class="footer-container">
      <AppFooter />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { chatWithLoveApp } from '../api'

// 动态引入徽章图片
const logo = new URL('../assets/nju-im-logo.png', import.meta.url).href

// 设置页面标题和元数据
useHead({
  title: '南京大学信息管理学院AI助手',
  meta: [
    {
      name: 'description',
      content: '南京大学信息管理学院AI助手，提供信息管理领域学术辅导、院系资讯与智能问答服务'
    },
    {
      name: 'keywords',
      content: '南京大学,信息管理学院,AI助手,智能问答,学术辅导,院系资讯'
    }
  ]
})

const router = useRouter()
const messages = ref([])
const chatId = ref('')
const connectionStatus = ref('disconnected')
let eventSource = null

// 生成随机会话ID
const generateChatId = () => {
  return 'nju_im_' + Math.random().toString(36).substring(2, 10)
}

// 添加消息到列表
const addMessage = (content, isUser) => {
  messages.value.push({
    content,
    isUser,
    time: new Date().getTime()
  })
}

// 发送消息
const sendMessage = (message) => {
  addMessage(message, true)

  // 关闭可能存在的旧 SSE
  if (eventSource) {
    eventSource.close()
  }

  // 创建一个空的 AI 回复消息
  const aiMessageIndex = messages.value.length
  addMessage('', false)

  connectionStatus.value = 'connecting'
  // 暂时复用原恋爱大师接口，如后端支持可替换为新的院系助手接口
  eventSource = chatWithLoveApp(message, chatId.value)

  // 监听 SSE 消息
  eventSource.onmessage = (event) => {
    const data = event.data
    if (data && data !== '[DONE]') {
      // 更新最新的 AI 消息内容，而不是创建新消息
      if (aiMessageIndex < messages.value.length) {
        messages.value[aiMessageIndex].content += data
      }
    }

    if (data === '[DONE]') {
      connectionStatus.value = 'disconnected'
      eventSource.close()
    }
  }

  // 监听 SSE 错误
  eventSource.onerror = (error) => {
    console.error('SSE Error:', error)
    connectionStatus.value = 'error'
    eventSource.close()
  }
}

// 返回主页
const goBack = () => {
  router.push('/')
}

// 页面加载时添加欢迎消息
onMounted(() => {
  // 生成聊天 ID
  chatId.value = generateChatId()

  // 添加欢迎消息
  addMessage('欢迎来到南京大学信息管理学院 AI 助手，请提出您的问题或需求，我将竭诚为您服务。', false)
})

// 组件销毁前关闭 SSE 连接
onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.nju-im-assistant-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #f5f7ff;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background-color: #005bac; /* 南大信息管理学院蓝 */
  color: #ffffff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 10;
}

.left-section {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  height: 36px;
  width: auto;
}

.back-button {
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: opacity 0.2s;
  margin-right: 12px;
}

.back-button:hover {
  opacity: 0.8;
}

.back-button:before {
  content: '←';
  margin-right: 6px;
}

.title {
  font-size: 20px;
  font-weight: bold;
  margin: 0;
}

.chat-id {
  font-size: 14px;
  opacity: 0.85;
}

.content-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.chat-area {
  flex: 1;
  padding: 16px;
  overflow: hidden;
  position: relative;
  min-height: calc(100vh - 56px - 180px);
  margin-bottom: 16px;
}

.footer-container {
  margin-top: auto;
}

/* 响应式样式 */
@media (max-width: 768px) {
  .header {
    padding: 10px 16px;
  }
  .title {
    font-size: 18px;
  }
  .chat-id {
    font-size: 12px;
  }
  .chat-area {
    padding: 12px;
    min-height: calc(100vh - 48px - 160px);
    margin-bottom: 12px;
  }
}

@media (max-width: 480px) {
  .header {
    padding: 8px 12px;
  }
  .back-button {
    font-size: 14px;
  }
  .title {
    font-size: 16px;
  }
  .chat-id {
    display: none;
  }
  .chat-area {
    padding: 8px;
    min-height: calc(100vh - 42px - 150px);
    margin-bottom: 8px;
  }
}
</style> 