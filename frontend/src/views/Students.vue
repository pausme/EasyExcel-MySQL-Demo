<template>
  <div>
    <!-- AI 自然语言查询 -->
    <el-card class="mb16" header="AI 自然语言查询（需服务端配置 APP_AI_*）">
      <div class="ai-row">
        <el-input v-model="nlQuery" placeholder="例：查一班20岁以下姓张的学生" clearable @keyup.enter="doAiQuery" />
        <el-button type="primary" :loading="aiLoading" @click="doAiQuery">AI 查询</el-button>
      </div>
      <el-alert v-if="parsedFilters" :closable="false" type="success" class="mt8"
                :title="'解析出的条件：' + JSON.stringify(parsedFilters)" />
    </el-card>

    <!-- 手动条件 -->
    <el-card header="学生查询" class="mb16">
      <el-form inline :model="q">
        <el-form-item label="学号"><el-input v-model="q.studentNo" clearable style="width:130px" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="q.nameKeyword" clearable style="width:110px" /></el-form-item>
        <el-form-item label="班级"><el-input v-model="q.className" clearable style="width:110px" /></el-form-item>
        <el-form-item label="性别">
          <el-select v-model="q.gender" clearable style="width:80px">
            <el-option label="男" value="男" /><el-option label="女" value="女" />
          </el-select>
        </el-form-item>
        <el-form-item label="年龄">
          <el-input-number v-model="q.minAge" :min="0" :max="150" controls-position="right" style="width:90px" />
          <span class="ml4 mr4">-</span>
          <el-input-number v-model="q.maxAge" :min="0" :max="150" controls-position="right" style="width:90px" />
        </el-form-item>
        <el-button type="primary" @click="load(1)">查询</el-button>
      </el-form>

      <el-table :data="rows" stripe v-loading="loading">
        <el-table-column prop="studentNo" label="学号" width="150" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="age" label="年龄" width="70" />
        <el-table-column prop="gender" label="性别" width="70" />
        <el-table-column prop="className" label="班级" />
        <el-table-column prop="email" label="邮箱" />
        <el-table-column prop="birthday" label="生日" width="110" />
      </el-table>
      <el-pagination class="mt8" layout="total, prev, pager, next, sizes"
        :total="total" :current-page="q.pageNo" :page-size="q.pageSize"
        :page-sizes="[10, 20, 50]" @current-change="p => load(p)" @size-change="s => { q.pageSize = s; load(1) }" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData, postData } from '../api/http'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ pageNo: 1, pageSize: 20 })

const nlQuery = ref('')
const aiLoading = ref(false)
const parsedFilters = ref(null)

async function load(page) {
  if (page) q.pageNo = page
  loading.value = true
  try {
    const data = await postData('/api/students/page', clean(q))
    rows.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function doAiQuery() {
  if (!nlQuery.value.trim()) return
  aiLoading.value = true
  try {
    const data = await postData('/api/ai/students/query', { query: nlQuery.value.trim() })
    parsedFilters.value = data.parsedFilters
    rows.value = data.page?.records || []
    total.value = data.page?.total || 0
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    aiLoading.value = false
  }
}

function clean(o) {
  const r = {}
  for (const [k, v] of Object.entries(o)) {
    if (v !== '' && v !== null && v !== undefined) r[k] = v
  }
  return r
}

onMounted(() => load())
</script>

<style scoped>
.mb16 { margin-bottom: 16px; }
.mt8 { margin-top: 8px; }
.ml4 { margin-left: 4px; } .mr4 { margin-right: 4px; }
.ai-row { display: flex; gap: 8px; }
</style>
