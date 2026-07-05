<template>
  <Teleport to="body">
    <div v-if="visible" class="trace-overlay" @click.self="$emit('close')">
      <div class="trace-modal">
        <!-- Header -->
        <div class="modal-header">
          <div class="header-left">
            <button v-if="view === 'detail'" class="back-btn" @click="view = 'list'">
              <span class="back-arrow">&larr;</span> 返回列表
            </button>
            <h2 v-else class="modal-title">Agent Trace</h2>
          </div>
          <div class="header-right">
            <button v-if="isLoggedIn" class="logout-btn" @click="logout">退出登录</button>
            <button class="close-btn" @click="$emit('close')">&times;</button>
          </div>
        </div>

        <!-- Login -->
        <div v-if="!isLoggedIn" class="login-panel">
          <div class="login-card">
            <div class="login-icon">&#128274;</div>
            <h3>Trace 管理登录</h3>
            <p class="login-hint">请输入管理员账号查看 Agent 执行追踪</p>
            <form @submit.prevent="handleLogin">
              <div class="form-group">
                <label>账号</label>
                <input v-model="loginForm.username" type="text" placeholder="请输入账号" autocomplete="username" />
              </div>
              <div class="form-group">
                <label>密码</label>
                <input v-model="loginForm.password" type="password" placeholder="请输入密码" autocomplete="current-password" />
              </div>
              <p v-if="loginError" class="login-error">{{ loginError }}</p>
              <button type="submit" class="login-submit">登录</button>
            </form>
          </div>
        </div>

        <!-- List View -->
        <div v-else-if="view === 'list'" class="trace-list-panel">
          <div class="list-toolbar">
            <span class="trace-count">共 {{ traces.length }} 条追踪记录</span>
            <button class="refresh-btn" @click="loadTraces" :disabled="loading">
              {{ loading ? '加载中...' : '刷新' }}
            </button>
          </div>
          <div v-if="loading" class="loading-state">加载中...</div>
          <div v-else-if="traces.length === 0" class="empty-state">暂无追踪记录，请先在聊天中发送消息</div>
          <div v-else class="trace-table-wrapper">
            <table class="trace-table">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>用户问题</th>
                  <th>重写后</th>
                  <th>意图</th>
                  <th>置信度</th>
                  <th>工具调用</th>
                  <th>检索条目</th>
                  <th>耗时</th>
                  <th>Token</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="trace in traces" :key="trace.traceId" @click="viewDetail(trace.traceId)">
                  <td class="cell-time">{{ formatTime(trace.timestamp) }}</td>
                  <td class="cell-query">{{ truncate(trace.userQuery, 30) }}</td>
                  <td class="cell-query">{{ truncate(trace.rewrittenQuery, 25) }}</td>
                  <td><span class="intent-badge" :class="intentClass(trace.intent)">{{ trace.intent }}</span></td>
                  <td class="cell-num">{{ formatConfidence(trace.confidence) }}</td>
                  <td class="cell-num">{{ trace.toolCalls?.length || 0 }}</td>
                  <td class="cell-num">{{ trace.retrievalContext?.length || 0 }}</td>
                  <td class="cell-num">{{ trace.durationMs }}ms</td>
                  <td class="cell-num">{{ trace.tokenEstimate }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Detail View -->
        <div v-else-if="view === 'detail'" class="trace-detail-panel">
          <div v-if="detailLoading" class="loading-state">加载详情中...</div>
          <div v-else-if="selectedTrace" class="detail-content">
            <!-- 基本信息 -->
            <section class="detail-section">
              <h4 class="section-title">基本信息</h4>
              <div class="info-grid">
                <div class="info-item"><span class="info-label">Trace ID</span><span class="info-value mono">{{ selectedTrace.traceId }}</span></div>
                <div class="info-item"><span class="info-label">会话 ID</span><span class="info-value mono">{{ selectedTrace.conversationId }}</span></div>
                <div class="info-item"><span class="info-label">时间</span><span class="info-value">{{ formatTime(selectedTrace.timestamp) }}</span></div>
                <div class="info-item"><span class="info-label">耗时</span><span class="info-value">{{ selectedTrace.durationMs }}ms</span></div>
                <div class="info-item"><span class="info-label">Token 估算</span><span class="info-value">{{ selectedTrace.tokenEstimate }}</span></div>
              </div>
            </section>

            <!-- 查询处理 -->
            <section class="detail-section">
              <h4 class="section-title">查询处理</h4>
              <div class="query-flow">
                <div class="query-box">
                  <span class="query-label">原始问题</span>
                  <p class="query-text">{{ selectedTrace.userQuery }}</p>
                </div>
                <span class="flow-arrow">&rarr;</span>
                <div class="query-box">
                  <span class="query-label">重写后</span>
                  <p class="query-text">{{ selectedTrace.rewrittenQuery || '(未重写)' }}</p>
                </div>
              </div>
            </section>

            <!-- 意图分类 -->
            <section class="detail-section">
              <h4 class="section-title">意图分类</h4>
              <div class="intent-detail">
                <span class="intent-badge large" :class="intentClass(selectedTrace.intent)">{{ selectedTrace.intent }}</span>
                <span class="confidence-bar">
                  <span class="confidence-fill" :style="{ width: (selectedTrace.confidence * 100) + '%' }"></span>
                </span>
                <span class="confidence-text">{{ formatConfidence(selectedTrace.confidence) }}</span>
              </div>
            </section>

            <!-- 检索上下文 -->
            <section v-if="selectedTrace.retrievalContext?.length" class="detail-section">
              <h4 class="section-title">检索上下文 ({{ selectedTrace.retrievalContext.length }})</h4>
              <div class="retrieval-list">
                <div v-for="(item, idx) in selectedTrace.retrievalContext" :key="idx" class="retrieval-item">
                  <div class="retrieval-header">
                    <span class="retrieval-source">{{ item.source || 'unknown' }}</span>
                    <span class="retrieval-score">score: {{ item.score?.toFixed(4) }}</span>
                  </div>
                  <p class="retrieval-snippet">{{ item.snippet }}</p>
                </div>
              </div>
            </section>

            <!-- Rerank 结果 -->
            <section v-if="selectedTrace.rerankContext?.length" class="detail-section">
              <h4 class="section-title">Rerank 结果 ({{ selectedTrace.rerankContext.length }})</h4>
              <div class="retrieval-list">
                <div v-for="(item, idx) in selectedTrace.rerankContext" :key="idx" class="retrieval-item">
                  <div class="retrieval-header">
                    <span class="retrieval-source">{{ item.source || 'unknown' }}</span>
                    <span class="retrieval-score">score: {{ item.score?.toFixed(4) }}</span>
                  </div>
                  <p class="retrieval-snippet">{{ item.snippet }}</p>
                </div>
              </div>
            </section>

            <!-- 工具调用 -->
            <section v-if="selectedTrace.toolCalls?.length" class="detail-section">
              <h4 class="section-title">工具调用 ({{ selectedTrace.toolCalls.length }})</h4>
              <div class="tool-list">
                <div v-for="(tool, idx) in selectedTrace.toolCalls" :key="idx" class="tool-item">
                  <div class="tool-header">
                    <span class="tool-name">{{ tool.toolName }}</span>
                    <span class="tool-duration">{{ tool.durationMs }}ms</span>
                  </div>
                  <div class="tool-body">
                    <div class="tool-field">
                      <span class="tool-field-label">参数</span>
                      <pre class="tool-field-value">{{ tool.arguments }}</pre>
                    </div>
                    <div class="tool-field">
                      <span class="tool-field-label">结果</span>
                      <pre class="tool-field-value">{{ tool.result }}</pre>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <!-- 最终回答 -->
            <section v-if="selectedTrace.finalAnswer" class="detail-section">
              <h4 class="section-title">最终回答</h4>
              <div class="final-answer" :class="{ expanded: answerExpanded }">
                <p>{{ selectedTrace.finalAnswer }}</p>
              </div>
              <button v-if="selectedTrace.finalAnswer?.length > 300" class="expand-btn" @click="answerExpanded = !answerExpanded">
                {{ answerExpanded ? '收起' : '展开全文' }}
              </button>
            </section>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { fetchTraces, fetchTraceDetail } from '../api'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['close'])

