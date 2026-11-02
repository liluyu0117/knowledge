<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Cpu, Search, Connection, FolderOpened, Refresh } from '@element-plus/icons-vue'

const settings = ref(null)
const tab = ref('model')
const mainRef = ref(null)

// 厂商编辑：draft 是可改的草稿，snapshot 是上次保存时的规范化串，用来判脏
const draft = ref([])
const snapshot = ref('')
const errors = ref({})            // uid -> { name?, baseUrl?, dim? }
const chatTest = ref({})          // uid -> { ok, tookMs, error }
const embTest = ref({})           // uid -> { ok, dim, tookMs, error }
const openPanels = ref([])        // 展开的厂商（按 uid，改名不影响）
let uidSeq = 0
const newUid = () => 'pv' + (++uidSeq)

const mcpEnabled = ref(true)
const mcpInfo = ref({ toolCount: 0, tools: [], sseUrl: 'http://localhost:8080/sse', messageEndpoint: '' })
const autoMinutes = ref(0)
const apiCfg = ref({ enabled: false, key: '' })
const usage = ref(null)

const syncCfg = ref({ server: '', user: '', appPassword: '', autoDaily: false })
const syncPwd = ref('')
const hasSyncPwd = ref(false)
const syncRunning = ref(false)
const syncProg = ref({ phase: '', percent: 0, error: null, sizeKB: null })
let syncProgTimer = null

const rerankKey = ref('')
const rerankTesting = ref(false)
const rerankTest = ref(null)

// 切换分组后回到顶部，否则从长页切到短页会停在半截
watch(tab, () => { if (mainRef.value) mainRef.value.scrollTop = 0 })

// ── 派生状态 ──
/** 服务端已保存的厂商（「当前使用」只看这个，草稿里的新增项不会污染它） */
const providers = computed(() => settings.value?.providers || [])

/** 规范化：只取参与「是否改动」判断的字段 */
function normList(list) {
  return list.map((p) => [
    (p.name || '').trim(),
    (p.baseUrl || '').trim(),
    (p.chatModel || '').trim(),
    (p.embeddingModel || '').trim(),
    Number(p.embeddingDim) || 0,
    (p._key || '').trim() ? '1' : '0',
  ])
}
const normJson = (list) => JSON.stringify(normList(list))

const dirty = computed(() => normJson(draft.value) !== snapshot.value)

/** 与已保存版本逐项比对，算出「有几项被改过」 */
function isChanged(p) {
  if (p._isNew) return true
  const saved = providers.value.find((x) => x.name === p._oldName)
  if (!saved) return true
  // 注意：这里要带上 _key —— 只填了新 Key 也是一处未保存改动
  return normJson([p]) !== normJson([{ ...saved, _key: '' }])
}
const changedCount = computed(() => draft.value.filter(isChanged).length)
const chatProvider = computed(() => providers.value.find((p) => p.name === settings.value?.activeChat) || null)
const embProvider = computed(() => providers.value.find((p) => p.name === settings.value?.activeEmbedding) || null)
const rerankOn = computed(() => !!settings.value?.rerank?.enabled)
const rerankReady = computed(() => {
  const r = settings.value?.rerank
  return !!(r && r.baseUrl && r.model && r.hasKey)
})

/** 左侧导航：标题 + 一行实时状态，扫一眼就知道每块配没配好 */
const NAV = computed(() => [
  {
    k: 'model', t: '模型服务', i: Cpu,
    sub: chatProvider.value ? `${chatProvider.value.name} · ${chatProvider.value.chatModel || '未配模型'}` : '尚未配置厂商',
  },
  {
    k: 'retrieval', t: '索引与用量', i: Search,
    sub: autoMinutes.value ? `自动重扫 · 每 ${autoMinutes.value} 分钟` : '自动重扫已关闭',
  },
  {
    k: 'integration', t: '集成与接口', i: Connection,
    sub: `MCP ${mcpEnabled.value ? '开' : '关'} · REST ${apiCfg.value.enabled ? '开' : '关'}`,
  },
  {
    k: 'data', t: '数据与备份', i: FolderOpened,
    sub: syncCfg.value.user ? `坚果云 · ${syncCfg.value.user}` : '坚果云未配置',
  },
])

const AUTO_OPTS = [
  { v: 0, l: '关闭（手动构建）' },
  { v: 10, l: '每 10 分钟' },
  { v: 30, l: '每 30 分钟' },
  { v: 60, l: '每 1 小时' },
]

const usageTiles = computed(() => {
  const u = usage.value || {}
  return [
    { k: '问答', v: u.ask || 0, s: '次' },
    { k: '检索', v: u.search || 0, s: '次' },
    { k: '重排', v: u.rerank || 0, s: '次' },
  ]
})

const curlExample = computed(() => {
  // 开发期页面在 5173，接口在 8080；生产由 8080 托管，直接用当前 origin
  const origin = typeof location === 'undefined'
    ? 'http://localhost:8080'
    : (location.port && location.port !== '8080'
        ? location.protocol + '//' + location.hostname + ':8080'
        : location.origin)
  return `curl -H "X-API-Key: ${apiCfg.value.key || 'YOUR_KEY'}" \\\n  "${origin}/api/v1/search?q=上线流程&kbId=YOUR_KB_ID&topK=5"`
})

// ── 通用 ──
/**
 * 应用服务端返回。resetDraft=true 时重建草稿（首次加载 / 保存后 / 删除后）；
 * 否则保留草稿 —— 否则切一下「当前问答厂商」就会把正在编辑的厂商配置冲掉。
 */
function applySettings(s, opts = {}) {
  s.providers = (s.providers || []).map((p) => ({ ...p }))
  settings.value = s
  if (opts.resetDraft) initDraft(s.providers)
  return s
}

/** 用服务端数据重建草稿，并把当前状态记为「干净」 */
function initDraft(list) {
  draft.value = (list || []).map((p) => ({
    _uid: newUid(),
    _oldName: p.name,          // 改名迁移用
    _key: '',                  // 待保存的新 Key（空 = 不改）
    _isNew: false,
    name: p.name,
    baseUrl: p.baseUrl || '',
    chatModel: p.chatModel || '',
    embeddingModel: p.embeddingModel || '',
    embeddingDim: p.embeddingDim || 1024,
    hasKey: !!p.hasKey,
    apiKeyMasked: p.apiKeyMasked || '',
  }))
  errors.value = {}
  chatTest.value = {}
  embTest.value = {}
  snapshot.value = normJson(draft.value)
}

