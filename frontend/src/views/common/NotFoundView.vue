<template>
  <main class="not-found">
    <el-result icon="warning" title="页面不存在" sub-title="你访问的地址可能已变更。">
      <template #extra><el-button type="primary" @click="$router.push('/dashboard')">返回工作台</el-button></template>
    </el-result>
  </main>
</template>

<style scoped>
.not-found { display: grid; place-items: center; min-height: 100vh; background: #f2f5f7; }
</style>
