<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { money } from '../mock'

const props = defineProps({
  orderNo: { type: String, required: true }
})
const route = useRoute()
const router = useRouter()

// 与后端退款状态机保持一致，防止待收货订单展示一个必然失败的入口。
const REFUNDABLE_ORDER_STATUSES = ['PAID', 'WAITING_SHIPMENT']

const order = ref(null)
const loading = ref(true)
const errorMessage = ref('')
const paying = ref(false)
const walletBalanceCent = ref(0)
const paymentError = ref('')
const cancelling = ref(false)
const cancelError = ref('')
const selectedPaymentChannel = ref('BALANCE')
const currentTime = ref(Date.now())
const showRefundForm = ref(false)
const refundReason = ref('')
const refunding = ref(false)
const refundError = ref('')
const refundSuccess = ref('')
const confirmingReceipt = ref(false)
const receiptError = ref('')
const contacting = ref(false)
const orderReviews = ref([])
const reviewDrafts = ref({})
const reviewingItemId = ref('')
const reviewError = ref('')
const reviewSuccess = ref('')
let expiryTimer = null

/**
 * 后端定时取消可能存在几十秒调度延迟。
 * 在这段窗口期内，前端也不允许已经超过付款截止时间的订单继续支付。
 */
const isPaymentExpired = computed(() => {
  if (order.value?.status !== 'PENDING_PAYMENT' || !order.value?.expireAt) return false
  const expireTime = new Date(order.value.expireAt).getTime()
  return Number.isFinite(expireTime) && expireTime <= currentTime.value
})

const displayStatusText = computed(() => {
  if (isPaymentExpired.value) return '已取消'
  return statusText[order.value?.status] || '状态处理中'
})

const canApplyRefund = computed(() => (
  REFUNDABLE_ORDER_STATUSES.includes(order.value?.status)
))

const statusText = {
  PENDING_PAYMENT: '待付款',
  PAID: '已支付',
  WAITING_SHIPMENT: '未发货',
  WAITING_RECEIPT: '待收货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUNDING: '退款中',
  REFUNDED: '已退款'
}

function formatTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : '-'
}

function formatSpecs(specsJson) {
  if (!specsJson) return '默认规格'
  try {
    const specs = JSON.parse(specsJson)
    return Object.entries(specs).map(([key, value]) => `${key}：${value}`).join('；') || '默认规格'
  } catch (_) {
    // 旧数据可能不是 JSON，原样展示比让整个详情页渲染失败更安全。
    return specsJson
  }
}

function stars(score) {
  const normalized = Math.max(0, Math.min(5, Number(score) || 0))
  return '★'.repeat(normalized) + '☆'.repeat(5 - normalized)
}

/**
 * 单独刷新评价状态，避免评价成功后重复查询钱包和整份订单详情。
 */
async function loadOrderReviews() {
  reviewError.value = ''
  try {
    const records = await api.orderReviews(props.orderNo)
    orderReviews.value = records || []

    // 只为尚未评价的订单项建立草稿，已填写内容在局部刷新时继续保留。
    const nextDrafts = { ...reviewDrafts.value }
    orderReviews.value.forEach((item) => {
      const key = String(item.orderItemId)
      if (!item.reviewed && !nextDrafts[key]) {
        nextDrafts[key] = { score: 5, content: '' }
      }
    })
    reviewDrafts.value = nextDrafts
  } catch (error) {
    orderReviews.value = []
    reviewError.value = error.message || '评价状态加载失败，请稍后重试'
  }
}

function setReviewScore(orderItemId, score) {
  const key = String(orderItemId)
  reviewDrafts.value = {
    ...reviewDrafts.value,
    [key]: {
      ...(reviewDrafts.value[key] || { content: '' }),
      score
    }
  }
}

