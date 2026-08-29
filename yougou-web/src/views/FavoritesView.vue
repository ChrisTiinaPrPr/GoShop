<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '../api'
import { money } from '../mock'

const favorites = ref([])
const loading = ref(true)
const errorMessage = ref('')
const removingProductId = ref('')
const page = ref(1)
const pageSize = 12
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

async function loadFavorites(targetPage = page.value) {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await api.favorites(targetPage, pageSize)
    favorites.value = data?.records || []
    total.value = data?.total || 0
    page.value = data?.page || targetPage
  } catch (error) {
    favorites.value = []
    total.value = 0
    errorMessage.value = error.message || '收藏列表加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function removeFavorite(productId) {
  removingProductId.value = String(productId)
  errorMessage.value = ''
  try {
    await api.removeFavorite(productId)

    /*
     * 删除当前页最后一项后返回上一页，避免停留在超出总页数的空页；
     * 其他情况重新请求当前页，让后端分页总数保持最终一致。
     */
    const shouldGoPrevious = favorites.value.length === 1 && page.value > 1
    await loadFavorites(shouldGoPrevious ? page.value - 1 : page.value)
  } catch (error) {
    errorMessage.value = error.message || '取消收藏失败，请稍后重试'
  } finally {
    removingProductId.value = ''
  }
}

function formatDate(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => loadFavorites(1))
</script>

<template>
  <section class="content favorites-page">
    <div class="section-head">
      <div>
        <p class="eyebrow">MY PICKS</p>
        <h1>我的收藏</h1>
        <p class="muted">共收藏 {{ total }} 件商品，价格与上架状态均为当前信息。</p>
      </div>
    </div>

    <p v-if="loading" class="muted">收藏加载中…</p>
    <p v-else-if="errorMessage && !favorites.length" class="error">{{ errorMessage }}</p>

    <div v-else-if="!favorites.length" class="favorite-empty">
      <span aria-hidden="true">♡</span>
      <h2>还没有收藏商品</h2>
      <p class="muted">遇到喜欢的商品时，点击详情页的收藏按钮就能保存到这里。</p>
      <RouterLink class="primary-button" to="/">去发现好物</RouterLink>
    </div>

    <template v-else>
      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      <div class="favorite-grid">
        <article v-for="item in favorites" :key="item.productId" class="favorite-card" :class="{ unavailable: !item.available }">
          <RouterLink v-if="item.available" class="favorite-image-link" :to="`/product/${item.productId}`">
            <img v-if="item.mainImage" :src="item.mainImage" :alt="item.title">
            <span v-else class="product-image-placeholder"></span>
          </RouterLink>
          <div v-else class="favorite-image-link" aria-disabled="true">
            <img v-if="item.mainImage" :src="item.mainImage" :alt="item.title">
            <span v-else class="product-image-placeholder"></span>
            <strong class="unavailable-badge">已下架</strong>
          </div>

          <div class="favorite-card-body">
            <RouterLink class="merchant" :to="`/merchant/${item.merchantId}`">{{ item.merchantName }}</RouterLink>
            <RouterLink v-if="item.available" :to="`/product/${item.productId}`"><h2>{{ item.title }}</h2></RouterLink>
            <h2 v-else>{{ item.title }}</h2>
            <strong class="price">{{ item.minPriceCent == null ? '暂无可售规格' : money(item.minPriceCent) }}</strong>
            <small class="muted">收藏于 {{ formatDate(item.favoritedAt) }}</small>
            <button
              class="text-button favorite-remove"
              type="button"
              :disabled="removingProductId === String(item.productId)"
              @click="removeFavorite(item.productId)"
            >
              {{ removingProductId === String(item.productId) ? '取消中…' : '取消收藏' }}
            </button>
          </div>
        </article>
      </div>

      <nav v-if="totalPages > 1" class="pagination" aria-label="收藏分页">
        <button type="button" :disabled="page === 1 || loading" @click="loadFavorites(page - 1)">上一页</button>
        <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 件</span>
        <button type="button" :disabled="page === totalPages || loading" @click="loadFavorites(page + 1)">下一页</button>
      </nav>
    </template>
  </section>
</template>
