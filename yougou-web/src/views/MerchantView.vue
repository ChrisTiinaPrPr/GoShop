<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, streamMerchantAiQuestion } from '../api'
import { merchantAiAnswerParts } from '../merchant-ai-answer'
import { money } from '../mock'

const props = defineProps({ id: { type: String, required: true } })
const router = useRouter()
const merchant = ref(null)
const products = ref([])
const merchantLoading = ref(true)
const productsLoading = ref(false)
const contacting = ref(false)
const merchantError = ref('')
const productsError = ref('')
const actionError = ref('')
const aiPanelOpen = ref(false)
const aiQuestion = ref('')
const aiMessages = ref([])
const aiSubmitting = ref(false)
const aiReceivingText = ref(false)
const aiError = ref('')
const aiTranscript = ref(null)
const assistantProfile = ref(null)
const aiRequestController = ref(null)
const keyword = ref('')
const sort = ref('latest')
const page = ref(1)
const pageSize = 8
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const assistantName = computed(() => (
  assistantProfile.value?.name || `${merchant.value?.name || '店铺'}智能导购`
))
const assistantAvatarUrl = computed(() => (
  assistantProfile.value?.avatarUrl || merchant.value?.logoUrl || ''
))
const aiSuggestions = [
  '有什么值得推荐的商品？',
  '帮我按使用场景选一款',
  '这些商品有什么区别？'
]

async function loadMerchant() {
  merchantLoading.value = true
  merchantError.value = ''
  try {
    merchant.value = await api.merchant(props.id)
  } catch (error) {
    merchant.value = null
    merchantError.value = error.message || '商家信息加载失败'
  } finally {
    merchantLoading.value = false
  }
}

/**
 * 查询当前店铺的公开商品。
 *
 * 店铺 ID 只来自路由，后端仍会校验店铺启用状态并在 SQL 层按
 * merchant_id 过滤；前端不能把列表结果视为权限校验依据。
 */
async function loadProducts(targetPage = page.value) {
  productsLoading.value = true
  productsError.value = ''

  try {
    const data = await api.merchantProducts(props.id, {
      page: targetPage,
      pageSize,
      keyword: keyword.value.trim() || undefined,
      sort: sort.value
    })

    products.value = data.records || []
    total.value = data.total || 0
    page.value = data.page || targetPage
  } catch (error) {
    products.value = []
    total.value = 0
    productsError.value = error.message || '店铺商品加载失败，请稍后重试'
  } finally {
    productsLoading.value = false
  }
}

function submitSearch() {
  loadProducts(1)
}

async function contactMerchant() {
  if (!localStorage.getItem('yougou_buyer_access_token')) {
    router.push({ path: '/login', query: { redirect: `/merchant/${props.id}` } })
    return
  }
  contacting.value = true
  actionError.value = ''
  try {
    const conversation = await api.createChatConversation(props.id)
    router.push(`/messages/${conversation.id}`)
  } catch (error) {
    actionError.value = error.message || '暂时无法联系商家'
  } finally {
    contacting.value = false
  }
}

/**
 * 智能导购只允许登录买家使用。登录跳转保留当前店铺地址，成功后可直接
 * 回到原店铺继续提问；路由守卫只负责体验，真正的 USER 权限仍由后端校验。
 */
function toggleAiAssistant() {
  if (!localStorage.getItem('yougou_buyer_access_token')) {
    router.push({ path: '/login', query: { redirect: `/merchant/${props.id}` } })
    return
  }
  aiPanelOpen.value = !aiPanelOpen.value
  if (aiPanelOpen.value) {
    nextTick(() => aiTranscript.value?.scrollTo({ top: aiTranscript.value.scrollHeight }))
  }
}

function useAiSuggestion(suggestion) {
  aiQuestion.value = suggestion
  nextTick(() => document.querySelector('#merchant-ai-question')?.focus())
}

