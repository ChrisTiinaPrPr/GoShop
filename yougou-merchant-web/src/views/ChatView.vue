<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { merchantApi } from '../api'
import { createMerchantChatRealtime } from '../chat-realtime'

const route = useRoute()
const router = useRouter()
const conversations = ref([])
const messages = ref([])
const selectedId = ref(null)
const listLoading = ref(true)
const messagesLoading = ref(false)
const loadingOlder = ref(false)
const hasMore = ref(false)
const oldestMessageId = ref(null)
const draft = ref('')
const sending = ref(false)
const uploading = ref(false)
const realtimeStatus = ref('disconnected')
const peerLastReadMessageId = ref(null)
const messagePanel = ref(null)
const fileInput = ref(null)
const orderDialog = ref(false)
const orderNo = ref('')
const seenEventIds = new Set()
let routeWatchReady = false

const selectedConversation = computed(() => conversations.value.find(
  (item) => String(item.id) === String(selectedId.value)
))

const realtimeLabel = computed(() => ({
  connected: '实时在线',
  connecting: '连接中',
  disconnected: '正在重连',
  error: '连接异常'
}[realtimeStatus.value] || '离线'))

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

const money = (cent) => `¥${(Number(cent || 0) / 100).toFixed(2)}`

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

function preview(conversation) {
  const last = conversation.lastMessage
  if (!last) return '暂无消息'
  if (last.type === 'IMAGE') return '[图片]'
  if (last.type === 'ORDER') return `[订单] ${last.orderCard?.orderNo || ''}`
  return last.content || '新消息'
}

function formatTime(value) {
  return value ? value.replace('T', ' ').slice(5, 16) : ''
}

function isOwn(message) {
  return message.sender?.role === 'MERCHANT'
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
  listLoading.value = true
  try {
    const result = await merchantApi.chatConversations(1, 50)
    conversations.value = result.records || []
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    listLoading.value = false
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
  peerLastReadMessageId.value = null
  try {
    const result = await merchantApi.chatMessages(conversationId, { limit: 30 })
    messages.value = result.items || []
    hasMore.value = Boolean(result.hasMore)
    oldestMessageId.value = result.oldestMessageId
    await scrollToBottom()
    await markLatestRead()
  } catch (error) {
    messages.value = []
    ElMessage.error(error.message)
  } finally {
    messagesLoading.value = false
  }
}

function selectConversation(conversation) {
  if (String(selectedId.value) !== String(conversation.id)) {
    router.push(`/messages/${conversation.id}`)
  }
}

async function loadOlderMessages() {
  if (!hasMore.value || !oldestMessageId.value || loadingOlder.value) return
  loadingOlder.value = true
  const previousHeight = messagePanel.value?.scrollHeight || 0
  try {
    const result = await merchantApi.chatMessages(selectedId.value, {
      beforeMessageId: oldestMessageId.value,
      limit: 30
    })
    mergeMessages(result.items)
    hasMore.value = Boolean(result.hasMore)
    oldestMessageId.value = result.oldestMessageId || oldestMessageId.value
    await nextTick()
    if (messagePanel.value) messagePanel.value.scrollTop += messagePanel.value.scrollHeight - previousHeight
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loadingOlder.value = false
  }
}

async function markLatestRead() {
  const latest = messages.value.at(-1)
  if (!selectedId.value || !latest) return
  try {
    await merchantApi.markChatRead(selectedId.value, latest.id)
    const conversation = selectedConversation.value
    if (conversation) {
      conversation.unreadCount = 0
      conversation.lastReadMessageId = latest.id
    }
  } catch (_) {
    // 不影响客服继续查看和回复消息。
  }
}

async function sendText() {
  const content = draft.value.trim()
  if (!content || !selectedId.value || sending.value) return
  sending.value = true
  try {
    const message = await merchantApi.sendChatMessage(selectedId.value, {
      clientMessageId: crypto.randomUUID(),
      type: 'TEXT',
      content
    })
    mergeMessages([message])
    draft.value = ''
    await scrollToBottom()
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    sending.value = false
  }
}

function composerKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendText()
  }
}

async function uploadImage(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || !selectedId.value) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    ElMessage.warning('只支持 JPEG、PNG 或 WebP 图片')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 5MB')
    return
  }
  uploading.value = true
  try {
    const message = await merchantApi.sendChatImage(selectedId.value, crypto.randomUUID(), file)
    mergeMessages([message])
    await scrollToBottom()
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    uploading.value = false
  }
}

async function sendOrder() {
  const normalizedOrderNo = orderNo.value.trim()
  if (!normalizedOrderNo || !selectedId.value || sending.value) return
  sending.value = true
  try {
    const message = await merchantApi.sendChatMessage(selectedId.value, {
      clientMessageId: crypto.randomUUID(),
      type: 'ORDER',
      orderNo: normalizedOrderNo
    })
    mergeMessages([message])
    orderNo.value = ''
    orderDialog.value = false
    await scrollToBottom()
  } catch (error) {
    ElMessage.error(error.message)
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
      if (event.message.sender?.role === 'USER') await markLatestRead()
    }
  } else if (event.eventType === 'CONVERSATION_UPDATED') {
    upsertConversation(event.conversation)
  } else if (event.eventType === 'MESSAGE_READ' && event.readReceipt?.readerRole === 'USER') {
    if (String(event.conversationId) === String(selectedId.value)) {
      peerLastReadMessageId.value = event.readReceipt.lastReadMessageId
    }
  }
}

