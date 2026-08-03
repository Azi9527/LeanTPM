<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { mobileApi, type MobileEquipmentContext } from '@/api/mobile'
import { inspectionApi } from '@/api/inspection'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const creating = ref(false)
const createVisible = ref(false)
const createKey = ref('')
const context = ref<MobileEquipmentContext | null>(null)
const createForm = reactive({
  schemeVersionId: undefined as number | undefined,
  plannedStartTime: '',
  dueTime: '',
  assigneeUserIds: [] as number[],
  teamCode: '',
  remark: '',
})

onMounted(async () => {
  loading.value = true
  try {
    context.value = await mobileApi.equipment(String(route.params.token))
  } catch (error) {
    ElMessage.error(errorMessage(error, '设备二维码无效或无权访问'))
  } finally {
    loading.value = false
  }
})

function dateTime(value?: string): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

function localDateTime(value: Date): string {
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
    + `T${pad(value.getHours())}:${pad(value.getMinutes())}`
}

function openCreate() {
  if (!context.value?.inspectionSchemes.length) {
    ElMessage.warning('该设备没有可用的已发布点检方案')
    return
  }
  const now = new Date()
  const due = new Date(now)
  due.setHours(23, 59, 0, 0)
  const me = context.value.assignees.find((item) => item.userId === auth.user?.id)
  Object.assign(createForm, {
    schemeVersionId: context.value.inspectionSchemes[0]?.schemeVersionId,
    plannedStartTime: localDateTime(now),
    dueTime: localDateTime(due),
    assigneeUserIds: auth.user?.id ? [auth.user.id] : [],
    teamCode: me?.teamCode || '',
    remark: '设备扫码手工创建',
  })
  const random = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  createKey.value = `mobile-create-${random}`
  createVisible.value = true
}

