<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, streamAgentMessage } from '../api'
import { createReactiveStreamMessage } from '../agent-stream-state'

const route = useRoute()
const router = useRouter()

const conversations = ref([])
const messages = ref([])
const selectedId = ref(null)
const conversationsLoading = ref(true)
const messagesLoading = ref(false)
const loadingOlder = ref(false)
const creating = ref(false)
const sending = ref(false)
const deletingId = ref(null)
const pendingDelete = ref(null)
const hasMore = ref(false)
const oldestMessageId = ref(null)
const errorMessage = ref('')
const messagePanel = ref(null)
const draft = ref('')
const nowMs = ref(Date.now())

/* eventId 是服务端生成的事件唯一键。网络重放时可能收到重复事件，
 * 必须在追加文本、工具和动作卡片之前去重。 */
const seenEventIds = new Set()
let expiryTimer = null

/*
 * 路由监听只在首屏初始化完成后启用。否则 onMounted 中的路由校准和
 * watch 会同时加载同一份历史，造成重复 HTTP 请求和加载状态闪烁。
 */
let routeWatchReady = false
let historyRequestVersion = 0
let scrollScheduled = false

const selectedConversation = computed(() => conversations.value.find(
  (conversation) => String(conversation.id) === String(selectedId.value)
))

const canSend = computed(() => (
  Boolean(selectedId.value) &&
  Boolean(draft.value.trim()) &&
  draft.value.trim().length <= 1000 &&
  !sending.value
))

/**
 * 雪花 ID 超过 JavaScript Number 的安全整数范围。
 * 后端已经把 Long 序列化为字符串，这里只用 BigInt 比较，绝不把 ID
 * 转成 Number，确保删除、路由和历史游标始终指向正确记录。
 */
function compareIds(left, right) {
  try {
    const a = BigInt(String(left))
    const b = BigInt(String(right))
    return a < b ? -1 : a > b ? 1 : 0
  } catch (_) {
    return String(left).localeCompare(String(right))
  }
}

function formatTime(value) {
  if (!value) return '暂无消息'
  return value.replace('T', ' ').slice(5, 16)
}

function messageStatusText(status) {
  return {
    STREAMING: '生成中',
    COMPLETED: '已完成',
    FAILED: '生成失败'
  }[status] || status
}

function formatMoney(value) {
  const amount = Number(value)
  return Number.isFinite(amount) ? amount.toFixed(2) : '0.00'
}

/** 后端卡片金额统一使用“分”，这里只在展示时转换为元。 */
function formatCent(value) {
  const cent = Number(value)
  return Number.isFinite(cent) ? (cent / 100).toFixed(2) : '0.00'
}

function orderStatusText(status) {
  return {
    PENDING_PAYMENT: '待付款',
    PAID: '已支付',
    WAITING_SHIPMENT: '待发货',
    WAITING_RECEIPT: '待收货',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
    REFUNDING: '退款中',
    REFUNDED: '已退款'
  }[status] || '状态处理中'
}

function actionTotal(action) {
  const total = Number(action.unitPrice) * Number(action.quantity)
  return Number.isFinite(total) ? total.toFixed(2) : '0.00'
}

function isActionExpired(action) {
  if (!action?.expiresAt) return false
  return new Date(action.expiresAt).getTime() <= nowMs.value
}

function actionStatusText(action) {
  if (action.status === 'CONFIRMED') return '已加入购物车'
  if (action.status === 'CANCELLED') return '已取消'
  if (action.status === 'EXPIRED' || isActionExpired(action)) return '已过期'
  return '等待确认'
}

function mergeOlderMessages(incoming) {
  const byId = new Map()

  for (const message of [...(incoming || []), ...messages.value]) {
    byId.set(String(message.id), message)
  }

  messages.value = [...byId.values()]
    .sort((left, right) => compareIds(left.id, right.id))
}

async function scrollToBottom() {
  await nextTick()
  if (messagePanel.value) {
    messagePanel.value.scrollTop = messagePanel.value.scrollHeight
  }
}

function scheduleScrollToBottom() {
  if (scrollScheduled) return
  scrollScheduled = true

  requestAnimationFrame(async () => {
    scrollScheduled = false
    await scrollToBottom()
  })
}

async function loadConversations() {
  conversationsLoading.value = true
  errorMessage.value = ''

  try {
    const result = await api.agentConversations(1, 50)
    conversations.value = result?.records || []
  } catch (error) {
    conversations.value = []
    errorMessage.value = error.message || '购物助手会话加载失败'
  } finally {
    conversationsLoading.value = false
  }
}

/**
 * 一轮消息结束后静默刷新会话摘要，让后端生成的首条消息标题和
 * lastMessageAt 立即出现在侧栏。刷新失败不影响已经完成的对话。
 */
async function refreshConversationList() {
  try {
    const result = await api.agentConversations(1, 50)
    conversations.value = result?.records || conversations.value
  } catch (_) {
    // 页面仍可使用当前本地列表，下次进入页面会重新从服务端校准。
  }
}

function upsertTool(assistantMessage, data, status) {
  const tools = assistantMessage.tools || (assistantMessage.tools = [])
  const index = tools.findIndex(
    (tool) => tool.toolCallId === data.toolCallId
  )
  const tool = {
    toolCallId: data.toolCallId,
    toolName: data.toolName,
    displayText: data.displayText,
    success: data.success,
    status
  }

  if (index >= 0) tools.splice(index, 1, tool)
  else tools.push(tool)
}

/**
 * 结果卡片以工具调用 ID 去重。卡片只接受 TOOL_COMPLETED 中由服务端
 * 生成的 resultCard，不能从模型正文中解析商品 ID、金额或订单号。
 */
function upsertResultCard(assistantMessage, data) {
  if (!data?.resultCard || !data.toolCallId) return

  const resultCards = assistantMessage.resultCards || (
    assistantMessage.resultCards = []
  )
  const card = {
    ...data.resultCard,
    toolCallId: data.toolCallId
  }
  const index = resultCards.findIndex(
    (item) => item.toolCallId === data.toolCallId
  )

  if (index >= 0) resultCards.splice(index, 1, card)
  else resultCards.push(card)
}

/**
 * 将统一 SSE 包络映射为当前助手草稿。
 *
 * 模型文本只处理 CONTENT_DELTA；商品动作只接受 ACTION_REQUIRED 的
 * 服务端结构化字段。绝不能从模型正文中解析 actionId、skuId 或价格。
 */
