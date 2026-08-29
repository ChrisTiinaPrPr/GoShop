<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { money } from '../mock'

const router = useRouter()
const addresses = ref([])
const cartItems = ref([])
const addressId = ref('')
const loading = ref(true)
const submitting = ref(false)
const errorMessage = ref('')
const total = computed(() => cartItems.value.reduce((sum, item) => sum + item.priceCent * item.quantity, 0))
const submitKey = crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`

async function loadCheckout() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [addressData, cartData] = await Promise.all([api.addresses(), api.cart()])
    addresses.value = addressData || []
    cartItems.value = (cartData || []).filter((item) => item.selected !== false && item.valid !== false)
    addressId.value = addresses.value.find((item) => item.isDefault === 1)?.id || addresses.value[0]?.id || ''
  } catch (error) {
    errorMessage.value = error.message || '结算信息加载失败'
  } finally {
    loading.value = false
  }
}

async function submitOrder() {
  errorMessage.value = ''
  if (!addressId.value) {
    errorMessage.value = '请先新增并选择收货地址'
    return
  }
  if (!cartItems.value.length) {
    errorMessage.value = '请在购物车选择至少一件有效商品'
    return
  }

  submitting.value = true
  try {
    const result = await api.createOrders({
      // 雪花 Long ID 超出 JavaScript 安全整数范围，必须以字符串原样提交。
      addressId: addressId.value,
      items: cartItems.value.map((item) => ({ skuId: item.skuId, quantity: item.quantity }))
    }, submitKey)
    // 当前版本按商家拆单；单商家时直接跳转详情，多商家时返回订单列表。
    if (result.orders?.length === 1) {
      router.replace(`/orders/${result.orders[0].orderNo}`)
    } else {
      router.replace('/orders')
    }
  } catch (error) {
    errorMessage.value = error.message || '提交订单失败'
  } finally {
    submitting.value = false
  }
}

onMounted(loadCheckout)
</script>

<template>
  <section class="content narrow checkout-page">
    <p class="eyebrow">CHECKOUT</p><h1>确认订单</h1>
    <p v-if="loading" class="muted">正在核对购物车和收货地址…</p>
    <p v-else-if="errorMessage && !cartItems.length" class="error">{{ errorMessage }}</p>
    <template v-else>
      <section class="checkout-block">
        <h2>收货地址</h2>
        <label v-for="address in addresses" :key="address.id" class="address-option">
          <input v-model="addressId" type="radio" :value="address.id">
          <span><strong>{{ address.receiver }} {{ address.phone }}</strong><br>{{ address.province }}{{ address.city }}{{ address.district }}{{ address.detail }}</span>
        </label>
        <p v-if="!addresses.length" class="muted">还没有收货地址，<RouterLink class="login-link" to="/addresses">现在去新增</RouterLink>。</p>
      </section>
      <section class="checkout-block">
        <h2>商品清单</h2>
        <div v-for="item in cartItems" :key="item.skuId" class="checkout-item"><span>{{ item.title }} × {{ item.quantity }}</span><strong>{{ money(item.priceCent * item.quantity) }}</strong></div>
        <p v-if="!cartItems.length" class="muted">没有已勾选的有效商品。</p>
      </section>
      <div class="cart-total"><strong>应付 {{ money(total) }}</strong><button class="primary-button" :disabled="submitting" @click="submitOrder">{{ submitting ? '正在创建订单…' : '提交订单' }}</button></div>
      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
    </template>
  </section>
</template>