onMounted(async () => {
  const st0 = applySettings(await api.get('/api/settings'), { resetDraft: true })
  autoMinutes.value = st0.index?.autoMinutes ?? 0
  chunkMode.value = st0.index?.chunkMode || 'fixed'
  childChars.value = st0.index?.childChars ?? 380
  parentChars.value = st0.index?.parentChars ?? 2000
  apiCfg.value = { ...(st0.api || { enabled: false, key: '' }) }
  mcpEnabled.value = st0.mcp?.enabled !== false
  loadMcp()
  loadUsage()
  api.get('/api/sync/config').then((c) => {
    syncCfg.value = { ...c, appPassword: '' }
    hasSyncPwd.value = false
  }).catch(() => {})
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))

/** 刷新/关标签页时提醒（SPA 内跳转由路由守卫处理） */
function onBeforeUnload(e) {
  if (!dirty.value) return
  e.preventDefault()
  e.returnValue = ''
}

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm(
      '厂商配置有未保存的修改，离开后会丢失。',
      '未保存的修改',
      { type: 'warning', confirmButtonText: '放弃修改并离开', cancelButtonText: '留在此页' },
    )
    return true
  } catch (e) {
    return false
  }
})

// ── 厂商：草稿校验 / 保存 / 撤销 / 增删 / 测试 ──
const trim = (v) => (v == null ? '' : String(v)).trim()

/** 只拦会让人白配的错误；Key 缺失只提示不拦（允许先存空壳再补） */
function validate() {
  const errs = {}
  const seen = new Map()
  for (const p of draft.value) {
    const e = {}
    const name = trim(p.name)
    if (!name) e.name = '名称不能为空'
    else if (seen.has(name)) e.name = '与「' + seen.get(name) + '」重名'
    else seen.set(name, name)
    const url = trim(p.baseUrl)
    if (!url) e.baseUrl = '必填'
    else if (!/^https?:\/\//i.test(url)) e.baseUrl = '需以 http:// 或 https:// 开头'
    const dim = Number(p.embeddingDim)
    if (trim(p.embeddingModel) && !(dim >= 256 && dim <= 4096)) e.dim = '维度需在 256 - 4096'
    if (Object.keys(e).length) errs[p._uid] = e
  }
  return errs
}

async function saveProviders() {
  if (!draft.value.length) {
    ElMessage.warning('至少保留一个厂商，否则无法问答')
    return
  }
  const errs = validate()
  errors.value = errs
  const badUids = Object.keys(errs)
  if (badUids.length) {
    openPanels.value = [...new Set([...openPanels.value, ...badUids])]
    const first = draft.value.find((p) => p._uid === badUids[0])
    ElMessage.error(`「${trim(first?.name) || '未命名厂商'}」填写不完整，请按红字修正`)
    return
  }
  const added = draft.value.filter((p) => p._isNew).length
  const body = {
    providers: draft.value.map((p) => ({
      name: trim(p.name),
      oldName: p._oldName || '',
      baseUrl: trim(p.baseUrl),
      chatModel: trim(p.chatModel),
      embeddingModel: trim(p.embeddingModel),
      embeddingDim: Number(p.embeddingDim) || 1024,
      apiKey: trim(p._key),
    })),
  }
  try {
    const s = await api.putJson('/api/settings', body)
    applySettings(s, { resetDraft: true })
    ElMessage.success(added ? `已保存（其中新增 ${added} 个厂商）` : '厂商配置已保存')
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || e))
  }
}

function discard() {
  ElMessageBox.confirm('撤销所有未保存的修改，回到上次保存的状态？', '撤销修改', {
    type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消',
  }).then(() => {
    initDraft(providers.value)
    openPanels.value = []
    ElMessage.success('已恢复到上次保存的配置')
  }).catch(() => {})
}

function addProvider() {
  const used = new Set(draft.value.map((p) => trim(p.name)))
  let i = draft.value.length + 1
  let name = '新厂商'
  while (used.has(name)) name = '新厂商' + (++i)
  const item = {
    _uid: newUid(), _oldName: '', _key: '', _isNew: true,
    name, baseUrl: '', chatModel: '', embeddingModel: '', embeddingDim: 1024,
    hasKey: false, apiKeyMasked: '',
  }
  draft.value = [...draft.value, item]
  openPanels.value = [item._uid]
  nextTick(() => {
    const el = document.querySelector(`[data-uid="${item._uid}"] input`)
    el?.focus()
    el?.select?.()
  })
}

function removeProvider(p) {
  // 草稿有改动时先让用户保存或撤销，避免「删除请求把别的改动一起带走」这种模糊状态
  if (dirty.value) {
    ElMessage.warning('先保存或撤销当前修改，再删除厂商')
    return
  }
  const others = providers.value.filter((x) => x.name !== p.name)
  if (!others.length) {
    ElMessage.warning('这是最后一个厂商，删掉就没法问答了')
    return
  }
  const fallback = others[0].name
  const tips = ['已保存的 API Key 会一并删除。']
  if (p.name === settings.value?.activeChat) tips.push(`问答当前用的就是它，删除后会切到「${fallback}」。`)
  if (p.name === settings.value?.activeEmbedding) tips.push(`向量化当前用的就是它，删除后会切到「${fallback}」。`)

  ElMessageBox.confirm(`删除厂商「${p.name}」？${tips.join('')}`, '删除厂商', {
    type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
  }).then(async () => {
    const body = {
      providers: others.map((x) => ({
        name: x.name, oldName: x.name,
        baseUrl: x.baseUrl, chatModel: x.chatModel,
        embeddingModel: x.embeddingModel, embeddingDim: x.embeddingDim || 1024,
        apiKey: '',
      })),
    }
    try {
      const s = await api.putJson('/api/settings', body)
      applySettings(s, { resetDraft: true })
      ElMessage.success('厂商已删除')
    } catch (e) {
      ElMessage.error('删除失败：' + (e.message || e))
    }
  }).catch(() => {})
}