async function handleAgentEvent(event, streamState) {
  if (!event || !event.type) return

  if (event.eventId) {
    if (seenEventIds.has(event.eventId)) return
    seenEventIds.add(event.eventId)
  }

  const data = event.data || {}
  const assistantMessage = streamState.assistantMessage

  if (event.type === 'RUN_STARTED') {
    streamState.runId = event.runId
    streamState.userMessage.id = data.userMessageId
    assistantMessage.id = data.assistantMessageId
    assistantMessage.runId = event.runId
  } else if (event.type === 'CONTENT_DELTA') {
    assistantMessage.content += data.delta || ''
  } else if (event.type === 'TOOL_STARTED') {
    upsertTool(assistantMessage, data, 'RUNNING')
  } else if (event.type === 'TOOL_COMPLETED') {
    upsertTool(
      assistantMessage,
      data,
      data.success ? 'SUCCEEDED' : 'FAILED'
    )
    if (data.success) upsertResultCard(assistantMessage, data)
  } else if (event.type === 'ACTION_REQUIRED') {
    const actions = assistantMessage.actions || (assistantMessage.actions = [])
    const duplicate = actions.some(
      (action) => String(action.actionId) === String(data.actionId)
    )

    if (!duplicate) {
      actions.push({
        ...data,
        status: 'PENDING',
        busy: false,
        error: '',
        idempotencyKey: null
      })
    }
  } else if (event.type === 'MESSAGE_COMPLETED') {
    const finalMessage = data.message || {}
    const tools = assistantMessage.tools || []
    const actions = assistantMessage.actions || []
    const cardMap = new Map()

    /*
     * 正常流中卡片会先随 TOOL_COMPLETED 到达，完成事件又会携带数据库
     * 权威副本。按 toolCallId 合并后既能去重，也能补回偶发漏收的事件。
     */
    for (const card of [
      ...(assistantMessage.resultCards || []),
      ...(finalMessage.resultCards || [])
    ]) {
      if (card?.toolCallId) cardMap.set(card.toolCallId, card)
    }

    /*
     * 完成事件携带数据库中的权威消息，用它覆盖增量草稿，避免丢包或
     * Unicode 分块导致正文不完整；工具和动作属于本次前端流状态，保留。
     */
    Object.assign(assistantMessage, finalMessage)
    assistantMessage.tools = tools
    assistantMessage.actions = actions
    assistantMessage.resultCards = [...cardMap.values()]
    streamState.terminal = true
  } else if (event.type === 'RUN_FAILED') {
    assistantMessage.status = 'FAILED'
    assistantMessage.content = data.message || '购物助手暂时不可用，请稍后重试'
    assistantMessage.retryable = Boolean(data.retryable)
    assistantMessage.errorCode = data.code
    streamState.terminal = true

    if (String(streamState.conversationId) === String(selectedId.value)) {
      errorMessage.value = assistantMessage.content
    }
  }

  scheduleScrollToBottom()
}

async function reconcileHistoryAfterFailure(conversationId) {
  if (String(conversationId) !== String(selectedId.value)) return

  try {
    const result = await api.agentMessages(conversationId, { limit: 30 })
    messages.value = result?.items || []
    hasMore.value = Boolean(result?.hasMore)
    oldestMessageId.value = result?.oldestMessageId || null
    await scrollToBottom()
  } catch (_) {
    // 原始流异常提示更有价值，历史校准失败时不覆盖它。
  }
}

async function sendMessage() {
  const content = draft.value.trim()
  if (!canSend.value) return

  const conversationId = String(selectedId.value)
  const clientMessageId = crypto.randomUUID()
  const createdAt = new Date().toISOString()
  const userMessage = {
    id: 'local-user-' + clientMessageId,
    conversationId,
    role: 'USER',
    content,
    status: 'COMPLETED',
    clientMessageId,
    createdAt,
    completedAt: createdAt
  }
  /*
   * SSE 后续会持续修改这一个对象，因此必须在保存原始引用前就用 reactive
   * 包装。若把普通对象 push 到 ref 数组后仍修改普通对象本身，Vue 只代理
   * 数组中的副本引用，CONTENT_DELTA 不会触发视图刷新；直到完成事件重新
   * 赋值时，页面才会看起来像“一次性输出完整回答”。
   */
  const assistantMessage = createReactiveStreamMessage({
    id: 'local-assistant-' + clientMessageId,
    conversationId,
    role: 'ASSISTANT',
    content: '',
    status: 'STREAMING',
    runId: null,
    createdAt,
    completedAt: null,
    tools: [],
    actions: [],
    resultCards: []
  })
  const streamState = {
    conversationId,
    userMessage,
    assistantMessage,
    runId: null,
    terminal: false
  }

  messages.value.push(userMessage, assistantMessage)
  draft.value = ''
  sending.value = true
  errorMessage.value = ''
  await scrollToBottom()

  try {
    await streamAgentMessage(
      conversationId,
      { clientMessageId, content },
      {
        onEvent: (event) => handleAgentEvent(event, streamState)
      }
    )

    if (!streamState.terminal) {
      throw new Error('流式连接已结束，但没有收到运行完成事件')
    }
  } catch (error) {
    errorMessage.value = error.message || '消息发送失败，请稍后重试'

    /*
     * HTTP 冲突或网络断开后不猜测运行结果，重新查询 MySQL 可见历史。
     * 若后端已经完成，页面会显示权威消息；若仍在运行，则保留其
     * STREAMING 状态，用户稍后重新打开会话即可再次校准。
     */
    await reconcileHistoryAfterFailure(conversationId)
  } finally {
    sending.value = false
    await refreshConversationList()
  }
}

function handleComposerKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

async function confirmAction(action) {
  if (
    action.busy ||
    action.status !== 'PENDING' ||
    isActionExpired(action)
  ) return

  action.busy = true
  action.error = ''
  // 网络重试必须复用同一个幂等键，不能每点击一次都生成新键。
  action.idempotencyKey ||= crypto.randomUUID()

  try {
    const result = await api.confirmAgentAction(
      action.actionId,
      action.idempotencyKey
    )
    action.status = result.status
    action.cartItem = result.cartItem
  } catch (error) {
    action.error = error.message || '加入购物车失败'
  } finally {
    action.busy = false
  }
}

