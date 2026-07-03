<template>
  <div class="chat-container">
    <!-- 聊天记录区域 -->
    <div class="chat-messages" ref="messagesContainer">
      <div
        v-for="(msg, index) in messages"
        :key="index"
        class="message-row"
        :class="msg.isUser ? 'row-user' : 'row-ai'"
      >
        <div v-if="!msg.isUser" class="avatar ai-avatar">
          <AiAvatarFallback :type="aiType" />
        </div>

        <div class="bubble-column">
          <div class="sender-label">{{ msg.isUser ? '我' : '南大信管 · AI 智能体' }}</div>
          <div class="message-bubble" :class="msg.isUser ? 'bubble-user' : 'bubble-ai'">
            <div class="message-content">
              <template v-if="msg.content">{{ msg.content }}</template>
              <span
                v-if="!msg.isUser && connectionStatus === 'connecting' && index === messages.length - 1 && msg.content"
                class="typing-indicator"
              >▍</span>
              <span
                v-if="!msg.isUser && connectionStatus === 'connecting' && !msg.content && index === messages.length - 1"
                class="typing-dots"
              >
                <i></i><i></i><i></i>
              </span>
            </div>
            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </div>
        </div>

        <div v-if="msg.isUser" class="avatar user-avatar">
          <AiAvatarFallback type="user" />
        </div>
      </div>
    </div>

    <!-- 快捷提问 -->
    <div v-if="showQuickPrompts" class="quick-prompts">
      <span class="quick-title">试试这样问</span>
      <button
        v-for="(q, i) in quickPrompts"
        :key="i"
        class="quick-chip"
        :disabled="connectionStatus === 'connecting'"
        @click="handleQuick(q)"
      >{{ q }}</button>
    </div>

    <!-- 输入区域 -->
    <div class="chat-input-container">
      <div class="chat-input">
        <textarea
          v-model="inputMessage"
          @keydown="onKeydown"
          @input="autoResize"
          ref="textareaRef"
          placeholder="输入你的问题，按 Enter 发送，Shift + Enter 换行"
          class="input-box"
          rows="1"
          :disabled="connectionStatus === 'connecting'"
        ></textarea>
        <button
          @click="sendMessage"
          class="send-button"
          :class="{ 'is-busy': connectionStatus === 'connecting' }"
          :disabled="connectionStatus === 'connecting' || !inputMessage.trim()"
          :aria-label="connectionStatus === 'connecting' ? '思考中' : '发送'"
        >
          <span v-if="connectionStatus !== 'connecting'" class="send-inner">
            <span class="send-label">发送</span>
            <svg class="send-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M5 12h14"/>
              <path d="M13 6l6 6-6 6"/>
            </svg>
          </span>
          <span v-else class="send-inner">
            <span class="mini-spinner"></span>
            <span class="send-label">思考中</span>
          </span>
        </button>
      </div>
      <div class="input-hint">
        <kbd>Enter</kbd> 发送 · <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, watch, computed } from 'vue'
import AiAvatarFallback from './AiAvatarFallback.vue'

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  },
  connectionStatus: {
    type: String,
    default: 'disconnected'
  },
  aiType: {
    type: String,
    default: 'default'
  },
  quickPrompts: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['send-message'])

const inputMessage = ref('')
const messagesContainer = ref(null)
const textareaRef = ref(null)

const showQuickPrompts = computed(() =>
  props.quickPrompts.length > 0 &&
  props.messages.length <= 1 &&
  props.connectionStatus !== 'connecting'
)

const sendMessage = () => {
  const text = inputMessage.value.trim()
  if (!text) return
  emit('send-message', text)
  inputMessage.value = ''
  nextTick(() => {
    if (textareaRef.value) {
      textareaRef.value.style.height = 'auto'
    }
  })
}

const onKeydown = (e) => {
  // Enter 发送；Shift+Enter 允许原生换行行为
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    sendMessage()
  }
}

const autoResize = () => {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

const handleQuick = (text) => {
  emit('send-message', text)
}

const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

watch(() => props.messages.length, scrollToBottom)
watch(() => props.messages.map(m => m.content).join(''), scrollToBottom)
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  background: var(--nju-surface);
  border: 1px solid var(--nju-border);
  border-radius: 20px;
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  position: relative;
  text-align: left;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 28px 32px 24px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  background:
    radial-gradient(500px 320px at 0% 0%, rgba(27, 58, 112, 0.05), transparent 60%),
    radial-gradient(600px 400px at 100% 100%, rgba(201, 168, 106, 0.05), transparent 60%),
    var(--nju-surface-soft);
}

/* 每条消息一行；用 justify 分左右 */
.message-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  width: 100%;
  animation: njuFadeUp 0.28s ease both;
}

.row-ai {
  justify-content: flex-start;
}

.row-user {
  justify-content: flex-end;
}

.avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  box-shadow: 0 4px 12px -4px rgba(10, 37, 64, 0.28);
  margin-bottom: 4px;
}

.bubble-column {
  display: flex;
  flex-direction: column;
  max-width: min(720px, 78%);
  min-width: 0;
}

.row-ai .bubble-column {
  align-items: flex-start;
  text-align: left;
}

.row-user .bubble-column {
  align-items: flex-end;
  text-align: left;
}

.sender-label {
  font-size: 12px;
  color: var(--nju-text-muted);
  margin-bottom: 6px;
  letter-spacing: 0.03em;
  padding: 0 4px;
}

.message-bubble {
  padding: 12px 16px;
  border-radius: 14px;
  position: relative;
  word-wrap: break-word;
  box-shadow: var(--shadow-sm);
  text-align: left;
  max-width: 100%;
}

