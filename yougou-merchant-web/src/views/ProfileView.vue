<script setup>
import { onMounted, reactive, ref } from 'vue'
import { merchantApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'
const form=reactive({name:'',description:'',logo:null}); const loading=ref(false); const auth=useAuthStore()
onMounted(async()=>{ const p=await merchantApi.profile(); form.name=p.name; form.description=p.description||'' })
function pick(file){form.logo=file.raw}
async function save(){const data=new FormData();data.append('name',form.name);data.append('description',form.description);if(form.logo)data.append('logo',form.logo);loading.value=true;try{auth.profile=await merchantApi.updateProfile(data);ElMessage.success('店铺资料已保存')}catch(e){ElMessage.error(e.message)}finally{loading.value=false}}
</script>
<template><el-card class="page-card"><el-form label-width="90px" style="max-width:640px"><el-form-item label="店铺名称"><el-input v-model="form.name" /></el-form-item><el-form-item label="店铺简介"><el-input v-model="form.description" type="textarea" :rows="5" /></el-form-item><el-form-item label="更换 Logo"><el-upload :auto-upload="false" :limit="1" :on-change="pick"><el-button>选择图片</el-button></el-upload></el-form-item><el-button type="primary" :loading="loading" @click="save">保存</el-button></el-form></el-card></template>
