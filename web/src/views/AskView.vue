<script setup>
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { api, askSse, downloadText } from '../api'
import { preview } from '../store/preview'
import PreviewDrawer from '../components/PreviewDrawer.vue'
import { mdToHtml } from '../utils/md'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Search, EditPen, Collection, Delete, RefreshLeft } from '@element-plus/icons-vue'

const sessions = ref([])
const currentId = ref('')
const messages = ref([]) // {role, content, sources?}
const question = ref('')
const askInputRef = ref(null)
const upInput = ref(null)
const uploading = ref(false)
const topK = ref(5)              // 送进上下文的片段数
const styleMode = ref('normal')  // brief | normal | detail

const STYLES = [
  { v: 'brief', l: '简洁' },
  { v: 'normal', l: '标准' },
  { v: 'detail', l: '详细' },
]

/** 常用指令：点一下填进输入框，不直接发送，避免误触 */
const QUICK_PROMPTS = [
  { label: '总结关键节点', q: '把这个流程的关键节点总结成 5 条，按先后顺序排列' },
  { label: '上线审批清单', q: '列出上线前需要完成的审批清单，按先后顺序' },
  { label: '需求变更点', q: '相比上一版，这份需求有哪些变更点？' },
  { label: '各环节责任人', q: '这个流程里各环节的责任人/角色分别是谁？' },
  { label: '驳回后怎么处理', q: '如果某个审批环节被驳回，应该怎么处理？' },
]
const generating = ref(false)
const follow = ref(true)
const chatEl = ref(null)
let abortCtl = null
const folders = ref([])
const activeFolder = ref('')
const agentKbs = ref([{ id: 'default', name: '默认知识库' }])
const settings = ref(null)
const showArchived = ref(false)
const renaming = ref('')
const renameText = ref('')

const activeList = computed(() => sessions.value.filter((s) => !s.archived))
const archivedList = computed(() => sessions.value.filter((s) => s.archived))
const sessQ = ref('')
const agentId = ref('')   // 当前选中的智能体（不再有「默认助手」兜底，必选其一）
const agentList = ref([])
const agentDialog = ref(false)
const agentForm = ref({})
const editingAgentId = ref(null)
const collapsed = ref({})
const AGENT_COLORS = ['#6366F1', '#22A06B', '#C77808', '#E5484D', '#0EA5E9', '#D946EF', '#8F949E']

const agentOf = (id) => agentList.value.find((x) => x.id === id) || null
const agentKbId = () => {
  const a = agentOf(agentId.value)
  return (a && a.kbId) || 'default'
}
/** 当前检索范围所属知识库名（范围条标题用） */
const currentKbName = computed(() => {
  const id = agentKbId()
  return agentKbs.value.find((k) => k.id === id)?.name || '默认知识库'
})
/** 会话已绑定的智能体：有值时模型与检索范围都由它决定 */
const lockedAgent = computed(() => {
  if (!currentId.value) return null
  const s = sessions.value.find((x) => x.id === currentId.value)
  return s?.agentId ? agentOf(s.agentId) : null
})
/** 范围条显示「全部」，内部空串表示不过滤 */
/** 操作条上显示的检索范围文案 */
const scopeLabel = computed(() => (activeFolder.value ? activeFolder.value : '全部'))

const scopeValue = computed({
  get: () => activeFolder.value || '全部',
  set: (v) => { activeFolder.value = v === '全部' ? '' : v },
})
/** 按智能体所属知识库重算检索范围，默认「全部」 */
async function applyAgentScope(aid) {
  activeFolder.value = ''
  try {
    folders.value = await api.get('/api/docs/folders?kbId=' + ((agentOf(aid)?.kbId) || 'default'))
  } catch (e) {
    folders.value = []
  }
  loadKbTags()
}

// ── 标签过滤（可选）：只检索带所选标签的文档 ──
const askTags = ref([])
const kbAllTags = ref([])
async function loadKbTags() {
  askTags.value = []
  try {
    const r = await api.get('/api/kb/' + agentKbId() + '/docmeta')
    kbAllTags.value = r.ok ? (r.tags || []) : []
  } catch { kbAllTags.value = [] }
}
function onAgentChange(v) {
  agentId.value = v
  applyAgentScope(v)
}
function loadAgents() {
  api.get('/api/agents').then((a) => { agentList.value = a })
}
function openAgentDialog(agent) {
  editingAgentId.value = agent?.id || null
  agentForm.value = agent
    ? { ...agent }
    : { name: '', color: AGENT_COLORS[agentList.value.length % AGENT_COLORS.length],
        description: '', provider: (settings.value?.providers || [])[0]?.name || '',
        model: '', kbId: agentKbId(), systemPrompt: '' }
  agentDialog.value = true
}
function saveAgent() {
  const b = agentForm.value
  if (!b.name.trim()) { ElMessage.warning('请填写智能体名称'); return }
  const req = editingAgentId.value
    ? api.putJson('/api/agents/' + editingAgentId.value, b)
    : api.post('/api/agents', b)
  req.then(() => {
    agentDialog.value = false
    loadAgents()
    ElMessage.success(editingAgentId.value ? '智能体已更新' : '智能体已创建')
  })
}
function deleteAgentFromDialog() {
  if (!editingAgentId.value) return
  ElMessageBox.confirm(`删除智能体「${agentForm.value.name}」？其会话将转给剩余的第一个智能体。`, '删除智能体', {
    type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
  }).then(() => {
    api.del('/api/agents/' + editingAgentId.value).then(() => {
      agentDialog.value = false
      const removed = editingAgentId.value
      loadAgents().then(() => {
        if (agentId.value === removed || !agentOf(agentId.value)) onAgentChange(agentList.value[0]?.id || '')
      })
      reloadSessions()
      ElMessage.success('智能体已删除')
    })
  }).catch(() => {})
}
function genPrompt() {
  const f = agentForm.value
  if (!f.provider) { ElMessage.warning('请先选择厂商'); return }
  f._generating = true
  api.post('/api/agents/gen-prompt', { name: f.name, description: f.description, provider: f.provider, model: f.model })
    .then((r) => {
      if (r.ok) { agentForm.value.systemPrompt = r.prompt; ElMessage.success('提示词已生成，可自由修改') }
      else ElMessage.error(r.error || '生成失败')
    })
    .finally(() => { f._generating = false })
}