async function cancelAction(action) {
  if (
    action.busy ||
    action.status !== 'PENDING' ||
    isActionExpired(action)
  ) return

  action.busy = true
  action.error = ''

  try {
    const result = await api.cancelAgentAction(action.actionId)
    action.status = result.status
  } catch (error) {
    action.error = error.message || '取消动作失败'
  } finally {
    action.busy = false
  }
}

/**
 * 加载选中会话最新一页历史。
 *
 * requestVersion 用于处理快速切换：如果旧请求比新请求更晚返回，旧响应
 * 会被丢弃，避免把 A 会话的消息错误渲染到 B 会话中。
 */
async function openConversation(conversationId) {
  const requestVersion = ++historyRequestVersion

  if (!conversationId) {
    selectedId.value = null
    messages.value = []
    hasMore.value = false
    oldestMessageId.value = null
    return
  }

  selectedId.value = String(conversationId)
  messagesLoading.value = true
  errorMessage.value = ''

  try {
    const result = await api.agentMessages(conversationId, { limit: 30 })

    if (requestVersion !== historyRequestVersion) return

    messages.value = result?.items || []
    hasMore.value = Boolean(result?.hasMore)
    oldestMessageId.value = result?.oldestMessageId || null
    await scrollToBottom()
  } catch (error) {
    if (requestVersion !== historyRequestVersion) return

    messages.value = []
    hasMore.value = false
    oldestMessageId.value = null
    errorMessage.value = error.message || '会话历史加载失败'
  } finally {
    if (requestVersion === historyRequestVersion) {
      messagesLoading.value = false
    }
  }
}

async function selectConversation(conversation) {
  if (sending.value) {
    errorMessage.value = '当前回复仍在生成，请完成后再切换会话'
    return
  }
  if (String(conversation.id) === String(selectedId.value)) return
  await router.push('/assistant/' + conversation.id)
}

async function createConversation(replaceRoute = false) {
  if (creating.value || sending.value) {
    if (sending.value) {
      errorMessage.value = '当前回复仍在生成，请完成后再创建会话'
    }
    return null
  }

  creating.value = true
  errorMessage.value = ''

  try {
    const conversation = await api.createAgentConversation()

    // 新建会话立即放在顶部，用户不必在长列表中寻找刚创建的记录。
    conversations.value = [
      conversation,
      ...conversations.value.filter(
        (item) => String(item.id) !== String(conversation.id)
      )
    ]

    const path = '/assistant/' + conversation.id
    if (replaceRoute) await router.replace(path)
    else await router.push(path)

    return conversation
  } catch (error) {
    errorMessage.value = error.message || '创建会话失败'
    return null
  } finally {
    creating.value = false
  }
}

async function loadOlderMessages() {
  if (
    !selectedId.value ||
    !hasMore.value ||
    !oldestMessageId.value ||
    loadingOlder.value
  ) return

  loadingOlder.value = true
  errorMessage.value = ''
  const conversationId = selectedId.value
  const previousHeight = messagePanel.value?.scrollHeight || 0

  try {
    const result = await api.agentMessages(conversationId, {
      beforeMessageId: oldestMessageId.value,
      limit: 30
    })

    // 用户在请求期间切换了会话时，不能合并已经过期的历史响应。
    if (String(conversationId) !== String(selectedId.value)) return

    mergeOlderMessages(result?.items)
    hasMore.value = Boolean(result?.hasMore)
    oldestMessageId.value = result?.oldestMessageId || oldestMessageId.value

    await nextTick()
    if (messagePanel.value) {
      messagePanel.value.scrollTop +=
        messagePanel.value.scrollHeight - previousHeight
    }
  } catch (error) {
    errorMessage.value = error.message || '更早消息加载失败'
  } finally {
    loadingOlder.value = false
  }
}

function askDelete(conversation) {
  if (
    sending.value &&
    String(conversation.id) === String(selectedId.value)
  ) {
    errorMessage.value = '当前回复仍在生成，请完成后再删除会话'
    return
  }
  pendingDelete.value = conversation
}

function cancelDelete() {
  if (deletingId.value) return
  pendingDelete.value = null
}

/**
 * 确认删除后，前端只更新本地会话状态，不尝试删除消息、运行或动作。
 * 这些关联数据必须由后端事务统一处理，前端只调用一个会话删除接口。
 */
async function confirmDelete() {
  const conversation = pendingDelete.value
  if (!conversation || deletingId.value) return

  const conversationId = String(conversation.id)
  const removedIndex = conversations.value.findIndex(
    (item) => String(item.id) === conversationId
  )

  deletingId.value = conversationId
  errorMessage.value = ''

  try {
    await api.deleteAgentConversation(conversationId)

    conversations.value = conversations.value.filter(
      (item) => String(item.id) !== conversationId
    )
    pendingDelete.value = null

    if (conversationId !== String(selectedId.value)) return

    // 立即清空已经删除的历史，避免路由切换期间继续显示旧内容。
    selectedId.value = null
    messages.value = []
    hasMore.value = false
    oldestMessageId.value = null

    if (conversations.value.length > 0) {
      const nextIndex = Math.min(
        Math.max(removedIndex, 0),
        conversations.value.length - 1
      )
      await router.replace(
        '/assistant/' + conversations.value[nextIndex].id
      )
    } else {
      /*
       * 删除最后一个会话后自动创建空会话，让工作台仍然保持可用。
       * 如果创建失败，页面会停留在欢迎态并展示后端错误，用户可以再次
       * 点击“新会话”重试。
       */
      await router.replace('/assistant')
      await createConversation(true)
    }
  } catch (error) {
    // 运行中的会话会由后端返回 40901，此处直接展示安全业务提示。
    errorMessage.value = error.message || '删除会话失败'
  } finally {
    deletingId.value = null
  }
}

watch(
  () => route.params.conversationId,
  (conversationId) => {
    if (routeWatchReady) openConversation(conversationId)
  }
)

onMounted(async () => {
  await loadConversations()

  let initialId = route.params.conversationId
  const routeConversationExists = conversations.value.some(
    (conversation) => String(conversation.id) === String(initialId)
  )

  // URL 中的会话不在当前用户列表时，不继续请求历史，统一回到最新会话。
  if (!routeConversationExists) {
    initialId = conversations.value[0]?.id || null

    if (initialId) await router.replace('/assistant/' + initialId)
    else if (route.params.conversationId) await router.replace('/assistant')
  }

  await openConversation(initialId)
  routeWatchReady = true

  // 每秒刷新动作过期状态，不向服务端轮询，也不修改后端事实。
  expiryTimer = window.setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (expiryTimer) window.clearInterval(expiryTimer)
})
</script>

