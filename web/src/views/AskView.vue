<script setup>
defineOptions({ name: 'AskView' }) // keep-alive include 依赖此名字
import { ref, reactive, computed, nextTick, onMounted, onBeforeUnmount, onActivated } from 'vue'
import { api, askSse, downloadText } from '../api'
import { preview } from '../store/preview'
import PreviewDrawer from '../components/PreviewDrawer.vue'
import { mdToHtml } from '../utils/md'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Search, EditPen, Collection, Delete, RefreshLeft, Lock, Setting, Select, CloseBold, Loading, Download } from '@element-plus/icons-vue'

const sessions = ref([])
const currentId = ref('')
const messages = ref([]) // {role, content, sources?}
const question = ref('')
const askInputRef = ref(null)
const upInput = ref(null)
const uploading = ref(false)
const upStatus = ref('')         // 上传/建索引进度文案
const expandedQ = ref({})        // 长提问展开状态
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
let currentAns = null            // 正在生成的回答对象（切会话后仍可定位）
const sessionCache = new Map()   // sid -> 实时消息数组（含生成中回答），切换会话时暂存/恢复
const genSid = ref('')           // 正在生成的会话 id（本地即时标记，不等列表接口刷新）
const folders = ref([])
const activeFolder = ref('')
const agentKbs = ref([{ id: 'default', name: '默认知识库' }])
const settings = ref(null)
const showArchived = ref(false)

const activeList = computed(() => sessions.value.filter((s) => !s.archived))
const archivedList = computed(() => sessions.value.filter((s) => s.archived))
const sessQ = ref('')
const agentId = ref('')   // 当前选中的智能体（不再有「默认助手」兜底，必选其一）
const agentList = ref([])
const agentDialog = ref(false)
const agentForm = ref({})
const editingAgentId = ref(null)
const agentSaving = ref(false)      // 智能体保存中
const agentDeleting = ref(false)    // 智能体删除中
const sessionBusyId = ref('')       // 正在重命名/归档/删除的会话
const ratingMsgId = ref('')         // 正在提交评价的消息
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
/** 检索设置有非默认项时给入口加个角标 */
const scopeDirty = computed(() =>
  !!activeFolder.value || topK.value !== 5 || styleMode.value !== 'normal' || askTags.value.length > 0)

/** 顶部智能体下拉的选项文案：名称 + 模型，一眼看清用的是什么模型 */
function agentLabel(a) {
  const model = a?.model || '默认模型'
  return `${a?.name || '未命名'} · ${model}`
}
/** 顶部下拉选到「新建智能体」时打开对话框 */
function onTopAgentChange(v) {
  if (v === '__new__') { openAgentDialog(null); return }
  onAgentChange(v)
}
/** 长提问折叠/展开 */
function toggleQ(i) {
  expandedQ.value = { ...expandedQ.value, [i]: !expandedQ.value[i] }
}

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
  return api.get('/api/agents').then((a) => { agentList.value = a })
}
function openAgentDialog(agent) {
  editingAgentId.value = agent?.id || null
  const firstProvider = (settings.value?.providers || [])[0]
  agentForm.value = agent
    ? { ...agent }
    : { name: '', color: AGENT_COLORS[agentList.value.length % AGENT_COLORS.length],
        description: '', provider: firstProvider?.name || '',
        model: firstProvider?.chatModel || '', kbId: agentKbId(), systemPrompt: '' }
  agentDialog.value = true
}

/** 厂商 → 该厂商在设置里配置的对话模型 */
function providerOf(name) {
  return (settings.value?.providers || []).find((p) => p.name === name) || null
}