const isLoggedIn = ref(sessionStorage.getItem('trace_auth') === 'true')
const view = ref('list')
const traces = ref([])
const selectedTrace = ref(null)
const loading = ref(false)
const detailLoading = ref(false)
const answerExpanded = ref(false)

const loginForm = ref({ username: '', password: '' })
const loginError = ref('')

function handleLogin() {
  if (loginForm.value.username === 'joeychen' && loginForm.value.password === '123456') {
    sessionStorage.setItem('trace_auth', 'true')
    isLoggedIn.value = true
    loginError.value = ''
    loadTraces()
  } else {
    loginError.value = '账号或密码错误'
  }
}

function logout() {
  sessionStorage.removeItem('trace_auth')
  isLoggedIn.value = false
  view.value = 'list'
  traces.value = []
  selectedTrace.value = null
}

async function loadTraces() {
  loading.value = true
  try {
    const res = await fetchTraces()
    traces.value = res.data || []
  } catch (e) {
    console.error('加载 trace 列表失败', e)
  } finally {
    loading.value = false
  }
}

async function viewDetail(traceId) {
  view.value = 'detail'
  detailLoading.value = true
  answerExpanded.value = false
  try {
    const res = await fetchTraceDetail(traceId)
    selectedTrace.value = res.data
  } catch (e) {
    console.error('加载 trace 详情失败', e)
  } finally {
    detailLoading.value = false
  }
}

