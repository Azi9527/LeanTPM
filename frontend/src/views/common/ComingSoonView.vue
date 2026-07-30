<template>
  <div class="surface-card empty-state">
    <el-result icon="info" title="模块将在后续阶段建设" sub-title="当前优先完成安全、权限与基础工程能力。">
      <template #extra><el-button type="primary" @click="$router.push('/dashboard')">返回工作台</el-button></template>
    </el-result>
  </div>
</template>