const filteredActive = computed(() => {
  const k = sessQ.value.trim()
  return activeList.value.filter((s) => !k || s.title.includes(k))
})
// 会话按智能体分组（每个会话都必须归属某个智能体）
const agentGroups = computed(() => {
  const groups = agentList.value.map((a) => ({
    key: a.id, name: a.name, color: a.color || '#6366F1', sessions: [],
  }))
  const map = new Map(groups.map((g) => [g.key, g]))
  for (const s of filteredActive.value) {
    // 智能体已被删除的历史会话，暂落在第一个智能体下（后端启动时会自动改绑）
    const g = map.get(s.agentId) || groups[0]
    if (g) g.sessions.push(s)
  }
  return groups
})
/** 当前将被使用的智能体（会话未建立时也能确定） */
const activeAgent = computed(() => agentOf(agentId.value))
/** 智能体绑定的知识库是否已被删除 */
const kbMissing = computed(() => {
  if (!agentKbs.value.length) return false
  return !agentKbs.value.some((k) => k.id === agentKbId())
})
const filteredArchived = computed(() => {
  const k = sessQ.value.trim()
  return archivedList.value.filter((s) => !k || s.title.includes(k))
})
const activeProvider = () => settings.value?.providers?.find((p) => p.name === settings.value?.activeChat)

function pickProvider(name) {
  api.putJson('/api/settings', { activeChat: name }).then((s) => { settings.value = s })
}

onMounted(async () => {
  const results = await Promise.allSettled([
    reloadSessions(),
    loadAgents(),
    api.get('/api/settings').then((s) => { settings.value = s }),
    applyAgentScope(''),
    api.get('/api/kb/s').then((k) => { agentKbs.value = k }),
  ])
  for (const r of results) if (r.status === 'rejected') console.warn('加载失败:', r.reason)
  // 不再有「默认助手」：没有选中项时默认落到第一个智能体
  if (!agentId.value && agentList.value.length) {
    agentId.value = agentList.value[0].id
    await applyAgentScope(agentId.value)
  }
})

async function reloadSessions() {
  sessions.value = await api.get('/api/sessions')
}

function groupOf(s) {
  const d = new Date(s.updatedAt)
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const yest = new Date(today); yest.setDate(yest.getDate() - 1)
  if (d >= today) return '今天'
  if (d >= yest) return '昨天'
  return '更早'
}

async function openSession(id) {
  const s = sessions.value.find((x) => x.id === id)
  currentId.value = id
  messages.value = await api.get('/api/sessions/' + id)
  // 回显会话绑定的智能体；未绑定或智能体已删除则落到第一个智能体
  agentId.value = s?.agentId && agentOf(s.agentId) ? s.agentId : (agentList.value[0]?.id || '')
  await applyAgentScope(agentId.value)
  await nextTick(); scrollBottom()
}

function newSession() {
  currentId.value = ''
  messages.value = []
  if (!agentId.value && agentList.value.length) agentId.value = agentList.value[0].id
  applyAgentScope(agentId.value)
}

async function archiveSession(s, v) {
  await api.post(`/api/sessions/${s.id}/archive?value=${v}`)
  await reloadSessions()
}

function renameSession(s) {
  ElMessageBox.prompt('重命名会话：', '重命名', {
    inputValue: s.title, confirmButtonText: '保存', cancelButtonText: '取消',
  }).then(({ value }) => {
    const t = (value || '').trim()
    if (!t || t === s.title) return
    api.post(`/api/sessions/${s.id}/rename?title=${encodeURIComponent(t)}`).then(() => reloadSessions())
  }).catch(() => {})
}

function commitRename(s) {
  const t = renameText.value.trim()
  renaming.value = ''
  if (!t || t === s.title) return
  api.post(`/api/sessions/${s.id}/rename?title=${encodeURIComponent(t)}`).then(() => reloadSessions())
}

function removeSession(s) {
  ElMessageBox.confirm(`删除会话「${s.title}」及其全部消息？删除后不可恢复。`, '删除会话', {
    type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
  }).then(() => {
    api.del(`/api/sessions/${s.id}`).then(() => {
      if (currentId.value === s.id) { currentId.value = ''; messages.value = [] }
      reloadSessions()
      ElMessage.success('会话已删除')
    })
  }).catch(() => {})
}

