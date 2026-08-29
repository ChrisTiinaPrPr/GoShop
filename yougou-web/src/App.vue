<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { api, clearSession } from './api'

const router = useRouter()
const route = useRoute()
const loggedIn = ref(Boolean(localStorage.getItem('yougou_buyer_access_token')))
const currentUser = ref(null)

async function loadCurrentUser() {
  if (!loggedIn.value) {
    currentUser.value = null
    return
  }

  try {
    currentUser.value = await api.profile()
  } catch (_) {
    // 资料加载失败不阻断页面渲染；401 会由统一请求方法清理登录态。
    currentUser.value = null
  }
}

function syncAuthState() {
  loggedIn.value = Boolean(localStorage.getItem('yougou_buyer_access_token'))
  loadCurrentUser()
}

function syncProfileState(event) {
  // 编辑资料页保存成功后直接使用最新响应，避免额外发起一次请求。
  currentUser.value = event.detail || null
}

onMounted(() => {
  window.addEventListener('yougou-auth-changed', syncAuthState)
  window.addEventListener('yougou-profile-changed', syncProfileState)
  loadCurrentUser()
})

onUnmounted(() => {
  window.removeEventListener('yougou-auth-changed', syncAuthState)
  window.removeEventListener('yougou-profile-changed', syncProfileState)
})

async function logout() {
  try {
    // 即使服务端令牌已失效，也必须清理本地登录态。
    await api.logout()
  } catch (_) {
    // 本地退出不依赖服务端响应。
  } finally {
    clearSession()
    router.replace('/')
  }
}
</script>

<template>
  <header class="topbar">
    <RouterLink class="brand" to="/"><span>优</span>购商城</RouterLink>
    <nav>
      <RouterLink to="/">发现好物</RouterLink>
      <RouterLink to="/cart">购物车</RouterLink>
      <RouterLink to="/orders">我的订单</RouterLink>
      <RouterLink v-if="loggedIn" to="/favorites">我的收藏</RouterLink>
      <RouterLink v-if="loggedIn" to="/assistant">购物助手</RouterLink>
      <RouterLink v-if="loggedIn" to="/messages">消息</RouterLink>
      <RouterLink v-if="loggedIn" to="/addresses">收货地址</RouterLink>
    </nav>
    <div class="account">
      <RouterLink v-if="!loggedIn" class="login-link" to="/login">登录 / 注册</RouterLink>
      <template v-else>
        <RouterLink class="account-profile" to="/profile" aria-label="编辑个人资料">
          <img
            v-if="currentUser?.avatarUrl"
            class="account-avatar"
            :src="currentUser.avatarUrl"
            alt="用户头像"
          >
          <span v-else class="account-avatar account-avatar-placeholder">
            {{ currentUser?.nickname?.slice(0, 1) || '我' }}
          </span>
          <span class="account-nickname">{{ currentUser?.nickname || '个人资料' }}</span>
        </RouterLink>
        <button class="text-button" @click="logout">退出登录</button>
      </template>
    </div>
  </header>
  <main><RouterView /></main>
  <footer v-if="!route.meta.fullHeight">优购商城 · 为每一份喜欢认真挑选</footer>
</template>
