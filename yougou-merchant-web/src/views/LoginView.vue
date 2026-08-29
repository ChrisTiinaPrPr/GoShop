<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { merchantApi } from '../api'
import { useAuthStore } from '../stores/auth'

const form = reactive({ phone: '', code: '' })
const sending = ref(false)
const loading = ref(false)
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
async function sendCode() { sending.value = true; try { await merchantApi.sendCode(form.phone); ElMessage.success('验证码已发送') } catch (e) { ElMessage.error(e.message) } finally { sending.value = false } }
async function login() { loading.value = true; try { const result = await merchantApi.login(form.phone, form.code); auth.saveSession(result); await router.replace(route.query.redirect || '/dashboard') } catch (e) { ElMessage.error(e.message) } finally { loading.value = false } }
</script>
<template><div class="auth-page"><el-form class="auth-card" label-position="top" @submit.prevent="login"><h1>商家登录</h1><el-form-item label="手机号"><el-input v-model="form.phone" maxlength="11" /></el-form-item><el-form-item label="验证码"><el-input v-model="form.code" maxlength="6"><template #append><el-button :loading="sending" @click="sendCode">获取验证码</el-button></template></el-input></el-form-item><el-button native-type="submit" type="primary" :loading="loading" style="width:100%">登录商家后台</el-button><p><RouterLink to="/register">尚未开店？立即开通</RouterLink></p></el-form></div></template>
