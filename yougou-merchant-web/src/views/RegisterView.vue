<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { merchantApi } from '../api'
import { useAuthStore } from '../stores/auth'
const form = reactive({ phone: '', code: '', name: '', description: '', logo: null })
const sending = ref(false); const loading = ref(false); const router = useRouter(); const auth = useAuthStore()
async function sendCode() { sending.value = true; try { await merchantApi.sendCode(form.phone, 'REGISTER'); ElMessage.success('验证码已发送') } catch (e) { ElMessage.error(e.message) } finally { sending.value = false } }
function selectLogo(file) { form.logo = file.raw }
async function register() { if (!form.logo) return ElMessage.warning('请选择店铺 Logo'); const data = new FormData(); Object.entries(form).forEach(([k,v]) => { if (v !== null) data.append(k,v) }); loading.value=true; try { const result=await merchantApi.register(data); auth.saveSession(result); await router.replace('/dashboard') } catch(e){ ElMessage.error(e.message) } finally { loading.value=false } }
</script>
<template><div class="auth-page"><el-form class="auth-card" label-position="top" @submit.prevent="register"><h1>开通商家</h1><el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item><el-form-item label="验证码"><el-input v-model="form.code"><template #append><el-button :loading="sending" @click="sendCode">获取验证码</el-button></template></el-input></el-form-item><el-form-item label="店铺名称"><el-input v-model="form.name" /></el-form-item><el-form-item label="店铺简介"><el-input v-model="form.description" type="textarea" /></el-form-item><el-form-item label="Logo"><el-upload :auto-upload="false" :limit="1" :on-change="selectLogo"><el-button>选择图片</el-button></el-upload></el-form-item><el-button native-type="submit" type="primary" :loading="loading" style="width:100%">立即开通</el-button><p><RouterLink to="/login">返回登录</RouterLink></p></el-form></div></template>