<template>
  <section class="assistant-page">
    <aside class="assistant-sidebar">
      <header class="assistant-sidebar-head">
        <div>
          <p class="eyebrow">SHOPPING AGENT</p>
          <h1>购物助手</h1>
        </div>
        <button
          class="new-conversation-button"
          type="button"
          :disabled="creating || sending"
          aria-label="创建新会话"
          @click="createConversation(false)"
        >
          <span>＋</span>
          <b>{{ creating ? '创建中' : '新会话' }}</b>
        </button>
      </header>

      <p v-if="conversationsLoading" class="assistant-empty">
        会话加载中…
      </p>
      <p v-else-if="!conversations.length" class="assistant-empty">
        还没有购物助手会话<br>点击“新会话”开始
      </p>

      <div
        v-for="conversation in conversations"
        :key="conversation.id"
        class="agent-conversation-item"
        :class="{
          active: String(conversation.id) === String(selectedId)
        }"
      >
        <button
          class="agent-conversation-main"
          type="button"
          @click="selectConversation(conversation)"
        >
          <span class="agent-conversation-icon">AI</span>
          <span class="agent-conversation-copy">
            <strong>{{ conversation.title || '新会话' }}</strong>
            <small>{{ formatTime(conversation.lastMessageAt || conversation.createdAt) }}</small>
          </span>
        </button>
        <button
          class="conversation-delete-button"
          type="button"
          :disabled="Boolean(deletingId) || (sending && String(conversation.id) === String(selectedId))"
          :aria-label="'删除会话：' + (conversation.title || '新会话')"
          title="删除会话"
          @click="askDelete(conversation)"
        >
          ×
        </button>
      </div>
    </aside>

    <main v-if="selectedId" class="assistant-main">
      <header class="assistant-chat-head">
        <div>
          <strong>{{ selectedConversation?.title || '购物助手会话' }}</strong>
          <small :class="{ generating: sending }">
            {{ sending ? '购物助手正在生成回复…' : '历史消息已安全保存' }}
          </small>
        </div>
        <button
          v-if="selectedConversation"
          class="header-delete-button"
          type="button"
          :disabled="sending"
          @click="askDelete(selectedConversation)"
        >
          删除会话
        </button>
      </header>

      <div ref="messagePanel" class="assistant-message-panel">
        <button
          v-if="hasMore"
          class="load-older"
          type="button"
          :disabled="loadingOlder"
          @click="loadOlderMessages"
        >
          {{ loadingOlder ? '加载中…' : '查看更早消息' }}
        </button>

        <p v-if="messagesLoading" class="assistant-empty">
          历史消息加载中…
        </p>
        <div v-else-if="!messages.length" class="assistant-conversation-empty">
          <span>AI</span>
          <h2>这是一个新会话</h2>
          <p>告诉我你想找什么商品，或者查询购物车与订单。</p>
        </div>

        <article
          v-for="message in messages"
          :key="message.id"
          class="assistant-message-row"
          :class="{
            own: message.role === 'USER',
            failed: message.status === 'FAILED',
            streaming: message.role === 'ASSISTANT' && message.status === 'STREAMING'
          }"
        >
          <span class="assistant-message-avatar">
            {{ message.role === 'USER' ? '我' : 'AI' }}
          </span>
          <div class="assistant-message-wrap">
            <div v-if="message.tools?.length" class="agent-tool-list">
              <div
                v-for="tool in message.tools"
                :key="tool.toolCallId"
                class="agent-tool-item"
                :class="tool.status.toLowerCase()"
              >
                <span class="agent-tool-indicator"></span>
                <span>{{ tool.displayText || tool.toolName }}</span>
              </div>
            </div>

            <!-- Vue 文本插值会自动转义 HTML，不能使用 v-html 渲染模型内容。 -->
            <div v-if="message.content" class="assistant-message-bubble">{{ message.content }}</div>
            <div
              v-else-if="message.status === 'STREAMING'"
              class="assistant-typing"
              aria-label="购物助手正在输入"
            >
              <i></i><i></i><i></i>
            </div>

            <section
              v-for="card in message.resultCards || []"
              :key="card.toolCallId"
              class="agent-result-card"
            >
              <header class="agent-result-head">
                <strong>
                  {{ card.cardType === 'PRODUCT_DETAIL' ? '商品详情' :
                    card.cardType === 'PRODUCT_LIST' ? '为你找到的商品' :
                    card.cardType === 'ORDER_DETAIL' ? '订单详情' : '我的订单' }}
                </strong>
                <span v-if="card.hasMore">共 {{ card.total }} 条，仅展示部分</span>
              </header>

              <div v-if="card.cardType.startsWith('PRODUCT_')" class="agent-product-results">
                <article
                  v-for="product in card.products || []"
                  :key="product.productId"
                  class="agent-product-result"
                >
                  <img
                    v-if="product.imageUrl"
                    :src="product.imageUrl"
                    :alt="product.title || '商品图片'"
                  >
                  <span v-else class="agent-result-image-placeholder">优</span>
                  <div class="agent-result-copy">
                    <RouterLink :to="'/product/' + product.productId">
                      {{ product.title || '商品' }}
                    </RouterLink>
                    <p>
                      <b>¥{{ formatCent(product.minPriceCent) }}</b>
                      <span>已售 {{ product.salesCount || 0 }}</span>
                    </p>
                    <ul v-if="product.skus?.length" class="agent-sku-list">
                      <li v-for="sku in product.skus" :key="sku.skuId">
                        <span>{{ sku.specificationText }}</span>
                        <b>¥{{ formatCent(sku.priceCent) }}</b>
                        <em :class="{ empty: sku.availableStock <= 0 }">
                          {{ sku.availableStock > 0 ? `库存 ${sku.availableStock}` : '暂时缺货' }}
                        </em>
                      </li>
                    </ul>
                    <small v-if="product.skusTruncated">规格较多，仅展示部分</small>
                  </div>
                </article>
              </div>

              <div v-else class="agent-order-results">
                <article
                  v-for="order in card.orders || []"
                  :key="order.orderNo"
                  class="agent-order-result"
                >
                  <header>
                    <div>
                      <strong>{{ orderStatusText(order.status) }}</strong>
                      <small>{{ formatTime(order.createdAt) }}</small>
                    </div>
                    <b>¥{{ formatCent(order.payAmountCent) }}</b>
                  </header>
                  <div class="agent-order-items">
                    <div v-for="item in order.items || []" :key="item.skuId">
                      <img v-if="item.imageUrl" :src="item.imageUrl" :alt="item.title || '订单商品'">
                      <span v-else class="agent-order-image-placeholder"></span>
                      <p>
                        <RouterLink :to="'/product/' + item.productId">
                          {{ item.title || '订单商品' }}
                        </RouterLink>
                        <small v-if="item.specificationText">{{ item.specificationText }}</small>
                      </p>
                      <em>×{{ item.quantity }}</em>
                    </div>
                  </div>
                  <p v-if="order.shippingCompany" class="agent-shipping-line">
                    {{ order.shippingCompany }} · {{ order.trackingNo || '暂无运单号' }}
                  </p>
                  <footer>
                    <span>
                      共 {{ order.itemLineCount }} 种商品
                      {{ order.itemsTruncated ? '，仅展示部分' : '' }}
                    </span>
                    <RouterLink :to="`/orders/${order.orderNo}`">查看订单</RouterLink>
                  </footer>
                </article>
              </div>
            </section>

            <section
              v-for="action in message.actions || []"
              :key="action.actionId"
              class="agent-action-card"
              :class="String(action.status).toLowerCase()"
            >
              <div class="agent-action-product">
                <img
                  v-if="action.imageUrl"
                  :src="action.imageUrl"
                  :alt="action.description || '待确认商品'"
                >
                <span v-else class="agent-action-image-placeholder">购</span>
                <div>
                  <small>{{ action.title || '确认加入购物车' }}</small>
                  <RouterLink :to="'/product/' + action.productId">
                    {{ action.description || '商品' }}
                  </RouterLink>
                  <p>{{ action.skuName || '默认规格' }}</p>
                </div>
              </div>

              <dl class="agent-action-summary">
                <div>
                  <dt>单价</dt>
                  <dd>¥{{ formatMoney(action.unitPrice) }}</dd>
                </div>
                <div>
                  <dt>数量</dt>
                  <dd>{{ action.quantity }} 件</dd>
                </div>
                <div>
                  <dt>小计</dt>
                  <dd>¥{{ actionTotal(action) }}</dd>
                </div>
              </dl>

              <p v-if="action.error" class="agent-action-error">
                {{ action.error }}
              </p>

              <footer class="agent-action-footer">
                <span
                  class="agent-action-status"
                  :class="String(action.status).toLowerCase()"
                >
                  {{ actionStatusText(action) }}
                </span>
                <div v-if="action.status === 'PENDING' && !isActionExpired(action)">
                  <button
                    class="action-cancel-button"
                    type="button"
                    :disabled="action.busy"
                    @click="cancelAction(action)"
                  >
                    取消
                  </button>
                  <button
                    class="action-confirm-button"
                    type="button"
                    :disabled="action.busy"
                    @click="confirmAction(action)"
                  >
                    {{ action.busy ? '处理中…' : '确认加购' }}
                  </button>
                </div>
                <RouterLink
                  v-else-if="action.status === 'CONFIRMED'"
                  class="action-cart-link"
                  to="/cart"
                >
                  查看购物车
                </RouterLink>
              </footer>
            </section>

            <small class="assistant-message-meta">
              {{ formatTime(message.completedAt || message.createdAt) }}
              · {{ messageStatusText(message.status) }}
            </small>
          </div>
        </article>
      </div>

      <p v-if="errorMessage" class="assistant-error">{{ errorMessage }}</p>

      <form class="assistant-composer" @submit.prevent="sendMessage">
        <div class="assistant-composer-field">
          <textarea
            v-model="draft"
            rows="1"
            maxlength="1000"
            :disabled="sending"
            placeholder="例如：预算 300 元，推荐一款红轴机械键盘"
            @keydown="handleComposerKeydown"
          ></textarea>
          <small>{{ draft.length }}/1000 · Enter 发送，Shift + Enter 换行</small>
        </div>
        <button type="submit" :disabled="!canSend">
          {{ sending ? '生成中' : '发送' }}
        </button>
      </form>
    </main>

    <div v-else class="assistant-welcome">
      <div>
        <span class="assistant-welcome-mark">AI</span>
        <p class="eyebrow">YOUR SHOPPING COPILOT</p>
        <h1>把挑选商品变得简单一点</h1>
        <p>创建一个会话，购物助手会在这里保存你的咨询历史。</p>
        <button
          class="primary-button"
          type="button"
          :disabled="creating || sending"
          @click="createConversation(false)"
        >
          {{ creating ? '正在创建…' : '创建新会话' }}
        </button>
      </div>
      <p v-if="errorMessage" class="assistant-error welcome-error">
        {{ errorMessage }}
      </p>
    </div>

    <div
      v-if="pendingDelete"
      class="assistant-dialog-backdrop"
      role="presentation"
      @click.self="cancelDelete"
    >
      <section
        class="assistant-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-agent-title"
      >
        <span class="assistant-dialog-icon">!</span>
        <p class="eyebrow">DELETE CONVERSATION</p>
        <h2 id="delete-agent-title">删除这个会话？</h2>
        <p>
          “{{ pendingDelete.title || '新会话' }}”的消息、运行记录、工具审计和待确认动作都会被永久删除。
          已经加入购物车的商品不会被移除。
        </p>
        <div class="assistant-dialog-actions">
          <button
            class="dialog-cancel-button"
            type="button"
            :disabled="Boolean(deletingId)"
            @click="cancelDelete"
          >
            取消
          </button>
          <button
            class="dialog-delete-button"
            type="button"
            :disabled="Boolean(deletingId)"
            @click="confirmDelete"
          >
            {{ deletingId ? '正在删除…' : '确认删除' }}
          </button>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.assistant-page {
  display: grid;
  height: calc(100vh - 72px);
  height: calc(100dvh - 72px);
  min-height: 0;
  grid-template-columns: 320px minmax(0, 1fr);
  overflow: hidden;
  background:
    radial-gradient(circle at 85% 5%, #f8ddd2 0, transparent 28%),
    #f5f2ec;
}

.assistant-sidebar {
  min-height: 0;
  overflow-y: auto;
  border-right: 1px solid #e6e1d8;
  background: #fffdfa;
}

.assistant-sidebar-head {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 112px;
  gap: 12px;
  border-bottom: 1px solid #eee9e1;
  padding: 22px 20px 17px;
  background: #fffdfaeF;
  backdrop-filter: blur(12px);
}

.assistant-sidebar-head .eyebrow {
  margin: 0;
}

.assistant-sidebar-head h1 {
  margin: 3px 0 0;
  font-size: 30px;
}

.new-conversation-button {
  display: inline-flex;
  min-width: 84px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  cursor: pointer;
  border: 0;
  border-radius: 20px;
  padding: 8px 12px;
  background: #242722;
  color: #fff;
}

.new-conversation-button span {
  font-size: 17px;
  line-height: 1;
}

.new-conversation-button b {
  font-size: 12px;
  font-weight: 500;
}

.new-conversation-button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.agent-conversation-item {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 32px;
  align-items: center;
  border-bottom: 1px solid #f1ede7;
  transition: background .2s;
}

.agent-conversation-item:hover {
  background: #faf5ee;
}

.agent-conversation-item.active {
  background: #fff0e9;
}

.agent-conversation-item.active::before {
  position: absolute;
  top: 13px;
  bottom: 13px;
  left: 0;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #ef5332;
  content: '';
}

.agent-conversation-main {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
  cursor: pointer;
  border: 0;
  padding: 15px 6px 15px 17px;
  background: transparent;
  color: inherit;
  text-align: left;
}

.agent-conversation-icon {
  display: grid;
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  place-items: center;
  border-radius: 13px;
  background: #292c27;
  color: #fff;
  font-family: Georgia, serif;
  font-size: 13px;
  letter-spacing: .04em;
}

.agent-conversation-item.active .agent-conversation-icon {
  background: #ef5332;
}

.agent-conversation-copy {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.agent-conversation-copy strong,
.agent-conversation-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-conversation-copy strong {
  font-size: 14px;
  font-weight: 600;
}

.agent-conversation-copy small {
  color: #92938d;
  font-size: 11px;
}

.conversation-delete-button {
  width: 27px;
  height: 27px;
  cursor: pointer;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #aaa59d;
  font-size: 20px;
  line-height: 24px;
  opacity: 0;
  transition: opacity .2s, color .2s, background .2s;
}

.agent-conversation-item:hover .conversation-delete-button,
.agent-conversation-item.active .conversation-delete-button,
.conversation-delete-button:focus-visible {
  opacity: 1;
}

.conversation-delete-button:hover {
  background: #f9ded8;
  color: #bf3d2a;
}

.assistant-main {
  display: grid;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  grid-template-rows: 70px minmax(0, 1fr) auto auto;
  background: #f8f5efb8;
}

.assistant-chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid #e8e3da;
  padding: 0 25px;
  background: #fffdfa;
}

.assistant-chat-head > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.assistant-chat-head strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-chat-head small {
  color: #92938d;
  font-size: 11px;
}

.assistant-chat-head small.generating {
  color: #df5b3f;
}

.header-delete-button {
  flex: none;
  cursor: pointer;
  border: 1px solid #e5dcd4;
  border-radius: 18px;
  padding: 7px 12px;
  background: #fff;
  color: #9e493a;
  font-size: 12px;
}

.header-delete-button:hover {
  border-color: #ef8a75;
  background: #fff4f1;
}

.header-delete-button:disabled {
  cursor: not-allowed;
  opacity: .45;
}

.assistant-message-panel {
  overflow-y: auto;
  padding: 24px clamp(18px, 5vw, 76px);
  scroll-behavior: smooth;
}

.assistant-message-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 18px 0;
}

