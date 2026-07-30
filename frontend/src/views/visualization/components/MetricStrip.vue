<script setup lang="ts">
export interface MetricCard {
  label: string
  value: string | number
  hint?: string
  color: string
}

defineProps<{ metrics: MetricCard[] }>()
const emit = defineEmits<{ select: [label: string] }>()
</script>

<template>
  <section class="metric-strip">
    <button
      v-for="metric in metrics"
      :key="metric.label"
      class="metric"
      :style="{ '--metric-color': metric.color }"
      @click="emit('select', metric.label)"
    >
      <span>{{ metric.label }}</span>
      <strong>{{ metric.value }}</strong>
      <small>{{ metric.hint || '实时汇总' }}</small>
    </button>
  </section>
</template>

<style scoped>
.metric-strip {
  display: grid;
  grid-template-columns: repeat(9, minmax(105px, 1fr));
  gap: 10px;
}
.metric {
  min-width: 0;
  padding: 14px 14px 12px;
  text-align: left;
  color: #dff4ff;
  border: 1px solid color-mix(in srgb, var(--metric-color) 38%, transparent);
  border-radius: 14px;
  background:
    linear-gradient(145deg, color-mix(in srgb, var(--metric-color) 14%, transparent), transparent 70%),
    rgba(8, 25, 43, 0.9);
  cursor: pointer;
  transition: transform 0.2s ease, border-color 0.2s ease;
}
.metric:hover { transform: translateY(-2px); border-color: var(--metric-color); }
.metric span, .metric small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.metric span { color: #8ba8bf; font-size: 12px; }
.metric strong { display: block; margin: 7px 0 5px; color: var(--metric-color); font-size: 26px; line-height: 1; }
.metric small { color: #617e96; font-size: 10px; }
@media (max-width: 1400px) { .metric-strip { grid-template-columns: repeat(5, 1fr); } }
@media (max-width: 760px) { .metric-strip { grid-template-columns: repeat(2, 1fr); } }
</style>