function formatTime(ts) {
  if (!ts) return '-'
  const d = new Date(ts)
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function formatConfidence(c) {
  if (c == null || c < 0) return '-'
  return (c * 100).toFixed(0) + '%'
}

function truncate(str, len) {
  if (!str) return '-'
  return str.length > len ? str.slice(0, len) + '...' : str
}

function intentClass(intent) {
  return {
    'intent-chat': intent === 'CHAT',
    'intent-knowledge': intent === 'KNOWLEDGE',
    'intent-task': intent === 'TASK',
    'intent-reject': intent === 'REJECT'
  }
}

watch(() => props.visible, (val) => {
  if (val && isLoggedIn.value) {
    loadTraces()
  }
})
</script>

<style scoped>
.trace-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(10, 37, 64, 0.6);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.trace-modal {
  width: 92vw;
  height: 88vh;
  max-width: 1400px;
  background: var(--nju-surface);
  border-radius: 16px;
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: slideUp 0.25s ease;
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

/* Header */
.modal-header {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid var(--nju-border);
  background: var(--nju-surface-soft);
}

.modal-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--nju-navy);
  letter-spacing: 0.02em;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 1px solid var(--nju-border-strong);
  border-radius: 8px;
  background: var(--nju-surface);
  font-size: 13px;
  color: var(--nju-text-soft);
  transition: all 0.15s;
}

.back-btn:hover {
  background: var(--nju-surface-soft);
  color: var(--nju-text);
}

.back-arrow {
  font-size: 16px;
}

.logout-btn {
  padding: 5px 12px;
  border: 1px solid var(--nju-border-strong);
  border-radius: 6px;
  background: transparent;
  font-size: 12px;
  color: var(--nju-text-muted);
  transition: all 0.15s;
}

.logout-btn:hover {
  color: #d9534f;
  border-color: #d9534f;
}

