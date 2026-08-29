<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { createBuyerChatRealtime } from '../chat-realtime'
import { money } from '../mock'

const route = useRoute()
const router = useRouter()

const conversations = ref([])
const messages = ref([])
const selectedId = ref(null)
const conversationsLoading = ref(true)
const messagesLoading = ref(false)
const loadingOlder = ref(false)
const hasMore = ref(false)
const oldestMessageId = ref(null)
const draft = ref('')
const sending = ref(false)
const uploading = ref(false)
const errorMessage = ref('')
const realtimeStatus = ref('disconnected')
const peerLastReadMessageId = ref(null)
const messagePanel = ref(null)
const fileInput = ref(null)
const showOrderPicker = ref(false)
const orders = ref([])
const ordersLoading = ref(false)
const seenEventIds = new Set()
let routeWatchReady = false

const selectedConversation = computed(() => conversations.value.find(
  (item) => String(item.id) === String(selectedId.value)
))

const availableOrders = computed(() => {
  const merchantId = selectedConversation.value?.peer?.merchantId
  return orders.value.filter((order) => String(order.merchantId) === String(merchantId))
})

const realtimeText = computed(() => ({
  connected: '实时连接正常',
  connecting: '正在连接…',
  disconnected: '实时连接已断开，正在重连',
  error: '实时连接异常，消息仍可发送'
}[realtimeStatus.value] || ''))

const statusText = {
  PENDING_PAYMENT: '待付款',
  PAID: '已支付',
  WAITING_SHIPMENT: '待发货',
  WAITING_RECEIPT: '待收货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUNDING: '退款中',
  REFUNDED: '已退款'
}

function newClientMessageId() {
  return crypto.randomUUID()
}

function compareIds(left, right) {
  try {
    const a = BigInt(String(left))
    const b = BigInt(String(right))
    return a < b ? -1 : a > b ? 1 : 0
  } catch (_) {
    return String(left).localeCompare(String(right))
  }
}

function mergeMessages(incoming) {
  const byId = new Map(messages.value.map((message) => [String(message.id), message]))
  for (const message of incoming || []) byId.set(String(message.id), message)
  messages.value = [...byId.values()].sort((a, b) => compareIds(a.id, b.id))
}

function upsertConversation(conversation) {
  if (!conversation) return
  const index = conversations.value.findIndex((item) => String(item.id) === String(conversation.id))
  if (index >= 0) conversations.value.splice(index, 1, conversation)
  else conversations.value.push(conversation)
  conversations.value.sort((a, b) => compareIds(b.lastMessage?.id || 0, a.lastMessage?.id || 0))
}

function formatTime(value) {
  return value ? value.replace('T', ' ').slice(5, 16) : ''
}

function conversationPreview(conversation) {
  const last = conversation.lastMessage
  if (!last) return '还没有消息，打个招呼吧'
  if (last.type === 'IMAGE') return '[图片]'
  if (last.type === 'ORDER') return `[订单] ${last.orderCard?.orderNo || ''}`
  return last.content || '新消息'
}

function isOwn(message) {
  return message.sender?.role === 'USER'
}

function isOwnMessageRead(message) {
  return isOwn(message)
    && peerLastReadMessageId.value != null
    && compareIds(message.id, peerLastReadMessageId.value) <= 0
}

async function scrollToBottom() {
  await nextTick()
  if (messagePanel.value) messagePanel.value.scrollTop = messagePanel.value.scrollHeight
}

async function loadConversations() {
  conversationsLoading.value = true
  try {
    const result = await api.chatConversations(1, 50)
    conversations.value = result.records || []
  } catch (error) {
    errorMessage.value = error.message || '会话列表加载失败'
  } finally {
    conversationsLoading.value = false
  }
}

