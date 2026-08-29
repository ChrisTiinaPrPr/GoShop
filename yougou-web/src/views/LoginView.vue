<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, saveSession } from '../api'

const phone = ref('')
const code = ref('')
const sent = ref(false)
const seconds = ref(0)
const message = ref('')
const error = ref('')
const submitting = ref(false)
const router = useRouter()
const route = useRoute()

const validPhone = () => /^1[3-9]\d{9}$/.test(phone.value)

function getSafeRedirect() {
  const redirect = route.query.redirect

  // 只接受站内相对路径，避免 URL 参数被利用来跳转到外部站点。
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : '/'
}

async function sendCode() {
  error.value = ''
  if (!validPhone()) {
    error.value = '请输入正确的 11 位手机号'
    return
  }

  try {
    await api.sendCode(phone.value)
    sent.value = true
    seconds.value = 60
    const timer = setInterval(() => {
      if (--seconds.value <= 0) clearInterval(timer)
    }, 1000)
    message.value = '验证码已发送，请注意查收'
  } catch (requestError) {
    error.value = requestError.message
  }
}

async function login() {
  error.value = ''
  if (!validPhone() || !/^\d{6}$/.test(code.value)) {
    error.value = '请填写手机号和 6 位验证码'
    return
  }

  submitting.value = true
  try {
    const data = await api.login(phone.value, code.value)
    if (!data?.accessToken) {
      throw new Error('登录响应中缺少 accessToken')
    }

    // 先保存令牌，再执行路由跳转；否则路由守卫会再次把用户送回登录页。
    saveSession(data.accessToken)
    await router.replace(getSafeRedirect())
  } catch (requestError) {
    error.value = requestError.message || '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-page">
    <form class="auth-card" @submit.prevent="login">
      <p class="eyebrow">WELCOME TO YOUGOU</p>
      <h1>欢迎回来</h1>
      <p class="muted">手机号验证后即可登录或自动注册</p>

      <label>
        手机号
        <input v-model.trim="phone" inputmode="numeric" maxlength="11" placeholder="请输入手机号">
      </label>

      <label>
        验证码
        <div class="code-row">
          <input v-model.trim="code" inputmode="numeric" maxlength="6" placeholder="6 位验证码">
          <button type="button" :disabled="seconds > 0" @click="sendCode">
            {{ seconds ? `${seconds}s 后重发` : '获取验证码' }}
          </button>
        </div>
      </label>

      <p v-if="message" class="success">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
      <button class="primary-button submit" type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登录 / 注册' }}
      </button>
    </form>
  </section>
</template>