async function submitReview(item) {
  const key = String(item.orderItemId)
  const draft = reviewDrafts.value[key] || {}
  if (!draft.score) {
    reviewError.value = '请选择 1 到 5 星评分'
    return
  }

  reviewingItemId.value = key
  reviewError.value = ''
  reviewSuccess.value = ''
  try {
    await api.createReview({
      orderItemId: item.orderItemId,
      score: draft.score,
      content: draft.content?.trim() || null
    })
    reviewSuccess.value = `“${item.productTitle}”评价已发布`
    await loadOrderReviews()
  } catch (error) {
    reviewError.value = error.message || '评价提交失败，请稍后重试'
  } finally {
    reviewingItemId.value = ''
  }
}

async function loadOrder() {
  loading.value = true
  errorMessage.value = ''
  try {
    // 订单和钱包数据互不依赖，并发请求可以减少页面等待时间。
    const [orderResult, walletResult] = await Promise.all([
      api.order(props.orderNo),
      api.wallet()
    ])
    /*
     * 后端为了避免 JavaScript 丢失 BIGINT 精度，可能会把 Long 序列化成字符串。
     * 金额在进入比较逻辑前必须显式转成 Number，否则字符串会按字典序比较：
     * "100000" < "48800" 会得到 true，进而错误显示余额不足。
     */
    order.value = {
      ...orderResult,
      payAmountCent: Number(orderResult?.payAmountCent ?? 0)
    }
    walletBalanceCent.value = Number(walletResult?.balanceCent ?? 0)

    if (order.value.status === 'COMPLETED') {
      // 评价状态加载失败不影响订单详情主体展示，错误在评价区单独提示。
      await loadOrderReviews()
    } else {
      orderReviews.value = []
    }

    // 从订单列表的“申请退款”入口进入时，自动展开退款表单。
    if (route.query.refund === '1' && REFUNDABLE_ORDER_STATUSES.includes(order.value.status)) {
      showRefundForm.value = true
    }
  } catch (error) {
    errorMessage.value = error.message || '订单详情加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function payWithBalance() {
  paymentError.value = ''

  // 取消请求和支付请求不能由同一页面同时提交；后端行锁仍是最终并发边界。
  if (cancelling.value) return

  if (walletBalanceCent.value < order.value.payAmountCent) {
    paymentError.value = '账户余额不足，无法完成支付'
    return
  }

  // 付款是不可逆的资金操作，提交前再让用户确认一次金额。
  const confirmed = window.confirm(`确认使用余额支付 ${money(order.value.payAmountCent)} 吗？`)
  if (!confirmed) return

  paying.value = true
  try {
    // 前端只发起请求，余额扣减和订单状态变更必须全部由后端事务完成。
    await api.createPayment(props.orderNo, 'BALANCE')

    // 支付成功后重新读取后端数据，不在前端自行修改订单状态或余额。
    await loadOrder()
  } catch (error) {
    paymentError.value = error.message || '余额支付失败，请稍后重试'
  } finally {
    paying.value = false
  }
}

async function payWithAlipay() {
  paymentError.value = ''

  if (cancelling.value) return

  // 必须在点击事件中同步打开新窗口，否则 await 结束后可能被浏览器拦截。
  const paymentWindow = window.open('about:blank', '_blank')
  paying.value = true

  try {
    const payment = await api.createPayment(props.orderNo, 'ALIPAY')
    if (!payment?.alipayForm) {
      throw new Error('未获取到支付宝支付表单')
    }

    if (paymentWindow) {
      // 后端支付宝 SDK 返回的 HTML 会自动提交至沙箱收银台。
      paymentWindow.document.open()
      paymentWindow.document.write(payment.alipayForm)
      paymentWindow.document.close()
    } else {
      // 新窗口被拦截时，退回当前页提交支付表单。
      const container = document.createElement('div')
      container.innerHTML = payment.alipayForm
      const form = container.querySelector('form')
      if (!form) throw new Error('支付宝支付表单格式错误')
      document.body.appendChild(form)
      form.submit()
    }
  } catch (error) {
    paymentWindow?.close()
    paymentError.value = error.message || '创建支付宝支付单失败'
  } finally {
    paying.value = false
  }
}

function payOrder() {
  if (selectedPaymentChannel.value === 'ALIPAY') {
    return payWithAlipay()
  }
  return payWithBalance()
}

/**
 * 买家主动取消待支付订单。
 *
 * 后端会锁定订单并与支付、超时任务竞争状态迁移。前端成功后重新读取订单，
 * 不在本地直接伪造 CANCELLED 状态，确保页面始终展示数据库事实。
 */
async function cancelOrder() {
  cancelError.value = ''

  if (paying.value || cancelling.value) return
  if (!window.confirm('确认取消这个订单吗？取消后无法继续支付，库存将自动恢复。')) return

  cancelling.value = true
  try {
    await api.cancelOrder(props.orderNo)
    await loadOrder()
  } catch (error) {
    cancelError.value = error.message || '取消订单失败，请稍后重试'
  } finally {
    cancelling.value = false
  }
}

function openRefundForm() {
  refundError.value = ''
  refundSuccess.value = ''
  showRefundForm.value = true
}

function closeRefundForm() {
  if (refunding.value) return
  showRefundForm.value = false
  refundError.value = ''
}

async function submitRefund() {
  refundError.value = ''
  refundSuccess.value = ''

  const reason = refundReason.value.trim()
  if (reason.length < 2) {
    refundError.value = '退款原因至少需要 2 个字符'
    return
  }

  if (!window.confirm(`确认申请整单退款 ${money(order.value.payAmountCent)} 吗？`)) {
    return
  }

  refunding.value = true
  try {
    const result = await api.applyRefund(props.orderNo, reason)
    refundSuccess.value = `退款申请已提交，退款单号：${result.refundNo}`
    refundReason.value = ''
    showRefundForm.value = false

    // 以后端最新订单状态为准，成功后应更新为 REFUNDING。
    await loadOrder()
  } catch (error) {
    refundError.value = error.message || '退款申请提交失败，请稍后重试'
  } finally {
    refunding.value = false
  }
}

async function confirmReceipt() {
  receiptError.value = ''
  if (!window.confirm('确认已收到商品吗？确认后订单将完成。')) return
  confirmingReceipt.value = true
  try {
    await api.confirmReceipt(props.orderNo)
    await loadOrder()
  } catch (error) {
    receiptError.value = error.message || '确认收货失败'
  } finally {
    confirmingReceipt.value = false
  }
}

async function contactMerchant() {
  if (!order.value?.merchantId || contacting.value) return
  contacting.value = true
  errorMessage.value = ''
  try {
    const conversation = await api.createChatConversation(order.value.merchantId)
    router.push(`/messages/${conversation.id}`)
  } catch (error) {
    errorMessage.value = error.message || '暂时无法联系商家'
  } finally {
    contacting.value = false
  }
}

onMounted(() => {
  loadOrder()
  // 每秒推进一次当前时间，使付款截止状态能够在不刷新页面的情况下立即更新。
  expiryTimer = window.setInterval(() => {
    currentTime.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (expiryTimer !== null) window.clearInterval(expiryTimer)
})
</script>

<template>
  <section class="content narrow">
    <RouterLink class="text-button" to="/orders">← 返回订单列表</RouterLink>
    <p v-if="loading" class="muted">订单详情加载中…</p>
    <p v-else-if="errorMessage" class="error">{{ errorMessage }}</p>

    <template v-else-if="order">
      <header class="order-detail-head">
        <div>
          <p class="eyebrow">ORDER DETAIL</p>
          <h1>订单详情</h1>
          <p class="muted">订单号：{{ order.orderNo }}</p>
        </div>
        <strong class="order-detail-status">{{ displayStatusText }}</strong>
        <button class="secondary-button" type="button" :disabled="contacting" @click="contactMerchant">
          {{ contacting ? '正在进入会话…' : '联系商家' }}
        </button>
      </header>

      <section class="checkout-block">
        <h2>商品信息</h2>
        <article v-for="item in order.items" :key="item.skuId" class="order-detail-item">
          <img v-if="item.productImage" :src="item.productImage" :alt="item.productTitle">
          <div v-else class="order-item-image-placeholder" aria-hidden="true"></div>
          <div class="grow">
            <h3>{{ item.productTitle }}</h3>
            <p class="muted">{{ formatSpecs(item.specsJson) }}</p>
          </div>
          <div class="order-detail-price">
            <strong>{{ money(item.subtotalCent) }}</strong>
            <span class="muted">{{ money(item.unitPriceCent) }} × {{ item.quantity }}</span>
          </div>
        </article>
      </section>

      <section class="checkout-block">
        <h2>收货信息</h2>
        <dl class="detail-kv">
          <dt>收货人</dt><dd>{{ order.address.receiver }}</dd>
          <dt>联系电话</dt><dd>{{ order.address.phone }}</dd>
          <dt>收货地址</dt><dd>{{ order.address.province }}{{ order.address.city }}{{ order.address.district }}{{ order.address.detail }}</dd>
        </dl>
      </section>

      <section v-if="order.status === 'COMPLETED'" class="checkout-block order-review-block">
        <h2>商品评价</h2>
        <p class="muted">评价来自真实完成订单，每个订单商品只能评价一次。</p>
        <p v-if="reviewSuccess" class="success">{{ reviewSuccess }}</p>
        <p v-if="reviewError" class="error">{{ reviewError }}</p>

        <article v-for="item in orderReviews" :key="item.orderItemId" class="order-review-item">
          <img v-if="item.productImage" :src="item.productImage" :alt="item.productTitle">
          <div v-else class="order-item-image-placeholder" aria-hidden="true"></div>
          <div class="order-review-content">
            <RouterLink :to="`/product/${item.productId}`"><h3>{{ item.productTitle }}</h3></RouterLink>
            <p class="muted">{{ formatSpecs(item.specsJson) }}</p>

            <div v-if="item.reviewed" class="review-published">
              <strong class="review-stars" :aria-label="`${item.score} 星`">{{ stars(item.score) }}</strong>
              <p>{{ item.content || '用户未填写文字评价' }}</p>
              <small class="muted">已评价 · {{ formatTime(item.reviewedAt) }}</small>
            </div>

            <form v-else class="review-form" @submit.prevent="submitReview(item)">
              <fieldset>
                <legend>商品评分</legend>
                <button
                  v-for="score in 5"
                  :key="score"
                  type="button"
                  :class="{ active: score <= (reviewDrafts[String(item.orderItemId)]?.score || 0) }"
                  :aria-label="`${score} 星`"
                  @click="setReviewScore(item.orderItemId, score)"
                >★</button>
              </fieldset>
              <textarea
                v-model="reviewDrafts[String(item.orderItemId)].content"
                maxlength="1000"
                rows="3"
                placeholder="分享这件商品的使用感受（选填，最多 1000 字）"
              ></textarea>
              <div class="review-form-footer">
                <small class="muted">{{ reviewDrafts[String(item.orderItemId)]?.content?.length || 0 }} / 1000</small>
                <button
                  class="primary-button"
                  type="submit"
                  :disabled="reviewingItemId === String(item.orderItemId)"
                >
                  {{ reviewingItemId === String(item.orderItemId) ? '发布中…' : '发布评价' }}
                </button>
              </div>
            </form>
          </div>
        </article>
      </section>

      <section class="checkout-block">
        <h2>订单信息</h2>
        <dl class="detail-kv">
          <dt>订单状态</dt><dd>{{ displayStatusText }}</dd>
          <dt>下单时间</dt><dd>{{ formatTime(order.createdAt) }}</dd>
          <dt>付款截止</dt><dd>{{ formatTime(order.expireAt) }}</dd>
          <dt>实付金额</dt><dd class="price">{{ money(order.payAmountCent) }}</dd>
          <template v-if="order.shippingCompany">
            <dt>物流公司</dt><dd>{{ order.shippingCompany }}</dd>
            <dt>运单号</dt><dd>{{ order.trackingNo }}</dd>
            <dt>发货时间</dt><dd>{{ formatTime(order.shippedAt) }}</dd>
          </template>
        </dl>
      </section>

      <section v-if="order.status === 'WAITING_RECEIPT'" class="checkout-block">
        <h2>确认收货</h2>
        <p class="muted">请在实际收到商品后再确认收货。</p>
        <p v-if="receiptError" class="error">{{ receiptError }}</p>
        <button class="primary-button" type="button" :disabled="confirmingReceipt" @click="confirmReceipt">
          {{ confirmingReceipt ? '正在确认…' : '确认收货' }}
        </button>
      </section>

      <section v-if="isPaymentExpired" class="checkout-block">
        <h2>订单已取消</h2>
        <p class="muted">该订单因超时未付款已取消，无法继续支付。</p>
      </section>

      <section v-else-if="order.status === 'PENDING_PAYMENT'" class="checkout-block">
        <h2>支付订单</h2>

        <div class="payment-method-list" role="radiogroup" aria-label="选择支付方式">
          <label
            class="payment-method-option"
            :class="{ selected: selectedPaymentChannel === 'BALANCE' }"
          >
            <input v-model="selectedPaymentChannel" type="radio" value="BALANCE">
            <span>
              <strong>余额支付</strong>
              <small>可用余额：{{ money(walletBalanceCent) }}</small>
            </span>
          </label>

          <label
            class="payment-method-option"
            :class="{ selected: selectedPaymentChannel === 'ALIPAY' }"
          >
            <input v-model="selectedPaymentChannel" type="radio" value="ALIPAY">
            <span>
              <strong>支付宝</strong>
              <small>跳转至支付宝沙箱收银台</small>
            </span>
          </label>
        </div>

        <p class="payment-amount">应付：<strong>{{ money(order.payAmountCent) }}</strong></p>

        <p v-if="paymentError" class="error">{{ paymentError }}</p>
        <p v-if="cancelError" class="error">{{ cancelError }}</p>
        <p
          v-if="selectedPaymentChannel === 'BALANCE' && walletBalanceCent < order.payAmountCent"
          class="error"
        >
          账户余额不足，还差 {{ money(order.payAmountCent - walletBalanceCent) }}
        </p>

        <div class="refund-form-actions">
          <button
            class="primary-button"
            type="button"
            :disabled="paying || cancelling || (selectedPaymentChannel === 'BALANCE' && walletBalanceCent < order.payAmountCent)"
            @click="payOrder"
          >
            {{ paying
              ? '正在发起支付…'
              : selectedPaymentChannel === 'BALANCE'
                ? `余额支付 ${money(order.payAmountCent)}`
                : `支付宝支付 ${money(order.payAmountCent)}`
            }}
          </button>
          <button
            class="secondary-button"
            type="button"
            :disabled="paying || cancelling"
            @click="cancelOrder"
          >
            {{ cancelling ? '正在取消…' : '取消订单' }}
          </button>
        </div>
      </section>

      <section v-if="canApplyRefund" class="checkout-block">
        <h2>售后服务</h2>
        <p class="muted">当前支持整单退款，申请后将由商家审核。</p>

        <button
          v-if="!showRefundForm"
          class="secondary-button"
          type="button"
          @click="openRefundForm"
        >
          申请退款
        </button>

        <form v-else class="refund-form" @submit.prevent="submitRefund">
          <label for="refund-reason">退款原因</label>
          <textarea
            id="refund-reason"
            v-model="refundReason"
            minlength="2"
            maxlength="255"
            rows="4"
            placeholder="请说明申请退款的原因"
            :disabled="refunding"
          ></textarea>
          <small class="muted">{{ refundReason.length }} / 255</small>
          <p v-if="refundError" class="error">{{ refundError }}</p>
          <div class="refund-form-actions">
            <button class="primary-button" type="submit" :disabled="refunding">
              {{ refunding ? '正在提交…' : '确认申请退款' }}
            </button>
            <button class="text-button" type="button" :disabled="refunding" @click="closeRefundForm">
              取消
            </button>
          </div>
        </form>
      </section>

      <section v-else-if="order.status === 'REFUNDING'" class="checkout-block">
        <h2>退款申请</h2>
        <p class="muted">退款申请已提交，正在等待商家审核。</p>
        <p v-if="refundSuccess" class="success">{{ refundSuccess }}</p>
      </section>
    </template>
  </section>
</template>
