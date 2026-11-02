<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const settings = ref(null)
const keyDraft = ref({})          // name -> 待保存的新 key
const testing = ref({})           // name -> {ok, error}
const embTest = ref({})           // name -> embedding 测试结果
const openCollapses = ref([])     // el-collapse 展开的厂商
const mcpEnabled = ref(true)
const syncCfg = ref({ server: '', user: '', appPassword: '', autoDaily: false })
const syncPwd = ref('')
const syncMsg = ref('')








// 厂商配置统一走这里：附加 _oldName 供改名迁移
function applySettings(s) {
  s.providers = (s.providers || []).map((p) => ({ ...p, _oldName: p.name }))
  settings.value = s
  return s
}

onMounted(async () => {
  applySettings(await api.get('/api/settings'))
  api.get('/api/sync/config').then((c) => {
    syncCfg.value = { ...c, appPassword: '' }
  }).catch(() => {})
  mcpEnabled.value = settings.value.mcp?.enabled !== false
})






function buildProviderBody() {
  return settings.value.providers.map((x) => ({
    name: x.name, oldName: x._oldName || x.name,
    baseUrl: x.baseUrl, chatModel: x.chatModel,
    embeddingModel: x.embeddingModel, embeddingDim: x.embeddingDim || 1024,
    apiKey: keyDraft.value[x._oldName || x.name] || '',
  }))
}

function saveProviders() {
  api.putJson('/api/settings', { providers: buildProviderBody() }).then((s) => {
    applySettings(s)
    keyDraft.value = {}
    testing.value = {}
    embTest.value = {}
    ElMessage.success('厂商配置已保存')
  })
}

function addProvider() {
  let i = settings.value.providers.length + 1
  let name = '新厂商'
  while (settings.value.providers.some((p) => p.name === name)) name = '新厂商' + (++i)
  settings.value.providers.push({
    name, _oldName: '', baseUrl: '', chatModel: '',
    embeddingModel: '', embeddingDim: 1024, hasKey: false, apiKeyMasked: '',
  })
  openCollapses.value = [name]
}

function removeProvider(p) {
  ElMessageBox.confirm(`删除厂商「${p.name}」？已保存的 API Key 将一并移除。`, '删除厂商', {
    type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
  }).then(() => {
    const rest = settings.value.providers.filter((x) => x.name !== p.name)
    const body = {
      providers: rest.map((x) => ({
        name: x.name, oldName: x._oldName || x.name,
        baseUrl: x.baseUrl, chatModel: x.chatModel,
        embeddingModel: x.embeddingModel, embeddingDim: x.embeddingDim || 1024,
        apiKey: keyDraft.value[x._oldName || x.name] || '',
      })),
    }
    api.putJson('/api/settings', body).then((s) => {
      applySettings(s)
      openCollapses.value = openCollapses.value.filter((n) => n !== p.name)
      keyDraft.value = {}
      ElMessage.success('厂商已删除')
    })
  }).catch(() => {})
}

function setEmbeddingProvider(name) {
  api.putJson('/api/settings', { activeEmbedding: name }).then(applySettings)
}

function testConn(name) {
  testing.value = { ...testing.value, [name]: { ok: null } }
  api.get('/api/llm/test?name=' + encodeURIComponent(name))
    .then((r) => { testing.value = { ...testing.value, [name]: r } })
}

function testEmbedding(name) {
  embTest.value = { ...embTest.value, [name]: { ok: null } }
  api.get('/api/llm/test-embedding?name=' + encodeURIComponent(name))
    .then((r) => { embTest.value = { ...embTest.value, [name]: r } })
}