function setActiveChat(p) {
  if (!p.hasKey || !p.chatModel) {
    ElMessage.warning('该厂商还没配置聊天模型或 Key，先在下面补齐')
    return
  }
  if (p.name === settings.value?.activeChat) return
  api.putJson('/api/settings', { activeChat: p.name }).then((s) => {
    applySettings(s)
    ElMessage.success('问答已切换到「' + p.name + '」')
  })
}

function setEmbeddingProvider(name) {
  api.putJson('/api/settings', { activeEmbedding: name }).then(applySettings)
}

/** 字段一改就清掉过期结果，避免「改完还挂着上次的 ✓」 */
function touch(p) {
  if (chatTest.value[p._uid]) chatTest.value = { ...chatTest.value, [p._uid]: null }
  if (embTest.value[p._uid]) embTest.value = { ...embTest.value, [p._uid]: null }
  if (errors.value[p._uid]) {
    const next = { ...errors.value }
    delete next[p._uid]
    errors.value = next
  }
}

/** 测试用的是「表单当前值」，不需要先保存（新增/改名也能测） */
async function testConn(p) {
  chatTest.value = { ...chatTest.value, [p._uid]: { ok: null } }
  try {
    const r = await api.post('/api/llm/test', {
      name: p._oldName || '',
      baseUrl: trim(p.baseUrl),
      chatModel: trim(p.chatModel),
      apiKey: trim(p._key),
    })
    chatTest.value = { ...chatTest.value, [p._uid]: r }
  } catch (e) {
    chatTest.value = { ...chatTest.value, [p._uid]: { ok: false, error: String(e.message || e) } }
  }
}

async function testEmbedding(p) {
  embTest.value = { ...embTest.value, [p._uid]: { ok: null } }
  try {
    const r = await api.post('/api/llm/test-embedding', {
      name: p._oldName || '',
      baseUrl: trim(p.baseUrl),
      embeddingModel: trim(p.embeddingModel),
      embeddingDim: Number(p.embeddingDim) || 1024,
      apiKey: trim(p._key),
    })
    embTest.value = { ...embTest.value, [p._uid]: r }
  } catch (e) {
    embTest.value = { ...embTest.value, [p._uid]: { ok: false, error: String(e.message || e) } }
  }
}

/** 测试返回的维度与填写不一致时，一键采纳 */
function useTestedDim(p) {
  const dim = embTest.value[p._uid]?.dim
  if (dim) { p.embeddingDim = dim; touch(p) }
}

// ── 重排 ──
function saveRerank() {
  const r = settings.value.rerank
  api.putJson('/api/settings', {
    rerank: {
      enabled: r.enabled, baseUrl: r.baseUrl, model: r.model,
      candidate: r.candidate, topN: r.topN, apiKey: rerankKey.value,
    },
  }).then((st) => {
    applySettings(st)
    rerankKey.value = ''
    ElMessage.success('重排序配置已保存')
  })
}

async function testRerank() {
  rerankTesting.value = true
  try {
    rerankTest.value = await api.post('/api/settings/rerank/test', {})
  } catch (e) {
    rerankTest.value = { ok: false, error: String(e.message || e) }
  } finally {
    rerankTesting.value = false
  }
}

// ── 索引 / 用量 ──
function saveAutoMinutes(v) {
  autoMinutes.value = v
  api.putJson('/api/settings', { index: { autoMinutes: v } }).then(() => {
    ElMessage.success(v ? '已开启自动增量重扫' : '已关闭自动重扫')
  })
}

// ── 分块模式 ──
const chunkMode = ref('fixed')
const childChars = ref(380)
const parentChars = ref(2000)

function saveChunkMode(v) {
  chunkMode.value = v
  api.putJson('/api/settings', { index: { chunkMode: v } }).then(() => {
    ElMessage.success(v === 'parent-child'
      ? '已切换到父子分块，请到「知识库」页构建索引（自动全量重建）'
      : '已切换到滑动窗口分块，请到「知识库」页构建索引（自动全量重建）')
  })
}

function saveChunkParams() {
  api.putJson('/api/settings', { index: { childChars: childChars.value, parentChars: parentChars.value } })
    .then(() => ElMessage.success('已保存，下次构建生效（全量重建）'))
}

function loadUsage() {
  api.get('/api/settings/usage').then((u) => { usage.value = u }).catch(() => {})
}

// ── 开放接口 ──
function saveApi(patch) {
  api.putJson('/api/settings', { api: patch }).then((st) => {
    applySettings(st)
    apiCfg.value = { ...st.api }
    ElMessage.success('已保存')
  })
}

function regenKey() { saveApi({ regenerate: true }) }

function copyKey() {
  navigator.clipboard.writeText(apiCfg.value.key || '')
  ElMessage.success('已复制 API Key')
}