.assistant-message-row.own {
  flex-direction: row-reverse;
}

.assistant-message-avatar {
  display: grid;
  width: 35px;
  height: 35px;
  flex: 0 0 35px;
  place-items: center;
  border-radius: 12px;
  background: #2a2d28;
  color: #fff;
  font-family: Georgia, serif;
  font-size: 11px;
}

.assistant-message-row.own .assistant-message-avatar {
  border-radius: 50%;
  background: #ef5332;
  font-family: inherit;
}

.assistant-message-wrap {
  display: grid;
  max-width: min(72%, 720px);
  gap: 5px;
}

.assistant-message-row.own .assistant-message-wrap {
  justify-items: end;
}

.assistant-message-bubble {
  border: 1px solid #e4dfd6;
  border-radius: 3px 16px 16px;
  padding: 12px 15px;
  background: #fff;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: 0 5px 18px #796a5810;
}

/*
 * 正文开始出现后，原来的三点等待动画会被文本气泡替换。流式状态光标
 * 继续提示连接尚未结束，并能直观看出 CONTENT_DELTA 正在追加文本。
 */
.assistant-message-row.streaming .assistant-message-bubble::after {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 3px;
  background: #ef5332;
  content: '';
  vertical-align: -2px;
  animation: assistant-stream-caret .8s steps(1) infinite;
}