.close-btn {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  font-size: 22px;
  color: var(--nju-text-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}

.close-btn:hover {
  background: rgba(217, 83, 79, 0.1);
  color: #d9534f;
}

/* Login */
.login-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-card {
  width: 360px;
  padding: 40px 36px;
  text-align: center;
}

.login-icon {
  font-size: 40px;
  margin-bottom: 16px;
}

.login-card h3 {
  font-size: 20px;
  font-weight: 700;
  color: var(--nju-navy);
  margin-bottom: 8px;
}

.login-hint {
  font-size: 13px;
  color: var(--nju-text-muted);
  margin-bottom: 28px;
}

.form-group {
  text-align: left;
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--nju-text-soft);
  margin-bottom: 6px;
}

.form-group input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid var(--nju-border-strong);
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.form-group input:focus {
  border-color: var(--nju-blue-2);
  box-shadow: 0 0 0 3px rgba(44, 90, 160, 0.1);
}

.login-error {
  color: #d9534f;
  font-size: 13px;
  margin-bottom: 12px;
}

.login-submit {
  width: 100%;
  padding: 11px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--nju-blue-2), var(--nju-navy));
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  transition: opacity 0.2s;
}

.login-submit:hover {
  opacity: 0.9;
}

/* List View */
.trace-list-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.list-toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  border-bottom: 1px solid var(--nju-border);
}

.trace-count {
  font-size: 13px;
  color: var(--nju-text-muted);
}

.refresh-btn {
  padding: 6px 16px;
  border: 1px solid var(--nju-border-strong);
  border-radius: 6px;
  background: var(--nju-surface);
  font-size: 13px;
  color: var(--nju-text-soft);
  transition: all 0.15s;
}

.refresh-btn:hover:not(:disabled) {
  background: var(--nju-surface-soft);
  border-color: var(--nju-blue-2);
  color: var(--nju-blue-2);
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.loading-state, .empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--nju-text-muted);
  font-size: 14px;
}

.trace-table-wrapper {
  flex: 1;
  overflow: auto;
  padding: 0 24px 24px;
}

.trace-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.trace-table th {
  position: sticky;
  top: 0;
  background: var(--nju-surface);
  padding: 12px 10px;
  text-align: left;
  font-weight: 600;
  color: var(--nju-text-soft);
  border-bottom: 2px solid var(--nju-border-strong);
  white-space: nowrap;
}

.trace-table td {
  padding: 11px 10px;
  border-bottom: 1px solid var(--nju-border);
  color: var(--nju-text);
  vertical-align: middle;
}

.trace-table tbody tr {
  cursor: pointer;
  transition: background 0.12s;
}

.trace-table tbody tr:hover {
  background: var(--nju-surface-soft);
}

.cell-time {
  white-space: nowrap;
  font-size: 12px;
  color: var(--nju-text-muted);
  font-variant-numeric: tabular-nums;
}

