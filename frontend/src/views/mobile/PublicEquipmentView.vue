<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { equipmentApi, type PublicEquipmentView } from '@/api/equipment'
import { errorMessage } from '@/utils/http'

const route = useRoute()
const loading = ref(true)
const error = ref('')
const equipment = ref<PublicEquipmentView | null>(null)

const statusLabels: Record<string, string> = {
  NOT_ENABLED: '未启用',
  IDLE: '空闲',
  RUNNING: '运行',
  COMMISSIONING: '调试',
  CHANGEOVER: '换型',
  MAINTENANCE: '保养',
  INSPECTION: '点检',
  FAULT: '故障',
  REPAIR: '维修',
  STOPPED: '停机',
  SCRAPPED: '报废',
  OFFLINE: '离线',
}

const statusClass = computed(() => {
  if (equipment.value?.currentStatusCode === 'RUNNING') return 'running'
  if (['FAULT', 'REPAIR'].includes(equipment.value?.currentStatusCode || '')) return 'danger'
  return 'neutral'
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    equipment.value = await equipmentApi.publicView(String(route.params.token))
  } catch (caught) {
    error.value = errorMessage(caught, '二维码无效、已解绑或设备已停用')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="mobile-page">
    <header class="brand">
      <span class="brand-mark">TPM</span>
      <div>
        <strong>LeanTPM</strong>
        <small>精益设备管理</small>
      </div>
    </header>

    <section v-if="loading" class="mobile-card">
      <el-skeleton :rows="6" animated />
    </section>

    <section v-else-if="error" class="mobile-card error-state">
      <div class="error-icon">!</div>
      <h1>无法识别设备</h1>
      <p>{{ error }}</p>
      <el-button type="primary" plain @click="load">重新加载</el-button>
    </section>

    <template v-else-if="equipment">
      <section class="equipment-hero" :class="statusClass">
        <span class="eyebrow">设备扫码信息</span>
        <h1>{{ equipment.equipmentName }}</h1>
        <span class="equipment-code">{{ equipment.equipmentCode }}</span>
        <div class="status-pill">
          <i />
          {{ statusLabels[equipment.currentStatusCode] || equipment.currentStatusCode }}
        </div>
      </section>

      <section class="mobile-card detail-list">
        <div>
          <span>设备分类</span>
          <strong>{{ equipment.categoryName }}</strong>
        </div>
        <div>
          <span>当前位置</span>
          <strong>{{ equipment.locationName }}</strong>
        </div>
        <div>
          <span>状态开始时间</span>
          <strong>{{ equipment.statusSince || '—' }}</strong>
        </div>
      </section>

      <p class="security-note">
        此页面由随机安全令牌访问，不展示责任人、资产价值、技术参数等敏感信息。
      </p>
    </template>
  </main>
</template>

<style scoped>
.mobile-page {
  min-height: 100vh;
  padding: 20px;
  color: #172033;
  background:
    radial-gradient(circle at 100% 0, rgba(56, 189, 248, .2), transparent 38%),
    linear-gradient(180deg, #eef7fb, #f7fafc 48%, #edf2f7);
}

.brand {
  display: flex;
  gap: 10px;
  align-items: center;
  max-width: 520px;
  margin: 0 auto 22px;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  color: #fff;
  background: #075985;
  font-weight: 800;
  font-size: 13px;
}

.brand strong,
.brand small {
  display: block;
}

.brand small {
  margin-top: 2px;
  color: #64748b;
}

.equipment-hero,
.mobile-card {
  max-width: 520px;
  margin: 0 auto 16px;
  border-radius: 22px;
  box-shadow: 0 14px 40px rgba(15, 23, 42, .09);
}

.equipment-hero {
  position: relative;
  overflow: hidden;
  padding: 28px;
  color: #fff;
  background: linear-gradient(135deg, #334155, #0f172a);
}

.equipment-hero.running {
  background: linear-gradient(135deg, #047857, #064e3b);
}

.equipment-hero.danger {
  background: linear-gradient(135deg, #dc2626, #7f1d1d);
}

.equipment-hero::after {
  content: "";
  position: absolute;
  right: -50px;
  bottom: -70px;
  width: 180px;
  height: 180px;
  border: 28px solid rgba(255, 255, 255, .08);
  border-radius: 50%;
}

.eyebrow {
  font-size: 13px;
  opacity: .75;
}

.equipment-hero h1 {
  margin: 12px 0 6px;
  font-size: clamp(28px, 8vw, 40px);
  line-height: 1.2;
}

.equipment-code {
  font-family: "SFMono-Regular", Consolas, monospace;
  opacity: .78;
}

.status-pill {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  margin-top: 28px;
  padding: 9px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, .16);
}

.status-pill i {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 5px rgba(255, 255, 255, .16);
}

.mobile-card {
  padding: 22px;
  background: rgba(255, 255, 255, .92);
}

.detail-list > div {
  display: grid;
  gap: 5px;
  padding: 16px 0;
  border-bottom: 1px solid #e2e8f0;
}

.detail-list > div:first-child {
  padding-top: 0;
}

.detail-list > div:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.detail-list span,
.security-note {
  color: #64748b;
  font-size: 13px;
}

.security-note {
  max-width: 490px;
  margin: 24px auto;
  text-align: center;
  line-height: 1.7;
}

.error-state {
  padding: 48px 24px;
  text-align: center;
}

.error-icon {
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  margin: 0 auto;
  border-radius: 50%;
  color: #fff;
  background: #dc2626;
  font-size: 30px;
  font-weight: 800;
}
</style>