async function createInspectionTask() {
  if (!context.value || !createForm.schemeVersionId || !createForm.dueTime) {
    ElMessage.warning('请选择点检方案并填写截止时间')
    return
  }
  if (!createForm.assigneeUserIds.length) {
    ElMessage.warning('请至少选择一名执行人')
    return
  }
  creating.value = true
  try {
    const result = await inspectionApi.createTask({
      equipmentId: context.value.equipment.equipmentId,
      schemeVersionId: createForm.schemeVersionId,
      plannedDate: createForm.plannedStartTime.slice(0, 10),
      plannedStartTime: createForm.plannedStartTime,
      dueTime: createForm.dueTime,
      assigneeUserIds: createForm.assigneeUserIds,
      teamCode: createForm.teamCode || null,
      backfill: false,
      remark: createForm.remark || null,
    }, createKey.value)
    createVisible.value = false
    ElMessage.success('点检任务已创建')
    await router.push(`/mobile/inspection?taskId=${result.id}`)
  } catch (error) {
    ElMessage.error(errorMessage(error, '创建点检任务失败'))
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="equipment-page">
    <template v-if="context">
      <section class="equipment-hero">
        <div class="status-dot" :style="{ background: context.equipment.statusColor }"></div>
        <p class="mono">{{ context.equipment.equipmentCode }}</p>
        <h1>{{ context.equipment.equipmentName }}</h1>
        <el-tag :color="context.equipment.statusColor" effect="dark">
          {{ context.equipment.statusName }}
        </el-tag>
      </section>

      <section class="detail-card">
        <div><span>设备分类</span><strong>{{ context.equipment.categoryName }}</strong></div>
        <div><span>所属组织</span><strong>{{ context.equipment.organizationName }}</strong></div>
        <div><span>安装位置</span><strong>{{ context.equipment.locationName }}</strong></div>
        <div><span>设备负责人</span><strong>{{ context.equipment.responsibleName || '未设置' }}</strong></div>
        <div><span>状态开始</span><strong>{{ dateTime(context.equipment.statusSince) }}</strong></div>
      </section>

      <section class="action-card">
        <h2>现场作业</h2>
        <el-button type="primary" size="large" :disabled="!context.inspectionSchemes.length" @click="openCreate">
          创建点检任务
        </el-button>
        <el-button size="large" disabled>保养任务（尚未开发）</el-button>
        <el-button size="large" disabled>故障报修（尚未开发）</el-button>
        <p v-if="!context.inspectionSchemes.length">该设备暂无适用且已发布的点检方案</p>
      </section>

      <section class="task-section">
        <div class="section-head"><div><h2>可执行任务</h2><p>仅展示分派给你的未关闭任务</p></div><b>{{ context.activeTasks.length }}</b></div>
        <button
          v-for="task in context.activeTasks"
          :key="`${task.workflowType}-${task.taskId}`"
          type="button"
          class="task-link"
          @click="router.push(task.routePath)"
        >
          <span class="task-type" :class="task.workflowType.toLowerCase()">
            {{ task.workflowType === 'INSPECTION' ? '点检' : '维保' }}
          </span>
          <div><strong>{{ task.taskCode }}</strong><p>{{ task.schemeName }}</p><small>截止 {{ dateTime(task.dueTime) }}</small></div>
          <el-icon><ArrowRight /></el-icon>
        </button>
        <el-empty v-if="!context.activeTasks.length" description="该设备当前没有分派给你的任务" />
      </section>

      <el-dialog v-model="createVisible" title="扫码创建点检任务" width="min(92vw, 520px)">
        <el-form label-position="top">
          <el-form-item label="点检方案" required>
            <el-select v-model="createForm.schemeVersionId" filterable style="width: 100%">
              <el-option
                v-for="scheme in context.inspectionSchemes"
                :key="scheme.schemeVersionId"
                :label="`${scheme.schemeName}（${scheme.schemeCode}）`"
                :value="scheme.schemeVersionId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="执行人员" required>
            <el-select v-model="createForm.assigneeUserIds" multiple filterable collapse-tags style="width: 100%">
              <el-option
                v-for="user in context.assignees"
                :key="user.userId"
                :label="`${user.realName}（${user.username}）`"
                :value="user.userId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="班组">
            <el-select v-model="createForm.teamCode" clearable filterable style="width: 100%">
              <el-option
                v-for="team in context.teams"
                :key="team.teamCode"
                :label="team.teamName"
                :value="team.teamCode"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="计划开始" required>
            <el-date-picker v-model="createForm.plannedStartTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
          </el-form-item>
          <el-form-item label="截止时间" required>
            <el-date-picker v-model="createForm.dueTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="createForm.remark" type="textarea" :rows="2" maxlength="1000" show-word-limit />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="createInspectionTask">创建并进入任务</el-button>
        </template>
      </el-dialog>
    </template>
    <el-result v-else-if="!loading" icon="warning" title="无法查看设备" sub-title="二维码可能失效，或设备不在你的数据权限范围内">
      <template #extra><el-button type="primary" @click="router.replace('/mobile/scan')">重新扫码</el-button></template>
    </el-result>
  </div>
</template>

<style scoped>
.equipment-page { display: grid; gap: 16px; }
.equipment-hero, .detail-card, .action-card, .task-section { padding: 20px; border-radius: 20px; background: white; box-shadow: 0 8px 24px rgba(23, 58, 69, .07); }
.equipment-hero { position: relative; overflow: hidden; }
.equipment-hero::after { position: absolute; right: -30px; bottom: -44px; width: 140px; height: 140px; border-radius: 50%; background: #e7f5f7; content: ""; }
.status-dot { width: 10px; height: 10px; margin-bottom: 16px; border-radius: 50%; box-shadow: 0 0 0 6px rgba(15, 115, 137, .08); }
.equipment-hero p, .equipment-hero h1 { margin: 0; }
.equipment-hero h1 { margin: 5px 0 14px; font-size: 24px; }
.detail-card { display: grid; gap: 15px; }
.detail-card div { display: flex; justify-content: space-between; gap: 18px; }
.detail-card span { color: #7b8c93; font-size: 13px; }
.detail-card strong { text-align: right; font-size: 14px; }
.action-card { display: grid; gap: 10px; }
.action-card h2, .action-card p { margin: 0; }
.action-card .el-button { width: 100%; margin: 0; }
.action-card p { color: #85949a; font-size: 12px; }
.section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.section-head h2, .section-head p { margin: 0; }
.section-head p { margin-top: 4px; color: #85949a; font-size: 12px; }
.section-head b { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; color: var(--tpm-primary); background: var(--tpm-primary-soft); }
.task-link { display: grid; width: 100%; grid-template-columns: auto 1fr auto; align-items: center; gap: 12px; padding: 14px 0; border: 0; border-top: 1px solid #edf1f2; text-align: left; background: transparent; }
.task-type { padding: 7px; border-radius: 10px; color: var(--tpm-primary); background: var(--tpm-primary-soft); font-size: 12px; }
.task-type.maintenance { color: #a25b10; background: #fff3e5; }
.task-link p, .task-link small { margin: 3px 0 0; color: #7d8d94; font-size: 12px; }
</style>
