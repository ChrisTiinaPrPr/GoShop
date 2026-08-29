<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'

const profile = ref(null)
const nickname = ref('')
const avatar = ref(null)
const loading = ref(true)
const saving = ref(false)
const message = ref('')
const errorMessage = ref('')

async function loadProfile() {
  loading.value = true
  try {
    profile.value = await api.profile()
    nickname.value = profile.value.nickname || ''
  } catch (error) {
    errorMessage.value = error.message || '个人资料加载失败'
  } finally {
    loading.value = false
  }
}

function selectAvatar(event) {
  avatar.value = event.target.files?.[0] || null
}

async function saveProfile() {
  message.value = ''
  errorMessage.value = ''
  if (!nickname.value.trim() && !avatar.value) {
    errorMessage.value = '请修改昵称或选择新头像后再保存'
    return
  }

  saving.value = true
  try {
    profile.value = await api.updateProfile({ nickname: nickname.value.trim(), avatar: avatar.value })
    nickname.value = profile.value.nickname || ''
    avatar.value = null
    // 通知顶部导航立即更新头像和昵称，无需刷新页面。
    window.dispatchEvent(new CustomEvent('yougou-profile-changed', { detail: profile.value }))
    message.value = '个人资料已保存'
  } catch (error) {
    errorMessage.value = error.message || '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

onMounted(loadProfile)
</script>

<template>
  <section class="content narrow">
    <p class="eyebrow">MY PROFILE</p>
    <h1>个人资料</h1>
    <p v-if="loading" class="muted">资料加载中…</p>
    <p v-else-if="errorMessage && !profile" class="error">{{ errorMessage }}</p>
    <form v-else-if="profile" class="form-card" @submit.prevent="saveProfile">
      <div class="profile-avatar">
        <img v-if="profile.avatarUrl" :src="profile.avatarUrl" alt="用户头像">
        <span v-else>{{ profile.nickname?.slice(0, 1) || '我' }}</span>
      </div>
      <label>手机号<input :value="profile.phone" disabled></label>
      <label>昵称<input v-model.trim="nickname" maxlength="50" placeholder="请输入昵称"></label>
      <label>头像<input type="file" accept="image/*" @change="selectAvatar"></label>
      <p class="muted">角色：{{ profile.role }}</p>
      <p v-if="message" class="success">{{ message }}</p>
      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      <button class="primary-button" :disabled="saving" type="submit">{{ saving ? '保存中…' : '保存资料' }}</button>
    </form>
  </section>
</template>
