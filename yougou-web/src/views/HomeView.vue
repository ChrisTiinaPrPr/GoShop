<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '../api'
import { money } from '../mock'

const products = ref([])
const categories = ref([])
const loading = ref(true)
const errorMessage = ref('')
const keyword = ref('')
const categoryId = ref('')
const sort = ref('latest')
const page = ref(1)
const pageSize = 12
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function flattenCategories(nodes, result = []) {
  nodes.forEach((node) => {
    result.push(node)
    if (node.children?.length) flattenCategories(node.children, result)
  })
  return result
}

const flatCategories = computed(() => flattenCategories(categories.value))

async function loadProducts(targetPage = page.value) {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await api.products({
      page: targetPage,
      pageSize,
      categoryId: categoryId.value || undefined,
      keyword: keyword.value.trim() || undefined,
      sort: sort.value
    })
    products.value = data.records || []
    total.value = data.total || 0
    page.value = data.page || targetPage
  } catch (error) {
    products.value = []
    total.value = 0
    errorMessage.value = error.message || '商品加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function submitSearch() {
  loadProducts(1)
}

onMounted(async () => {
  try {
    categories.value = await api.categories()
  } catch (_) {
    // 分类加载失败不阻塞商品查询，页面仍可使用关键词浏览。
  }
  await loadProducts(1)
})
</script>

<template>
  <section class="hero">
    <p>有品质的日常，值得被看见</p>
    <h1>今天，也挑一件<br><em>真正喜欢的东西</em></h1>
    <RouterLink v-if="products[0]" :to="`/product/${products[0].id}`" class="primary-button">看看精选商品</RouterLink>
  </section>

  <section class="content">
    <div class="section-head">
      <div><p class="eyebrow">EXPLORE</p><h2>为你精选</h2></div>
      <form class="product-filters" @submit.prevent="submitSearch">
        <input v-model.trim="keyword" maxlength="50" placeholder="搜索商品">
        <select v-model="categoryId" @change="submitSearch">
          <option value="">全部分类</option>
          <option v-for="category in flatCategories" :key="category.id" :value="category.id">{{ category.name }}</option>
        </select>
        <select v-model="sort" @change="submitSearch">
          <option value="latest">最新上架</option>
          <option value="sales">销量优先</option>
          <option value="priceAsc">价格从低到高</option>
          <option value="priceDesc">价格从高到低</option>
        </select>
        <button class="primary-button" type="submit">搜索</button>
      </form>
    </div>

    <p v-if="loading" class="muted">商品加载中…</p>
    <p v-else-if="errorMessage" class="error">{{ errorMessage }}</p>
    <p v-else-if="!products.length" class="muted">没有找到符合条件的商品。</p>
    <div v-else class="product-grid">
      <RouterLink v-for="item in products" :key="item.id" :to="`/product/${item.id}`" class="product-card">
        <img v-if="item.mainImage" :src="item.mainImage" :alt="item.title">
        <div v-else class="product-image-placeholder"></div>
        <div class="product-body">
          <p class="merchant">商家编号：{{ item.merchantId }}</p>
          <h3>{{ item.title }}</h3>
          <strong>{{ money(item.minPriceCent) }}</strong>
          <small class="muted">已售 {{ item.salesCount || 0 }}</small>
        </div>
      </RouterLink>
    </div>

    <nav v-if="totalPages > 1" class="pagination" aria-label="商品分页">
      <button type="button" :disabled="page === 1 || loading" @click="loadProducts(page - 1)">上一页</button>
      <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 件</span>
      <button type="button" :disabled="page === totalPages || loading" @click="loadProducts(page + 1)">下一页</button>
    </nav>
  </section>
</template>