/** 切换厂商时默认带出该厂商的对话模型（用户仍可手改） */
function onAgentProviderChange(name) {
  agentForm.value.provider = name
  const p = providerOf(name)
  agentForm.value.model = p?.chatModel || ''
}
async function saveAgent() {
  if (agentSaving.value) return
  const b = agentForm.value
  if (!b.name.trim()) { ElMessage.warning('请填写智能体名称'); return }
  agentSaving.value = true
  try {
    if (editingAgentId.value) await api.putJson('/api/agents/' + editingAgentId.value, b)
    else await api.post('/api/agents', b)
    agentDialog.value = false
    loadAgents()
    ElMessage.success(editingAgentId.value ? '智能体已更新' : '智能体已创建')
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || e))
  } finally {
    agentSaving.value = false
  }
}
async function deleteAgentFromDialog() {
  if (!editingAgentId.value || agentDeleting.value) return
  try {
    await ElMessageBox.confirm(`删除智能体「${agentForm.value.name}」？其会话将转给剩余的第一个智能体。`, '删除智能体', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch { return } // 用户取消
  agentDeleting.value = true
  try {
    const removed = editingAgentId.value
    await api.del('/api/agents/' + removed)
    agentDialog.value = false
    await loadAgents()
    if (agentId.value === removed || !agentOf(agentId.value)) onAgentChange(agentList.value[0]?.id || '')
    reloadSessions()
    ElMessage.success('智能体已删除')
  } catch (e) {
    ElMessage.error('删除失败：' + (e.message || e))
  } finally {
    agentDeleting.value = false
  }
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
/** 智能体绑定的知识库是否已被删除 */
const kbMissing = computed(() => {
  if (!agentKbs.value.length) return false
  return !agentKbs.value.some((k) => k.id === agentKbId())
})
const filteredArchived = computed(() => {
  const k = sessQ.value.trim()
  return archivedList.value.filter((s) => !k || s.title.includes(k))
})
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
  // 刷新页面后自动回到最近使用的会话（进行中的半截回答后端已保存，能直接看到）
  const last = localStorage.getItem('kb-last-session')
  if (last && sessions.value.some((x) => x.id === last && !x.archived)) {
    openSession(last)
  }
  window.addEventListener('keydown', onGlobalKey)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKey))

let everShown = false
/** keep-alive 切回本页：静默刷新会话/智能体/设置，不影响正在生成的回答 */
onActivated(async () => {
  if (!everShown) { everShown = true; return } // 首次进入由 onMounted 负责
  const results = await Promise.allSettled([
    reloadSessions(),
    loadAgents(),
    api.get('/api/settings').then((v) => { settings.value = v }),
  ])
  for (const r of results) if (r.status === 'rejected') console.warn('切回刷新失败:', r.reason)
})

/** 全局快捷键：Ctrl/Cmd+K 新会话，Esc 停止生成（输入框内不劫持 Esc） */
function onGlobalKey(e) {
  const mod = e.ctrlKey || e.metaKey
  if (mod && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    newSession()
    return
  }
  if (e.key === 'Escape' && generating.value) {
    stopGen()
  }
}

async function reloadSessions() {
  sessions.value = await api.get('/api/sessions')
}


function rememberSession(id) {
  if (id) localStorage.setItem('kb-last-session', id)
  else localStorage.removeItem('kb-last-session')
}

async function openSession(id) {
  // 暂存当前会话的实时消息（可能含生成中的回答），切回来时恢复
  if (currentId.value && messages.value.length) sessionCache.set(currentId.value, messages.value)
  const s = sessions.value.find((x) => x.id === id)
  currentId.value = id
  rememberSession(id)
  if (sessionCache.has(id)) {
    // 该会话有实时视图（比如正在生成回答）：直接恢复，不覆盖
    messages.value = sessionCache.get(id)
  } else {
    messages.value = await api.get('/api/sessions/' + id)
  }
  // 回显会话绑定的智能体；未绑定或智能体已删除则落到第一个智能体
  agentId.value = s?.agentId && agentOf(s.agentId) ? s.agentId : (agentList.value[0]?.id || '')
  await applyAgentScope(agentId.value)
  await nextTick(); scrollBottom()
}

function newSession() {
  if (currentId.value && messages.value.length) sessionCache.set(currentId.value, messages.value)
  currentId.value = ''
  rememberSession('')
  messages.value = []
  if (!agentId.value && agentList.value.length) agentId.value = agentList.value[0].id
  applyAgentScope(agentId.value)
}

async function archiveSession(s, v) {
  if (sessionBusyId.value) return
  sessionBusyId.value = s.id
  try {
    await api.post(`/api/sessions/${s.id}/archive?value=${v}`)
    sessionCache.delete(s.id)
    await reloadSessions()
  } catch (e) {
    ElMessage.error('操作失败：' + (e.message || e))
  } finally {
    sessionBusyId.value = ''
  }
}

function renameSession(s) {
  if (sessionBusyId.value) return
  ElMessageBox.prompt('重命名会话：', '重命名', {
    inputValue: s.title, confirmButtonText: '保存', cancelButtonText: '取消',
  }).then(async ({ value }) => {
    const t = (value || '').trim()
    if (!t || t === s.title) return
    sessionBusyId.value = s.id
    try {
      await api.post(`/api/sessions/${s.id}/rename?title=${encodeURIComponent(t)}`)
      sessionCache.delete(s.id)
      await reloadSessions()
    } catch (e) {
      ElMessage.error('重命名失败：' + (e.message || e))
    } finally {
      sessionBusyId.value = ''
    }
  }).catch(() => {})
}

function removeSession(s) {
  if (sessionBusyId.value) return
  ElMessageBox.confirm(`删除会话「${s.title}」及其全部消息？删除后不可恢复。`, '删除会话', {
    type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
  }).then(async () => {
    sessionBusyId.value = s.id
    try {
      await api.del(`/api/sessions/${s.id}`)
      sessionCache.delete(s.id)
      if (currentId.value === s.id) { currentId.value = ''; messages.value = [] }
      await reloadSessions()
      ElMessage.success('会话已删除')
    } catch (e) {
      ElMessage.error('删除失败：' + (e.message || e))
    } finally {
      sessionBusyId.value = ''
    }
  }).catch(() => {})
}

async function ask(qOverride, opts = {}) {
  const q = (qOverride ?? question.value).trim()
  if (!q || generating.value) return
  if (qOverride === undefined) question.value = ''
  generating.value = true
  follow.value = true
  // 新会话先落库拿到 id：切换会话/标题显示/实时缓存都需要归属，不等生成完
  if (!currentId.value) {
    try {
      const t = q.replace(/\s+/g, ' ').slice(0, 18) || '新会话'
      const created = await api.post('/api/sessions?title=' + encodeURIComponent(t))
      currentId.value = created.id
      rememberSession(created.id)
      reloadSessions()
    } catch (e) { /* 失败则退回旧行为：由后端生成完成后创建 */ }
  }
  const askSid = currentId.value
  genSid.value = askSid
  // regen 复用已有提问，不再重复入栈
  if (!opts.skipUser) messages.value.push({ role: 'user', content: q })
  const ans = reactive({ role: 'assistant', content: '', raw: '', sources: null, error: null, stopped: false, uid: 'a' + Date.now() })
  messages.value.push(ans)
  currentAns = ans
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
      // 用户可能已切到其他会话：只有仍停留在这个会话时才跟随/回读
      const stillHere = !askSid || currentId.value === askSid
      if (stillHere) currentId.value = done.sessionId
      if (stillHere) {
        // 取回落库后的消息 id，点赞/踩要用
        try {
          const saved = await api.get('/api/sessions/' + done.sessionId)
          const last = saved[saved.length - 1]
          if (last && last.role === 'assistant') {
            ans.id = last.id
            ans.content = last.content
          }
        } catch (e) { /* ignore */ }
      }
      sessionCache.delete(done.sessionId) // 已入库，下次进入重新拉取
      currentAns = null
      genSid.value = ''
      await reloadSessions()
      if (currentId.value === done.sessionId) scrollBottom()
    },
    (msg) => {
      if (String(msg).includes('abort')) return
      generating.value = false
      abortCtl = null
      flushNow()
      ans.error = msg
      currentAns = null
      genSid.value = ''
      if (askSid) sessionCache.delete(askSid)
      // 预创建的会话一条消息都没落库时删掉，避免列表留空壳
      if (askSid) {
        api.get('/api/sessions/' + askSid).then((saved) => {
          if (!saved.length) api.del('/api/sessions/' + askSid).then(() => {
            if (currentId.value === askSid) { currentId.value = ''; messages.value = [] }
            reloadSessions()
          }).catch(() => {})
        }).catch(() => {})
      }
      scrollBottom()
    },
    abortCtl.signal,
  )
}

