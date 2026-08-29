<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { money } from '../mock'

const router = useRouter()
const items = ref([])
const loading = ref(true)
const updatingSkuId = ref(null)
const errorMessage = ref('')

const validItems = computed(() => items.value.filter((item) => item.valid !== false))
const invalidItems = computed(() => items.value.filter((item) => item.valid === false))
const selectedItems = computed(() => validItems.value.filter((item) => item.selected !== false))
const total = computed(() => selectedItems.value.reduce((sum, item) => sum + item.priceCent * item.quantity, 0))

async function loadCart() {
  loading.value = true
  errorMessage.value = ''
  try {
    items.value = await api.cart()
  } catch (error) {
    items.value = []
    errorMessage.value = error.message || '购物车加载失败'
  } finally {
    loading.value = false
  }
}

async function updateItem(item, payload) {
  updatingSkuId.value = item.skuId
  errorMessage.value = ''
  try {
    const updated = await api.updateCart(item.skuId, payload)
    const index = items.value.findIndex((current) => current.skuId === item.skuId)
    if (index >= 0) items.value[index] = updated
  } catch (error) {
    errorMessage.value = error.message || '购物车更新失败'
  } finally {
    updatingSkuId.value = null
  }
}

async function changeQuantity(item, step) {
  const nextQuantity = Math.max(1, Math.min(item.availableStock || 1, item.quantity + step))
  if (nextQuantity !== item.quantity) await updateItem(item, { quantity: nextQuantity })
}

async function removeItem(item) {
  try {
    await api.removeCart(item.skuId)
    items.value = items.value.filter((current) => current.skuId !== item.skuId)
  } catch (error) {
    errorMessage.value = error.message || '移除商品失败'
  }
}

async function clearInvalidItems() {
  try {
    await api.clearInvalidCart()
    await loadCart()
  } catch (error) {
    errorMessage.value = error.message || '清理失效商品失败'
  }
}

function checkout() {
  if (!selectedItems.value.length) {
    errorMessage.value = '请至少选择一件有效商品'
    return
  }
  router.push('/checkout')
}

onMounted(loadCart)
</script>

<template>
  <section class="content narrow">
    <p class="eyebrow">MY CART</p>
    <h1>购物车</h1>
    <p v-if="loading" class="muted">购物车加载中…</p>
    <p v-else-if="errorMessage" class="error">{{ errorMessage }}</p>
    <p v-else-if="!items.length" class="muted">购物车还是空的，去首页看看吧。</p>

    <template v-else>
      <article v-for="item in validItems" :key="item.skuId" class="cart-row">
        <input :checked="item.selected !== false" type="checkbox" aria-label="选择商品" :disabled="updatingSkuId === item.skuId" @change="updateItem(item, { selected: $event.target.checked })">
        <img v-if="item.mainImage" :src="item.mainImage" :alt="item.title">
        <div v-else class="cart-image-placeholder"></div>
        <div class="grow"><h3>{{ item.title }}</h3><p class="muted">{{ item.specsJson || '默认规格' }}</p><strong>{{ money(item.priceCent) }}</strong></div>
        <div class="quantity"><button :disabled="updatingSkuId === item.skuId" @click="changeQuantity(item, -1)">−</button><span>{{ item.quantity }}</span><button :disabled="updatingSkuId === item.skuId || item.quantity >= item.availableStock" @click="changeQuantity(item, 1)">+</button></div>
        <button class="text-button" @click="removeItem(item)">移除</button>
      </article>

      <section v-if="invalidItems.length" class="invalid-cart">
        <p>有 {{ invalidItems.length }} 件失效商品，无法结算。</p>
        <button class="text-button" @click="clearInvalidItems">清理失效商品</button>
      </section>
      <div class="cart-total"><strong>合计 {{ money(total) }}</strong><button class="primary-button" @click="checkout">去结算</button></div>
    </template>
  </section>
</template>