/** 中文输入法确认候选词时也会触发 Enter，组合输入尚未结束时不能发送。 */
function handleAiQuestionKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  askAiAssistant()
}

/**
 * 后端当前是无 conversationId 的单轮问答。页面可以在内存中保留展示历史，
 * 但每次请求只提交当前问题，不能把本地历史伪装成已经持久化的多轮上下文。
 */
async function askAiAssistant() {
  const question = aiQuestion.value.trim()
  if (!question || aiSubmitting.value) return
  if (!localStorage.getItem('yougou_buyer_access_token')) {
    router.push({ path: '/login', query: { redirect: `/merchant/${props.id}` } })
    return
  }

  aiSubmitting.value = true
  aiReceivingText.value = false
  aiError.value = ''
  aiQuestion.value = ''
  aiMessages.value.push({
    id: `buyer-${Date.now()}-${aiMessages.value.length}`,
    role: 'buyer',
    text: question
  })
  await scrollAiTranscript()

  /*
   * 必须使用 reactive 包装流式消息。若只把普通对象 push 到响应式数组后
   * 继续修改原始引用，后续 delta 不一定触发 Vue 更新，页面仍会表现成
   * “首分片后停住，结束时一次性显示”。
   */
  const assistantMessage = reactive({
    id: `assistant-${Date.now()}-${aiMessages.value.length}`,
    role: 'assistant',
    text: '',
    grounded: false,
    streaming: true
  })
  let assistantMessageAdded = false
  let completed = false
  const requestController = new AbortController()
  aiRequestController.value = requestController

  try {
    await streamMerchantAiQuestion(props.id, question, {
      signal: requestController.signal,
      onEvent: async (event) => {
        if (event.type === 'STARTED') {
          assistantProfile.value = {
            name: event.assistantName,
            avatarUrl: event.assistantAvatarUrl
          }
          return
        }

        if (event.type === 'TEXT_DELTA') {
          /*
           * 第一个文本分片到达时才创建回答气泡；检索阶段继续显示加载提示，
           * 避免页面先出现一个空白气泡。后续分片原样按顺序追加，空格和
           * 换行不能 trim，否则模型输出的段落格式会被破坏。
           */
          if (!assistantMessageAdded) {
            aiMessages.value.push(assistantMessage)
            assistantMessageAdded = true
          }
          assistantMessage.text += event.delta || ''
          aiReceivingText.value = true
          await scrollAiTranscript()
          return
        }

        if (event.type === 'COMPLETED') {
          assistantMessage.grounded = Boolean(event.grounded)
          assistantMessage.streaming = false
          completed = true
          return
        }

        if (event.type === 'ERROR') {
          const error = new Error(event.message || '智能导购回答生成中断，请稍后重试')
          error.code = event.code
          throw error
        }
      }
    })

    if (!completed) {
      throw new Error('智能导购连接提前结束，请重新提问')
    }
  } catch (error) {
    /* 主动切换店铺或离开页面属于正常取消，不显示误导性的网络错误。 */
    if (error.name === 'AbortError') return
    /* 流中断后的半条回答不能作为可靠答案保留在页面中。 */
    if (assistantMessageAdded) {
      aiMessages.value = aiMessages.value.filter(
        (message) => message.id !== assistantMessage.id
      )
    }
    aiError.value = error.message || '智能导购暂时无法回答，请稍后重试'
  } finally {
    if (aiRequestController.value === requestController) {
      aiRequestController.value = null
      aiSubmitting.value = false
      aiReceivingText.value = false
      await scrollAiTranscript()
    }
  }
}

async function scrollAiTranscript() {
  await nextTick()
  const element = aiTranscript.value
  if (element) element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' })
}

/**
 * 路由切换到另一家店铺时，重置上一家店铺的筛选和分页状态。
 */