@keyframes assistant-stream-caret {
  0%, 45% { opacity: 1; }
  46%, 100% { opacity: 0; }
}

.agent-tool-list {
  display: grid;
  gap: 6px;
  margin-bottom: 3px;
}

.agent-tool-item {
  display: flex;
  width: fit-content;
  align-items: center;
  gap: 8px;
  border: 1px solid #e6dfd5;
  border-radius: 14px;
  padding: 5px 10px;
  background: #fffaf4;
  color: #777870;
  font-size: 11px;
}

.agent-tool-indicator {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d6973d;
  box-shadow: 0 0 0 3px #f7e8cf;
}

.agent-tool-item.running .agent-tool-indicator {
  animation: agent-pulse 1s ease-in-out infinite;
}

.agent-tool-item.succeeded .agent-tool-indicator {
  background: #3b9a66;
  box-shadow: 0 0 0 3px #dcefe4;
}

.agent-tool-item.failed {
  border-color: #f0c1b8;
  color: #af4432;
}

.agent-tool-item.failed .agent-tool-indicator {
  background: #cf4933;
  box-shadow: 0 0 0 3px #f7dfda;
}

@keyframes agent-pulse {
  50% { opacity: .35; transform: scale(.8); }
}

.assistant-typing {
  display: inline-flex;
  width: fit-content;
  gap: 5px;
  border: 1px solid #e4dfd6;
  border-radius: 3px 16px 16px;
  padding: 14px 17px;
  background: #fff;
}

.assistant-typing i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a9a59e;
  animation: typing-dot 1.2s infinite ease-in-out;
}

.assistant-typing i:nth-child(2) { animation-delay: .15s; }
.assistant-typing i:nth-child(3) { animation-delay: .3s; }