function saveSyncCfg() {
  api.post('/api/sync/config', {
    server: syncCfg.value.server, user: syncCfg.value.user,
    appPassword: (syncPwd.value || '').trim(), autoDaily: syncCfg.value.autoDaily,
  }).then((c) => {
    syncCfg.value = { ...c, appPassword: '' }
    syncPwd.value = ''
    ElMessage.success('备份配置已保存')
  })
}
function testSync() {
  saveSyncCfg()
  api.get('/api/sync/test').then((r) => {
    if (r.ok) ElMessage.success('坚果云连接成功')
    else ElMessage.error(r.error || '连接失败')
  })
}
const syncRunning = ref(false)
const syncProg = ref({ phase: '', percent: 0, error: null, sizeKB: null })
let syncProgTimer = null

function pollSync() {
  syncProgTimer = setInterval(() => {
    api.get('/api/sync/progress').then((p) => {
      syncProg.value = p
      if (!p.running) {
        clearInterval(syncProgTimer)
        syncRunning.value = false
        if (p.phase === '备份完成') ElMessage.success(`备份完成（${p.sizeKB} KB）`)
        else if (p.phase === '恢复完成') ElMessage.success('恢复完成，索引将自动重建')
        else if (p.error) ElMessage.error(p.error)
            }
    })
  }, 600)
}
function doBackup() {
  api.post('/api/sync/backup').then((r) => {
    if (r.started) { syncRunning.value = true; pollSync() }
    else ElMessage.warning('已有同步任务在进行中')
  })
}
function doRestore() {
  ElMessageBox.confirm('恢复将覆盖本地的会话、知识库与智能体数据（恢复前会自动做安全备份）。继续？', '从云端恢复', {
    type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消',
  }).then(() => {
    api.get('/api/sync/list').then((list) => {
      const latest = (Array.isArray(list) && list.length) ? list[0].name : null
      if (!latest) { ElMessage.error('云端没有可恢复的备份'); return }
      api.post('/api/sync/restore', { name: latest }).then((r) => {
        if (r.started) { syncRunning.value = true; pollSync() }
      })
    })
  }).catch(() => {})
}
function toggleMcp(v) {
  api.putJson('/api/settings', { mcp: { enabled: v } }).then(() => {
    ElMessage.success(v ? 'MCP 服务已启用，重启后端后完全生效' : 'MCP 服务已停用')
  })
}