async function openConversation(conversationId) {
  if (!conversationId) {
    selectedId.value = null
    messages.value = []
    return
  }

  selectedId.value = String(conversationId)
  messagesLoading.value = true
  errorMessage.value = ''
  peerLastReadMessageId.value = null

  try {
    const result = await api.chatMessages(conversationId, { limit: 30 })
    messages.value = result.items || []
    hasMore.value = Boolean(result.hasMore)
    oldestMessageId.value = result.oldestMessageId
    await scrollToBottom()
    await markLatestRead()
  } catch (error) {
    messages.value = []
    errorMessage.value = error.message || '聊天记录加载失败'
  } finally {
    messagesLoading.value = false
  }
}

async function selectConversation(conversation) {
  if (String(selectedId.value) === String(conversation.id)) return
  await router.push(`/messages/${conversation.id}`)
}

async function loadOlderMessages() {
  if (!selectedId.value || !hasMore.value || loadingOlder.value || !oldestMessageId.value) return
  loadingOlder.value = true
  const oldHeight = messagePanel.value?.scrollHeight || 0
  try {
    const result = await api.chatMessages(selectedId.value, {
      beforeMessageId: oldestMessageId.value,
      limit: 30
    })
    mergeMessages(result.items)
    hasMore.value = Boolean(result.hasMore)
    oldestMessageId.value = result.oldestMessageId || oldestMessageId.value
    await nextTick()
    if (messagePanel.value) messagePanel.value.scrollTop += messagePanel.value.scrollHeight - oldHeight
  } catch (error) {
    errorMessage.value = error.message || '更早消息加载失败'
  } finally {
    loadingOlder.value = false
  }
}

async function markLatestRead() {
  const latest = messages.value.at(-1)
  if (!selectedId.value || !latest) return
  try {
    await api.markChatRead(selectedId.value, latest.id)
    const conversation = selectedConversation.value
    if (conversation) {
      conversation.unreadCount = 0
      conversation.lastReadMessageId = latest.id
    }
  } catch (_) {
    // 已读上报失败不阻断查看消息，下次打开会话会再次推进。
  }
}

async function sendText() {
  const content = draft.value.trim()
  if (!content || !selectedId.value || sending.value) return
  sending.value = true
  errorMessage.value = ''
  const clientMessageId = newClientMessageId()
  try {
    const message = await api.sendChatMessage(selectedId.value, {
      clientMessageId,
      type: 'TEXT',
      content
    })
    mergeMessages([message])
    draft.value = ''
    await scrollToBottom()
  } catch (error) {
    errorMessage.value = error.message || '消息发送失败'
  } finally {
    sending.value = false
  }
}

function handleComposerKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendText()
  }
}

function chooseImage() {
  fileInput.value?.click()
}

async function uploadImage(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || !selectedId.value) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    errorMessage.value = '只支持 JPEG、PNG 或 WebP 图片'
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    errorMessage.value = '图片不能超过 5MB'
    return
  }

  uploading.value = true
  errorMessage.value = ''
  try {
    const message = await api.sendChatImage(selectedId.value, newClientMessageId(), file)
    mergeMessages([message])
    await scrollToBottom()
  } catch (error) {
    errorMessage.value = error.message || '图片发送失败'
  } finally {
    uploading.value = false
  }
}

async function openOrderPicker() {
  showOrderPicker.value = true
  if (orders.value.length) return
  ordersLoading.value = true
  try {
    const result = await api.orders(1, 50)
    orders.value = result.records || []
  } catch (error) {
    errorMessage.value = error.message || '订单加载失败'
  } finally {
    ordersLoading.value = false
  }
}

async function sendOrder(orderNo) {
  if (!selectedId.value || sending.value) return
  sending.value = true
  errorMessage.value = ''
  try {
    const message = await api.sendChatMessage(selectedId.value, {
      clientMessageId: newClientMessageId(),
      type: 'ORDER',
      orderNo
    })
    mergeMessages([message])
    showOrderPicker.value = false
    await scrollToBottom()
  } catch (error) {
    errorMessage.value = error.message || '订单卡片发送失败'
  } finally {
    sending.value = false
  }
}

