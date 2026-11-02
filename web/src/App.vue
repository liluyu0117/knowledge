<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { api } from './api'
import { ChatDotRound, Files, Collection, EditPen, Setting, Search } from '@element-plus/icons-vue'
import './styles/tokens.css'
import './styles/el-theme.css'

const route = useRoute()
const statusText = ref('索引未构建')
const statusType = ref('info')
let statusTimer = null

function refreshStatus() {
  api.get('/api/index/status').then((s) => {
    const l = s.live || {}
    if (s.running) {
      statusType.value = 'warning'
      if (l.phase === 'awaiting_confirm') statusText.value = `等待确认「${s.runningKbName}」`
      else if (l.phase === 'scanning') statusText.value = `扫描中「${s.runningKbName}」`
      else statusText.value = `构建中「${s.runningKbName}」${l.filesDone}/${l.filesTotal}`
    } else if (s.anyBuilt) {
      if (s.failedCount > 0) {
        statusText.value = `就绪 · ${s.failedCount} 个失败`
        statusType.value = 'warning'
      } else {
        statusText.value = '就绪'
        statusType.value = 'success'
      }
    } else {
      statusText.value = '索引未构建'
      statusType.value = 'info'
    }
  }).catch(() => {
    statusText.value = '后端未连接'
    statusType.value = 'danger'
  })
}

function scheduleStatus() {
  clearTimeout(statusTimer)
  statusTimer = setTimeout(async () => {
    refreshStatus()
    scheduleStatus()
  }, statusType.value === 'warning' ? 3000 : 20000)
}

onMounted(() => {
  refreshStatus()
  scheduleStatus()
})
onUnmounted(() => clearTimeout(statusTimer))
</script>

<template>
  <div class="shell">
    <aside class="rail">
      <div class="logo">知</div>
      <router-link to="/" class="rail-item" active-class="on">
        <el-icon><ChatDotRound /></el-icon><span>问答</span>
      </router-link>
      <router-link to="/kb" class="rail-item" active-class="on">
        <el-icon><Collection /></el-icon><span>知识库</span>
      </router-link>
      <router-link to="/notes" class="rail-item" active-class="on">
        <el-icon><EditPen /></el-icon><span>笔记</span>
      </router-link>
      <router-link to="/settings" class="rail-item" active-class="on">
        <el-icon><Setting /></el-icon><span>设置</span>
      </router-link>
      <router-link to="/retrievetest" class="rail-item" active-class="on">
        <el-icon><Search /></el-icon><span>调参</span>
      </router-link>
      <div class="rail-foot">
        <el-tag :type="statusType" effect="plain" size="small">{{ statusText }}</el-tag>
      </div>
    </aside>
    <router-view v-slot="{ Component }">
      <transition name="el-fade-in-linear">
        <component :is="Component" />
      </transition>
    </router-view>
  </div>
</template>

<style scoped>
.shell{height:100vh;display:flex}
/* 路由页面铺满导航栏以外的全部空间 */
.shell > *:not(.rail){flex:1 1 0;min-width:0;height:100%;overflow:hidden}
.rail{
  width:64px;flex:0 0 64px;display:flex;flex-direction:column;align-items:center;
  background:#FBFBFC;border-right:1px solid var(--line);padding:14px 0 12px;gap:4px;
}
.rail .logo{
  width:36px;height:36px;border-radius:10px;display:grid;place-items:center;
  background:var(--accent);color:#fff;font-weight:700;font-size:16px;margin-bottom:14px;
}
.rail-foot{margin-top:auto;padding:0 6px;width:100%;text-align:center}
.rail-foot .el-tag{max-width:100%;white-space:normal}
</style>