const addCmd = 'claude mcp add --transport sse kb http://localhost:8080/sse'
function copyCmd() {
  navigator.clipboard.writeText(addCmd)
  ElMessage.success('接入命令已复制到剪贴板')
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="ph-main">
        <div class="pt">设置</div>
        <div class="ps">厂商与密钥、备份同步、MCP 服务；知识库与索引在「知识库」页管理</div>
      </div>
      <div class="pact">
        <el-tag size="small" effect="plain" type="info">配置存于 .kb/app.json</el-tag>
      </div>
    </div>
    <div class="set-body">
    <el-card shadow="never" class="card">
      <div class="sec-head">
        <div class="sec-title">LLM 厂商</div>
        <div class="sec-sub">OpenAI 兼容协议 · 点击切换当前问答厂商</div>
      </div>
      <div class="models">
        <el-check-tag v-for="p in settings?.providers || []" :key="p.name"
                      :checked="p.name === settings?.activeChat"
                      @change="api.putJson('/api/settings', { activeChat: p.name }).then(applySettings)">
          {{ p.name }}
        </el-check-tag>
        <el-button size="small" @click="addProvider">＋ 新增厂商</el-button>
      </div>
      <div class="embrow">
        <span>Embedding 由</span>
        <el-select :model-value="settings?.activeEmbedding" style="width:160px" size="small"
                   @change="(v) => api.putJson('/api/settings', { activeEmbedding: v }).then(applySettings)">
          <el-option v-for="p in settings?.providers || []" :key="p.name" :value="p.name" :label="p.name" />
        </el-select>
        <span class="emb-hint">需在该厂商里填 Embedding 模型与 Key</span>
      </div>
    </el-card>

    <el-card shadow="never" class="card">
      <div class="sec-head">
        <div class="sec-title">厂商配置</div>
        <div class="sec-sub">名称、地址、模型均可自由编辑；密钥仅存本机，不随备份同步</div>
      </div>
      <el-collapse v-model="openCollapses" class="pv-collapse">
        <el-collapse-item v-for="p in settings?.providers || []" :key="p._oldName || p.name" :name="p.name">
          <template #title>
            <span class="pv-title">{{ p.name }} · {{ p.chatModel || '未配模型' }}</span>
          </template>
          <el-form label-width="110px" size="small" class="pv-form">
            <el-form-item label="名称"><el-input v-model="p.name" /></el-form-item>
            <el-form-item label="Base URL"><el-input v-model="p.baseUrl" placeholder="https://…/v1" /></el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="keyDraft[p._oldName || p.name]" type="password" show-password
                        :placeholder="p.hasKey ? `已保存（${p.apiKeyMasked}），留空不改` : 'sk-…'" />
            </el-form-item>
            <el-form-item label="聊天模型"><el-input v-model="p.chatModel" /></el-form-item>
            <el-form-item label="Embedding 模型">
              <el-input v-model="p.embeddingModel" placeholder="如 embedding-3，留空则不启用向量" />
            </el-form-item>
            <el-form-item label="向量维度">
              <el-input-number v-model="p.embeddingDim" :min="256" :max="4096" :step="256" />
            </el-form-item>
            <el-form-item label="">
              <el-button size="small" @click="testConn(p.name)">测试连通</el-button>
              <el-button size="small" @click="testEmbedding(p.name)">测试向量</el-button>
              <el-button size="small" type="primary" @click="saveProviders()">保存全部厂商</el-button>
              <el-button size="small" type="danger" plain @click="removeProvider(p)">删除厂商</el-button>
            </el-form-item>
            <el-form-item v-if="testing[p.name] || embTest[p.name]" label="">
              <el-alert v-if="testing[p.name]?.ok === true" type="success" :closable="false" title="✓ 聊天连通" />
              <el-alert v-else-if="testing[p.name]?.ok === false" type="error" :closable="false"
                        :title="'✕ ' + (testing[p.name].error || '')" />
              <el-alert v-if="embTest[p.name]?.ok === true" type="success" :closable="false"
                        :title="`✓ 向量 OK（${embTest[p.name].dim} 维）`" />
              <el-alert v-else-if="embTest[p.name]?.ok === false" type="error" :closable="false"
                        :title="'✕ ' + (embTest[p.name].error || '')" />
            </el-form-item>
          </el-form>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <el-card shadow="never" class="card">
      <div class="sec-head">
        <div class="sec-title">备份与同步（坚果云）</div>
        <div class="sec-sub">通过 WebDAV 备份数据、恢复快照、多台电脑间同步；索引不参与同步，恢复后自动重建</div>
      </div>
      <el-form label-width="90px" size="small">
        <el-form-item label="服务器"><el-input v-model="syncCfg.server" /></el-form-item>
        <el-form-item label="账号"><el-input v-model="syncCfg.user" placeholder="手机号或邮箱" /></el-form-item>
        <el-form-item label="应用密码">
          <el-input v-model="syncPwd" type="password" show-password
                    :placeholder="syncCfg.appPassword ? '已保存，留空不改' : '坚果云生成的应用密码'" />
        </el-form-item>
      </el-form>
      <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
        <el-button size="small" :disabled="syncRunning" @click="testSync">测试连接</el-button>
        <el-button size="small" type="primary" :disabled="syncRunning" @click="doBackup">⬆ 立即备份</el-button>
        <el-button size="small" :disabled="syncRunning" @click="doRestore">⟳ 从云端恢复</el-button>
        <el-switch v-model="syncCfg.autoDaily" active-text="每日自动备份" @change="saveSyncCfg" />
      </div>
      <el-progress v-if="syncRunning" :percentage="syncProg.percent" :stroke-width="10"
                   style="margin-top:10px" :show-text="false" status="warning" />
      <div v-if="syncRunning" class="syncmsg">{{ syncProg.phase }}（{{ syncProg.percent }}%）</div>
      <div v-if="syncMsg" class="syncmsg">{{ syncMsg }}</div>
      <el-divider style="margin:12px 0" />
      <el-text size="small" type="info">同步内容：文档副本 · 会话 · 智能体配置（不含 API Key 与索引）；后写胜出，恢复前自动做安全备份</el-text>
    </el-card>

    <el-card shadow="never" class="card">
      <div class="sec-head">
        <div class="sec-title">MCP 服务</div>
        <div class="sec-sub">供 Claude Code / 桌面版等客户端检索本库</div>
      </div>
      <div class="mcprow">
        <el-tag size="small" type="success" effect="plain">SSE</el-tag>
        <code>http://localhost:8080/sse</code>
      </div>
      <div class="mcprow">
        <span>kb_search</span><span class="r">混合检索 · 返回片段与出处</span>
      </div>
      <div class="mcprow">
        <span>kb_open</span><span class="r">读取文档原文 / 指定分块</span>
      </div>
      <div class="mcprow">
        <el-switch :model-value="mcpEnabled" @change="toggleMcp" />
        <span class="r">{{ mcpEnabled ? '已启用' : '已停用' }}</span>
      </div>
      <el-button size="small" class="copybtn" @click="copyCmd">复制接入命令</el-button>
    </el-card>
    </div>
  </div>
</template>

<style scoped>
.set-body{flex:1;min-height:0;overflow:auto;padding:0 var(--page-x) 26px;
  display:grid;grid-template-columns:1fr 1fr;gap:var(--gap);align-content:start}
.set-body .card{border-radius:var(--radius)}
.set-body :deep(.el-card__body){padding:16px 18px}
.models{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.embrow{display:flex;align-items:center;gap:8px;margin-top:14px;font-size:12px;color:var(--ink2)}
.emb-hint{font-size:11px;color:var(--ink3)}
.pv-collapse{border:0}
.pv-collapse :deep(.el-collapse-item__header){font-size:12.5px;font-weight:600;background:var(--paper);
  border-radius:var(--radius-sm);padding:0 12px}
.pv-collapse :deep(.el-collapse-item__wrap){background:transparent;border-bottom:0}
.pv-form{margin-top:6px}
.idxrow{display:flex;align-items:center;gap:16px;margin:6px 0}
.idxnum .v{font-size:22px;font-weight:700;font-family:var(--mono);color:var(--ink)}
.idxnum .k{font-size:11px;color:var(--ink3)}
.bar{flex:1}
.idxmeta{font-size:11.5px;color:var(--ink3);margin-top:8px;line-height:1.9}
.mono-path{font-family:var(--mono);font-size:10.5px}
.failed{margin-top:12px;border:1px solid #E8D5C4;border-radius:var(--radius-sm);background:#FDF8F4;padding:10px 14px;max-height:180px;overflow:auto}
.failed.okbox{border-color:var(--line);background:var(--paper)}
.failed .fh{font-size:11.5px;color:var(--amber);font-weight:600;margin-bottom:6px}
.failed.okbox .fh{color:var(--mint)}
.failed .frow{display:flex;gap:10px;font-size:11px;line-height:1.8;border-bottom:1px dashed var(--line-soft)}
.failed .frow:last-child{border-bottom:0}
.failed .fp{font-family:var(--mono);color:var(--ink2);flex:0 0 55%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.failed .fr{color:var(--ink3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mcprow{display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px dashed var(--line-soft);font-size:12.5px}
.mcprow code{background:var(--mist);border-radius:6px;padding:3px 9px;font-size:11px;color:var(--accent);font-family:var(--mono)}
.mcprow .r{margin-left:auto;font-size:11px;color:var(--ink3)}
.copybtn{margin-top:10px}
</style>