async function handleRealtimeEvent(event) {
  if (!event?.eventId || seenEventIds.has(event.eventId)) return
  seenEventIds.add(event.eventId)
  if (seenEventIds.size > 500) seenEventIds.delete(seenEventIds.values().next().value)

  if (event.eventType === 'MESSAGE_CREATED' && event.message) {
    if (String(event.conversationId) === String(selectedId.value)) {
      mergeMessages([event.message])
      await scrollToBottom()
      if (event.message.sender?.role === 'MERCHANT') await markLatestRead()
    }
  } else if (event.eventType === 'CONVERSATION_UPDATED') {
    upsertConversation(event.conversation)
  } else if (event.eventType === 'MESSAGE_READ' && event.readReceipt?.readerRole === 'MERCHANT') {
    if (String(event.conversationId) === String(selectedId.value)) {
      peerLastReadMessageId.value = event.readReceipt.lastReadMessageId
    }
  }
}

const realtime = createBuyerChatRealtime({
  onEvent: handleRealtimeEvent,
  onStatus: (status) => { realtimeStatus.value = status }
})

watch(() => route.params.conversationId, (conversationId) => {
  // 首次进入页面时由 onMounted 统一加载，避免路由替换后重复请求聊天记录。
  // 后续点击其他会话、浏览器前进或后退时，再根据路由中的会话 ID 切换。
  if (routeWatchReady) openConversation(conversationId)
})

onMounted(async () => {
  await loadConversations()

  // 顶部“消息”菜单进入的是 /messages，没有携带会话 ID。
  // 原来的页面只有 selectedId 存在时才渲染输入框，因此即使用户已经有会话，
  // 也必须再手动点击左侧联系人才能看见输入框。这里自动打开第一条（最新）会话。
  let initialConversationId = route.params.conversationId
  if (!initialConversationId && conversations.value.length > 0) {
    initialConversationId = conversations.value[0].id
    await router.replace(`/messages/${initialConversationId}`)
  }

  await openConversation(initialConversationId)
  routeWatchReady = true
  realtime.connect()
})

onUnmounted(() => realtime.disconnect())
</script>