async function ask(qOverride, opts = {}) {
  const q = (qOverride ?? question.value).trim()
  if (!q || generating.value) return
  if (qOverride === undefined) question.value = ''
  generating.value = true
  follow.value = true
  // regen 复用已有提问，不再重复入栈
  if (!opts.skipUser) messages.value.push({ role: 'user', content: q })
  const ans = reactive({ role: 'assistant', content: '', raw: '', sources: null, error: null, stopped: false, uid: 'a' + Date.now() })
  messages.value.push(ans)
  await nextTick(); scrollBottom()

  // 流式降频：chunk 先进 raw，每 100ms 同步到 content 触发一次渲染（长回答不再逐字卡顿）
  let flushTimer = null
  const flushNow = () => {
    if (flushTimer) { clearTimeout(flushTimer); flushTimer = null }
    ans.content = ans.raw
  }
  const onChunk = (text) => {
    ans.raw += text
    if (!flushTimer) {
      flushTimer = setTimeout(() => { flushTimer = null; ans.content = ans.raw; scheduleScroll() }, 100)
    }
  }

  abortCtl = new AbortController()
  askSse(
    {
      question: q, folder: activeFolder.value, sessionId: currentId.value,
      agentId: agentId.value, topK: topK.value, style: styleMode.value,
      tags: askTags.value.length ? [...askTags.value] : undefined,
    },
    onChunk,
    async (done) => {
      generating.value = false
      abortCtl = null
      flushNow()
      ans.sources = done.sources
      currentId.value = done.sessionId
      // 取回落库后的消息 id，点赞/踩要用
      try {
        const saved = await api.get('/api/sessions/' + done.sessionId)
        const last = saved[saved.length - 1]
        if (last && last.role === 'assistant') {
          ans.id = last.id
          ans.content = last.content
        }
      } catch (e) { /* ignore */ }
      await reloadSessions()
      scrollBottom()
    },
    (msg) => {
      if (String(msg).includes('abort')) return
      generating.value = false
      abortCtl = null
      flushNow()
      ans.error = msg
      scrollBottom()
    },
    abortCtl.signal,
  )
}

function stopGen() {
  if (abortCtl) abortCtl.abort()
  generating.value = false
  const last = messages.value[messages.value.length - 1]
  if (last?.role === 'assistant') {
    if (last.raw) last.content = last.raw   // 保留已生成的部分，不丢尾部
    last.stopped = true
  }
}

function copyAns(m) {
  navigator.clipboard.writeText(m.content || '')
  ElMessage.success('回答已复制')
}

function regen() {
  if (generating.value) return
  let q = ''
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') { q = messages.value[i].content; break }
  }
  if (!q) return
  while (messages.value.length && messages.value[messages.value.length - 1].role === 'assistant') {
    messages.value.pop()
  }
  ask(q, { skipUser: true })
}

function onChatScroll() {
  const el = chatEl.value
  if (!el) return
  const gap = el.scrollHeight - el.scrollTop - el.clientHeight
  follow.value = gap < 80
}

/** 流式期间高频调用：合并到一次 rAF，避免每个 chunk 都强制布局 */
let scrollRaf = false
function scheduleScroll() {
  if (scrollRaf) return
  scrollRaf = true
  requestAnimationFrame(() => { scrollRaf = false; scrollBottom() })
}

