<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { faultApi, type FaultStatistics } from '@/api/fault'
import { errorMessage } from '@/utils/http'
const data=ref<FaultStatistics>();onMounted(async()=>{try{data.value=await faultApi.statistics()}catch(error){ElMessage.error(errorMessage(error,'故障统计加载失败'))}})
</script>
<template><div class="page-shell"><header class="page-header"><div><h1>故障统计</h1><p>跟踪报修积压、在修工单、待验收、工时与材料费用。</p></div></header><section class="stats"><article><span>待处理报修</span><b>{{data?.openReports??0}}</b></article><article><span>在修工单</span><b>{{data?.activeRepairs??0}}</b></article><article><span>待验收</span><b>{{data?.pendingAcceptance??0}}</b></article><article><span>已关闭</span><b>{{data?.closedRepairs??0}}</b></article><article><span>材料费用</span><b>¥{{Number(data?.materialCost??0).toFixed(2)}}</b></article><article><span>平均维修工时</span><b>{{Number(data?.averageRepairMinutes??0).toFixed(1)}} 分</b></article></section></div></template>
<style scoped>.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px}.stats article{padding:24px;border-radius:16px;background:white;box-shadow:var(--tpm-shadow)}.stats span,.stats b{display:block}.stats span{color:var(--tpm-text-secondary)}.stats b{margin-top:12px;font-size:28px;color:var(--tpm-primary)}@media(max-width:760px){.stats{grid-template-columns:1fr 1fr}}</style>