function stopGen() {
  if (abortCtl) abortCtl.abort()
  generating.value = false
  // 用 ask 闭包里的回答对象定位；用户切到别的会话时 messages 已被替换，不能按末尾猜
  const target = currentAns || messages.value[messages.value.length - 1]
  if (target?.role === 'assistant') {
    if (target.raw) target.content = target.raw   // 保留已生成的部分，不丢尾部
    target.stopped = true
  }
  currentAns = null
  genSid.value = ''
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

/** 上传文档到当前智能体所属知识库，随后触发增量构建并轮询结果（带进度） */
async function onUpload(e) {
  const f = e.target.files?.[0]
  e.target.value = ''
  if (!f) return
  const kb = agentKbId()
  uploading.value = true
  upStatus.value = '上传中'
  try {
    const res = await fetch('/api/kb/' + kb + '/upload?name=' + encodeURIComponent(f.name), {
      method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: f,
    }).then((r) => r.json())
    if (!res.ok) { ElMessage.error(res.error || '上传失败'); return }
    upStatus.value = '建索引中'
    await api.post('/api/index/rebuild?kbId=' + kb, {})
    let finished = false
    for (let i = 0; i < 120; i++) {
      await new Promise((r) => setTimeout(r, 1500))
      const st = await api.get('/api/index/status?kbId=' + kb)
      const live = st.live || {}
      if (live.phase === 'awaiting_confirm') {
        ElMessage.warning('索引在等你确认向量化，请到「知识库」页处理后再提问')
        finished = true
        break
      }
      if (live.filesTotal) {
        upStatus.value = `建索引 ${live.filesDone || 0}/${live.filesTotal}`
      }
      if (!st.running) {
        ElMessage.success('「' + f.name + '」已加入知识库，可以直接提问了')
        finished = true
        break
      }
    }
    if (!finished) {
      ElMessage.warning('建索引时间较长，仍在后台继续；稍后可在「知识库」页查看进度')
    }
  } catch (err) {
    ElMessage.error('上传失败：' + (err.message || err))
  } finally {
    uploading.value = false
    upStatus.value = ''
  }
}

/** 赞/踩：同一条只保留最后一次；踩的时候顺带收一句备注 */
async function rate(m, val) {
  if (!m.id || ratingMsgId.value) return
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
  ratingMsgId.value = m.id
  try {
    await api.post('/api/feedback', { messageId: m.id, sessionId: currentId.value, rating: next, note })
    m.feedback = next === 0 ? null : next
    ElMessage.success(next === 0 ? '已取消评价' : '感谢反馈')
  } catch (e) {
    ElMessage.error('反馈失败：' + (e.message || e))
  } finally {
    ratingMsgId.value = ''
  }
}

function openSource(src) {
  preview.value = { open: true, docPath: src.docPath, seq: src.seq }
}

/** 导出单条问答：回答卡片上的「导出 MD」，只含这一问一答，不带来源文件列表 */
function exportOne(m) {
  // 找到这条回答前面最近的提问
  const i = messages.value.indexOf(m)
  const q = [...messages.value.slice(0, i)].reverse().find((x) => x.role === 'user')
  let md = ''
  if (q) md += '## 问\n\n' + q.content + '\n\n'
  md += '## 答\n\n' + (m.content || '') + '\n'
  const title = (q?.content || '问答').replace(/\s+/g, ' ').slice(0, 16)
  downloadText(`问答-${title}.md`, md)
  ElMessage.success('已导出该条问答（不含来源文件）')
}

/** 导出整个会话：全部问答 + 来源文件清单（侧栏会话操作里的「导出会话」） */
async function exportSession(s) {
  const sid = s?.id || currentId.value
  if (!sid) { ElMessage.warning('当前会话还没有内容'); return }
  const cur = sessions.value.find((x) => x.id === sid)
  // 当前打开的会话用实时视图（可能含生成中的内容）；其他会话从后端拉取
  let msgs = sid === currentId.value ? (sessionCache.get(sid) || messages.value) : null
  if (!msgs || !msgs.length) {
    try { msgs = await api.get('/api/sessions/' + sid) }
    catch (e) { ElMessage.error('读取会话失败：' + (e.message || e)); return }
  }
  if (!msgs.length) { ElMessage.warning('该会话还没有内容'); return }
  let md = `# ${cur?.title || '知识库会话'}\n\n> 导出时间：${new Date().toLocaleString('zh-CN')}\n\n`
  for (const m of msgs) {
    if (m.role === 'user') md += '## 问\n\n' + m.content + '\n\n'
    else md += '## 答\n\n' + (m.content || '') + '\n\n'
    if (m.sources?.length) {
      md += '### 来源\n\n'
      // file:// 链接在浏览器/编辑器里点不开，改成「标题（路径 · 块 n）」纯文本
      for (const s of m.sources) md += `- ${s.docTitle}（${s.docPath} · 块 ${s.seq}）\n`
      md += '\n'
    }
  }
  const name = (cur?.title || '知识库会话').replace(/[\\/:*?"<>|]/g, '_').slice(0, 30)
  downloadText(`会话-${name}.md`, md)
  ElMessage.success('已导出整个会话')
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

// 正文点击 → 引用角标打开来源 / 代码块复制
function onBodyClick(e, m) {
  const copyBtn = e.target.closest('.md-copy')
  if (copyBtn) {
    const pre = copyBtn.parentElement?.querySelector('pre')
    const code = pre ? pre.innerText : ''
    if (code) {
      navigator.clipboard.writeText(code)
      copyBtn.textContent = '已复制'
      setTimeout(() => { copyBtn.textContent = '复制' }, 1500)
    }
    return
  }
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
              <span class="t">
                <el-icon v-if="s.generating || s.id === genSid" class="gen-spin" title="正在生成回答"><Loading /></el-icon>
                {{ s.title }}
              </span>
              <span class="ops" :class="{ busy: sessionBusyId === s.id }">
                <el-icon @click.stop="exportSession(s)" title="导出会话 MD"><Download /></el-icon>
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
              <span class="ops" :class="{ busy: sessionBusyId === s.id }">
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
        <el-text tag="b" size="large" class="qa-title">
          {{ sessions.find((s) => s.id === currentId)?.title || '新会话' }}
        </el-text>
        <!-- 顶部只有一个控件：智能体 + 模型；会话已绑定时显示只读状态 -->
        <el-tooltip v-if="lockedAgent" placement="bottom"
                    content="该会话已绑定智能体，模型与检索范围随之固定；新建会话（Ctrl+K）可重新选择">
          <span class="agent-lock">
            <el-icon class="lockicon"><Lock /></el-icon>
            <b>{{ lockedAgent.name }}</b>
            <i>·</i>{{ lockedAgent.provider || '未设厂商' }} {{ lockedAgent.model || '' }}
          </span>
        </el-tooltip>
        <el-select v-else :model-value="agentId" size="small" class="agent-sel"
                   placeholder="选择智能体" @change="onTopAgentChange">
          <el-option v-for="a in agentList" :key="a.id" :value="a.id" :label="'🤖 ' + agentLabel(a)" />
          <el-option value="__new__" label="＋ 新建智能体" divided />
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
          <!-- 用户提问：自定义气泡，超长可展开 -->
          <div v-if="m.role === 'user'" class="q" :class="{ long: m.content.length > 120 && !expandedQ[i] }">
            <div class="q-body">{{ m.content }}</div>
            <span v-if="m.content.length > 120" class="q-toggle" @click="toggleQ(i)">
              {{ expandedQ[i] ? '收起' : '展开全文' }}
            </span>
          </div>
          <el-card v-else shadow="never" class="ans">
            <template #header>
              <div class="who">
                <el-text size="small" type="info">{{ m.sources ? `基于 ${m.sources.length} 个知识库片段` : '回答' }}</el-text>
                <el-tag v-if="m.stopped" size="small" type="warning" effect="plain">已停止</el-tag>
                <el-button link size="small" @click="copyAns(m)">复制</el-button>
                <span v-if="m.id" class="rate" :class="{ busy: ratingMsgId === m.id }">
                  <el-tooltip content="回答准确" placement="top">
                    <el-icon class="thumb" :class="{ on: m.feedback === 1 }" @click="rate(m, 1)"><Select /></el-icon>
                  </el-tooltip>
                  <el-tooltip content="答得不对" placement="top">
                    <el-icon class="thumb bad" :class="{ on: m.feedback === -1 }" @click="rate(m, -1)"><CloseBold /></el-icon>
                  </el-tooltip>
                </span>
                <!-- 低频操作：悬停卡片才出现，减少长会话噪音 -->
                <span class="act-more">
                  <el-button link size="small" @click="regen()">重新生成</el-button>
                  <el-button link size="small" @click="exportOne(m)">导出 MD</el-button>
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
                 :class="{ fresh: generating }" title="回到最新" @click="jumpToBottom">
        <template v-if="generating">有新内容 ↓</template>
        <template v-else>↓</template>
      </el-button>

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
              <el-icon><Plus /></el-icon>{{ uploading ? (upStatus || '上传中') : '附件' }}
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

          <!-- 检索相关设置收进一个入口，避免操作条被一堆 chip 占满 -->
          <el-popover trigger="click" width="310" placement="top-start">
            <template #reference>
              <span class="chip" :class="{ on: scopeDirty }" title="检索范围 / 片段数 / 回答风格">
                <el-icon><Setting /></el-icon>检索设置
              </span>
            </template>
            <div class="setrow">
              <label>范围</label>
              <el-select v-model="scopeValue" size="small" style="flex:1" :title="'知识库：' + currentKbName">
                <el-option label="全部" value="全部" />
                <el-option v-for="f in folders" :key="f" :label="f" :value="f" />
              </el-select>
            </div>
            <div class="setrow">
              <label>片段</label>
              <el-radio-group v-model="topK" size="small">
                <el-radio-button :value="3">3</el-radio-button>
                <el-radio-button :value="5">5</el-radio-button>
                <el-radio-button :value="8">8</el-radio-button>
              </el-radio-group>
            </div>
            <div class="setrow">
              <label>风格</label>
              <el-radio-group v-model="styleMode" size="small">
                <el-radio-button v-for="st in STYLES" :key="st.v" :value="st.v">{{ st.l }}</el-radio-button>
              </el-radio-group>
            </div>
            <div v-if="kbAllTags.length" class="setrow top">
              <label>标签</label>
              <el-checkbox-group v-model="askTags" class="tagpick">
                <el-checkbox v-for="t in kbAllTags" :key="t" :value="t">{{ t }}</el-checkbox>
              </el-checkbox-group>
            </div>
            <div class="settip">范围取自知识库「{{ currentKbName }}」；标签为任一命中</div>
          </el-popover>

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
          <el-select :model-value="agentForm.provider" style="width:100%" @change="onAgentProviderChange">
            <el-option v-for="p in settings?.providers || []" :key="p.name" :value="p.name"
                       :label="p.name + (p.chatModel ? ' · ' + p.chatModel : '')" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="agentForm.model" :placeholder="providerOf(agentForm.provider)?.chatModel || '如 deepseek-chat'" />
          <div class="form-tip">默认取「厂商」在设置里配置的对话模型，可改</div>
        </el-form-item>
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
                   :loading="agentDeleting" @click="deleteAgentFromDialog">删除智能体</el-button>
        <el-button @click="agentDialog = false">取消</el-button>
        <el-button type="primary" :loading="agentSaving" @click="saveAgent">保存</el-button>
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
.qa-main{display:flex;flex-direction:column;padding:0;position:relative}
.qa-top{display:flex;align-items:center;gap:12px;padding:var(--page-y) var(--page-x) 0;min-height:54px}
.qa-top h3, .qa-top .el-text{font-family:var(--sans);font-size:var(--fs-xl);font-weight:600;letter-spacing:0;line-height:1.35}
.qa-title{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.agent-sel{width:270px;flex:0 0 auto}
/* 会话已绑定智能体：只读胶囊 + 锁 */
.agent-lock{display:inline-flex;align-items:center;gap:5px;height:28px;padding:0 10px;border-radius:8px;
  background:var(--mist);border:1px solid var(--line);color:var(--ink2);font-size:var(--fs-xs);cursor:default}
.agent-lock b{font-weight:600;color:var(--ink)}
.agent-lock i{font-style:normal;opacity:.45}
.agent-lock .lockicon{font-size:12px;color:var(--ink3)}
.chat-scroll{flex:1;overflow:auto;padding:18px var(--page-x) 20px;display:flex;flex-direction:column;gap:16px}
/* 提问：主色气泡（长文本可折叠） */
.q{width:fit-content;margin-left:auto;max-width:66%;flex-shrink:0;background:var(--accent);color:#fff;
  padding:10px 16px;border-radius:var(--radius) var(--radius) 4px var(--radius);
  font-size:var(--fs-base);line-height:var(--lh-base);position:relative}
.q-body{white-space:pre-wrap;word-break:break-word}
.q.long .q-body{display:-webkit-box;-webkit-line-clamp:6;-webkit-box-orient:vertical;overflow:hidden}
.q-toggle{display:inline-block;margin-top:4px;font-size:var(--fs-2xs);opacity:.8;cursor:pointer;
  text-decoration:underline;text-underline-offset:2px}
.q-toggle:hover{opacity:1}
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
/* 生成中且有新内容：按钮变宽并提示 */
.to-bottom.fresh{width:auto;border-radius:14px;padding:0 12px;font-size:var(--fs-2xs);
  background:var(--accent);color:#fff;border-color:var(--accent)}
.to-bottom.fresh:hover{background:var(--accent);color:#fff;opacity:.92}
/* 检索设置面板 */
.setrow{display:flex;align-items:center;gap:10px;margin-bottom:10px}
.setrow label{flex:0 0 34px;font-size:var(--fs-xs);color:var(--ink3)}
.setrow.top{align-items:flex-start}
.settip{font-size:var(--fs-2xs);color:var(--el-text-color-secondary);margin-top:4px}
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
/* 代码块：容器 + 右上角复制按钮 */
.body :deep(.md-code){position:relative;margin:10px 0}
.body :deep(.md-code .md-copy){position:absolute;top:6px;right:8px;font-size:var(--fs-2xs);
  padding:2px 8px;border-radius:6px;border:1px solid var(--line);background:var(--card);
  color:var(--ink2);cursor:pointer;opacity:0;transition:all var(--t-fast) var(--ease)}
.body :deep(.md-code:hover .md-copy){opacity:1}
.body :deep(.md-code .md-copy:hover){border-color:var(--accent);color:var(--accent)}
.body :deep(.md-pre){background:var(--mist);border:1px solid var(--line);border-radius:8px;
  padding:12px 14px;margin:0;font-family:var(--mono);font-size:var(--fs-sm);line-height:1.6;
  white-space:pre-wrap;word-break:break-word}
.rate{display:inline-flex;gap:2px;margin-left:4px}
.rate .thumb{font-size:13px;cursor:pointer;color:var(--ink3);opacity:.5;transition:all var(--t-fast) var(--ease);
  padding:3px;border-radius:6px}
.rate .thumb:hover{opacity:1;background:var(--mist);color:var(--ink)}
.rate .thumb.bad:hover{color:var(--el-color-danger)}
.rate .thumb.on{opacity:1;transform:scale(1.08);color:var(--el-color-success);background:var(--el-color-success-light-9)}
.rate .thumb.bad.on{color:var(--el-color-danger);background:var(--el-color-danger-light-9)}
.srcrow{display:flex;gap:6px;flex-wrap:wrap;margin-top:10px;padding-top:10px;border-top:1px dashed var(--line-soft)}
.src{cursor:pointer;font-family:var(--sans);--el-tag-bg-color:var(--paper)}
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
.tagpick{display:flex;flex-direction:column;gap:0;max-height:160px;overflow:auto;flex:1}
.tagpick .el-checkbox{height:26px;margin-right:0}
.chip.busy{opacity:.6;pointer-events:none}
.chip .el-icon{font-size:13px}
.bar .grow{flex:1}
.bar .cnt{font-size:var(--fs-2xs);color:var(--ink3);font-variant-numeric:tabular-nums}
.bar .send{width:34px;height:34px;margin-left:2px}
.scope{display:flex;align-items:center;gap:8px;padding:0 var(--page-x) 8px;flex-wrap:wrap;background:var(--paper)}
.sess .lab.gh .gdot{width:9px;height:9px;border-radius:50%;flex:0 0 auto}
.sess .lab.gh .gedit{margin-left:auto;color:var(--ink3);cursor:pointer}
.sess .lab.gh .gedit:hover{color:var(--accent)}
.color-dot{width:20px;height:20px;border-radius:6px;cursor:pointer;border:2px solid transparent}
.scope-hint{color:var(--accent);opacity:.85}
/* 会话列表：正在生成的标记 */
.gen-spin{font-size:12px;color:var(--accent);margin-right:4px;animation:gen-rot 1s linear infinite;vertical-align:-2px}
@keyframes gen-rot{to{transform:rotate(360deg)}}
/* 提交中的图标按钮：置灰并禁用点击，避免重复触发 */
.ops.busy, .rate.busy{opacity:.35;pointer-events:none}
.form-tip{font-size:var(--fs-2xs);color:var(--ink3);margin-top:4px;line-height:1.4}
.color-dot.sel{border-color:var(--ink)}
</style>