@keyframes typing-dot {
  0%, 70%, 100% { opacity: .35; transform: translateY(0); }
  35% { opacity: 1; transform: translateY(-3px); }
}

.assistant-message-row.own .assistant-message-bubble {
  border-color: #ef5332;
  border-radius: 16px 3px 16px 16px;
  background: #ef5332;
  color: #fff;
}

.assistant-message-row.failed .assistant-message-bubble {
  border-color: #efb8ae;
  background: #fff2ef;
  color: #a23b2a;
}

.assistant-message-meta {
  color: #aaa69e;
  font-size: 10px;
}

.agent-result-card {
  width: min(650px, 68vw);
  overflow: hidden;
  border: 1px solid #e2ddd4;
  border-radius: 13px;
  margin-top: 6px;
  background: #fffdfa;
  box-shadow: 0 10px 28px #6d5f4b14;
}

.agent-result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid #eee8df;
  padding: 11px 14px;
  background: #faf6ef;
}

.agent-result-head strong { font-size: 13px; }
.agent-result-head span { color: #96938c; font-size: 10px; }

.agent-product-results,
.agent-order-results {
  display: grid;
}

.agent-product-result {
  display: flex;
  min-width: 0;
  gap: 12px;
  padding: 13px 14px;
  border-bottom: 1px solid #f0ebe3;
}

.agent-product-result:last-child,
.agent-order-result:last-child { border-bottom: 0; }

.agent-product-result > img,
.agent-result-image-placeholder {
  width: 66px;
  height: 66px;
  flex: 0 0 66px;
  border-radius: 9px;
  object-fit: cover;
  background: #f0ebe3;
}

.agent-result-image-placeholder {
  display: grid;
  place-items: center;
  color: #dd5b3f;
  font-family: Georgia, serif;
  font-size: 22px;
}

.agent-result-copy {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 5px;
}

.agent-result-copy > a {
  overflow: hidden;
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-result-copy > p {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 0;
}

.agent-result-copy > p b { color: #e65335; font-size: 16px; }
.agent-result-copy > p span,
.agent-result-copy > small { color: #99968f; font-size: 10px; }

.agent-sku-list {
  display: grid;
  gap: 3px;
  margin: 2px 0 0;
  padding: 0;
  list-style: none;
}

.agent-sku-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 9px;
  color: #77776f;
  font-size: 10px;
}

.agent-sku-list li > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agent-sku-list b { color: #4f514c; }
.agent-sku-list em { color: #38815a; font-style: normal; }
.agent-sku-list em.empty { color: #b85a48; }

.agent-order-result {
  border-bottom: 1px solid #eee8df;
  padding: 13px 14px;
}

.agent-order-result > header,
.agent-order-result > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.agent-order-result > header > div { display: grid; gap: 2px; }
.agent-order-result > header strong { font-size: 12px; }
.agent-order-result > header small,
.agent-order-result > footer span { color: #99968f; font-size: 10px; }
.agent-order-result > header > b { color: #e65335; font-size: 16px; }

.agent-order-items {
  display: grid;
  gap: 7px;
  margin: 11px 0;
}

.agent-order-items > div {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
}

.agent-order-items img,
.agent-order-image-placeholder {
  width: 38px;
  height: 38px;
  border-radius: 6px;
  object-fit: cover;
  background: #eee9e1;
}

.agent-order-items p { display: grid; gap: 2px; margin: 0; }
.agent-order-items a { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.agent-order-items small { color: #99968f; font-size: 9px; }
.agent-order-items em { color: #77776f; font-size: 10px; font-style: normal; }

.agent-shipping-line {
  margin: 4px 0 10px;
  color: #77776f;
  font-size: 10px;
}

.agent-order-result > footer {
  border-top: 1px dashed #e4ddd3;
  padding-top: 9px;
}

.agent-order-result > footer a {
  border-radius: 12px;
  padding: 4px 9px;
  background: #fbe9e3;
  color: #be4934;
  font-size: 10px;
}

.agent-action-card {
  width: min(560px, 66vw);
  overflow: hidden;
  border: 1px solid #e2ddd4;
  border-radius: 12px;
  margin-top: 6px;
  background: #fffdfa;
  box-shadow: 0 10px 28px #6d5f4b18;
}

.agent-action-card.confirmed {
  border-color: #b9dcc7;
}

.agent-action-card.cancelled {
  opacity: .75;
}

.agent-action-product {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 15px;
}

.agent-action-product img,
.agent-action-image-placeholder {
  width: 72px;
  height: 72px;
  flex: 0 0 72px;
  border-radius: 9px;
  object-fit: cover;
  background: #f1ece4;
}

.agent-action-image-placeholder {
  display: grid;
  place-items: center;
  color: #d8583d;
  font-family: Georgia, serif;
  font-size: 25px;
}

.agent-action-product > div {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.agent-action-product small {
  color: #d9573b;
  font-size: 10px;
  letter-spacing: .08em;
}

.agent-action-product a {
  overflow: hidden;
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-action-product p {
  overflow: hidden;
  margin: 0;
  color: #898a83;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-action-summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin: 0;
  border-top: 1px solid #eee9e2;
  border-bottom: 1px solid #eee9e2;
  background: #faf7f2;
}

.agent-action-summary > div {
  padding: 10px 13px;
  border-right: 1px solid #eee9e2;
}

.agent-action-summary > div:last-child {
  border-right: 0;
}

.agent-action-summary dt {
  color: #9a9992;
  font-size: 10px;
}

.agent-action-summary dd {
  margin: 4px 0 0;
  color: #343631;
  font-size: 13px;
  font-weight: 700;
}

.agent-action-error {
  margin: 0;
  padding: 8px 14px;
  background: #fff0ed;
  color: #b43c2a;
  font-size: 11px;
}

.agent-action-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 0;
  padding: 12px 14px;
  background: #fff;
  color: inherit;
  font-size: inherit;
  text-align: left;
}

.agent-action-footer > div {
  display: flex;
  gap: 8px;
}

.agent-action-footer button,
.action-cart-link {
  cursor: pointer;
  border-radius: 5px;
  padding: 7px 12px;
  font-size: 12px;
}

.agent-action-footer button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.action-cancel-button {
  border: 1px solid #ddd7ce;
  background: #fff;
  color: #64665f;
}

.action-confirm-button {
  border: 1px solid #ef5332;
  background: #ef5332;
  color: #fff;
}

.action-cart-link {
  background: #e4f3e9;
  color: #287b50;
}

.agent-action-status {
  color: #aa715f;
  font-size: 11px;
}

.agent-action-status.confirmed {
  color: #318459;
}

.agent-action-status.cancelled {
  color: #92938d;
}

.assistant-conversation-empty,
.assistant-welcome {
  display: grid;
  place-items: center;
  color: #7f8179;
  text-align: center;
}

.assistant-conversation-empty {
  min-height: 65%;
}

.assistant-conversation-empty span,
.assistant-welcome-mark {
  display: inline-grid;
  width: 70px;
  height: 70px;
  place-items: center;
  border-radius: 22px;
  background: #ef5332;
  color: #fff;
  font-family: Georgia, serif;
  font-size: 22px;
  transform: rotate(-4deg);
}

.assistant-conversation-empty h2 {
  margin: 19px 0 3px;
  color: #383a35;
  font-size: 27px;
}

.assistant-conversation-empty p {
  margin: 4px 0;
  font-size: 13px;
}

.assistant-welcome {
  position: relative;
  padding: 36px;
}

.assistant-welcome > div {
  max-width: 620px;
}

.assistant-welcome .eyebrow {
  margin: 22px 0 8px;
}

.assistant-welcome h1 {
  margin: 0 0 13px;
  color: #30322e;
  font-size: clamp(34px, 5vw, 58px);
  line-height: 1.1;
}

.assistant-welcome p:not(.eyebrow) {
  margin: 0 0 25px;
  line-height: 1.8;
}

.assistant-empty {
  padding: 34px 20px;
  color: #999991;
  font-size: 13px;
  line-height: 1.8;
  text-align: center;
}

.assistant-error {
  margin: 0;
  padding: 8px 22px;
  background: #fff0ed;
  color: #b93b29;
  font-size: 12px;
}

.welcome-error {
  position: absolute;
  right: 24px;
  bottom: 24px;
  left: 24px;
  border-radius: 4px;
}

.assistant-composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 76px;
  align-items: end;
  gap: 10px;
  border-top: 1px solid #e4ded5;
  padding: 13px 22px 17px;
  background: #fffdfa;
}

.assistant-composer-field {
  position: relative;
}

.assistant-composer textarea {
  display: block;
  width: 100%;
  min-width: 0;
  min-height: 48px;
  max-height: 120px;
  resize: vertical;
  border: 1px solid #dfdad2;
  border-radius: 8px;
  padding: 11px 13px 20px;
  background: #fff;
  color: #343631;
  font: inherit;
  line-height: 1.5;
}

.assistant-composer textarea:focus {
  border-color: #ef7a60;
  outline: none;
}

.assistant-composer textarea:disabled {
  background: #f3f0eb;
}

.assistant-composer-field small {
  position: absolute;
  right: 10px;
  bottom: 5px;
  color: #aaa69e;
  font-size: 9px;
}

.assistant-composer > button {
  height: 48px;
  cursor: pointer;
  border: 0;
  border-radius: 8px;
  background: #ef5332;
  color: #fff;
}

.assistant-composer > button:disabled {
  cursor: not-allowed;
  background: #c8c3ba;
}

.assistant-dialog-backdrop {
  position: fixed;
  z-index: 30;
  inset: 0;
  display: grid;
  padding: 24px;
  place-items: center;
  background: #211e1980;
  backdrop-filter: blur(3px);
}

.assistant-dialog {
  width: min(460px, 94vw);
  border-radius: 14px;
  padding: 29px;
  background: #fffdfa;
  box-shadow: 0 28px 80px #231a1240;
}

.assistant-dialog-icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 50%;
  background: #fee6e0;
  color: #c83d28;
  font-family: Georgia, serif;
  font-size: 23px;
}

.assistant-dialog .eyebrow {
  margin: 17px 0 4px;
  color: #b05747;
}

.assistant-dialog h2 {
  margin: 0 0 12px;
  font-size: 28px;
}

.assistant-dialog > p:not(.eyebrow) {
  color: #6f716a;
  font-size: 14px;
  line-height: 1.8;
}

.assistant-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 24px;
}

.assistant-dialog-actions button {
  cursor: pointer;
  border-radius: 5px;
  padding: 10px 17px;
  font: inherit;
}

.assistant-dialog-actions button:disabled {
  cursor: not-allowed;
  opacity: .6;
}

.dialog-cancel-button {
  border: 1px solid #ded8cf;
  background: #fff;
  color: #50524d;
}

.dialog-delete-button {
  border: 1px solid #c8432e;
  background: #c8432e;
  color: #fff;
}

@media (max-width: 760px) {
  .assistant-page {
    grid-template-columns: 92px minmax(0, 1fr);
  }

  .assistant-sidebar-head {
    min-height: 72px;
    justify-content: center;
    padding: 11px 8px;
  }

  .assistant-sidebar-head > div,
  .new-conversation-button b,
  .agent-conversation-copy {
    display: none;
  }

  .new-conversation-button {
    min-width: 42px;
    width: 42px;
    height: 42px;
    border-radius: 50%;
    padding: 0;
  }

  .agent-conversation-item {
    grid-template-columns: 1fr;
  }

  .agent-conversation-main {
    justify-content: center;
    padding: 13px 7px;
  }

  .conversation-delete-button {
    position: absolute;
    top: 5px;
    right: 5px;
    background: #fff;
    font-size: 17px;
    opacity: 1;
  }

  .assistant-chat-head {
    padding: 0 13px;
  }

  .header-delete-button {
    overflow: hidden;
    width: 31px;
    height: 31px;
    padding: 0;
    color: transparent;
  }

  .header-delete-button::after {
    color: #a94838;
    content: '×';
    font-size: 19px;
  }

  .assistant-message-panel {
    padding: 17px 11px;
  }

  .assistant-message-wrap {
    max-width: 84%;
  }

  .assistant-message-avatar {
    display: none;
  }

  .assistant-composer {
    grid-template-columns: minmax(0, 1fr) 62px;
    padding: 10px;
  }

  .assistant-composer-field small {
    display: none;
  }

  .assistant-composer textarea {
    padding-bottom: 11px;
  }

  .agent-action-card {
    width: 76vw;
  }

  .agent-result-card {
    width: 76vw;
  }

  .agent-action-product img,
  .agent-action-image-placeholder {
    width: 58px;
    height: 58px;
    flex-basis: 58px;
  }

  .assistant-welcome {
    padding: 24px 16px;
  }

  .assistant-dialog {
    padding: 23px;
  }
}
</style>