function scrollBottom() {
  nextTick(() => {
    if (follow.value && chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight
  })
}

function jumpToBottom() {
  follow.value = true
  scrollBottom()
}

function onEnter(e) {
  if (e.isComposing || e.keyCode === 229) return
  ask()
}

function focusAsk() {
  nextTick(() => askInputRef.value?.focus?.())
}

function onPromptCmd(q) {
  question.value = q
  focusAsk()
}

function onPlusCmd(cmd) {
  if (cmd === 'upload') upInput.value?.click()
  else if (cmd === 'clear') { question.value = ''; focusAsk() }
}

/** 上传文档到当前智能体所属知识库，随后触发增量构建并轮询结果 */
async function onUpload(e) {
  const f = e.target.files?.[0]
  e.target.value = ''
  if (!f) return
  const kb = agentKbId()
  uploading.value = true
  try {
    const res = await fetch('/api/kb/' + kb + '/upload?name=' + encodeURIComponent(f.name), {
      method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: f,
    }).then((r) => r.json())
    if (!res.ok) { ElMessage.error(res.error || '上传失败'); return }
    ElMessage.info('已上传，正在建索引…')
    await api.post('/api/index/rebuild?kbId=' + kb, {})
    for (let i = 0; i < 40; i++) {
      await new Promise((r) => setTimeout(r, 1500))
      const st = await api.get('/api/index/status?kbId=' + kb)
      if (st.live?.phase === 'awaiting_confirm') {
        ElMessage.warning('索引在等你确认向量化，请到「知识库」页处理后再提问')
        break
      }
      if (!st.running) {
        ElMessage.success('「' + f.name + '」已加入知识库，可以直接提问了')
        break
      }
    }
  } catch (err) {
    ElMessage.error('上传失败：' + (err.message || err))
  } finally {
    uploading.value = false
  }
}

/** 赞/踩：同一条只保留最后一次；踩的时候顺带收一句备注 */
async function rate(m, val) {
  if (!m.id) return
  const next = m.feedback === val ? 0 : val
  let note = ''
  if (next === -1) {
    try {
      const { value } = await ElMessageBox.prompt('哪里不对？写一句，方便后面调优（可留空）', '反馈问题', {
        inputPlaceholder: '例如：答案漏了审批环节',
        confirmButtonText: '提交', cancelButtonText: '跳过备注',
      })
      note = value || ''
    } catch (e) { /* 跳过备注也照常记录 */ }
  }
  try {
    await api.post('/api/feedback', { messageId: m.id, sessionId: currentId.value, rating: next, note })
    m.feedback = next === 0 ? null : next
    ElMessage.success(next === 0 ? '已取消评价' : '感谢反馈')
  } catch (e) {
    ElMessage.error('反馈失败：' + (e.message || e))
  }
}

function openSource(src) {
  preview.value = { open: true, docPath: src.docPath, seq: src.seq }
}

function exportMd() {
  const lastAns = [...messages.value].reverse().find((m) => m.role === 'assistant')
  if (!lastAns) return
  let md = ''
  for (const m of messages.value) {
    if (m.role === 'user') md += '## 问\n\n' + m.content + '\n\n'
    else md += '## 答\n\n' + m.content + '\n\n'
    if (m.sources?.length) {
      md += '### 来源\n\n'
      for (const s of m.sources) md += `- [${s.docTitle}](file://${s.docPath})（块 ${s.seq}）\n`
      md += '\n'
    }
  }
  downloadText('知识库问答.md', md)
}

/** markdown 渲染缓存：流式时同一版本只解析一次，避免每次重渲染全量解析 */
const htmlCache = new Map()
function renderAns(m) {
  if (!m) return ''
  const src = m.content || ''
  const hit = htmlCache.get(src)
  if (hit !== undefined) return hit
  const inline = (s) => String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/\[(\d{1,2})\]/g, '<span class="cite" data-cite="$1">$1</span>')
  const html = mdToHtml(src, { inline })
  if (htmlCache.size > 60) htmlCache.clear()
  htmlCache.set(src, html)
  return html
}

/** 底部来源合并：同一文档多个块只显示一条，块号聚在括号里 */
function mergedSources(sources) {
  const map = new Map()
  for (const s of sources || []) {
    const k = s.docPath
    if (!map.has(k)) map.set(k, { ...s, seqs: [s.seq] })
    else if (!map.get(k).seqs.includes(s.seq)) map.get(k).seqs.push(s.seq)
  }
  return [...map.values()]
}

// 角标点击 → 打开对应来源预览
function onBodyClick(e, m) {
  const el = e.target.closest('.cite')
  if (!el || !m.sources?.length) return
  const n = parseInt(el.dataset.cite, 10)
  const src = m.sources[n - 1]
  if (src) openSource(src)
}
</script>

