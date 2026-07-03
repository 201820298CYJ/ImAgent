<template>
  <div class="super-agent-container">
    <div class="header">
      <h1 class="title">南京大学信息管理学院 AI 智能体</h1>
    </div>

    <div class="content-wrapper">
      <div class="chat-area">
        <ChatRoom
          :messages="messages"
          :connection-status="connectionStatus"
          ai-type="super"
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
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { chatWithManus } from '../api'

// 设置页面标题和元数据
useHead({
  title: '南京大学信息管理学院 AI 智能体',
  meta: [
    {
      name: 'description',
      content: '南大信管 AI 智能体，能解答各类专业问题，提供精准建议和解决方案'
    },
    {
      name: 'keywords',
      content: 'AI智能体,智能助手,专业问答,AI问答,专业建议,信管'
    }
  ]
})

// 生成随机会话ID
const generateChatId = () => {
  return 'manus_' + Math.random().toString(36).substring(2, 10)
}

const messages = ref([])
const chatId = ref(generateChatId())
const connectionStatus = ref('disconnected')
let eventSource = null

// 添加消息到列表
const addMessage = (content, isUser, type = '') => {
  messages.value.push({
    content,
    isUser,
    type,
    time: new Date().getTime()
  })
}

// 发送消息
const sendMessage = (message) => {
  addMessage(message, true, 'user-question')

  // 关闭上一个连接
  if (eventSource) {
    eventSource.close()
  }

  connectionStatus.value = 'connecting'

  // 为本轮回答创建一条初始为空的 AI 消息，后续 chunk 直接追加到它的 content
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

    // 直接把增量追加到当前 AI 消息
    const target = messages.value[aiIndex]
    if (target) {
      target.content += data
    }
  }

  eventSource.onerror = (error) => {
    console.error('SSE Error:', error)
    // 服务端 complete() 后浏览器 EventSource 会自动触发 error，用当前累计内容判断是否真出错
    const target = messages.value[aiIndex]
    if (!target || !target.content) {
      addMessage('抱歉，连接出现异常，请稍后重试。', false, 'ai-error')
    }
    connectionStatus.value = 'disconnected'
    eventSource.close()
  }
}

// 页面加载时添加欢迎消息
onMounted(() => {
  addMessage('你好，我是南京大学信息管理学院 AI 智能体。可以回答学院资讯、学术问题，也能帮你完成搜索、查询知识库等任务，请问有什么可以帮你的？', false)
})

// 组件销毁前关闭SSE连接
onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.super-agent-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #f9fbff;
}

.header {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px 24px;
  background-color: #3f51b5;
  color: white;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 10;
}

.title {
  font-size: 20px;
  font-weight: bold;
  margin: 0;
  text-align: center;
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
  /* 设置最小高度确保内容显示正常 */
  min-height: calc(100vh - 56px - 180px); /* 100vh减去头部高度和页脚高度 */
  margin-bottom: 16px; /* 为页脚留出空间 */
}

.footer-container {
  margin-top: auto;
}

/* 响应式样式 */
@media (max-width: 768px) {
  .header {
    padding: 12px 16px;
  }

  .title {
    font-size: 18px;
  }

  .chat-area {
    padding: 12px;
    min-height: calc(100vh - 48px - 160px); /* 调整计算值 */
    margin-bottom: 12px;
  }
}

@media (max-width: 480px) {
  .header {
    padding: 10px 12px;
  }

  .title {
    font-size: 16px;
  }

  .chat-area {
    padding: 8px;
    min-height: calc(100vh - 42px - 150px); /* 再次调整计算值 */
    margin-bottom: 8px;
  }
}
</style>
