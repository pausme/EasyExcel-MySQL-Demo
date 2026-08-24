<template>
  <div>
    <el-row :gutter="16" class="mb16">
      <el-col :span="6"><el-statistic title="今日任务" :value="ops.todayTaskCount ?? 0" /></el-col>
      <el-col :span="6"><el-statistic title="今日失败" :value="ops.todayFailedTaskCount ?? 0" /></el-col>
      <el-col :span="6"><el-statistic title="补偿积压" :value="ops.compensationBacklogCount ?? 0" /></el-col>
      <el-col :span="6"><el-statistic title="今日上传" :value="ops.todayFileUploadCount ?? 0" /></el-col>
    </el-row>
    <el-card header="线程池快照" class="mb16">
      <el-table :data="ops.threadPools || []" stripe>
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="activeCount" label="活跃" width="80" />
        <el-table-column prop="poolSize" label="池大小" width="80" />
        <el-table-column prop="queueSize" label="队列" width="80" />
        <el-table-column prop="completedTaskCount" label="已完成" width="100" />
      </el-table>
    </el-card>
    <el-card header="存储">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="文件总存储">{{ fmtSize(ops.totalFileStorageBytes) }}</el-descriptions-item>
        <el-descriptions-item label="生成时间">{{ ops.generatedAt || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue'
import { getData } from '../api/http'

const ops = reactive({})

onMounted(async () => {
  try {
    Object.assign(ops, await getData('/api/admin/ops/overview'))
  } catch { /* 页面静默 */ }
})

function fmtSize(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB'
  return (bytes / 1073741824).toFixed(2) + ' GB'
}
</script>

<style scoped>
.mb16 { margin-bottom: 16px; }
</style>
