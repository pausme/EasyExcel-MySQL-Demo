<template>
  <div>
    <el-steps :active="step" finish-status="success" class="mb16">
      <el-step title="选择文件" />
      <el-step title="预检" />
      <el-step title="导入进度" />
      <el-step title="结果" />
    </el-steps>

    <el-card v-if="step === 0">
      <el-upload drag :auto-upload="false" :limit="1" :on-change="onPick" accept=".xlsx">
        <div style="font-size: 40px">📄</div>
        <div>拖拽 xlsx 到此处，或点击选择（≤200,000 行）</div>
      </el-upload>
      <div class="mt8">
        <el-button link type="primary" @click="downloadTemplate">下载导入模板</el-button>
      </div>
      <div class="mt8">
        <span>导入模式：</span>
        <el-select v-model="mode" style="width: 200px">
          <el-option label="覆盖发布（默认）" value="OVERWRITE" />
          <el-option label="追加更新" value="APPEND" />
          <el-option label="仅校验" value="VALIDATE_ONLY" />
        </el-select>
      </div>
      <el-button class="mt8" type="primary" :disabled="!file" @click="doPrecheck">下一步：预检</el-button>
    </el-card>

    <el-card v-else-if="step === 1" v-loading="checking">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="文件名">{{ precheck.originalName }}</el-descriptions-item>
        <el-descriptions-item label="数据行数">{{ precheck.dataRowCount || '-' }}</el-descriptions-item>
        <el-descriptions-item label="是否可导入">
          <el-tag :type="precheck.valid ? 'success' : 'danger'">{{ precheck.valid ? '通过' : '存在问题' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="错误数">{{ precheck.errorCount || 0 }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="!precheck.valid && precheck.errorSummary"
        class="mt8"
        type="warning"
        :closable="false"
        :title="errorSummaryText"
      />
      <div class="mt8">
        <el-button @click="step = 0">上一步</el-button>
        <el-button type="primary" :disabled="!precheck.valid" @click="doImport">开始导入</el-button>
      </div>
    </el-card>

    <el-card v-else-if="step === 2">
      <el-progress :percentage="task.progressPercent || 0" :stroke-width="20" striped striped-flow />
      <p>状态：{{ task.status }}　已处理：{{ task.completedCount || 0 }} / {{ task.totalCount || 0 }}</p>
      <p v-if="task.errorMessage" style="color: #f56c6c">{{ task.errorMessage }}</p>
    </el-card>

    <el-card v-else header="导入结果">
      <el-result
        :icon="task.status === 'SUCCESS' ? 'success' : 'error'"
        :title="task.status === 'SUCCESS' ? '导入成功' : '导入失败'"
        :sub-title="task.errorMessage || '已处理 ' + (task.completedCount || 0) + ' 行'"
      >
        <template #extra>
          <el-button v-if="task.hasErrorFile" type="primary" @click="downloadErrorFile">下载错误明细</el-button>
          <el-button @click="reset">再导一次</el-button>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getData } from '../api/http'

const step = ref(0)
const file = ref(null)
const mode = ref('OVERWRITE')
const checking = ref(false)
const precheck = reactive({})
const task = reactive({})
let timer = null

const errorSummaryText = computed(() => {
  if (!precheck.errorSummary) return ''
  return Object.entries(precheck.errorSummary).map(([k, v]) => k + ': ' + v).join('；')
})

function onPick(f) {
  file.value = f.raw
}

async function downloadTemplate() {
  const resp = await http.get('/api/excel/template', { responseType: 'blob' })
  const url = URL.createObjectURL(resp.data)
  const a = document.createElement('a')
  a.href = url
  a.download = 'student-import-template.xlsx'
  a.click()
  URL.revokeObjectURL(url)
}

async function doPrecheck() {
  checking.value = true
  try {
    const resp = await http.post('/api/excel/import/precheck', fileForm(), { timeout: 120000 })
    Object.assign(precheck, resp.data.data)
    step.value = 1
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    checking.value = false
  }
}

async function doImport() {
  try {
    const resp = await http.post('/api/excel/import?mode=' + mode.value, fileForm(), { timeout: 600000 })
    Object.assign(task, resp.data.data)
    step.value = 2
    timer = setInterval(poll, 2000)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function poll() {
  try {
    const t = await getData('/api/excel/import/' + task.taskId)
    Object.assign(task, t)
    if (['SUCCESS', 'FAILED', 'CANCELED'].includes(t.status)) {
      clearInterval(timer)
      step.value = 3
    }
  } catch {
    /* transient network error, continue polling */
  }
}

function fileForm() {
  const fd = new FormData()
  fd.append('file', file.value)
  return fd
}

async function downloadErrorFile() {
  // 直接打开 URL，浏览器自动跟 302 到 MinIO 签名地址
  window.open('/api/excel/import/' + task.taskId + '/error-file', '_blank')
}

function reset() {
  step.value = 0
  file.value = null
  Object.keys(precheck).forEach((k) => delete precheck[k])
  Object.keys(task).forEach((k) => delete task[k])
}
</script>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.mt8 {
  margin-top: 8px;
}
</style>