// ── 备份 ──
function saveSyncCfg() {
  api.post('/api/sync/config', {
    server: syncCfg.value.server, user: syncCfg.value.user,
    appPassword: (syncPwd.value || '').trim(), autoDaily: syncCfg.value.autoDaily,
  }).then((c) => {
    syncCfg.value = { ...c, appPassword: '' }
    if (syncPwd.value.trim()) hasSyncPwd.value = true
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

// ── MCP ──
function loadMcp() {
  api.get('/api/mcp/info').then((d) => {
    mcpInfo.value = d
    mcpEnabled.value = d.enabled !== false
  }).catch(() => {})
}

function toggleMcp(v) {
  mcpEnabled.value = v
  api.putJson('/api/settings', { mcp: { enabled: v } }).then(() => {
    ElMessage.success(v ? '已启用：新连接可直接接入' : '已停用：工具调用会立即被拒绝，已连接的客户端需重启后端才断开')
    loadMcp()
  })
}

const clients = computed(() => mcpInfo.value.clients || {})
const addCmd = computed(() => clients.value.claudeCode || `claude mcp add --transport sse kb ${mcpInfo.value.sseUrl || ''}`)
const desktopJson = computed(() => clients.value.json ||
  JSON.stringify({ mcpServers: { kb: { url: mcpInfo.value.sseUrl || '' } } }, null, 2))
const bridgeJson = computed(() => clients.value.bridge || '')

function copyText(text, label) {
  navigator.clipboard.writeText(text)
  ElMessage.success(label + '已复制')
}
function copyCmd() { copyText(addCmd.value, '接入命令') }
function copyJson() { copyText(desktopJson.value, 'JSON 配置') }
function copyBridge() { copyText(bridgeJson.value, '桥接配置') }
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="ph-main">
        <div class="pt">设置</div>
        <div class="ps">模型与重排、索引自动化、对外接口、数据备份；知识库与文档在「知识库」页管理</div>
      </div>
      <div class="pact">
        <span class="pill"><i class="dot" :class="rerankOn ? 'on' : ''"></i>重排 {{ rerankOn ? '已启用' : '关闭' }}</span>
        <span class="pill"><i class="dot" :class="autoMinutes ? 'on' : ''"></i>自动重扫 {{ autoMinutes ? '开' : '关' }}</span>
      </div>
    </div>

    <div class="set-wrap">
      <!-- 左：分组导航 -->
      <aside class="set-nav">
        <button v-for="n in NAV" :key="n.k" class="nv" :class="{ on: tab === n.k }" @click="tab = n.k">
          <el-icon class="nvi"><component :is="n.i" /></el-icon>
          <span class="nvm">
            <span class="nvt">{{ n.t }}</span>
            <span class="nvs">{{ n.sub }}</span>
          </span>
        </button>
      </aside>

      <!-- 右：内容 -->
      <main ref="mainRef" class="set-main">
        <div class="set-inner">

          <!-- ══ 模型服务 ══ -->
          <template v-if="tab === 'model'">
            <section class="card">
              <div class="card-h">
                <div>
                  <div class="sec-title">当前使用</div>
                  <div class="sec-sub">问答用哪个厂商的聊天模型，向量化用哪个厂商的 embedding</div>
                </div>
              </div>

              <div class="srow">
                <span class="rl">问答模型</span>
                <div class="tags">
                  <el-tooltip v-for="p in providers" :key="p.name" placement="top"
                              :disabled="p.hasKey && !!p.chatModel"
                              :content="!p.chatModel ? '还没配聊天模型' : '还没填 API Key'">
                    <span class="tagwrap">
                      <el-check-tag :checked="p.name === settings?.activeChat"
                                    :disabled="!p.hasKey || !p.chatModel"
                                    @change="setActiveChat(p)">
                        {{ p.name }}
                      </el-check-tag>
                    </span>
                  </el-tooltip>
                  <span v-if="!providers.length" class="hint">还没有厂商，先在下方新增</span>
                </div>
              </div>
              <div class="srow">
                <span class="rl">向量模型</span>
                <el-select :model-value="settings?.activeEmbedding" size="small" style="width:180px"
                           placeholder="选择一个厂商"
                           @change="setEmbeddingProvider">
                  <el-option v-for="p in providers" :key="p.name" :value="p.name"
                             :label="!p.embeddingModel ? p.name + '（未配向量）' : (!p.hasKey ? p.name + '（缺 Key）' : p.name)"
                             :disabled="!p.embeddingModel || !p.hasKey" />
                </el-select>
                <span class="hint">
                  {{ embProvider?.embeddingModel
                    ? `${embProvider.embeddingModel} · ${embProvider.embeddingDim || 1024} 维`
                    : '该厂商还没填 Embedding 模型' }}
                </span>
              </div>
              <div class="srow">
                <span class="rl">重排模型</span>
                <span class="state" :class="rerankOn ? 'ok' : ''">
                  {{ rerankOn ? (rerankReady ? '已启用 · ' + settings.rerank.model : '已开启但配置不完整') : '未启用（按 RRF 分数排序）' }}
                </span>
              </div>
            </section>

            <section class="card">
              <div class="card-h">
                <div>
                  <div class="sec-title">厂商配置</div>
                  <div class="sec-sub">改完记得保存；「测试」直接用当前填写值，不必先保存</div>
                </div>
                <el-button size="small" @click="addProvider">＋ 新增厂商</el-button>
              </div>

              <!-- 未保存提示：只有 dirty 时才出现，避免常驻噪音 -->
              <div v-if="dirty" class="dirty">
                <span class="dt"><i class="dot-warn"></i>{{ changedCount }} 项未保存</span>
                <span class="dh">改动只在本页生效，保存后问答与索引才会使用</span>
                <el-button size="small" type="primary" @click="saveProviders">保存修改</el-button>
                <el-button size="small" @click="discard">撤销</el-button>
              </div>

              <el-collapse v-model="openPanels" class="pv">
                <el-collapse-item v-for="p in draft" :key="p._uid" :name="p._uid" :data-uid="p._uid">
                  <template #title>
                    <span class="pv-t">
                      <span class="pv-n">
                        <i v-if="isChanged(p) && !p._isNew" class="dot-edit" title="有未保存的修改"></i>{{ trim(p.name) || '未命名厂商' }}
                      </span>
                      <span class="pv-m">{{ p.chatModel || '未配聊天模型' }}</span>
                      <el-tag v-if="p._isNew" size="small" effect="plain" type="warning" class="mini-tag">新增</el-tag>
                      <el-tag v-if="!p._isNew && p.name === settings?.activeChat" size="small" effect="plain" class="mini-tag">问答</el-tag>
                      <el-tag v-if="!p._isNew && p.name === settings?.activeEmbedding" size="small" effect="plain" type="info" class="mini-tag">向量</el-tag>
                      <el-tag size="small" effect="plain" :type="(p.hasKey || trim(p._key)) ? 'success' : 'warning'" class="mini-tag">
                        {{ trim(p._key) ? 'Key 待保存' : (p.hasKey ? 'Key 已保存' : '缺 Key') }}
                      </el-tag>
                    </span>
                  </template>

                  <el-form label-position="top" class="fgrid">
                    <el-form-item label="名称" :error="errors[p._uid]?.name">
                      <el-input v-model="p.name" size="small" maxlength="40" @input="touch(p)" />
                    </el-form-item>
                    <el-form-item label="Base URL" :error="errors[p._uid]?.baseUrl">
                      <el-input v-model="p.baseUrl" size="small" placeholder="https://…/v1" @input="touch(p)" />
                    </el-form-item>
                    <el-form-item label="API Key" class="span2">
                      <el-input v-model="p._key" type="password" show-password size="small" @input="touch(p)"
                                :placeholder="p.hasKey ? `已保存 ${p.apiKeyMasked}，留空不改` : 'sk-…'" />
                      <div class="fh">
                        {{ p.hasKey
                          ? '留空表示不修改；填写新值会覆盖已保存的 Key'
                          : '还没保存过 Key，填一个才能测试/使用' }}
                      </div>
                    </el-form-item>
                    <el-form-item label="聊天模型">
                      <el-input v-model="p.chatModel" size="small" placeholder="如 deepseek-chat" @input="touch(p)" />
                    </el-form-item>
                    <el-form-item label="Embedding 模型">
                      <el-input v-model="p.embeddingModel" size="small" placeholder="留空则不启用向量" @input="touch(p)" />
                    </el-form-item>
                    <el-form-item label="向量维度" :error="errors[p._uid]?.dim">
                      <div class="inline">
                        <el-input-number v-model="p.embeddingDim" size="small" :min="256" :max="4096" :step="256"
                                         controls-position="right" style="width:140px" @change="touch(p)" />
                        <el-button v-if="embTest[p._uid]?.dim && embTest[p._uid].dim !== p.embeddingDim"
                                   link size="small" type="primary" @click="useTestedDim(p)">
                          用实测 {{ embTest[p._uid].dim }} 维
                        </el-button>
                      </div>
                    </el-form-item>

                    <div class="span2 btnline">
                      <el-button size="small" :loading="chatTest[p._uid]?.ok === null" @click="testConn(p)">测试聊天</el-button>
                      <el-button size="small" :loading="embTest[p._uid]?.ok === null" @click="testEmbedding(p)">测试向量</el-button>
                      <el-button size="small" type="danger" plain @click="removeProvider(p)">删除</el-button>
                      <span v-if="chatTest[p._uid]?.ok === true" class="t ok">
                        ✓ 聊天连通{{ chatTest[p._uid].tookMs != null ? ` · ${chatTest[p._uid].tookMs}ms` : '' }}
                      </span>
                      <span v-else-if="chatTest[p._uid]?.ok === false" class="t bad">✕ 聊天：{{ chatTest[p._uid].error }}</span>
                      <span v-if="embTest[p._uid]?.ok === true" class="t ok">
                        ✓ 向量 OK · {{ embTest[p._uid].dim }} 维{{ embTest[p._uid].tookMs != null ? ` · ${embTest[p._uid].tookMs}ms` : '' }}
                      </span>
                      <span v-else-if="embTest[p._uid]?.ok === false" class="t bad">✕ 向量：{{ embTest[p._uid].error }}</span>
                    </div>
                  </el-form>
                </el-collapse-item>
              </el-collapse>

              <div v-if="!draft.length" class="empty">
                还没有厂商。点右上「新增厂商」填地址、模型与 Key，才能开始问答。
              </div>
              <div v-else class="card-foot">
                <el-button size="small" type="primary" :disabled="!dirty" @click="saveProviders">保存修改</el-button>
                <el-button size="small" :disabled="!dirty" @click="discard">撤销</el-button>
                <span class="hint">
                  {{ dirty ? `有 ${changedCount} 项待保存` : '所有修改已保存' }}
                </span>
              </div>
            </section>

            <section class="card">
              <div class="card-h">
                <div>
                  <div class="sec-title">重排序（Rerank）</div>
                  <div class="sec-sub">召回后再用重排模型逐条精读打分，把真正相关的顶到前面</div>
                </div>
                <span class="state" :class="rerankOn ? 'ok' : ''">{{ rerankOn ? '已启用' : '未启用' }}</span>
              </div>

              <el-form v-if="settings?.rerank" label-position="top" class="fgrid">
                <el-form-item label="启用重排" class="span2">
                  <div class="inline">
                    <el-switch v-model="settings.rerank.enabled" />
                    <span class="hint">开启后每次检索多一次模型调用，约 200-400ms；失败会自动回落到 RRF 顺序</span>
                  </div>
                </el-form-item>
                <el-form-item label="Base URL">
                  <el-input v-model="settings.rerank.baseUrl" size="small" placeholder="https://api.siliconflow.cn/v1" />
                </el-form-item>
                <el-form-item label="模型">
                  <el-input v-model="settings.rerank.model" size="small" placeholder="BAAI/bge-reranker-v2-m3" />
                </el-form-item>
                <el-form-item label="API Key" class="span2">
                  <el-input v-model="rerankKey" type="password" show-password size="small"
                            :placeholder="settings.rerank.hasKey ? `已保存 ${settings.rerank.apiKeyMasked}` : '填入重排服务密钥'" />
                </el-form-item>
                <el-form-item label="送排候选数">
                  <el-input-number v-model="settings.rerank.candidate" size="small" :min="5" :max="50"
                                   controls-position="right" style="width:130px" />
                </el-form-item>
                <el-form-item label="保留条数">
                  <el-input-number v-model="settings.rerank.topN" size="small" :min="0" :max="20"
                                   controls-position="right" style="width:130px" />
                  <span class="hint" style="margin-left:8px">0 = 用 top_k</span>
                </el-form-item>
                <div class="span2 card-foot">
                  <el-button size="small" :loading="rerankTesting" @click="testRerank">测试连通</el-button>
                  <el-button size="small" type="primary" @click="saveRerank">保存</el-button>
                  <span v-if="rerankTest" class="t" :class="rerankTest.ok ? 'ok' : 'bad'">
                    {{ rerankTest.ok
                      ? `连通正常 · ${rerankTest.tookMs}ms · 分数 ${(rerankTest.scores || []).map((x) => x.toFixed(3)).join(' / ')}`
                      : rerankTest.error }}
                  </span>
                </div>
              </el-form>
            </section>
          </template>

          <!-- ══ 索引与用量 ══ -->
          <template v-else-if="tab === 'retrieval'">
            <section class="card">
              <div class="card-h">
                <div>
                  <div class="sec-title">分块模式</div>
                  <div class="sec-sub">决定文档切分成检索单元的方式，改动后下次构建自动全量重建</div>
                </div>
                <el-select :model-value="chunkMode" size="small" style="width:240px" @change="saveChunkMode">
                  <el-option value="fixed" label="滑动窗口（默认）" />
                  <el-option value="parent-child" label="父子分块（检索子块 / 回读小节）" />
                </el-select>
              </div>
              <div class="notes" v-if="chunkMode === 'parent-child'">
                <div class="note-line">
                  子块
                  <el-input-number v-model="childChars" size="small" :min="120" :max="800" :step="20"
                                   style="width:110px;margin:0 6px" @change="saveChunkParams" />
                  字 · 父块上限
                  <el-input-number v-model="parentChars" size="small" :min="600" :max="6000" :step="200"
                                   style="width:120px;margin:0 6px" @change="saveChunkParams" />
                  字
                </div>
                <div class="note-line"><b>检索</b>用小子块，定位更准；<b>回答</b>用整小节上下文，句子不再被拦腰截断</div>
                <div class="note-line">切换或调参后全库分块结构变化，需要点「知识库 → 构建索引」全量重建（向量会按内容 hash 自动复用）</div>
              </div>
              <div class="notes" v-else>
                <div class="note-line">按目标块长滑动窗口切分，相邻窗口带重叠，是最稳妥的通用方式</div>
              </div>
            </section>

            <section class="card">
              <div class="card-h">
                <div>
                  <div class="sec-title">自动增量重扫</div>
                  <div class="sec-sub">定时对每个知识库跑一次增量构建：只处理新增与改动的文件</div>
                </div>
                <el-select :model-value="autoMinutes" size="small" style="width:190px" @change="saveAutoMinutes">
                  <el-option v-for="o in AUTO_OPTS" :key="o.v" :value="o.v" :label="o.l" />
                </el-select>
              </div>
              <div class="notes">
                <div class="note-line"><b>无变化时</b>只比对文件签名，通常 &lt; 1 秒，不会调用 embedding</div>
                <div class="note-line"><b>有改动时</b>按内容 hash 复用向量，只对新内容计费</div>
                <div class="note-line">超过成本护栏会停在「等待确认」，在「知识库」页处理；单个库正在构建时本轮跳过</div>
              </div>
            </section>

            <section class="card">
              <div class="card-h">
                <div>
                  <div class="sec-title">用量统计</div>
                  <div class="sec-sub">本机口径，只用于估算调用量，不做精确计费</div>
                </div>
                <el-button size="small" :icon="Refresh" @click="loadUsage">刷新</el-button>
              </div>
              <div class="tiles">
                <div v-for="t in usageTiles" :key="t.k" class="tile">
                  <div class="tv">{{ t.v }}</div>
                  <div class="tk">{{ t.k }}<span class="ts">{{ t.s }}</span></div>
                </div>
              </div>
              <div class="notes">
                <div class="note-line">
                  重排平均耗时 <b>{{ usage?.rerankAvgMs || 0 }} ms</b>，失败
                  <b>{{ usage?.rerankFail || 0 }}</b> 次（失败自动回落 RRF，不影响使用）
                </div>
                <div class="note-line" v-if="usage?.since">统计起始日：{{ usage.since }}，跨天不清零累计值</div>
              </div>
            </section>
          </template>

          <!-- ══ 集成与接口 ══ -->
          <template v-else-if="tab === 'integration'">
            <section class="card">
              <div class="card-h">
                <div>
                  <div class="sec-title">MCP 服务</div>
                  <div class="sec-sub">供 Claude Code / 桌面版 / Cursor 等支持 MCP 的客户端检索本库，纯只读、不调用大模型</div>
                </div>
                <el-switch :model-value="mcpEnabled" @change="toggleMcp" />
              </div>

              <div class="kv">
                <span class="kl">SSE 地址</span>
                <code>{{ mcpInfo.sseUrl || '加载中…' }}</code>
                <el-tag size="small" effect="plain" type="success">transport: sse</el-tag>
              </div>
              <div class="kv" v-if="mcpInfo.messageEndpoint">
                <span class="kl">消息端点</span>
                <span class="vs">{{ mcpInfo.messageEndpoint }}</span>
              </div>
              <div class="kv">
                <span class="kl">工具</span>
                <span class="vs">{{ mcpInfo.toolCount || 0 }} 个 · 由服务端实际注册的信息生成</span>
              </div>

              <div class="tools">
                <div v-for="t in mcpInfo.tools" :key="t.name" class="tool">
                  <div class="th">
                    <code class="tn">{{ t.name }}</code>
                    <span class="td">{{ t.description }}</span>
                  </div>
                  <div class="tps">
                    <span v-for="p in t.params" :key="p.name" class="tp" :class="{ req: p.required }"
                          :title="p.description">
                      {{ p.name }}<span class="tty">{{ p.type }}</span><span v-if="p.required" class="treq">*</span>
                    </span>
                  </div>
                </div>
                <div v-if="!mcpInfo.tools?.length" class="hint">拿不到工具列表（MCP 未启用或启动异常）</div>
              </div>

              <div class="card-foot">
                <el-button size="small" @click="copyCmd">复制 Claude Code 命令</el-button>
                <el-button size="small" @click="copyJson">复制 JSON 配置</el-button>
                <el-button size="small" :disabled="!bridgeJson" @click="copyBridge">复制桥接配置</el-button>
                <span class="hint">桌面版 / Cursor 用 JSON；客户端只认 stdio 时用 mcp-remote 桥接</span>
              </div>
              <div class="notes">
                <div class="note-line">停用后<strong>新连接立即拒绝、已有连接需重启后端才断开</strong>；工具调用会当场返回「MCP 已停用」</div>
                <div class="note-line">默认查第一个知识库；多库时用 <code>kb_list</code> 拿 kbId，也可直接传库名</div>
              </div>
            </section>

            <section class="card">
              <div class="card-h">
                <div>
                  <div class="sec-title">开放接口（REST）</div>
                  <div class="sec-sub">给其他系统调用检索能力，请求头带 X-API-Key；只读检索，不含 LLM</div>
                </div>
                <el-switch :model-value="apiCfg.enabled" @change="(v) => saveApi({ enabled: v })" />
              </div>
              <div class="kv">
                <span class="kl">API Key</span>
                <el-input :model-value="apiCfg.key" readonly size="small" style="max-width:300px"
                          :placeholder="apiCfg.key ? '' : '还没生成，点「重置」生成一个'" />
                <el-button size="small" :disabled="!apiCfg.key" @click="copyKey">复制</el-button>
                <el-button size="small" :disabled="!apiCfg.enabled" @click="regenKey">重置</el-button>
              </div>
              <div class="code-block">
                <div class="cb-h">调用示例</div>
                <pre class="codeq">{{ curlExample }}</pre>
              </div>
              <div class="notes">
                <div class="note-line">可用端点：<code>GET /api/v1/search</code> · <code>POST /api/v1/search</code> · <code>GET /api/v1/kbs</code></div>
              </div>
            </section>
          </template>

          <!-- ══ 数据与备份 ══ -->
          <template v-else>
            <section class="card">
              <div class="card-h">
                <div>
                  <div class="sec-title">备份与同步（坚果云）</div>
                  <div class="sec-sub">通过 WebDAV 备份数据、恢复快照、多台电脑间同步；索引不参与同步，恢复后自动重建</div>
                </div>
                <el-switch v-model="syncCfg.autoDaily" active-text="每日自动备份" @change="saveSyncCfg" />
              </div>

              <el-form label-position="top" class="fgrid">
                <el-form-item label="服务器" class="span2">
                  <el-input v-model="syncCfg.server" size="small" placeholder="https://dav.jianguoyun.com/dav/" />
                </el-form-item>
                <el-form-item label="账号">
                  <el-input v-model="syncCfg.user" size="small" placeholder="手机号或邮箱" />
                </el-form-item>
                <el-form-item label="应用密码">
                  <el-input v-model="syncPwd" type="password" show-password size="small"
                            :placeholder="hasSyncPwd ? '已保存，留空不改' : '坚果云 → 安全选项里生成的应用密码'" />
                </el-form-item>
              </el-form>

              <div class="card-foot">
                <el-button size="small" :disabled="syncRunning" @click="testSync">测试连接</el-button>
                <el-button size="small" :disabled="syncRunning" @click="saveSyncCfg">保存配置</el-button>
                <el-button size="small" type="primary" :disabled="syncRunning" @click="doBackup">立即备份</el-button>
                <el-button size="small" :disabled="syncRunning" @click="doRestore">从云端恢复</el-button>
              </div>

              <div v-if="syncRunning" class="prog">
                <el-progress :percentage="syncProg.percent" :stroke-width="8" :show-text="false" status="warning" />
                <div class="pt2">{{ syncProg.phase }}（{{ syncProg.percent }}%）</div>
              </div>

              <div class="notes">
                <div class="note-line">同步范围：文档副本 · 会话 · 智能体配置（<b>不含 API Key 与索引</b>）</div>
                <div class="note-line">冲突策略：后写胜出；恢复前会自动做一次本地安全备份</div>
              </div>
            </section>
          </template>

        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* 左导航 + 右内容：设置项多的时候比两列卡片流好找得多 */
.set-wrap{flex:1;min-height:0;display:flex;gap:20px;padding:0 var(--page-x)}
.set-nav{flex:0 0 216px;display:flex;flex-direction:column;gap:2px;overflow:auto;padding-bottom:24px}
.nv{display:flex;align-items:flex-start;gap:10px;width:100%;padding:9px 10px;border:0;border-radius:9px;
  background:transparent;text-align:left;cursor:pointer;color:var(--ink2);
  transition:background var(--t-fast) var(--ease),color var(--t-fast) var(--ease)}
.nv:hover{background:rgba(255,255,255,.7)}
.nv.on{background:var(--card);color:var(--accent);box-shadow:0 1px 2px rgba(31,35,41,.05),0 0 0 1px var(--line)}
.nvi{font-size:15px;margin-top:1px;color:var(--ink3)}
.nv.on .nvi{color:var(--accent)}
.nvm{display:flex;flex-direction:column;gap:2px;min-width:0}
.nvt{font-size:var(--fs-sm);font-weight:600;line-height:1.4}
.nvs{font-size:var(--fs-2xs);color:var(--ink3);line-height:1.4;
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.nv.on .nvs{color:var(--ink2)}

.set-main{flex:1;min-width:0;overflow:auto;padding-bottom:28px}
.set-inner{max-width:860px;display:flex;flex-direction:column;gap:var(--gap)}

.card{background:var(--card);border:1px solid var(--line);border-radius:var(--radius);padding:16px 18px}
.card-h{display:flex;align-items:flex-start;gap:12px;margin-bottom:14px}
.card-h .sec-sub{margin-top:3px}
.card-h .el-button,.card-h .el-select,.card-h .el-switch,.card-h .state{margin-left:auto;flex:0 0 auto}
.card-foot{display:flex;align-items:center;gap:8px;flex-wrap:wrap;
  margin-top:6px;padding-top:13px;border-top:1px dashed var(--line-soft)}

/* 单行设置项：左标签定宽，右边任意控件 */
.srow{display:flex;align-items:center;gap:10px;padding:7px 0;flex-wrap:wrap}
.srow + .srow{border-top:1px dashed var(--line-soft)}
.rl{flex:0 0 72px;font-size:var(--fs-xs);color:var(--ink3)}
.tags{display:flex;flex-wrap:wrap;gap:7px;align-items:center}
.tagwrap{display:inline-flex}
.state{font-size:var(--fs-xs);color:var(--ink3)}
.state.ok{color:var(--mint)}
.hint{font-size:var(--fs-2xs);color:var(--ink3);line-height:1.5}
.inline{display:flex;align-items:center;gap:10px;flex-wrap:wrap}

.pill{display:inline-flex;align-items:center;gap:5px;height:24px;padding:0 9px;border-radius:7px;
  background:var(--mist);border:1px solid var(--line);font-size:var(--fs-2xs);color:var(--ink2)}
.pill .dot{width:5px;height:5px;border-radius:50%;background:#C9CCD2}
.pill .dot.on{background:var(--mint)}

/* 厂商折叠：标题行做出「一行摘要」的效果 */
.pv{border:0}
.pv :deep(.el-collapse-item__header){height:44px;padding:0 12px;background:var(--mist);
  border:1px solid var(--line-soft);border-radius:var(--radius-sm);margin-bottom:6px}
.pv :deep(.el-collapse-item__header.is-active){border-color:var(--line);border-bottom-left-radius:0;border-bottom-right-radius:0}
.pv :deep(.el-collapse-item__wrap){background:transparent;border-bottom:0;padding:0}
.pv :deep(.el-collapse-item__content){padding:2px 12px 10px;border:1px solid var(--line-soft);border-top:0;
  border-bottom-left-radius:var(--radius-sm);border-bottom-right-radius:var(--radius-sm);margin-top:-6px;margin-bottom:8px}
.pv-t{display:flex;align-items:center;gap:8px;min-width:0;font-size:var(--fs-sm)}
.pv-n{display:inline-flex;align-items:center;font-weight:600;color:var(--ink)}
.dot-edit{width:5px;height:5px;border-radius:50%;background:var(--amber);margin-right:5px;flex:0 0 5px}
.pv-m{color:var(--ink3);font-size:var(--fs-xs);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mini-tag{margin-left:2px;transform:scale(.94)}

.fgrid{display:grid;grid-template-columns:1fr 1fr;gap:0 14px}
.fgrid :deep(.el-form-item){margin-bottom:12px}
.fgrid .span2{grid-column:1 / -1}
.fgrid :deep(.el-form-item__label){font-size:var(--fs-2xs);color:var(--ink3);padding-bottom:3px;line-height:1.4}
.btnline{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:2px}
.btnline .t{margin-left:4px}
/* 字段下的补充说明（如 Key 留空 = 不改） */
.fh{font-size:var(--fs-2xs);color:var(--ink3);line-height:1.5;margin-top:3px}
/* 未保存提示条：只在该卡有改动时出现 */
.dirty{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:0 0 12px;
  padding:8px 12px;border-radius:var(--radius-sm);background:#FFF8EC;border:1px solid #F2E2C4}
.dirty .dt{display:inline-flex;align-items:center;gap:6px;font-size:var(--fs-xs);
  font-weight:600;color:#8A5A00}
.dirty .dot-warn{width:6px;height:6px;border-radius:50%;background:var(--amber)}
.dirty .dh{font-size:var(--fs-2xs);color:#A2751F}
.dirty .el-button{margin-left:0}
.dirty .el-button:first-of-type{margin-left:auto}

.t{font-size:var(--fs-2xs);line-height:1.5}
.t.ok{color:var(--mint)}
.t.bad{color:var(--el-color-danger)}

.notes{margin-top:12px;padding-top:10px;border-top:1px dashed var(--line-soft);display:flex;flex-direction:column;gap:5px}
.note-line{font-size:var(--fs-2xs);color:var(--ink3);line-height:1.7}
.note-line b{color:var(--ink2);font-weight:600}
.note-line code{background:var(--mist);border:1px solid var(--line-soft);border-radius:5px;padding:1px 5px;color:var(--ink2)}

/* 用量数字块 */
.tiles{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
.tile{background:var(--mist);border:1px solid var(--line-soft);border-radius:var(--radius-sm);padding:12px 14px}
.tv{font-size:var(--fs-2xl);font-weight:700;color:var(--ink);line-height:1.2;font-variant-numeric:tabular-nums}
.tk{font-size:var(--fs-xs);color:var(--ink2);margin-top:4px}
.ts{font-size:var(--fs-2xs);color:var(--ink3);margin-left:5px}

/* 键值行（MCP / 接口） */
.kv{display:flex;align-items:center;gap:10px;padding:7px 0;border-bottom:1px dashed var(--line-soft);flex-wrap:wrap}
.kv:last-of-type{border-bottom:0}
.kl{flex:0 0 76px;font-size:var(--fs-xs);color:var(--ink3)}
.vs{font-size:var(--fs-xs);color:var(--ink2)}
.kv code{background:var(--mist);border-radius:6px;padding:3px 9px;font-family:var(--mono);
  font-size:var(--fs-2xs);color:var(--accent);border:1px solid var(--line-soft)}

.code-block{margin-top:12px}
.cb-h{font-size:var(--fs-2xs);color:var(--ink3);margin-bottom:6px}
.codeq{margin:0;padding:11px 13px;background:var(--mist);border:1px solid var(--line-soft);border-radius:8px;
  font-family:var(--mono);font-size:var(--fs-2xs);color:var(--ink2);white-space:pre-wrap;
  overflow-wrap:anywhere;line-height:1.75}
.tools{margin-top:12px;border-top:1px dashed var(--line-soft);padding-top:10px;
  display:flex;flex-direction:column;gap:9px}
.tool{display:flex;flex-direction:column;gap:5px}
.th{display:flex;gap:8px;align-items:baseline;flex-wrap:wrap}
.tn{font-family:var(--mono);font-size:var(--fs-xs);color:var(--accent);background:var(--accent-soft);
  border-radius:5px;padding:1px 7px}
.td{font-size:var(--fs-xs);color:var(--ink3);line-height:1.6;flex:1;min-width:0}
.tps{display:flex;flex-wrap:wrap;gap:5px;padding-left:2px}
.tp{display:inline-flex;align-items:center;gap:4px;height:22px;padding:0 7px;border-radius:6px;
  border:1px solid var(--line-soft);background:var(--mist);font-size:var(--fs-2xs);
  color:var(--ink2);font-family:var(--mono)}
.tp.req{color:var(--ink);border-color:var(--line)}
.tp .tty{color:var(--ink3);font-size:10px}
.tp .treq{color:var(--el-color-danger)}
.inline-code{background:var(--mist);border:1px solid var(--line-soft);border-radius:6px;padding:3px 8px;
  font-family:var(--mono);font-size:var(--fs-2xs);color:var(--ink3);white-space:nowrap;
  overflow:hidden;text-overflow:ellipsis;max-width:100%}

.prog{margin-top:14px}
.pt2{font-size:var(--fs-2xs);color:var(--ink3);margin-top:5px}
.empty{padding:16px 0;font-size:var(--fs-sm);color:var(--ink3)}
</style>