watch(
  () => props.id,
  async () => {
    /* 店铺知识空间发生变化时，旧店铺尚未结束的 SSE 必须立即取消。 */
    aiRequestController.value?.abort()
    aiRequestController.value = null
    keyword.value = ''
    sort.value = 'latest'
    page.value = 1
    total.value = 0
    products.value = []
    productsError.value = ''
    actionError.value = ''
    aiPanelOpen.value = false
    aiQuestion.value = ''
    aiMessages.value = []
    aiSubmitting.value = false
    aiReceivingText.value = false
    aiError.value = ''
    assistantProfile.value = null

    await loadMerchant()

    // 店铺资料校验失败时，不再发起没有展示意义的商品查询。
    if (merchant.value) await loadProducts(1)
  },
  { immediate: true }
)

/** 离开店铺页后不再让后台流继续消耗模型连接或修改已卸载页面状态。 */
onBeforeUnmount(() => aiRequestController.value?.abort())
</script>

<template>
  <section class="content narrow merchant-page">
    <p v-if="merchantLoading" class="muted">商家信息加载中…</p>
    <p v-else-if="merchantError" class="error">{{ merchantError }}</p>
    <template v-else-if="merchant">
      <img v-if="merchant.logoUrl" class="merchant-logo" :src="merchant.logoUrl" :alt="merchant.name">
      <p class="eyebrow">MERCHANT</p>
      <h1>{{ merchant.name }}</h1>
      <p class="description">{{ merchant.description || '该商家暂未填写介绍。' }}</p>
      <div class="merchant-actions">
        <button
          class="primary-button merchant-ai-trigger"
          type="button"
          :aria-expanded="aiPanelOpen"
          aria-controls="merchant-ai-panel"
          @click="toggleAiAssistant"
        >
          {{ aiPanelOpen ? '收起智能导购' : '问问 AI 导购' }}
        </button>
        <button class="secondary-button" type="button" :disabled="contacting" @click="contactMerchant">
          {{ contacting ? '正在进入会话…' : '联系人工商家' }}
        </button>
      </div>
      <p v-if="actionError" class="error">{{ actionError }}</p>
    </template>
  </section>

  <section
    v-if="merchant && aiPanelOpen"
    id="merchant-ai-panel"
    class="content merchant-ai-section"
    aria-label="店铺智能导购"
  >
    <div class="merchant-ai-shell">
      <header class="merchant-ai-head">
        <img v-if="assistantAvatarUrl" :src="assistantAvatarUrl" :alt="assistantName">
        <span v-else class="merchant-ai-avatar-fallback">AI</span>
        <div>
          <p class="eyebrow">STORE AI GUIDE</p>
          <h2>{{ assistantName }}</h2>
          <p>基于本店导购资料与实时公开商品信息回答</p>
        </div>
        <span class="merchant-ai-status"><i></i> 店内问答</span>
      </header>

      <div ref="aiTranscript" class="merchant-ai-transcript" aria-live="polite">
        <div v-if="!aiMessages.length" class="merchant-ai-empty">
          <span class="merchant-ai-spark" aria-hidden="true">✦</span>
          <h3>想买什么，可以直接告诉我</h3>
          <p>我会从当前店铺的导购资料中查找依据，并结合实时商品价格回答。</p>
          <div class="merchant-ai-suggestions">
            <button
              v-for="suggestion in aiSuggestions"
              :key="suggestion"
              type="button"
              @click="useAiSuggestion(suggestion)"
            >{{ suggestion }}</button>
          </div>
        </div>

        <article
          v-for="message in aiMessages"
          :key="message.id"
          class="merchant-ai-message"
          :class="message.role"
        >
          <div v-if="message.role === 'assistant'" class="merchant-ai-message-avatar">
            <img v-if="assistantAvatarUrl" :src="assistantAvatarUrl" alt="">
            <span v-else>AI</span>
          </div>
          <div class="merchant-ai-message-content">
            <p class="merchant-ai-bubble">
              <template
                v-for="(part, partIndex) in merchantAiAnswerParts(message.text)"
                :key="`${message.id}-${partIndex}`"
              >
                <strong v-if="part.type === 'strong'">{{ part.text }}</strong>
                <span v-else>{{ part.text }}</span>
              </template>
            </p>
            <small v-if="message.role === 'assistant' && !message.grounded" class="merchant-ai-ungrounded">
              当前店铺资料不足，本次未调用模型扩写
            </small>
          </div>
        </article>

        <div v-if="aiSubmitting && !aiReceivingText" class="merchant-ai-thinking">
          <span></span><span></span><span></span>
          正在检索店铺资料与实时商品…
        </div>
      </div>

      <p v-if="aiError" class="merchant-ai-error" role="alert">{{ aiError }}</p>

      <form class="merchant-ai-composer" @submit.prevent="askAiAssistant">
        <label class="merchant-ai-question-label" for="merchant-ai-question">向店铺智能导购提问</label>
        <textarea
          id="merchant-ai-question"
          v-model="aiQuestion"
          maxlength="500"
          rows="2"
          :disabled="aiSubmitting"
          placeholder="例如：有没有适合打 FPS 的游戏鼠标？"
          @keydown="handleAiQuestionKeydown"
        ></textarea>
        <div class="merchant-ai-composer-footer">
          <span>{{ aiQuestion.length }}/500 · Enter 发送，Shift + Enter 换行</span>
          <button class="primary-button" type="submit" :disabled="aiSubmitting || !aiQuestion.trim()">
            {{ aiSubmitting ? '回答中…' : '发送问题' }}
          </button>
        </div>
        <small>每次问题独立检索；价格以当前商品信息为准，库存及规格请在商品页确认。</small>
      </form>
    </div>
  </section>

  <section v-if="merchant" class="content merchant-products-section">
    <div class="section-head">
      <div>
        <p class="eyebrow">STORE PRODUCTS</p>
        <h2>店铺商品</h2>
        <p class="muted">共 {{ total }} 件公开商品</p>
      </div>

      <form class="product-filters" @submit.prevent="submitSearch">
        <input v-model="keyword" maxlength="50" :placeholder="`搜索${merchant.name}的商品`">
        <select v-model="sort" @change="submitSearch">
          <option value="latest">最新上架</option>
          <option value="sales">销量优先</option>
          <option value="priceAsc">价格从低到高</option>
          <option value="priceDesc">价格从高到低</option>
        </select>
        <button class="primary-button" type="submit" :disabled="productsLoading">搜索</button>
      </form>
    </div>

    <p v-if="productsLoading" class="muted">店铺商品加载中…</p>
    <p v-else-if="productsError" class="error">{{ productsError }}</p>
    <p v-else-if="!products.length" class="muted">该店铺暂无符合条件的公开商品。</p>

    <div v-else class="product-grid">
      <RouterLink
        v-for="item in products"
        :key="item.id"
        :to="`/product/${item.id}`"
        class="product-card"
      >
        <img v-if="item.mainImage" :src="item.mainImage" :alt="item.title">
        <div v-else class="product-image-placeholder"></div>
        <div class="product-body">
          <p class="merchant">{{ merchant.name }}</p>
          <h3>{{ item.title }}</h3>
          <strong>{{ money(item.minPriceCent) }}</strong>
          <small class="muted">已售 {{ item.salesCount || 0 }}</small>
        </div>
      </RouterLink>
    </div>

    <nav v-if="totalPages > 1" class="pagination" aria-label="店铺商品分页">
      <button
        type="button"
        :disabled="page === 1 || productsLoading"
        @click="loadProducts(page - 1)"
      >上一页</button>
      <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 件</span>
      <button
        type="button"
        :disabled="page === totalPages || productsLoading"
        @click="loadProducts(page + 1)"
      >下一页</button>
    </nav>
  </section>
</template>
