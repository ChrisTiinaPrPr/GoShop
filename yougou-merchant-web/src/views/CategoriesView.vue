<script setup>
import { onMounted, reactive, ref } from 'vue'
import { merchantApi } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
const rows=ref([]);const dialog=ref(false);const editing=ref(null);const form=reactive({name:'',parentId:null,sort:0,status:1})
async function load(){rows.value=await merchantApi.categories()}
function open(row){editing.value=row||null;Object.assign(form,row?{name:row.name,parentId:row.parentId,sort:row.sort,status:row.status}:{name:'',parentId:null,sort:0,status:1});dialog.value=true}
async function save(){try{if(editing.value)await merchantApi.updateCategory(editing.value.id,form);else await merchantApi.createCategory({name:form.name,parentId:form.parentId,sort:form.sort});dialog.value=false;await load();ElMessage.success('已保存')}catch(e){ElMessage.error(e.message)}}
async function remove(row){await ElMessageBox.confirm('确认删除该分类？');try{await merchantApi.deleteCategory(row.id);await load()}catch(e){ElMessage.error(e.message)}}
onMounted(()=>load().catch(e=>ElMessage.error(e.message)))
</script>
<template><el-card><div class="toolbar"><el-button type="primary" @click="open(null)">新增分类</el-button></div><el-table :data="rows" row-key="id"><el-table-column prop="name" label="分类名称"/><el-table-column prop="sort" label="排序"/><el-table-column label="状态"><template #default="s">{{ s.row.status===1?'启用':'停用' }}</template></el-table-column><el-table-column label="操作"><template #default="s"><el-button link @click="open(s.row)">编辑</el-button><el-button link type="danger" @click="remove(s.row)">删除</el-button></template></el-table-column></el-table></el-card><el-dialog v-model="dialog" :title="editing?'编辑分类':'新增分类'" width="480"><el-form label-width="80"><el-form-item label="名称"><el-input v-model="form.name"/></el-form-item><el-form-item label="父分类 ID"><el-input-number v-model="form.parentId" :min="0"/></el-form-item><el-form-item label="排序"><el-input-number v-model="form.sort" :min="0"/></el-form-item><el-form-item v-if="editing" label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0"/></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template></el-dialog></template>
