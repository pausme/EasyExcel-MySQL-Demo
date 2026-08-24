<template>
  <el-container class="layout">
    <el-aside width="200px">
      <div class="logo">EasyExcel</div>
      <el-menu router :default-active="$route.path" class="menu">
        <el-menu-item v-if="auth.isAdmin" index="/ops">运维概览</el-menu-item>
        <el-menu-item index="/students">学生查询</el-menu-item>
        <el-menu-item index="/import">数据导入</el-menu-item>
        <el-menu-item index="/tasks">任务中心</el-menu-item>
        <el-menu-item index="/files">文件中心</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ auth.username }}（{{ auth.roles.join('/') }}）</span>
        <el-button link type="danger" @click="doLogout">登出</el-button>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function doLogout() {
  await auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.logo {
  height: 60px; line-height: 60px; text-align: center;
  font-weight: 700; font-size: 18px; color: #409eff;
  border-bottom: 1px solid #e6e6e6;
}
.menu { border-right: none; }
.header {
  display: flex; justify-content: flex-end; align-items: center; gap: 16px;
  border-bottom: 1px solid #e6e6e6;
}
</style>