const realtime = createMerchantChatRealtime({
  onEvent: handleRealtimeEvent,
  onStatus: (status) => { realtimeStatus.value = status }
})

watch(() => route.params.conversationId, (conversationId) => {
  // 首次进入页面由 onMounted 统一处理，后续再响应会话切换和浏览器前进、后退。
  if (routeWatchReady) openConversation(conversationId)
})

onMounted(async () => {
  await loadConversations()

  // 从侧边栏进入的是 /messages，不带会话 ID。
  // 自动打开最新会话，否则 selectedId 为空时整个消息输入区都不会渲染。
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
  <el-card class="merchant-chat-card" shadow="never">
    <div class="merchant-chat-layout">
      <aside class="merchant-conversations">
        <header>
          <div><strong>客户会话</strong><small>共 {{ conversations.length }} 个</small></div>
          <el-tag :type="realtimeStatus === 'connected' ? 'success' : 'warning'" size="small">{{ realtimeLabel }}</el-tag>
        </header>
        <div v-loading="listLoading" class="merchant-conversation-list">
          <el-empty v-if="!listLoading && !conversations.length" description="暂无客户会话" :image-size="72" />
          <button
            v-for="conversation in conversations"
            :key="conversation.id"
            type="button"
            :class="{ active: String(conversation.id) === String(selectedId) }"
            @click="selectConversation(conversation)"
          >
            <el-avatar :src="conversation.peer?.avatarUrl" :size="42">{{ conversation.peer?.displayName?.slice(0, 1) }}</el-avatar>
            <span class="merchant-conversation-copy">
              <strong>{{ conversation.peer?.displayName || '买家' }}</strong>
              <small>{{ preview(conversation) }}</small>
            </span>
            <el-badge v-if="conversation.unreadCount" :value="conversation.unreadCount" :max="99" />
          </button>
        </div>
      </aside>

      <section v-if="selectedId" class="merchant-chat-main">
        <header class="merchant-chat-head">
          <div>
            <strong>{{ selectedConversation?.peer?.displayName || '客户会话' }}</strong>
            <small>消息 ID 为可靠游标，断线后会自动从历史记录补齐</small>
          </div>
        </header>

        <div ref="messagePanel" v-loading="messagesLoading" class="merchant-message-panel">
          <el-button v-if="hasMore" link type="primary" :loading="loadingOlder" @click="loadOlderMessages">加载更早消息</el-button>
          <el-empty v-if="!messagesLoading && !messages.length" description="暂无消息，回复客户开始沟通" :image-size="80" />

          <article v-for="message in messages" :key="message.id" class="merchant-message-row" :class="{ own: isOwn(message) }">
            <el-avatar :src="message.sender?.avatarUrl" :size="32">{{ message.sender?.displayName?.slice(0, 1) }}</el-avatar>
            <div class="merchant-message-wrap">
              <div v-if="message.type === 'TEXT'" class="merchant-message-bubble">{{ message.content }}</div>
              <a v-else-if="message.type === 'IMAGE'" :href="message.image?.url" target="_blank" rel="noreferrer" class="merchant-message-image">
                <img :src="message.image?.url" alt="聊天图片">
              </a>
              <button v-else-if="message.type === 'ORDER'" type="button" class="merchant-order-message" @click="router.push(`/orders/${message.orderCard?.orderNo}`)">
                <img v-if="message.orderCard?.productImage" :src="message.orderCard.productImage" alt="">
                <span>
                  <small>订单 · {{ statusText[message.orderCard?.status] || message.orderCard?.status }}</small>
                  <strong>{{ message.orderCard?.productTitle }}</strong>
                  <em>{{ message.orderCard?.productTypeCount }} 种商品 · {{ money(message.orderCard?.payAmountCent) }}</em>
                  <i>{{ message.orderCard?.orderNo }}</i>
                </span>
              </button>
              <small class="merchant-message-meta">{{ formatTime(message.createdAt) }}<template v-if="isOwnMessageRead(message)"> · 客户已读</template></small>
            </div>
          </article>
        </div>

        <footer class="merchant-composer">
          <div class="merchant-composer-tools">
            <el-button size="small" :loading="uploading" @click="fileInput?.click()">发送图片</el-button>
            <el-button size="small" @click="orderDialog = true">发送订单</el-button>
            <input ref="fileInput" hidden type="file" accept="image/jpeg,image/png,image/webp" @change="uploadImage">
          </div>
          <el-input v-model="draft" type="textarea" :rows="3" maxlength="2000" show-word-limit resize="none" placeholder="输入回复，Enter 发送，Shift + Enter 换行" @keydown="composerKeydown" />
          <el-button type="primary" :loading="sending" :disabled="!draft.trim()" @click="sendText">发送</el-button>
        </footer>
      </section>

      <section v-else class="merchant-chat-placeholder">
        <el-empty description="从左侧选择一个客户会话" />
      </section>
    </div>
  </el-card>

  <el-dialog v-model="orderDialog" title="发送订单卡片" width="460px">
    <el-alert type="info" :closable="false" show-icon>请输入属于当前客户和本店铺的订单号，后端会再次校验归属。</el-alert>
    <el-input v-model="orderNo" maxlength="32" placeholder="请输入订单号" style="margin-top: 16px" @keyup.enter="sendOrder" />
    <template #footer>
      <el-button @click="orderDialog = false">取消</el-button>
      <el-button type="primary" :loading="sending" :disabled="!orderNo.trim()" @click="sendOrder">发送订单</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.merchant-chat-card { height: calc(100vh - 104px); height: calc(100dvh - 104px); min-height: 0; }
.merchant-chat-card :deep(.el-card__body) { height: 100%; padding: 0; }
.merchant-chat-layout { display: grid; height: 100%; min-height: 0; grid-template-columns: 310px minmax(0, 1fr); overflow: hidden; }
.merchant-conversations { border-right: 1px solid #e8ebf0; background: #fbfcfd; }
.merchant-conversations > header { display: flex; height: 68px; align-items: center; justify-content: space-between; border-bottom: 1px solid #e8ebf0; padding: 0 16px; }
.merchant-conversations > header > div { display: grid; gap: 4px; }
.merchant-conversations > header small { color: #949cab; }
.merchant-conversation-list { height: calc(100% - 68px); overflow-y: auto; }
.merchant-conversation-list > button { display: flex; width: 100%; align-items: center; gap: 10px; cursor: pointer; border: 0; border-bottom: 1px solid #edf0f4; padding: 14px; background: transparent; color: inherit; text-align: left; }
.merchant-conversation-list > button:hover { background: #f2f6fb; }
.merchant-conversation-list > button.active { background: #eaf2ff; box-shadow: inset 3px 0 #409eff; }
.merchant-conversation-copy { display: grid; min-width: 0; flex: 1; gap: 5px; }
.merchant-conversation-copy strong, .merchant-conversation-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.merchant-conversation-copy small { color: #939baa; }
.merchant-chat-main { display: grid; min-width: 0; min-height: 0; overflow: hidden; grid-template-rows: 68px minmax(0, 1fr) auto; background: #f6f8fb; }
.merchant-chat-head { display: flex; align-items: center; border-bottom: 1px solid #e5e9ef; padding: 0 20px; background: #fff; }
.merchant-chat-head > div { display: grid; gap: 5px; }
.merchant-chat-head small { color: #99a0ac; }
.merchant-message-panel { overflow-y: auto; padding: 20px 28px; }
.merchant-message-panel > .el-button { display: block; margin: 0 auto 16px; }
.merchant-message-row { display: flex; align-items: flex-start; gap: 9px; margin: 15px 0; }
.merchant-message-row.own { flex-direction: row-reverse; }
.merchant-message-wrap { display: grid; max-width: min(68%, 620px); gap: 4px; }
.merchant-message-row.own .merchant-message-wrap { justify-items: end; }
.merchant-message-bubble { border: 1px solid #dfe4eb; border-radius: 3px 12px 12px; padding: 10px 13px; background: #fff; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.merchant-message-row.own .merchant-message-bubble { border-color: #409eff; border-radius: 12px 3px 12px 12px; background: #409eff; color: #fff; }
.merchant-message-image { display: block; overflow: hidden; border-radius: 9px; background: #e8ecf2; }
.merchant-message-image img { display: block; max-width: 340px; max-height: 380px; object-fit: contain; }
.merchant-message-meta { color: #a5acb6; font-size: 10px; }
.merchant-order-message { display: flex; width: min(430px, 54vw); cursor: pointer; border: 1px solid #dfe4eb; border-radius: 8px; padding: 11px; background: #fff; color: inherit; text-align: left; }
.merchant-order-message > img { width: 72px; height: 72px; flex: 0 0 72px; border-radius: 5px; object-fit: cover; }
.merchant-order-message > span { display: grid; min-width: 0; gap: 4px; padding-left: 10px; }
.merchant-order-message small { color: #409eff; }
.merchant-order-message strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.merchant-order-message em { color: #4b5563; font-size: 12px; font-style: normal; }
.merchant-order-message i { color: #9aa1ac; font-size: 10px; font-style: normal; }
.merchant-composer { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 10px; border-top: 1px solid #e3e7ed; padding: 12px 16px 16px; background: #fff; }
.merchant-composer-tools { display: flex; align-items: center; gap: 7px; }
.merchant-composer > .el-button { align-self: stretch; }
.merchant-chat-placeholder { display: grid; place-items: center; background: #f6f8fb; }
@media (max-width: 900px) {
  .merchant-chat-layout { grid-template-columns: 240px minmax(0, 1fr); }
  .merchant-message-wrap { max-width: 82%; }
}
</style>