.cell-query {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-num {
  text-align: center;
  font-variant-numeric: tabular-nums;
}

/* Intent Badge */
.intent-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.intent-badge.large {
  padding: 4px 14px;
  font-size: 13px;
  border-radius: 6px;
}

.intent-chat { background: #e6f9ef; color: #1a7f4b; }
.intent-knowledge { background: #e8f2ff; color: #1d5cb8; }
.intent-task { background: #fff8e6; color: #8b6914; }
.intent-reject { background: #fde8e8; color: #c0392b; }

/* Detail View */
.trace-detail-panel {
  flex: 1;
  overflow: auto;
  padding: 24px;
}

.detail-content {
  max-width: 900px;
  margin: 0 auto;
}

.detail-section {
  margin-bottom: 28px;
}

.section-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--nju-navy);
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--nju-border);
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 14px;
  background: var(--nju-surface-soft);
  border-radius: 8px;
}

.info-label {
  font-size: 11px;
  font-weight: 500;
  color: var(--nju-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.info-value {
  font-size: 13px;
  color: var(--nju-text);
  word-break: break-all;
}

.info-value.mono {
  font-family: 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
  font-size: 12px;
}

/* Query Flow */
.query-flow {
  display: flex;
  align-items: center;
  gap: 16px;
}

.query-box {
  flex: 1;
  padding: 14px 16px;
  background: var(--nju-surface-soft);
  border-radius: 10px;
  border: 1px solid var(--nju-border);
}

.query-label {
  display: block;
  font-size: 11px;
  font-weight: 500;
  color: var(--nju-text-muted);
  margin-bottom: 6px;
}

.query-text {
  font-size: 14px;
  color: var(--nju-text);
  line-height: 1.5;
}

.flow-arrow {
  font-size: 20px;
  color: var(--nju-text-muted);
  flex-shrink: 0;
}

/* Intent Detail */
.intent-detail {
  display: flex;
  align-items: center;
  gap: 16px;
}

.confidence-bar {
  flex: 1;
  max-width: 200px;
  height: 8px;
  background: var(--nju-border);
  border-radius: 4px;
  overflow: hidden;
}

.confidence-fill {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--nju-blue-2), var(--nju-accent));
  border-radius: 4px;
  transition: width 0.3s ease;
}

.confidence-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--nju-text);
  min-width: 40px;
}

/* Retrieval List */
.retrieval-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.retrieval-item {
  padding: 12px 16px;
  background: var(--nju-surface-soft);
  border-radius: 8px;
  border: 1px solid var(--nju-border);
}

.retrieval-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.retrieval-source {
  font-size: 12px;
  font-weight: 500;
  color: var(--nju-blue-2);
}

.retrieval-score {
  font-size: 11px;
  color: var(--nju-text-muted);
  font-family: 'SFMono-Regular', ui-monospace, monospace;
}

.retrieval-snippet {
  font-size: 13px;
  color: var(--nju-text-soft);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

/* Tool List */
.tool-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tool-item {
  border: 1px solid var(--nju-border);
  border-radius: 10px;
  overflow: hidden;
}

.tool-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  background: var(--nju-surface-soft);
  border-bottom: 1px solid var(--nju-border);
}

.tool-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--nju-navy);
}

.tool-duration {
  font-size: 12px;
  color: var(--nju-text-muted);
  font-variant-numeric: tabular-nums;
}

.tool-body {
  padding: 12px 16px;
}

.tool-field {
  margin-bottom: 10px;
}

.tool-field:last-child {
  margin-bottom: 0;
}

.tool-field-label {
  display: block;
  font-size: 11px;
  font-weight: 500;
  color: var(--nju-text-muted);
  margin-bottom: 4px;
}

.tool-field-value {
  font-size: 12px;
  font-family: 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
  color: var(--nju-text-soft);
  background: var(--nju-surface-soft);
  padding: 8px 12px;
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow: auto;
  margin: 0;
}

/* Final Answer */
.final-answer {
  padding: 14px 16px;
  background: var(--nju-surface-soft);
  border-radius: 8px;
  border: 1px solid var(--nju-border);
  font-size: 14px;
  line-height: 1.7;
  color: var(--nju-text);
  max-height: 150px;
  overflow: hidden;
  transition: max-height 0.3s ease;
}

.final-answer.expanded {
  max-height: none;
}

.expand-btn {
  margin-top: 8px;
  padding: 4px 12px;
  border: 1px solid var(--nju-border-strong);
  border-radius: 6px;
  background: transparent;
  font-size: 12px;
  color: var(--nju-blue-2);
  transition: all 0.15s;
}

.expand-btn:hover {
  background: var(--nju-surface-soft);
}

/* Responsive */
@media (max-width: 768px) {
  .trace-modal {
    width: 100vw;
    height: 100vh;
    border-radius: 0;
  }
  .query-flow {
    flex-direction: column;
  }
  .flow-arrow {
    transform: rotate(90deg);
  }
  .info-grid {
    grid-template-columns: 1fr;
  }
}
</style>
