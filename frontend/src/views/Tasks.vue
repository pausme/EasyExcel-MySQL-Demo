<template>
  <el-card header="我的异步任务">
    <el-form inline>
      <el-form-item label="类型">
        <el-select v-model="q.taskType" clearable style="width:120px">
          <el-option label="导入" value="IMPORT" />
          <el-option label="导出" value="EXPORT" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="q.status" clearable style="width:120px">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-button type="primary" @click="load(1)">查询</el-button>
      <el-button type="success" @click="submitExport" :loading="exporting">提交导出</el-button>
    </el-form>
    <el-table :data="rows" stripe v-loading="loading">
      <el-table-column prop="taskId" label="任务ID" width="280" show-overflow-tooltip />
      <el-table-column prop="taskType" label="类型" width="80" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="160">
        <template #default="{ row }">
          <el-progress :percentage="row.progressPercent || 0" :stroke-width="12" />
        </template>
      </el-table-column>
      <el-table-column prop="completedCount" label="已完成" width="90" />
      <el-table-column prop="totalCount" label="总数" width="90" />
      <el-table-column prop="retryCount" label="重试" width="60" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link size="small" @click="download(row)" v-if="row.taskType==='EXPORT'&&row.status==='SUCCESS'">下载</el-button>
          <el-button link size="small" type="warning" @click="cancel(row)" v-if="['CREATED','RUNNING'].includes(row.status)">取消</el-button>
          <el-button link size="small" type="success" @click="retry(row)" v-if="row.status==='FAILED'">重试</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="mt8" layout="total, prev, pager, next"
      :total="total" :current-page="q.pageNo" :page-size="q.pageSize"
      @current-change="p => load(p)" />
  </el-card>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getData, postData } from '../api/http'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const q = reactive({ pageNo: 1, pageSize: 20 })
const statuses = ['CREATED','RUNNING','SUCCESS','FAILED','CANCELED','EXPIRED']
let timer = null

async function load(page) {
  if (page) q.pageNo = page
  loading.value = true
  try {
    const data = await postData('/api/tasks/page', clean(q))
    rows.value = data.records || []
    total.value = data.total || 0
  } catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}

async function submitExport() {
  exporting.value = true
  try {
    await http.post('/api/excel/export')
    ElMessage.success('导出任务已提交')
    load(1)
  } catch (e) { ElMessage.error(e.message) } finally { exporting.value = false }
}

async function cancel(row) {
  try { await http.post(`/api/tasks/${row.taskId}/cancel`); load() }
  catch (e) { ElMessage.error(e.message) }
}

async function retry(row) {
  try {
    await http.post(`/api/tasks/${row.taskId}/retry`)
    ElMessage.success('已重新入队'); load()
  } catch (e) { ElMessage.error(e.message) }
}

async function download(row) {
  try {
    const resp = await http.get(`/api/excel/export/${row.taskId}/download`, { validateStatus: () => true })
    if (resp.status === 302 && resp.headers.location) window.open(resp.headers.location, '_blank')
    else ElMessage.warning('获取下载地址失败')
  } catch (e) { ElMessage.error(e.message) }
}

function tagType(s) {
  return { SUCCESS:'success', FAILED:'danger', RUNNING:'primary', CANCELED:'info', EXPIRED:'warning' }[s] || 'info'
}
function clean(o) {
  const r = {}
  for (const [k,v] of Object.entries(o)) if (v !== '' && v != null) r[k] = v
  return r
}

onMounted(() => { load(); timer = setInterval(() => load(), 5000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>.mt8{margin-top:8px}</style>
