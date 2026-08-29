<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { merchantApi } from '../api'
import { ElMessage } from 'element-plus'
const router=useRouter();const rows=ref([]);const total=ref(0);const query=reactive({page:1,pageSize:20,keyword:'',status:null,sort:'latest'})
const money=(c)=>`¥${(Number(c||0)/100).toFixed(2)}`
async function load(){try{const d=await merchantApi.products(query);rows.value=d.records||[];total.value=Number(d.total||0);query.page=Number(d.page||query.page)}catch(e){ElMessage.error(e.message)}}
async function toggle(row){try{await merchantApi.updateProductStatus(row.id,row.status===1?0:1);await load()}catch(e){ElMessage.error(e.message)}}
onMounted(load)
</script>
<template><el-card><div class="toolbar"><el-input v-model="query.keyword" placeholder="商品名称" clearable style="width:220px"/><el-select v-model="query.status" placeholder="全部状态" clearable style="width:140px"><el-option label="已上架" :value="1"/><el-option label="已下架" :value="0"/></el-select><el-button @click="query.page=1;load()">查询</el-button><el-button type="primary" @click="router.push('/products/new')">新增商品</el-button></div><el-table :data="rows"><el-table-column label="商品"><template #default="s"><div style="display:flex;align-items:center;gap:10px"><el-image :src="s.row.mainImage" style="width:48px;height:48px" fit="cover"/><span>{{ s.row.title }}</span></div></template></el-table-column><el-table-column label="价格"><template #default="s">{{ money(s.row.minPriceCent) }} - {{ money(s.row.maxPriceCent) }}</template></el-table-column><el-table-column prop="skuCount" label="SKU"/><el-table-column label="状态"><template #default="s"><el-tag :type="s.row.status===1?'success':'info'">{{s.row.status===1?'上架':'下架'}}</el-tag></template></el-table-column><el-table-column label="操作" width="190"><template #default="s"><el-button link @click="router.push(`/products/${s.row.id}/edit`)">编辑</el-button><el-button link @click="toggle(s.row)">{{s.row.status===1?'下架':'上架'}}</el-button></template></el-table-column></el-table><div class="pagination"><el-pagination v-model:current-page="query.page" :page-size="query.pageSize" :total="total" layout="prev,pager,next,total" @current-change="load"/></div></el-card></template>
