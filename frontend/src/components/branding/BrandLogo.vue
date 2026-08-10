<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useBranding } from '@/branding/branding'

const props = withDefaults(defineProps<{
  compact?: boolean
  height?: number
  light?: boolean
}>(), {
  compact: false,
  height: 44,
  light: false,
})

const branding = useBranding()
const failed = ref(false)
const initials = computed(() => branding.shortName.slice(0, 2))
const imageSource = computed(() => props.compact
  ? '/branding/baoshan-mining-mark.png?v=20260810'
  : branding.logoUrl)
watch(imageSource, () => { failed.value = false })
</script>

<template>
  <span
    class="brand-logo"
    :class="{ compact, light, fallback: failed }"
    :style="{ '--brand-logo-height': `${height}px` }"
  >
    <img v-if="!failed" :src="imageSource" :alt="`${branding.shortName} Logo`" @error="failed = true" />
    <strong v-else>{{ initials }}</strong>
  </span>
</template>

<style scoped>
.brand-logo {
  box-sizing: border-box;
  display: inline-flex;
  overflow: hidden;
  align-items: center;
  vertical-align: middle;
  line-height: 0;
  flex: 0 0 auto;
  width: min(100%, calc(var(--brand-logo-height) * 3.08));
  height: var(--brand-logo-height);
  border: 1px solid rgba(62, 58, 57, .1);
  border-radius: calc(var(--brand-logo-height) * .14);
  background: #fff;
}
.brand-logo img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.brand-logo.compact {
  width: var(--brand-logo-height);
}
.brand-logo.light { box-shadow: 0 6px 20px rgba(0, 0, 0, .14); }
.brand-logo.fallback {
  justify-content: center;
  color: #fff;
  background: linear-gradient(145deg, var(--tpm-primary), var(--tpm-secondary));
}
.brand-logo.fallback strong { font-size: calc(var(--brand-logo-height) * .3); }
</style>
