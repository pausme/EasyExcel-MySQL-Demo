<template>
  <el-card header="文件中心">
    <el-form inline>
      <el-form-item label="文件名"><el-input v-model="q.originalName" clearable style="width:150px" /></el-form-item>
      <el-form-item label="扩展名"><el-input v-model="q.fileExt" clearable style="width:90px" /></el-form-item>
      <el-button type="primary" @click="load(1)">查询</el-button>
      <el-button @click="uploadDialog = true">上传文件</el-button>
    </el-form>
    <el-table :data="rows" stripe v-loading="loading">
      <el-table-column prop="originalName" label="文件名" show-overflow-tooltip />
      <el-table-column prop="fileSize" label="大小" width="100">
        <template #default="{ row }">{{ fmtSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column prop="fileMd5" label="MD5" width="290" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="上传时间" width="170" />
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button link size="small" @click="download(row)">下载</el-button>
          <el-button link size="small" type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="mt8" layout="total, prev, pager, next"
      :total="total" :current-page="q.pageNo" :page-size="q.pageSize"
      @current-change="p => load(p)" />

    <el-dialog v-model="uploadDialog" title="上传文件" width="420">
      <el-upload drag :auto-upload="false" :limit="1" :on-change="f => (pickFile = f.raw)">
        <div>拖拽或点击选择文件</div>
      </el-upload>
      <template #footer>
        <el-button @click="uploadDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!pickFile" :loading="uploading" @click="doUpload">上传</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getData, postData } from '../api/http'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ pageNo: 1, pageSize: 20 })
const uploadDialog = ref(false)
const pickFile = ref(null)
const uploading = ref(false)

async function load(page) {
  if (page) q.pageNo = page
  loading.value = true
  try {
    const data = await postData('/api/files/page', clean(q))
    rows.value = data.records || []
    total.value = data.total || 0
  } catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}

async function doUpload() {
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', pickFile.value)
    await http.post('/api/files/upload', fd, { timeout: 300000 })
    ElMessage.success('上传成功')
    uploadDialog.value = false; pickFile.value = null; load(1)
  } catch (e) { ElMessage.error(e.message) } finally { uploading.value = false }
}

async function download(row) {
  // 直接打开，浏览器自动跟 302 到 MinIO 签名地址（需 Token 在 cookie 或 URL 传递）
  try {
    const resp = await fetch(`/api/files/${row.fileId}/download`, {
      headers: { Authorization: 'Bearer ' + (localStorage.getItem('at') || '') }
    })
    if (resp.ok) {
      const blob = await resp.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = row.originalName || 'file'
      a.click()
      URL.revokeObjectURL(url)
    } else if (resp.redirected) {
      window.open(resp.url, '_blank')
    } else {
      ElMessage.warning('获取下载地址失败')
    }
  } catch (e) { ElMessage.error(e.message) }
}

async function del(row) {
  await ElMessageBox.confirm(`确认删除「${row.originalName}」？`, '删除确认', { type: 'warning' })
  try { await http.post(`/api/files/${row.fileId}/delete`); ElMessage.success('已删除'); load() }
  catch (e) { ElMessage.error(e.message) }
}

function fmtSize(b) {
  if (b == null) return '-'
  if (b < 1024) return b + ' B'
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB'
  if (b < 1073741824) return (b / 1048576).toFixed(1) + ' MB'
  return (b / 1073741824).toFixed(2) + ' GB'
}
function clean(o) {
  const r = {}
  for (const [k,v] of Object.entries(o)) if (v !== '' && v != null) r[k] = v
  return r
}

onMounted(() => load())
</script>

<style scoped>.mt8{margin-top:8px}</style>
