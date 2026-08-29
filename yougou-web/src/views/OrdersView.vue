<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'
import { money } from '../mock'

const orders = ref([])
const loading = ref(true)
const errorMessage = ref('')
const cancelError = ref('')
const cancellingOrderNo = ref(null)
const page = ref(1)
const pageSize = 10
const total = ref(0)
const currentTime = ref(Date.now())
let expiryTimer = null

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

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

// 第一版仅支持未发货整单退款；进入待收货后不再展示退款入口。
const refundableOrderStatuses = ['PAID', 'WAITING_SHIPMENT']

function formatTime(value) {
  // LocalDateTime 通常不带时区，直接展示以避免浏览器错误地按 UTC 转换。
  return value ? value.replace('T', ' ').slice(0, 16) : '-'
}

function isPaymentExpired(order) {
  if (order.status !== 'PENDING_PAYMENT' || !order.expireAt) return false
  const expireTime = new Date(order.expireAt).getTime()
  return Number.isFinite(expireTime) && expireTime <= currentTime.value
}

function displayStatusText(order) {
  // 后端取消任务尚未执行的短暂窗口内，也不能继续向用户展示“待付款”。
  if (isPaymentExpired(order)) return '已取消'
  return statusText[order.status] || '状态处理中'
}

async function loadOrders(targetPage = page.value) {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await api.orders(targetPage, pageSize)
    orders.value = data.records || []

    /*
     * 后端可能为了保护 BIGINT 精度而把 long 序列化成字符串。
     * 如果 page 保持为 "1"，模板中的 page + 1 会得到 "11"，导致下一页请求被范围校验拒绝。
     */
    const responseTotal = Number(data.total ?? 0)
    const responsePage = Number(data.page ?? targetPage)
    total.value = Number.isFinite(responseTotal) ? responseTotal : 0
    page.value = Number.isFinite(responsePage) ? responsePage : Number(targetPage)
  } catch (error) {
    orders.value = []
    total.value = 0
    errorMessage.value = error.message || '订单加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function changePage(targetPage) {
  const normalizedTargetPage = Number(targetPage)
  if (
    Number.isInteger(normalizedTargetPage) &&
    normalizedTargetPage >= 1 &&
    normalizedTargetPage <= totalPages.value &&
    normalizedTargetPage !== page.value
  ) {
    loadOrders(normalizedTargetPage)
  }
}

/**
 * 从订单列表直接取消待支付订单。
 *
 * 只在订单未超过付款截止时间时展示入口；后端仍会重新校验订单归属和状态，
 * 防止用户篡改页面或与支付请求并发提交。
 */
async function cancelOrder(order) {
  cancelError.value = ''

  if (cancellingOrderNo.value !== null) return
  if (!window.confirm(`确认取消订单 ${order.orderNo} 吗？`)) return

  cancellingOrderNo.value = order.orderNo
  try {
    await api.cancelOrder(order.orderNo)
    await loadOrders(page.value)
  } catch (error) {
    cancelError.value = error.message || '取消订单失败，请稍后重试'
  } finally {
    cancellingOrderNo.value = null
  }
}

onMounted(() => {
  loadOrders()
  // 保证订单列表停留在页面时也会在付款截止点自动更新显示状态。
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
    <p class="eyebrow">MY ORDERS</p>
    <h1>我的订单</h1>

    <p v-if="cancelError" class="error">{{ cancelError }}</p>

    <p v-if="loading" class="muted">订单加载中…</p>
    <p v-else-if="errorMessage" class="error">{{ errorMessage }}</p>
    <p v-else-if="!orders.length" class="muted">暂无订单。完成首次下单后，订单会显示在这里。</p>

    <template v-else>
      <article v-for="order in orders" :key="order.orderNo" class="order-card">
        <div>
          <strong>订单号：{{ order.orderNo }}</strong>
          <p>{{ displayStatusText(order) }}</p>
          <small class="muted">下单时间：{{ formatTime(order.createdAt) }}</small>
        </div>
        <div class="order-actions">
          <RouterLink :to="`/orders/${order.orderNo}`">查看详情</RouterLink>
          <RouterLink
            v-if="refundableOrderStatuses.includes(order.status)"
            :to="{ path: `/orders/${order.orderNo}`, query: { refund: '1' } }"
          >
            申请退款
          </RouterLink>
          <button
            v-if="order.status === 'PENDING_PAYMENT' && !isPaymentExpired(order)"
            class="text-button danger-button"
            type="button"
            :disabled="cancellingOrderNo !== null"
            @click="cancelOrder(order)"
          >
            {{ cancellingOrderNo === order.orderNo ? '取消中…' : '取消订单' }}
          </button>
          <strong>{{ money(order.payAmountCent) }}</strong>
        </div>
      </article>

      <nav v-if="totalPages > 1" class="pagination" aria-label="订单分页">
        <button type="button" :disabled="page === 1 || loading" @click="changePage(page - 1)">上一页</button>
        <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 条</span>
        <button type="button" :disabled="page === totalPages || loading" @click="changePage(page + 1)">下一页</button>
      </nav>
    </template>
  </section>
</template>