.bubble-user {
  background: linear-gradient(135deg, var(--nju-blue-2) 0%, var(--nju-navy) 100%);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.bubble-ai {
  background: #fff;
  color: var(--nju-text);
  border: 1px solid var(--nju-border);
  border-bottom-left-radius: 4px;
}

.message-content {
  font-size: 15px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
  text-align: left;
}

.bubble-user .message-content {
  color: #fff;
}

.message-time {
  font-size: 11px;
  opacity: 0.5;
  margin-top: 6px;
  text-align: right;
  letter-spacing: 0.02em;
  font-variant-numeric: tabular-nums;
}

.bubble-user .message-time {
  color: rgba(255, 255, 255, 0.9);
  opacity: 0.75;
}

/* 打字光标 */
.typing-indicator {
  display: inline-block;
  color: var(--nju-blue-2);
  margin-left: 2px;
  animation: njuCursorBlink 1s infinite;
  font-weight: 500;
  transform: translateY(1px);
}

.typing-dots {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 20px;
}

.typing-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--nju-blue-2);
  opacity: 0.35;
  animation: njuDotPulse 1.1s infinite ease-in-out;
}

.typing-dots i:nth-child(2) { animation-delay: 0.15s; }
.typing-dots i:nth-child(3) { animation-delay: 0.3s; }

@keyframes njuDotPulse {
  0%, 80%, 100% { transform: scale(0.75); opacity: 0.35; }
  40% { transform: scale(1); opacity: 1; }
}

/* 快捷提问 */
.quick-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 32px 6px;
  border-top: 1px solid var(--nju-border);
  align-items: center;
  background: linear-gradient(180deg, transparent, rgba(255,255,255,0.6));
}

.quick-title {
  font-size: 12px;
  color: var(--nju-text-muted);
  margin-right: 4px;
  letter-spacing: 0.05em;
}

.quick-chip {
  border: 1px solid var(--nju-border-strong);
  background: #fff;
  color: var(--nju-blue-2);
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 13px;
  font-family: inherit;
  transition: all 0.2s ease;
  line-height: 1.4;
}

.quick-chip:hover:not(:disabled) {
  background: var(--nju-blue-2);
  color: #fff;
  border-color: transparent;
  transform: translateY(-1px);
  box-shadow: 0 6px 14px -6px rgba(44, 90, 160, 0.5);
}

.quick-chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 输入区 */
.chat-input-container {
  padding: 14px 22px 18px;
  background: linear-gradient(180deg, rgba(255,255,255,0.6), #fff 45%);
  border-top: 1px solid var(--nju-border);
}

.chat-input {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 8px 8px 8px 16px;
  background: #fff;
  border: 1px solid var(--nju-border-strong);
  border-radius: 14px;
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}

.chat-input:focus-within {
  border-color: var(--nju-blue-2);
  box-shadow: 0 0 0 4px rgba(44, 90, 160, 0.12);
}

.input-box {
  flex-grow: 1;
  border: none;
  outline: none;
  padding: 8px 4px;
  font-size: 15px;
  line-height: 1.55;
  font-family: inherit;
  color: var(--nju-text);
  background: transparent;
  resize: none;
  min-height: 24px;
  max-height: 160px;
  overflow-y: auto;
  scrollbar-width: thin;
  text-align: left;
}

.input-box::placeholder {
  color: var(--nju-text-muted);
}

.send-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: linear-gradient(135deg, var(--nju-blue-2), var(--nju-navy));
  color: white;
  border: none;
  border-radius: 10px;
  padding: 0 16px;
  font-size: 14px;
  font-weight: 500;
  height: 40px;
  box-shadow: 0 6px 16px -8px rgba(10, 37, 64, 0.55);
  transition: transform 0.15s ease, box-shadow 0.2s ease, opacity 0.2s ease;
  flex-shrink: 0;
}

.send-button:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 10px 22px -10px rgba(10, 37, 64, 0.7);
}

.send-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  box-shadow: none;
}

.send-inner {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.send-arrow {
  width: 16px;
  height: 16px;
}

.mini-spinner {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  animation: njuSpin 0.9s linear infinite;
}

@keyframes njuSpin {
  to { transform: rotate(360deg); }
}

.input-hint {
  margin-top: 8px;
  padding-left: 4px;
  font-size: 11.5px;
  color: var(--nju-text-muted);
  letter-spacing: 0.02em;
}

.input-hint kbd {
  display: inline-block;
  padding: 1px 6px;
  font-family: 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
  font-size: 11px;
  color: var(--nju-text-soft);
  background: #f1f4fa;
  border: 1px solid var(--nju-border-strong);
  border-radius: 5px;
  margin: 0 1px;
  box-shadow: 0 1px 0 rgba(20, 45, 90, 0.06);
}

/* 连续同侧消息隐藏头像+称谓，形成"消息组"视觉 */
.row-ai + .row-ai .avatar,
.row-ai + .row-ai .sender-label {
  visibility: hidden;
  height: 0;
  margin-bottom: 0;
}

.row-user + .row-user .avatar,
.row-user + .row-user .sender-label {
  visibility: hidden;
  height: 0;
  margin-bottom: 0;
}

.row-ai + .row-ai,
.row-user + .row-user {
  margin-top: -10px;
}

/* 响应式 */
@media (max-width: 768px) {
  .chat-container { border-radius: 16px; height: calc(100vh - 180px); }
  .chat-messages { padding: 20px 16px; gap: 14px; }
  .bubble-column { max-width: 88%; }
  .quick-prompts { padding: 12px 16px 4px; }
  .chat-input-container { padding: 12px 12px 14px; }
}

@media (max-width: 480px) {
  .avatar { width: 34px; height: 34px; }
  .message-bubble { padding: 10px 12px; }
  .message-content { font-size: 14px; }
  .send-button { padding: 0 12px; }
  .send-label { display: none; }
}
</style>
