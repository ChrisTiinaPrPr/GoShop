<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { money } from '../mock'

const props = defineProps({ id: { type: String, required: true } })
const router = useRouter()
const product = ref(null)
const selectedSkuId = ref('')
const quantity = ref(1)
const loading = ref(true)
const submitting = ref(false)
const contacting = ref(false)
const favoriteLoading = ref(false)
const favorited = ref(false)
const reviews = ref([])
const reviewsLoading = ref(true)
const reviewError = ref('')
const reviewPage = ref(1)
const reviewPageSize = 10
const reviewTotal = ref(0)
const averageScore = ref(0)
const message = ref('')
const errorMessage = ref('')

const selectedSku = computed(() => product.value?.skus?.find((sku) => String(sku.id) === String(selectedSkuId.value)))
const reviewTotalPages = computed(() => Math.max(1, Math.ceil(reviewTotal.value / reviewPageSize)))

function formatSpecs(specsJson) {
  if (!specsJson) return '默认规格'
  try {
    const specs = JSON.parse(specsJson)
    return Object.entries(specs).map(([key, value]) => `${key}：${value}`).join('；') || '默认规格'
  } catch (_) {
    return specsJson
  }
}

function formatReviewTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : '-'
}

function reviewStars(score) {
  const normalized = Math.max(0, Math.min(5, Number(score) || 0))
  return '★'.repeat(normalized) + '☆'.repeat(5 - normalized)
}

async function loadReviews(targetPage = reviewPage.value) {
  reviewsLoading.value = true
  reviewError.value = ''
  try {
    const data = await api.productReviews(props.id, targetPage, reviewPageSize)
    reviews.value = data?.records || []
    reviewTotal.value = data?.total || 0
    averageScore.value = Number(data?.averageScore || 0)
    reviewPage.value = data?.page || targetPage
  } catch (error) {
    reviews.value = []
    reviewTotal.value = 0
    averageScore.value = 0
    reviewError.value = error.message || '商品评价加载失败，请稍后重试'
  } finally {
    reviewsLoading.value = false
  }
}

async function loadProduct() {
  loading.value = true
  errorMessage.value = ''
  message.value = ''
  try {
    product.value = await api.product(props.id)
    const firstAvailableSku = product.value.skus?.find((sku) => sku.availableStock > 0) || product.value.skus?.[0]
    selectedSkuId.value = firstAvailableSku?.id || ''
    quantity.value = 1

    // 商品详情允许游客访问；只有存在买家令牌时才查询个人收藏状态。
    if (localStorage.getItem('yougou_buyer_access_token')) {
      try {
        const status = await api.favoriteStatus(props.id)
        favorited.value = Boolean(status?.favorited)
      } catch (_) {
        // 收藏状态属于辅助信息，加载失败不应阻断商品详情和加购操作。
        favorited.value = false
      }
    } else {
      favorited.value = false
    }
  } catch (error) {
    product.value = null
    errorMessage.value = error.message || '商品详情加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function toggleFavorite() {
  if (!localStorage.getItem('yougou_buyer_access_token')) {
    router.push({ path: '/login', query: { redirect: `/product/${props.id}` } })
    return
  }

  favoriteLoading.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    if (favorited.value) {
      await api.removeFavorite(props.id)
      favorited.value = false
      message.value = '已取消收藏'
    } else {
      await api.addFavorite(props.id)
      favorited.value = true
      message.value = '已加入收藏'
    }
  } catch (error) {
    errorMessage.value = error.message || '收藏操作失败，请稍后重试'
  } finally {
    favoriteLoading.value = false
  }
}

function changeQuantity(step) {
  const stock = selectedSku.value?.availableStock || 1
  quantity.value = Math.min(stock, Math.max(1, quantity.value + step))
}

async function addCart() {
  if (!localStorage.getItem('yougou_buyer_access_token')) {
    router.push({ path: '/login', query: { redirect: `/product/${props.id}` } })
    return
  }
  if (!selectedSku.value || selectedSku.value.availableStock < 1) {
    errorMessage.value = '当前商品规格暂时无库存'
    return
  }

  submitting.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    await api.addCart(selectedSku.value.id, quantity.value)
    message.value = '已加入购物车'
  } catch (error) {
    errorMessage.value = error.message || '加入购物车失败'
  } finally {
    submitting.value = false
  }
}

async function contactMerchant() {
  if (!localStorage.getItem('yougou_buyer_access_token')) {
    router.push({ path: '/login', query: { redirect: `/product/${props.id}` } })
    return
  }
  contacting.value = true
  errorMessage.value = ''
  try {
    const conversation = await api.createChatConversation(product.value.merchantId)
    router.push(`/messages/${conversation.id}`)
  } catch (error) {
    errorMessage.value = error.message || '暂时无法联系商家'
  } finally {
    contacting.value = false
  }
}