<template>
  <section class="chat-page">
    <aside class="chat-sidebar">
      <header class="chat-sidebar-head">
        <div>
          <p class="eyebrow">MESSAGES</p>
          <h1>消息</h1>
        </div>
        <span class="realtime-dot" :class="realtimeStatus" :title="realtimeText"></span>
      </header>

      <p v-if="conversationsLoading" class="chat-empty">会话加载中…</p>
      <p v-else-if="!conversations.length" class="chat-empty">还没有会话<br>可以从商品、商家或订单页联系商家</p>

      <button
        v-for="conversation in conversations"
        :key="conversation.id"
        class="conversation-item"
        :class="{ active: String(conversation.id) === String(selectedId) }"
        type="button"
        @click="selectConversation(conversation)"
      >
        <img v-if="conversation.peer?.avatarUrl" :src="conversation.peer.avatarUrl" alt="">
        <span v-else class="conversation-avatar">{{ conversation.peer?.displayName?.slice(0, 1) || '商' }}</span>
        <span class="conversation-content">
          <strong>{{ conversation.peer?.displayName || '商家' }}</strong>
          <small>{{ conversationPreview(conversation) }}</small>
        </span>
        <span v-if="conversation.unreadCount" class="unread-badge">{{ conversation.unreadCount > 99 ? '99+' : conversation.unreadCount }}</span>
      </button>
    </aside>

    <main v-if="selectedId" class="chat-main">
      <header class="chat-peer-head">
        <div>
          <strong>{{ selectedConversation?.peer?.displayName || '聊天会话' }}</strong>
          <small :class="realtimeStatus">{{ realtimeText }}</small>
        </div>
        <RouterLink
          v-if="selectedConversation?.peer?.merchantId"
          class="text-button"
          :to="`/merchant/${selectedConversation.peer.merchantId}`"
        >查看店铺</RouterLink>
      </header>

      <div ref="messagePanel" class="message-panel">
        <button v-if="hasMore" class="load-older" type="button" :disabled="loadingOlder" @click="loadOlderMessages">
          {{ loadingOlder ? '加载中…' : '查看更早消息' }}
        </button>
        <p v-if="messagesLoading" class="chat-empty">聊天记录加载中…</p>
        <p v-else-if="!messages.length" class="chat-empty">这是你们的第一次对话</p>

        <article
          v-for="message in messages"
          :key="message.id"
          class="message-row"
          :class="{ own: isOwn(message) }"
        >
          <img v-if="message.sender?.avatarUrl" class="message-avatar" :src="message.sender.avatarUrl" alt="">
          <span v-else class="message-avatar avatar-text">{{ message.sender?.displayName?.slice(0, 1) || '?' }}</span>
          <div class="message-wrap">
            <div v-if="message.type === 'TEXT'" class="message-bubble">{{ message.content }}</div>
            <a v-else-if="message.type === 'IMAGE'" class="message-image" :href="message.image?.url" target="_blank" rel="noreferrer">
              <img :src="message.image?.url" alt="聊天图片">
            </a>
            <RouterLink v-else-if="message.type === 'ORDER'" class="chat-order-card" :to="`/orders/${message.orderCard?.orderNo}`">
              <img v-if="message.orderCard?.productImage" :src="message.orderCard.productImage" alt="">
              <span class="chat-order-info">
                <small>订单卡片 · {{ statusText[message.orderCard?.status] || message.orderCard?.status }}</small>
                <strong>{{ message.orderCard?.productTitle }}</strong>
                <span>{{ message.orderCard?.productTypeCount }} 种商品 · {{ money(message.orderCard?.payAmountCent) }}</span>
                <em>订单号 {{ message.orderCard?.orderNo }}</em>
              </span>
            </RouterLink>
            <small class="message-meta">{{ formatTime(message.createdAt) }}<template v-if="isOwnMessageRead(message)"> · 已读</template></small>
          </div>
        </article>
      </div>

      <p v-if="errorMessage" class="chat-error">{{ errorMessage }}</p>
      <footer class="chat-composer">
        <div class="composer-tools">
          <button type="button" :disabled="uploading" @click="chooseImage">{{ uploading ? '上传中…' : '图片' }}</button>
          <button type="button" @click="openOrderPicker">订单</button>
          <input ref="fileInput" hidden type="file" accept="image/jpeg,image/png,image/webp" @change="uploadImage">
        </div>
        <textarea v-model="draft" maxlength="2000" rows="2" placeholder="输入消息，Enter 发送，Shift + Enter 换行" @keydown="handleComposerKeydown"></textarea>
        <button class="send-button" type="button" :disabled="sending || !draft.trim()" @click="sendText">{{ sending ? '发送中' : '发送' }}</button>
      </footer>
    </main>

    <main v-else class="chat-welcome">
      <div><span>聊</span><h2>选择一个会话</h2><p>消息会可靠保存，断线后重新打开即可补齐。</p></div>
    </main>

    <div v-if="showOrderPicker" class="order-picker-backdrop" @click.self="showOrderPicker = false">
      <section class="order-picker">
        <header><div><p class="eyebrow">SHARE ORDER</p><h2>发送订单卡片</h2></div><button type="button" @click="showOrderPicker = false">×</button></header>
        <p v-if="ordersLoading" class="muted">订单加载中…</p>
        <p v-else-if="!availableOrders.length" class="muted">没有与当前商家的订单。</p>
        <button v-for="order in availableOrders" :key="order.orderNo" class="order-picker-item" type="button" :disabled="sending" @click="sendOrder(order.orderNo)">
          <span><strong>{{ order.orderNo }}</strong><small>{{ statusText[order.status] || order.status }}</small></span>
          <strong>{{ money(order.payAmountCent) }}</strong>
        </button>
      </section>
    </div>
  </section>
</template>