<template>
  <el-container class="qa">
    <el-aside width="248px" class="sess">
      <el-button class="new" @click="newSession">
        <el-icon style="margin-right:5px"><Plus /></el-icon>新会话
      </el-button>
      <el-input v-model="sessQ" size="small" placeholder="搜索会话" clearable class="sessq">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-scrollbar class="sess-list">
        <el-button class="newagent" size="small" @click="openAgentDialog(null)">🤖 新建智能体</el-button>
        <template v-for="g in agentGroups" :key="g.key">
          <div class="lab gh" @click="collapsed[g.key] = !collapsed[g.key]">
            <span class="gdot" :style="{ background: g.color }"></span>
            <span class="arr" :class="{ on: !collapsed[g.key] }">▸</span>{{ g.name }}
            <span class="n">{{ g.sessions.length }}</span>
            <el-icon v-if="g.key !== 'default'" class="gedit" title="编辑智能体"
                     @click.stop="openAgentDialog(agentList.find((a) => a.id === g.key))"><EditPen /></el-icon>
          </div>
          <template v-if="!collapsed[g.key]">
            <div v-for="s in g.sessions" :key="s.id" class="it" :class="{ on: s.id === currentId }" @click="openSession(s.id)">
              <span class="t">{{ s.title }}</span>
              <span class="ops">
                <el-icon @click.stop="renameSession(s)" title="重命名"><EditPen /></el-icon>
                <el-icon @click.stop="archiveSession(s, true)" title="归档"><Collection /></el-icon>
              </span>
              <div class="d">{{ s.updatedAt.slice(5, 16).replace('T', ' ') }}</div>
            </div>
          </template>
        </template>
        <el-empty v-if="!agentGroups.length" :description="agentList.length ? '还没有会话' : '先新建一个智能体'" :image-size="60" />
        <template v-if="archivedList.length">
          <div class="lab arch" @click="showArchived = !showArchived">
            <el-icon><Collection /></el-icon>已归档
            <span class="n">{{ archivedList.length }}</span>
            <el-icon class="arr" :class="{ on: showArchived }"><ArrowDown /></el-icon>
          </div>
          <template v-if="showArchived">
            <div v-for="s in filteredArchived" :key="s.id" class="it" @click="openSession(s.id)">
              <span class="t">{{ s.title }}</span>
              <span class="ops">
                <el-icon @click.stop="archiveSession(s, false)" title="恢复"><RefreshLeft /></el-icon>
                <el-icon @click.stop="removeSession(s)" title="删除"><Delete /></el-icon>
              </span>
              <div class="d">{{ s.updatedAt.slice(5, 10) }}</div>
            </div>
          </template>
        </template>
      </el-scrollbar>
    </el-aside>

    <el-main class="qa-main">
      <div class="qa-top">
        <el-text tag="b" size="large">{{ sessions.find((s) => s.id === currentId)?.title || '新会话' }}</el-text>
        <el-tooltip :disabled="!lockedAgent" placement="bottom"
                    content="该会话已绑定智能体，模型与检索范围随之固定；新建会话可重新选择">
          <el-select :model-value="agentId" size="small" style="width:180px;margin-left:auto"
                     :disabled="!!lockedAgent" placeholder="选择智能体" @change="onAgentChange">
            <el-option v-for="a in agentList" :key="a.id" :value="a.id" :label="'🤖 ' + a.name" />
          </el-select>
        </el-tooltip>
        <el-tag v-if="activeAgent?.provider" size="small" effect="plain" class="model-tag">
          {{ activeAgent.provider }} · {{ activeAgent.model || '默认模型' }}
        </el-tag>
        <el-select v-else :model-value="settings?.activeChat" size="small" style="width:200px"
                   @change="(v) => pickProvider(v)">
          <template #label>{{ activeProvider()?.name || '未配置厂商' }} · {{ activeProvider()?.chatModel || '-' }}</template>
          <el-option v-for="p in settings?.providers || []" :key="p.name" :value="p.name"
                     :label="p.name + ' · ' + (p.chatModel || '')" />
        </el-select>
      </div>

      <div class="chat-scroll" ref="chatEl" @scroll="onChatScroll">
        <div v-if="!messages.length" class="empty-guide">
          <div class="eg-h">向知识库提问，或从下面挑一个开始：</div>
          <div class="eg-list">
            <span v-for="(p, i) in QUICK_PROMPTS.slice(0, 4)" :key="i" class="eg" @click="ask(p.q)">
              {{ p.label }}<i>↗</i>
            </span>
          </div>
          <div class="eg-tip">回答只基于知识库内容，正文中的角标可点开原文核对</div>
        </div>
        <template v-for="(m, i) in messages" :key="m.uid || m.id || i">
          <el-alert v-if="m.role === 'user'" class="q" type="info" :closable="false"
                    :title="m.content.length > 60 ? m.content.slice(0, 60) + '…' : m.content"
                    :description="m.content.length > 60 ? m.content : undefined" />
          <el-card v-else shadow="never" class="ans">
            <template #header>
              <div class="who">
                <el-text size="small" type="info">{{ m.sources ? `基于 ${m.sources.length} 个知识库片段` : '回答' }}</el-text>
                <el-tag v-if="m.stopped" size="small" type="warning" effect="plain">已停止</el-tag>
                <el-button link size="small" @click="copyAns(m)">复制</el-button>
                <span v-if="m.id" class="rate">
                  <el-tooltip content="回答准确" placement="top">
                    <span class="thumb" :class="{ on: m.feedback === 1 }" @click="rate(m, 1)">👍</span>
                  </el-tooltip>
                  <el-tooltip content="答得不对" placement="top">
                    <span class="thumb" :class="{ bad: m.feedback === -1 }" @click="rate(m, -1)">👎</span>
                  </el-tooltip>
                </span>
                <!-- 低频操作：悬停卡片才出现，减少长会话噪音 -->
                <span class="act-more">
                  <el-button link size="small" @click="regen()">重新生成</el-button>
                  <el-button v-if="m.sources" link size="small" @click="exportMd">导出 MD</el-button>
                </span>
              </div>
            </template>
            <div class="body" v-html="renderAns(m)" @click="onBodyClick($event, m)"></div>
            <el-skeleton v-if="generating && !m.content && !m.error && i === messages.length - 1" :rows="2" animated />
            <el-alert v-if="m.error" type="error" :closable="false" :title="m.error" />
            <div v-if="m.sources?.length" class="srcrow">
              <el-text size="small" type="info">来源：</el-text>
              <el-tag v-for="(s, j) in mergedSources(m.sources)" :key="j" class="src" effect="plain"
                      @click="openSource(s)">
                {{ j + 1 }}. {{ s.docTitle }}<span v-if="s.seqs.length > 1" class="srcseq">（{{ s.seqs.length }} 块）</span>
              </el-tag>
            </div>
          </el-card>
        </template>
      </div>
      <el-button v-if="!follow && messages.length" class="to-bottom" circle size="small"
                 title="回到最新" @click="jumpToBottom">↓</el-button>

      <div v-if="!agentList.length" class="scope">
        <el-text size="small" type="warning">还没有智能体，先新建一个再提问</el-text>
        <el-button size="small" type="primary" @click="openAgentDialog(null)">＋ 新建智能体</el-button>
      </div>
      <div v-else-if="lockedAgent || kbMissing" class="scope">
        <el-text v-if="lockedAgent" size="small" type="info" class="scope-hint">
          跟随智能体「{{ lockedAgent.name }}」· 知识库「{{ currentKbName }}」
        </el-text>
        <el-text v-if="kbMissing" size="small" type="danger">
          所属知识库已被删除，请到「知识库」页重建，或编辑智能体改绑
        </el-text>
      </div>

      <!-- 输入区：输入框 + 底部操作条 -->
      <div class="composer">
        <el-input ref="askInputRef" v-model="question" class="askinput" type="textarea"
                  :autosize="{ minRows: 3, maxRows: 10 }"
                  placeholder="向知识库提问，Enter 发送，Shift+Enter 换行…"
                  @keydown.enter.exact.prevent="onEnter" />
        <div class="bar">
          <el-dropdown trigger="click" @command="onPlusCmd">
            <span class="chip" :class="{ busy: uploading }">
              <el-icon><Plus /></el-icon>{{ uploading ? '上传中…' : '附件' }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="upload">上传文档到知识库</el-dropdown-item>
                <el-dropdown-item command="clear">清空输入</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <el-dropdown trigger="click" @command="onPromptCmd">
            <span class="chip">常用指令</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="(p, i) in QUICK_PROMPTS" :key="i" :command="p.q">
                  {{ p.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <el-dropdown trigger="click" @command="(v) => (activeFolder = v)">
            <span class="chip" :title="'检索范围 · ' + currentKbName">范围：{{ scopeLabel }}</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="">全部</el-dropdown-item>
                <el-dropdown-item v-for="f in folders" :key="f" :command="f">{{ f }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <el-popover v-if="kbAllTags.length" trigger="click" width="260" placement="top-start">
            <template #reference>
              <span class="chip" :class="{ on: askTags.length }"
                    :title="'标签过滤 · ' + currentKbName">
                标签{{ askTags.length ? `：${askTags.length}` : '' }}
              </span>
            </template>
            <el-checkbox-group v-model="askTags" class="tagpick">
              <el-checkbox v-for="t in kbAllTags" :key="t" :value="t">{{ t }}</el-checkbox>
            </el-checkbox-group>
            <div class="tagtip">只检索带所选标签（任一命中）的文档</div>
          </el-popover>

          <el-dropdown trigger="click" @command="(v) => (topK = v)">
            <span class="chip">片段 {{ topK }}</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :command="3">3 条（更聚焦）</el-dropdown-item>
                <el-dropdown-item :command="5">5 条（默认）</el-dropdown-item>
                <el-dropdown-item :command="8">8 条（更全面）</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <el-dropdown trigger="click" @command="(v) => (styleMode = v)">
            <span class="chip">{{ STYLES.find((x) => x.v === styleMode)?.l || '标准' }}</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="st in STYLES" :key="st.v" :command="st.v">{{ st.l }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <span class="grow"></span>
          <span v-if="question.length" class="cnt">{{ question.length }}</span>
          <el-button class="send" circle :type="generating ? 'info' : 'primary'"
                     :disabled="!agentList.length && !generating"
                     @click="generating ? stopGen() : ask()">{{ generating ? '■' : '↑' }}</el-button>
        </div>
      </div>
      <input ref="upInput" type="file" hidden @change="onUpload" />
    </el-main>

    <PreviewDrawer :target="preview" :kb-id="agentKbId()" @close="preview.open = false" />

    <!-- 智能体新建/编辑对话框 -->
    <el-dialog v-model="agentDialog" :title="editingAgentId ? '编辑智能体' : '新建智能体'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="agentForm.name" placeholder="例如：运维助手" /></el-form-item>
        <el-form-item label="头像色">
          <div style="display:flex;gap:6px">
            <span v-for="c in AGENT_COLORS" :key="c" class="color-dot" :style="{ background: c }"
                  :class="{ sel: agentForm.color === c }" @click="agentForm.color = c"></span>
          </div>
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="agentForm.description" placeholder="一句话介绍职责" /></el-form-item>
        <el-form-item label="厂商">
          <el-select v-model="agentForm.provider" style="width:100%">
            <el-option v-for="p in settings?.providers || []" :key="p.name" :value="p.name" :label="p.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型"><el-input v-model="agentForm.model" placeholder="如 deepseek-chat" /></el-form-item>
        <el-form-item label="知识库">
          <el-select v-model="agentForm.kbId" style="width:100%">
            <el-option v-for="k in agentKbs" :key="k.id" :value="k.id" :label="k.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input v-model="agentForm.systemPrompt" type="textarea" :rows="4"
                    placeholder="定义角色与回答风格；检索规则自动附加" />
          <el-button size="small" style="margin-top:6px" :loading="agentForm._generating" @click="genPrompt">
            ✨ AI 生成
          </el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="editingAgentId" type="danger" plain style="margin-right:auto"
                   @click="deleteAgentFromDialog">删除智能体</el-button>
        <el-button @click="agentDialog = false">取消</el-button>
        <el-button type="primary" @click="saveAgent">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.qa{height:100%}
.sess{border-right:1px solid var(--el-border-color-light);background:#FBFBFC;padding:12px 8px;display:flex;flex-direction:column}
.sess .new{width:100%}
.sess .sessq{margin-bottom:6px}
.sess .sess-list{flex:1}
.sess .lab{font-size:var(--fs-xs);color:var(--ink3);padding:9px 8px 5px;display:flex;align-items:center;gap:5px}
.sess .lab.arch{cursor:pointer;user-select:none;border-radius:6px;transition:background var(--t-fast) var(--ease)}
.sess .lab.arch:hover{background:#F0F1F4;color:var(--ink)}
.sess .lab.arch .n{margin-left:auto;font-size:var(--fs-2xs);color:var(--ink3)}
.sess .lab.arch .arr{margin-left:auto;font-size:11px;color:var(--ink3);transition:transform var(--t-med) var(--ease)}
.sess .lab.arch:hover .arr{color:var(--accent)}
.sess .lab.arch .arr.on{transform:rotate(180deg)}
.sess .it{position:relative;padding:8px 10px;border-radius:8px;cursor:pointer;font-size:var(--fs-sm);line-height:1.5}
.sess .it:hover{background:#F0F1F4}
.sess .it.on{background:var(--accent-soft)}
.sess .it.on .t{color:var(--accent);font-weight:600}
.sess .it .t{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding-right:46px}
.sess .it:hover .t{padding-right:52px}
.sess .it .ops{position:absolute;top:6px;right:6px;display:flex;gap:2px;opacity:0;transition:opacity var(--t-fast) var(--ease)}
.sess .it:hover .ops{opacity:1}
.sess .ops .el-icon{width:22px;height:22px;border-radius:6px;display:grid;place-items:center;
  font-size:13px;color:var(--ink3);background:#fff;border:1px solid var(--el-border-color);cursor:pointer}
.sess .ops .el-icon:hover{color:var(--accent);border-color:var(--accent)}
.sess .it .d{font-size:var(--fs-2xs);color:var(--ink3);margin-top:2px;font-variant-numeric:tabular-nums}
.qa-main{display:flex;flex-direction:column;padding:0}
.qa-top{display:flex;align-items:center;gap:12px;padding:var(--page-y) var(--page-x) 0;min-height:54px}
.qa-top h3, .qa-top .el-text{font-family:var(--sans);font-size:var(--fs-xl);font-weight:600;letter-spacing:0;line-height:1.35}
.chat-scroll{flex:1;overflow:auto;padding:18px var(--page-x) 20px;display:flex;flex-direction:column;gap:16px}
/* 提问：主色胶囊 */
.q{width:fit-content;margin-left:auto;max-width:66%;flex-shrink:0;background:var(--accent);color:#fff;
  padding:10px 16px;border-radius:var(--radius) var(--radius) 4px var(--radius);white-space:pre-wrap;
  font-size:var(--fs-base);line-height:var(--lh-base)}
/* 回答：档案摘录卡 —— 左侧书脊 */
.ans{max-width:84%;flex-shrink:0;--el-card-border-color:var(--line);
  border-left:3px solid var(--accent);border-radius:6px var(--radius) var(--radius) 6px;
  padding:4px 6px 6px}
.ans :deep(.el-card__header){padding:9px 18px;border-bottom:1px dashed var(--line-soft);background:var(--paper);
  border-radius:6px var(--radius) 0 0}
.ans :deep(.el-card__body){padding:14px 20px 16px}
.who{display:flex;align-items:center;gap:8px}
.who .el-button+.el-button{margin-left:0}
/* 重新生成/导出 MD 等低频操作：悬停卡片才出现 */
.who .act-more{display:inline-flex;gap:8px;opacity:0;transition:opacity var(--t-fast) var(--ease)}
.ans:hover .who .act-more{opacity:1}
/* 滚动到底部悬浮按钮 */
.to-bottom{position:absolute;right:calc(var(--page-x) + 24px);bottom:150px;z-index:6;
  box-shadow:0 4px 14px rgba(20,24,38,.14)}
/* 空态引导 */
.empty-guide{margin:auto;text-align:center;max-width:560px;padding:40px 0}
.eg-h{font-size:var(--fs-base);color:var(--ink2);margin-bottom:14px}
.eg-list{display:flex;flex-wrap:wrap;gap:10px;justify-content:center}
.eg{display:inline-flex;align-items:center;gap:6px;padding:9px 14px;border:1px solid var(--line);
  border-radius:10px;background:var(--card);color:var(--ink);font-size:var(--fs-sm);cursor:pointer;
  transition:all var(--t-fast) var(--ease)}
.eg i{font-style:normal;color:var(--accent);opacity:.5;font-size:12px}
.eg:hover{border-color:var(--accent);color:var(--accent);transform:translateY(-1px);box-shadow:0 4px 12px rgba(62,78,200,.10)}
.eg-tip{margin-top:16px;font-size:var(--fs-2xs);color:var(--ink3)}
.srcseq{opacity:.6;font-size:var(--fs-2xs)}
.ans :deep(.cite){display:inline-grid;place-items:center;min-width:17px;height:17px;padding:0 4px;
  border-radius:4px;background:var(--accent);color:#fff;font-size:10px;font-weight:700;font-family:var(--mono);
  vertical-align:1.5px;margin:0 2px;cursor:pointer}
.ans :deep(.cite:hover){background:var(--ink)}
/* EP 风格引用徽章：浅底描边，悬停填充主色 */
.body :deep(.cite){
  background:var(--el-color-primary-light-9);border:1px solid var(--el-color-primary-light-7);
  color:var(--el-color-primary);box-shadow:none;
}
.body :deep(.cite:hover){
  background:var(--el-color-primary);border-color:var(--el-color-primary);color:#fff;
  transform:translateY(-1px);
}
.body{font-size:var(--fs-base);line-height:var(--lh-loose)}
.body :deep(p){margin:0 0 9px}
.body :deep(ul),.body :deep(ol){margin:0 0 8px;padding-left:20px}
.body :deep(h2){font-family:var(--sans);margin:16px 0 8px;font-size:var(--fs-lg);font-weight:600}
.body :deep(h3){font-family:var(--sans);margin:13px 0 6px;font-size:var(--fs-md);font-weight:600}
.body :deep(h4){font-family:var(--sans);margin:12px 0 6px;font-size:var(--fs-base);font-weight:600}
.body :deep(.md-table){border-collapse:collapse;width:100%;margin:10px 0;font-size:var(--fs-sm);table-layout:fixed}
.body :deep(.md-table th),.body :deep(.md-table td){border:1px solid var(--el-border-color);padding:5px 10px;text-align:left;word-break:break-word;overflow-wrap:anywhere}
.body :deep(.md-table th){background:var(--paper);font-weight:600}
/* 防止长代码/链接/单词撑破卡片产生内部滚动条 */
.body :deep(pre),.body :deep(code){white-space:pre-wrap;word-break:break-word;overflow-x:visible}
.body :deep(a){word-break:break-all}
.body :deep(img){max-width:100%}
.body :deep(*){scrollbar-width:none}
.body :deep(*)::-webkit-scrollbar{display:none}
.rate{display:inline-flex;gap:4px;margin-left:6px}
.rate .thumb{font-size:13px;line-height:1;cursor:pointer;opacity:.45;filter:grayscale(1);transition:all var(--t-fast) var(--ease)}
.rate .thumb:hover{opacity:.9;filter:none}
.rate .thumb.on{opacity:1;filter:none;transform:scale(1.12)}
.rate .thumb.bad{opacity:1;filter:none}
.srcrow{display:flex;gap:6px;flex-wrap:wrap;margin-top:10px;padding-top:10px;border-top:1px dashed var(--line-soft)}
.src{cursor:pointer;font-family:var(--sans);--el-tag-bg-color:var(--paper)}
.thinking{display:flex;align-items:center;gap:6px;color:var(--ink3);font-size:var(--fs-xs);padding:4px 0}
.thinking span{width:6px;height:6px;border-radius:50%;background:var(--accent);opacity:.4;animation:pulse 1.2s infinite}
.thinking span:nth-child(2){animation-delay:.2s}
.thinking span:nth-child(3){animation-delay:.4s}
@keyframes pulse{0%,100%{opacity:.25;transform:scale(.85)}50%{opacity:1;transform:scale(1)}}
/* 输入区：一个带边框的卡片，上面写作、下面一排操作 */
.composer{margin:0 var(--page-x) 16px;background:var(--card);border:1px solid var(--line);
  border-radius:var(--radius-lg);padding:10px 12px 9px;transition:border-color var(--t-fast) var(--ease),box-shadow var(--t-fast) var(--ease)}
.composer:focus-within{border-color:var(--accent);box-shadow:0 0 0 3px rgba(62,78,200,.10)}
.askinput :deep(.el-textarea__inner){border:0;box-shadow:none;background:transparent;resize:none;
  padding:4px 4px 10px;font-size:var(--fs-base);line-height:1.75;min-height:88px}
.askinput :deep(.el-textarea__inner:focus){box-shadow:none}
.bar{display:flex;align-items:center;gap:6px;flex-wrap:wrap}
.chip{display:inline-flex;align-items:center;gap:4px;height:26px;padding:0 9px;border-radius:8px;
  border:1px solid var(--line);background:var(--paper);color:var(--ink2);font-size:var(--fs-xs);
  cursor:pointer;user-select:none;white-space:nowrap;transition:all var(--t-fast) var(--ease)}
.chip:hover{border-color:var(--accent);color:var(--accent)}
.chip.on{border-color:var(--accent);color:var(--accent);background:var(--el-color-primary-light-9)}
.tagpick{display:flex;flex-direction:column;gap:0;max-height:220px;overflow:auto}
.tagpick .el-checkbox{height:26px;margin-right:0}
.tagtip{font-size:var(--fs-2xs);color:var(--el-text-color-secondary);margin-top:6px}
.chip.busy{opacity:.6;pointer-events:none}
.chip .el-icon{font-size:13px}
.bar .grow{flex:1}
.bar .cnt{font-size:var(--fs-2xs);color:var(--ink3);font-variant-numeric:tabular-nums}
.bar .send{width:34px;height:34px;margin-left:2px}
.scope{display:flex;align-items:center;gap:8px;padding:0 var(--page-x) 8px;flex-wrap:wrap;background:var(--paper)}
.newagent{width:100%;margin-bottom:4px}
.sess .lab.gh .gdot{width:9px;height:9px;border-radius:50%;flex:0 0 auto}
.sess .lab.gh .gedit{margin-left:auto;color:var(--ink3);cursor:pointer}
.sess .lab.gh .gedit:hover{color:var(--accent)}
.color-dot{width:20px;height:20px;border-radius:6px;cursor:pointer;border:2px solid transparent}
.model-tag{font-size:var(--fs-xs);color:var(--ink2);background:var(--mist);border-color:var(--line)}
.scope-hint{color:var(--accent);opacity:.85}
.color-dot.sel{border-color:var(--ink)}
</style>