watch(() => props.id, () => {
  // 商品详情和公开评价互不依赖，并发加载可减少首屏等待时间。
  loadProduct()
  loadReviews(1)
}, { immediate: true })
</script>

<template>
  <section class="detail">
    <p v-if="loading" class="muted">商品加载中…</p>
    <p v-else-if="errorMessage && !product" class="error">{{ errorMessage }}</p>
    <template v-else-if="product">
      <img v-if="product.mainImage" class="detail-image" :src="product.mainImage" :alt="product.title">
      <div v-else class="detail-image product-image-placeholder"></div>
      <div class="detail-info">
        <RouterLink class="merchant" :to="`/merchant/${product.merchantId}`">查看商家</RouterLink>
        <h1>{{ product.title }}</h1>
        <p class="price">{{ money(selectedSku?.priceCent) }}</p>
        <p class="description">{{ product.description || '暂无商品详情。' }}</p>

        <label class="field-label">选择规格
          <select v-model="selectedSkuId" class="select-input" @change="quantity = 1">
            <option v-for="sku in product.skus" :key="sku.id" :value="sku.id" :disabled="sku.availableStock < 1">
              {{ formatSpecs(sku.specsJson) }} · {{ money(sku.priceCent) }} · 库存 {{ sku.availableStock }}
            </option>
          </select>
        </label>

        <div class="quantity">
          <button @click="changeQuantity(-1)">−</button><span>{{ quantity }}</span><button :disabled="quantity >= (selectedSku?.availableStock || 0)" @click="changeQuantity(1)">+</button>
        </div>
        <button class="primary-button" :disabled="submitting || !selectedSku || selectedSku.availableStock < 1" @click="addCart">
          {{ selectedSku?.availableStock > 0 ? (submitting ? '加入中…' : '加入购物车') : '暂时无货' }}
        </button>
        <button class="secondary-button" type="button" :disabled="contacting" @click="contactMerchant">
          {{ contacting ? '正在进入会话…' : '联系商家' }}
        </button>
        <button
          class="favorite-button"
          :class="{ active: favorited }"
          type="button"
          :aria-pressed="favorited"
          :disabled="favoriteLoading"
          @click="toggleFavorite"
        >
          <span aria-hidden="true">{{ favorited ? '♥' : '♡' }}</span>
          {{ favoriteLoading ? '处理中…' : (favorited ? '已收藏' : '收藏商品') }}
        </button>
        <p v-if="message" class="success">{{ message }}</p>
        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      </div>
    </template>
  </section>

  <section v-if="product" class="content product-reviews">
    <header class="product-review-head">
      <div>
        <p class="eyebrow">VERIFIED REVIEWS</p>
        <h2>商品评价</h2>
      </div>
      <div class="review-summary" :aria-label="`平均评分 ${averageScore.toFixed(1)} 星`">
        <strong>{{ reviewTotal ? averageScore.toFixed(1) : '-' }}</strong>
        <span class="review-stars">{{ reviewStars(Math.round(averageScore)) }}</span>
        <small class="muted">{{ reviewTotal }} 条真实购买评价</small>
      </div>
    </header>

    <p v-if="reviewsLoading" class="muted">评价加载中…</p>
    <p v-else-if="reviewError" class="error">{{ reviewError }}</p>
    <div v-else-if="!reviews.length" class="review-empty muted">暂时还没有评价，完成订单后可以发表第一条评价。</div>
    <div v-else class="public-review-list">
      <article v-for="review in reviews" :key="review.id" class="public-review-item">
        <div class="reviewer-avatar">
          <img v-if="review.reviewerAvatarUrl" :src="review.reviewerAvatarUrl" :alt="review.reviewerNickname">
          <span v-else>{{ review.reviewerNickname?.slice(0, 1) || '购' }}</span>
        </div>
        <div>
          <header>
            <strong>{{ review.reviewerNickname }}</strong>
            <span class="review-stars" :aria-label="`${review.score} 星`">{{ reviewStars(review.score) }}</span>
          </header>
          <p>{{ review.content || '用户未填写文字评价' }}</p>
          <small class="muted">{{ formatSpecs(review.specsJson) }} · {{ formatReviewTime(review.createdAt) }}</small>
        </div>
      </article>
    </div>

    <nav v-if="reviewTotalPages > 1" class="pagination" aria-label="评价分页">
      <button type="button" :disabled="reviewPage === 1 || reviewsLoading" @click="loadReviews(reviewPage - 1)">上一页</button>
      <span>第 {{ reviewPage }} / {{ reviewTotalPages }} 页，共 {{ reviewTotal }} 条</span>
      <button type="button" :disabled="reviewPage === reviewTotalPages || reviewsLoading" @click="loadReviews(reviewPage + 1)">下一页</button>
    </nav>
  </section>
</template>
