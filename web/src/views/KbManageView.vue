<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { api } from '../api'
import { preview } from '../store/preview'
import PreviewDrawer from '../components/PreviewDrawer.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Folder, FolderAdd, Document, Loading, Delete, Upload, PriceTag, Link } from '@element-plus/icons-vue'

const kbs = ref([])
const currentId = ref('')
const tree = ref({ name: '', root: '', stats: null, children: [] })
const treeLoading = ref(false)
const activeTab = ref('tree')
const kbDialog = ref(false)
const kbForm = ref({ name: '', path: '' })
const fsDialog = ref(false)
const fsCurrent = ref('')
const fsDirs = ref([])

// 文档检索
const docsQ = ref('')
const docs = ref({ total: 0, items: [] })
const docsPage = ref(0)
const docsSize = 20
const docsLoading = ref(false)

const currentKb = computed(() => kbs.value.find((k) => k.id === currentId.value))

// ── 索引状态轮询：构建中 1s，空闲 15s ──
const st = ref(null)            // 当前库状态响应
let pollTimer = null
let tick = 0
let watchBuild = false          // 本页触发的构建，结束后弹结果
const stats = computed(() => tree.value.stats || null)

const live = computed(() => st.value?.live || null)
const runningForThis = computed(() => !!st.value?.runningForThisKb)
const building = computed(() => runningForThis.value
  && ['scanning', 'parsing', 'embedding'].includes(live.value?.phase))
const awaiting = computed(() => runningForThis.value && live.value?.phase === 'awaiting_confirm')
const otherRunning = computed(() => !!st.value?.running && !st.value.runningForThisKb)

const filePct = computed(() => {
  const l = live.value
  if (!l || !l.filesTotal) return 0
  return Math.min(100, Math.round(l.filesDone * 100 / l.filesTotal))
})
const embedPct = computed(() => {
  const l = live.value
  if (!l || !l.needEmbed) return 0
  return Math.min(100, Math.round(l.embedded * 100 / l.needEmbed))
})

const phaseLabel = computed(() => {
  const p = live.value?.phase
  if (p === 'scanning') return '扫描文件'
  if (p === 'parsing') return '解析分块'
  if (p === 'embedding') return '向量化'
  if (p === 'awaiting_confirm') return '等待确认'
  return ''
})

onMounted(async () => {
  await reloadKbs()
  if (kbs.value.length) selectKb(kbs.value[0])
  await pollStatus()
  schedule()
})

onUnmounted(() => clearTimeout(pollTimer))

function schedule() {
  clearTimeout(pollTimer)
  const ms = st.value?.running ? 1000 : 15000
  pollTimer = setTimeout(async () => {
    await pollStatus()
    schedule()
  }, ms)
}

async function pollStatus() {
  if (!currentId.value) return
  try {
    const s = await api.get('/api/index/status?kbId=' + encodeURIComponent(currentId.value))
    const wasRunning = runningForThis.value
    st.value = s
    tick++
    // 构建中每 3 个轮询周期刷新一次文件树，让状态与分块数跟着走
    if (s.runningForThisKb && tick % 3 === 0) loadTree(currentId.value, true)

    if (wasRunning && !s.running) {
      await loadTree(currentId.value, true)
      if (activeTab.value === 'docs') loadDocs(0)
      if (watchBuild) {
        watchBuild = false
        const l = s.last
        if (!l) return
        if (l.phase === 'done') {
          ElMessage.success(`构建完成：新增 ${l.added} · 更新 ${l.updated} · 跳过 ${l.skipped}`
            + ` · 向量化 ${l.embedded} 块 · 耗时 ${fmtDur(l.durationMs)}`)
        } else if (l.phase === 'cancelled') {
          ElMessage.info('构建已取消，已完成的文件已保存')
        } else if (l.phase === 'error') {
          ElMessage.error('构建失败：' + (s.live?.error || '未知错误'))
        }
      }
    }
  } catch (e) { /* 后端未起时静默重试 */ }
}

function fmtNum(n) {
  if (n == null) return '0'
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万' : String(n)
}

function fmtTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function fmtDur(ms) {
  if (!ms || ms < 0) return '—'
  if (ms < 1000) return ms + ' 毫秒'
  const s = Math.round(ms / 1000)
  if (s < 60) return s + ' 秒'
  const m = Math.floor(s / 60)
  const r = s % 60
  return m + ' 分' + (r ? ' ' + r + ' 秒' : '')
}

// ── 操作 ──
const rebuildLoading = ref(false)         // 构建索引请求中
const cancelBuilding = ref(false)         // 取消构建请求中
const embeddingConfirming = ref(false)    // 向量化确认请求中
const tagSaving = ref(false)              // 标签保存中
const removingPath = ref('')              // 正在删除的文件

async function rebuildKb() {
  if (!currentId.value || rebuildLoading.value) return
  rebuildLoading.value = true
  try {
    const r = await api.post('/api/index/rebuild?kbId=' + encodeURIComponent(currentId.value))
    if (r.started) {
      watchBuild = true
      ElMessage.info('开始构建索引…')
    } else if (r.runningPhase === 'awaiting_confirm') {
      ElMessage.warning(`知识库「${r.runningKbName}」正在等待向量化确认，请先处理`)
    } else {
      ElMessage.warning(`知识库「${r.runningKbName}」正在构建中，请等待完成`)
    }
    pollStatus()
  } catch (e) {
    ElMessage.error('启动构建失败：' + (e.message || e))
  } finally {
    rebuildLoading.value = false
  }
}

function cancelBuild() {
  if (cancelBuilding.value) return
  ElMessageBox.confirm('取消当前构建任务？已处理的文件会保留，下次构建自动续上。', '取消构建', {
    type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '继续构建',
  }).then(async () => {
    cancelBuilding.value = true
    try {
      await api.post('/api/index/cancel')
      ElMessage.info('已请求取消')
      setTimeout(pollStatus, 400)
    } catch (e) {
      ElMessage.error('取消失败：' + (e.message || e))
    } finally {
      cancelBuilding.value = false
    }
  }).catch(() => {})
}

async function confirmEmbed(vector = true) {
  if (embeddingConfirming.value) return
  const tip = vector ? '已确认，开始向量化' : '已跳过向量化，仅建关键词索引'
  embeddingConfirming.value = true
  try {
    await api.post('/api/index/confirm-embed?vector=' + vector)
    ElMessage.info(tip)
    setTimeout(pollStatus, 300)
  } catch (e) {
    ElMessage.error('操作失败：' + (e.message || e))
  } finally {
    embeddingConfirming.value = false
  }
}

function goBuildKb() {
  const k = kbs.value.find((x) => x.id === st.value?.runningKbId)
  if (k) selectKb(k)
}

// ── 知识库列表与文件树 ──
async function reloadKbs() {
  kbs.value = await api.get('/api/kb/s')
}

function selectKb(k) {
  currentId.value = k.id
  showFailed.value = false
  tick = 0
  loadTree(k.id)
  st.value = null
  pollStatus()
  if (activeTab.value === 'docs') loadDocs(0)
}

async function loadTree(kbId, silent = false) {
  if (!silent) treeLoading.value = true
  try {
    const t = await api.get('/api/kb/' + kbId + '/tree')
    if (currentId.value === kbId) tree.value = t
  } finally {
    treeLoading.value = false
  }
  // 首次加载/切换库：重置为只展示第一层（全部折叠）；构建后刷新保留已展开目录
  if (!silent) expanded.value = {}
  loadDocMeta(kbId)
}

// ── 文档标签（元数据过滤）──
const metaDocs = ref({})        // path -> { tags, source, updatedAt }
const tagFilter = ref([])       // 文件树按标签筛选（空 = 不过滤）
const tagEdit = ref({ show: false, path: '', tags: [] })

async function loadDocMeta(kbId) {
  if (!kbId) return
  try {
    const r = await api.get(`/api/kb/${kbId}/docmeta`)
    if (r.ok && currentId.value === kbId) metaDocs.value = r.docs || {}
  } catch { /* 无标签数据不阻塞 */ }
}

const knownTags = computed(() => {
  const s = new Set()
  for (const d of Object.values(metaDocs.value)) (d.tags || []).forEach((t) => s.add(t))
  return [...s].sort()
})

function tagsOf(path) { return (metaDocs.value[path] || {}).tags || [] }

function hasTag(path) {
  const t = tagsOf(path)
  return tagFilter.value.some((x) => t.includes(x))
}

function openTagEdit(n) {
  tagEdit.value = { show: true, path: n.path, tags: [...tagsOf(n.path)] }
}

// ── 网页接入 ──
const urlDialog = ref(false)
const urlText = ref('')
const urlFolder = ref(ROOT_DIR)
const urlTags = ref([])
const urlBusy = ref(false)
const urlResults = ref([])
const urlCount = computed(() => urlText.value.split('\n').filter((l) => l.trim() && !l.trim().startsWith('#')).length)

async function doFetchUrl() {
  const urls = urlText.value.split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'))
  if (!urls.length) return
  urlBusy.value = true
  urlResults.value = []
  try {
    const r = await api.post(`/api/kb/${currentId.value}/fetch-url`, {
      urls,
      folder: urlFolder.value === ROOT_DIR ? '' : urlFolder.value,
      tags: urlTags.value,
    })
    urlResults.value = r.results || []
    const ok = r.success || 0
    if (ok > 0) {
      ElMessage.success(`已抓取 ${ok} 篇网页，点「构建索引」后进入检索`)
      loadTree(currentId.value)
    } else {
      ElMessage.error('全部抓取失败，见明细')
    }
  } catch (e) {
    ElMessage.error('抓取失败：' + e)
  } finally {
    urlBusy.value = false
  }
}

async function saveTags() {
  if (tagSaving.value) return
  const { path, tags } = tagEdit.value
  tagSaving.value = true
  try {
    await api.post(`/api/kb/${currentId.value}/docmeta`, { path, tags })
    metaDocs.value = { ...metaDocs.value, [path]: { ...(metaDocs.value[path] || {}), tags: [...tags] } }
    ElMessage.success('标签已保存')
    tagEdit.value.show = false
  } catch (e) {
    ElMessage.error('保存失败：' + e)
  } finally {
    tagSaving.value = false
  }
}

async function loadDocs(p = 0) {
  if (!currentId.value) return
  docsPage.value = p
  docsLoading.value = true
  try {
    const params = new URLSearchParams({ page: p, size: docsSize })
    if (docsQ.value.trim()) params.set('q', docsQ.value.trim())
    docs.value = await api.get('/api/kb/' + currentId.value + '/docs?' + params)
  } finally {
    docsLoading.value = false
  }
}

function onTabChange(t) {
  if (t === 'docs' && !docs.value.items.length) loadDocs(0)
}

const kbBusy = ref(false)

async function createKb() {
  if (!kbForm.value.name.trim() || !kbForm.value.path.trim()) {
    ElMessage.warning('请填写名称和本地目录')
    return
  }
  if (kbBusy.value) return   // 防重复提交：创建进行中时忽略后续点击
  kbBusy.value = true
  try {
    const r = await api.post('/api/kb/create', kbForm.value)
    if (r.ok) {
      ElMessage.success(r.existed ? `知识库「${r.name}」已存在，直接打开` : `知识库「${r.name}」已创建，开始构建`)
      kbDialog.value = false
      kbForm.value = { name: '', path: '' }
      watchBuild = true
      await reloadKbs().then(() => {
        currentId.value = r.id
        loadTree(r.id)
        pollStatus()
      })
    } else {
      ElMessage.error(r.error || '创建失败')
    }
  } catch (e) {
    ElMessage.error('创建失败：' + e)
  } finally {
    kbBusy.value = false
  }
}

const kbDeleteBusy = ref(false)

function deleteKb() {
  const k = currentKb.value
  if (!k || kbDeleteBusy.value) return
  ElMessageBox.confirm(`删除知识库「${k.name}」？索引与文档副本将一并删除（源目录不受影响）。`, '删除知识库', {
    type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
  }).then(() => {
    kbDeleteBusy.value = true
    api.del('/api/kb/' + k.id).then((r) => {
      const left = r?.leftoverCount || 0
      if (left) {
        const list = (r.leftover || []).slice(0, 5).map((p) => `<div style="font-family:Consolas,monospace;font-size:12px;word-break:break-all">${p}</div>`).join('')
        ElMessageBox.alert(
          `知识库已从列表移除，但有 <b>${left}</b> 个文件正被占用、未能删除：${list}`
          + '<div style="margin-top:8px;color:#8F949E;font-size:12px">关闭 Word / 预览窗口后可手动删除该目录。</div>',
          '部分文件未能删除', { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' },
        )
      } else {
        ElMessage.success('已删除')
      }
      currentId.value = ''
      reloadKbs().then(() => kbs.value.length && selectKb(kbs.value[0]))
    }).finally(() => { kbDeleteBusy.value = false })
  }).catch(() => {})
}

// ── 文档上传 / 删除（只动知识库副本，不碰源目录）──
const upDialog = ref(false)
const upFolder = ref('')
const upFiles = ref([])
const upTags = ref([])
const upBusy = ref(false)
const fileInput = ref(null)
const folderCreating = ref(false)   // 新建文件夹中
const lastUploadFolder = ref('')    // 上次上传的目录（跨次记忆）
const upProg = ref(null)            // 上传进度 {done,total,curName,percent}
const dragOver = ref(false)         // 拖拽悬停高亮

// el-select 把空串当成"未选择"，用哨兵值表示根目录
const ROOT_DIR = '__root__'
const folderOptions = computed(() => {
  const out = [{ label: '根目录（知识库最外层）', value: ROOT_DIR }]
  const walk = (nodes) => {
    for (const n of nodes || []) {
      if (n.type !== 'folder') continue
      out.push({ label: n.path, value: n.path })
      walk(n.children)
    }
  }
  walk(tree.value.children)
  return out
})

function openUpload() {
  if (!currentId.value) return
  // 记住上次上传目录（localStorage 持久化，按知识库区分）
  upFolder.value = localStorage.getItem('kb-up-folder:' + currentId.value) || ROOT_DIR
  upFiles.value = []
  upTags.value = []
  upDialog.value = true
}

/** 前端预校验：类型与大小，提前拦掉明显传不上去的 */
function precheck(f) {
  const okExt = ['docx', 'doc', 'pdf', 'xlsx', 'xls', 'pptx', 'ppt', 'md', 'markdown', 'txt', 'sql', 'yml', 'yaml', 'html', 'htm', 'csv', 'log', 'properties', 'ini', 'conf', 'java', 'py', 'ts', 'sh', 'bat']
  const ext = (f.name.split('.').pop() || '').toLowerCase()
  if (!okExt.includes(ext)) return `不支持的类型 .${ext}`
  if (f.size > 200 * 1024 * 1024) return '超过 200MB 上限'
  if (f.size === 0) return '空文件'
  return ''
}

function addFiles(list) {
  const bad = []
  for (const f of list) {
    const why = precheck(f)
    if (why) { bad.push(`${f.name}：${why}`); continue }
    if (upFiles.value.some((x) => x.name === f.name && x.size === f.size)) continue // 去重
    upFiles.value.push(f)
  }
  if (bad.length) ElMessage.warning(bad.slice(0, 5).join('；'))
}

function pickFiles(e) {
  addFiles([...(e.target.files || [])])
  e.target.value = ''
}

function onDrop(e) {
  dragOver.value = false
  addFiles([...(e.dataTransfer?.files || [])])
}

function removePicked(i) {
  upFiles.value.splice(i, 1)
}

/** 单文件 XHR 上传（可拿到进度） */
function xhrUpload(url, file, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress(e.loaded, e.total) }
    xhr.onload = () => {
      try { resolve(JSON.parse(xhr.responseText)) }
      catch { reject(new Error('响应解析失败')) }
    }
    xhr.onerror = () => reject(new Error('网络错误'))
    xhr.send(file)
  })
}

/** 新建文件夹：弹窗输入名称（可带子路径），建在当前知识库根目录下 */
/** 在指定目录下新建文件夹（parentPath 为空 = 根目录）；创建后自动展开父目录 */
function newFolderIn(parentPath) {
  if (!currentId.value || folderCreating.value) return
  const where = parentPath ? `「${parentPath}」下` : '根目录下'
  ElMessageBox.prompt(`在${where}新建文件夹`, '新建文件夹', {
    inputPlaceholder: '文件夹名（支持中文）',
    confirmButtonText: '创建', cancelButtonText: '取消',
    inputValidator: (v) => {
      const t = (v || '').trim()
      if (!t) return '请输入文件夹名'
      if (/[\\:*?"<>|]/.test(t)) return '不能包含 \\ / : * ? " < > |'
      return true
    },
  }).then(async ({ value }) => {
    const name = value.trim()
    folderCreating.value = true
    try {
      const r = await api.post(`/api/kb/${currentId.value}/folder?path=${encodeURIComponent(parentPath)}&name=${encodeURIComponent(name)}`)
      if (!r.ok && !/已存在/.test(r.error || '')) { ElMessage.error(r.error || '创建失败'); return }
      ElMessage.success('文件夹已创建')
      if (parentPath) expanded.value[parentPath] = true
      await loadTree(currentId.value, true)
    } catch (e) {
      ElMessage.error('创建失败：' + (e.message || e))
    } finally {
      folderCreating.value = false
    }
  }).catch(() => {})
}

/** 从目录行的按钮直接发起上传，目录已预选 */
function uploadTo(path) {
  openUpload()
  upFolder.value = path === '' ? ROOT_DIR : path
}


async function doUpload() {
  if (!upFiles.value.length || upBusy.value) return
  upBusy.value = true
  const dir = upFolder.value === ROOT_DIR ? '' : upFolder.value
  localStorage.setItem('kb-up-folder:' + currentId.value, upFolder.value)
  upProg.value = { done: 0, total: upFiles.value.length, curName: '', percent: 0 }
  let ok = 0
  const errs = []
  for (const f of upFiles.value) {
    upProg.value.curName = f.name
    upProg.value.percent = 0
    const url = `/api/kb/${currentId.value}/upload?folder=${encodeURIComponent(dir)}`
      + `&name=${encodeURIComponent(f.name)}`
      + (upTags.value.length ? `&tags=${encodeURIComponent(upTags.value.join(','))}` : '')
    try {
      const j = await xhrUpload(url, f, (loaded, total) => {
        upProg.value.percent = Math.round((loaded / total) * 100)
      })
      if (j.ok) { ok++; upProg.value.done++ }
      else errs.push(`${f.name}：${j.error}`)
    } catch (e) {
      errs.push(`${f.name}：${e.message || e}`)
    }
  }
  upBusy.value = false
  await loadTree(currentId.value)
  if (!errs.length) {
    upDialog.value = false
    try {
      await ElMessageBox.confirm(
        `已上传 ${ok} 个文件到「${upFolder.value === ROOT_DIR ? '根目录' : upFolder.value}」。现在构建索引吗？`,
        '上传完成', { confirmButtonText: '立即构建', cancelButtonText: '稍后自己构建', type: 'success' },
      )
      rebuildKb()
    } catch { /* 用户选稍后 */ }
  } else {
    upProg.value = null
    ElMessageBox.alert(errs.slice(0, 8).join('<br>'), `上传 ${ok} 个成功，${errs.length} 个失败`, {
      dangerouslyUseHTMLString: true, confirmButtonText: '知道了',
    })
  }
}

function removeFile(n) {
  if (removingPath.value) return
  ElMessageBox.confirm(
    `从知识库删除「${n.name}」？只删知识库副本，源目录不受影响；索引将在下次构建时移除该文档。`,
    '删除文件', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).then(async () => {
    removingPath.value = n.path
    try {
      const r = await api.del(`/api/kb/${currentId.value}/file?path=${encodeURIComponent(n.path)}`)
      if (r.ok) {
        ElMessage.success('已删除')
        loadTree(currentId.value, true)
      } else {
        ElMessage.error(r.error || '删除失败')
      }
    } catch (e) {
      ElMessage.error('删除失败：' + (e.message || e))
    } finally {
      removingPath.value = ''
    }
  }).catch(() => {})
}

// ── 目录选择器 ──
function openFs() {
  fsDialog.value = true
  fsGo('')
}

async function fsGo(path) {
  fsCurrent.value = path
  try {
    const d = await api.get('/api/fs/list?path=' + encodeURIComponent(path))
    fsDirs.value = d.dirs || []
  } catch (e) { /* ignore */ }
}

function fsPick() {
  kbForm.value.path = fsCurrent.value
  fsDialog.value = false
}

// ── 文件树扁平化（懒展开 + 每层限量：默认只渲染第一层，点开的文件夹才下钻）──
const expanded = ref({})      // path -> 是否展开；默认全部折叠
const moreOpened = ref({})    // path -> 已点开「显示全部」的文件夹
const CHILD_LIMIT = 100       // 每层最多直接渲染的子节点数，超出折叠为「还有 N 项」
const flatTree = computed(() => {
  const rows = []
  const filtering = tagFilter.value.length > 0
  function walk(nodes, depth, parentKey) {
    const limit = filtering ? Infinity : Number(moreOpened.value[parentKey] ?? CHILD_LIMIT)
    let shown = 0, hidden = 0
    for (const n of nodes) {
      if (shown >= limit) { hidden++; continue }
      if (n.type === 'folder') {
        const start = rows.length
        rows.push({ ...n, depth })
        // 标签筛选时无视折叠全量下钻（否则折叠的文件夹里命中的文件看不见）
        if (filtering || expanded.value[n.path]) walk(n.children, depth + 1, n.path)
        // 标签筛选时，没有任何命中后代的空文件夹直接隐藏
        if (filtering && rows.length === start + 1) rows.length = start
        shown++
      } else {
        if (filtering && !hasTag(n.path)) continue
        rows.push({ ...n, depth })
        shown++
      }
    }
    if (hidden > 0) {
      rows.push({ type: 'more', path: parentKey, depth, hidden, name: `还有 ${hidden} 项，点击展开` })
    }
  }
  walk(tree.value.children || [], 0, '__root__')
  return rows
})

function toggleNode(n) {
  if (n.type !== 'folder') return
  expanded.value = { ...expanded.value, [n.path]: !expanded.value[n.path] }
}

function showMore(n) {
  moreOpened.value = { ...moreOpened.value, [n.path]: Infinity }
}

/** 树形缩进引导线：每行按层级在各级缩进处画竖线，形成连续的树干线。 */
function guides(depth) {
  if (!depth) return ''
  const parts = []
  for (let i = 0; i < depth; i++) {
    parts.push(`linear-gradient(var(--el-border-color-lighter), var(--el-border-color-lighter)) no-repeat ${16 + i * 20}px 0/1px 100%`)
  }
  return parts.join(',')
}

function openFile(n) {
  if (n.type !== 'file') return
  preview.value = { open: true, docPath: n.path, seq: null }
}

function openDoc(it) {
  preview.value = { open: true, docPath: it.docPath, seq: it.hitSeq ?? null }
}

function nodeBuilding(n) {
  return building.value && live.value?.currentFile === n.path
}

const STATUS_META = {
  ok: { text: '✓', cls: 'ok', tip: '' },
  empty: { text: '空文档', cls: 'empty', tip: '文件可解析但没有提取到文本，未进入检索' },
  skipped: { text: '未索引', cls: 'skip', tip: '类型不在索引白名单内，或文件为空' },
  pending: { text: '待构建', cls: 'pending', tip: '可索引但尚未进入索引，点「构建索引」处理' },
  stale: { text: '待更新', cls: 'stale', tip: '源文件已变更，下次构建会更新该文档' },
  fail: { text: '⚠ 失败', cls: 'fail', tip: '构建失败，悬停查看原因' },
}
function stMeta(n) {
  return STATUS_META[n.status] || STATUS_META.ok
}

// 本库失败文件清单
const showFailed = ref(false)
const failedList = computed(() => st.value?.last?.failed || [])
</script>

<template>
  <el-container class="kbpage">
    <el-aside width="250px" class="kbside">
      <el-button type="primary" style="width:100%" @click="kbDialog = true">＋ 新建知识库</el-button>
      <div class="kbside-list">
        <div v-for="k in kbs" :key="k.id" class="kitem" :class="{ on: k.id === currentId }" @click="selectKb(k)">
          <div class="knm">
            {{ k.name }}
            <span v-if="st && st.runningKbId === k.id" class="kdot" />
          </div>
          <div class="kst"><span>{{ k.path }}</span></div>
        </div>
        <el-empty v-if="!kbs.length" description="还没有知识库" :image-size="60" />
      </div>
    </el-aside>

    <el-main class="kbmain">
      <div class="kbhead">
        <el-text tag="b" size="large">{{ tree.name || currentKb?.name || '知识库' }}</el-text>
        <el-select v-if="knownTags.length" v-model="tagFilter" multiple collapse-tags
                   placeholder="按标签筛选" clearable size="small" style="width:170px">
          <el-option v-for="t in knownTags" :key="t" :label="t" :value="t" />
        </el-select>
        <el-button size="small" type="primary" plain :disabled="!currentId" @click="openUpload">
          <el-icon style="margin-right:4px"><Upload /></el-icon>上传文档
        </el-button>
        <el-button size="small" plain :disabled="!currentId" @click="urlDialog = true">
          <el-icon style="margin-right:4px"><Link /></el-icon>网页接入
        </el-button>
        <el-tooltip content="增量构建：只处理新增与变更的文件，向量按内容 hash 复用，不重复调用接口" placement="bottom">
          <el-button size="small" :disabled="!currentId || runningForThis" :loading="rebuildLoading" @click="rebuildKb">构建索引</el-button>
        </el-tooltip>
        <el-button size="small" type="danger" plain :disabled="!currentId || kbDeleteBusy" :loading="kbDeleteBusy" @click="deleteKb">删除</el-button>
        <span class="hstat" v-if="stats">
          <span class="hs"><b>{{ stats.files }}</b> 文件</span>
          <span class="hs"><b>{{ stats.indexable }}</b> 可索引</span>
          <span class="hs ok"><b>{{ stats.indexed }}</b> 已索引</span>
          <span class="hs"><b>{{ fmtNum(stats.chunks) }}</b> 分块</span>
          <el-tooltip v-if="stats.skipped" :content="`${stats.skipped} 个文件类型不在索引白名单内（如 zip/png），不会进入检索`" placement="bottom">
            <span class="hs mute"><b>{{ stats.skipped }}</b> 未索引</span>
          </el-tooltip>
          <span v-if="stats.pending" class="hs pending"><b>{{ stats.pending }}</b> 待构建</span>
          <span v-if="stats.stale" class="hs stale"><b>{{ stats.stale }}</b> 待更新</span>
          <span v-if="stats.empty" class="hs mute"><b>{{ stats.empty }}</b> 空文档</span>
          <span v-if="stats.failed" class="hs danger"><b>{{ stats.failed }}</b> 失败</span>
        </span>
      </div>

      <!-- 本库构建进度（分阶段） -->
      <div v-if="building" class="buildbar">
        <div class="brow">
          <el-tag size="small" type="warning" effect="plain">{{ phaseLabel }}</el-tag>
          <template v-if="live.phase === 'scanning'">
            <el-progress :percentage="100" :stroke-width="6" :show-text="false" striped striped-flow style="flex:1" />
            <span class="btxt">正在扫描文件…</span>
          </template>
          <template v-else>
            <el-progress :percentage="filePct" :stroke-width="6" :show-text="false" style="flex:1" />
            <span class="btxt">{{ live.filesDone }}/{{ live.filesTotal }} 文件 · 已用 {{ fmtDur(live.elapsedMs) }}</span>
          </template>
          <el-button size="small" text type="danger" :loading="cancelBuilding" @click="cancelBuild">取消</el-button>
        </div>
        <div v-if="live.phase === 'embedding'" class="brow sub">
          <span class="btxt">向量化</span>
          <el-progress :percentage="embedPct" :stroke-width="6" status="success"
                       :format="() => `${live.embedded}/${live.needEmbed}`" style="flex:1" />
        </div>
        <div class="brow sub" v-if="live.currentFile">
          <span class="bpath">当前：{{ live.currentFile }}</span>
          <span v-if="live.errors" class="bdanger">失败 {{ live.errors }}</span>
        </div>
      </div>

      <!-- 向量化成本确认 -->
      <el-alert v-else-if="awaiting" type="warning" :closable="false" class="awaitbox"
                :title="`本次需要向量化 ${live.awaitingCount} 个新分块（约 ${fmtNum(live.awaitingChars)} 字 · ${live.awaitingModel}）`">
        <div class="awaitbody">
          <span class="awaittip">
            待向量化块数超过成本护栏阈值，因此先暂停确认。确认后调用 embedding 接口；也可以本次跳过向量化，先把文本检索建好（下次构建会自动补齐向量）。
            10 分钟内未操作将自动放弃本次构建。
          </span>
          <div class="awaitbtns">
            <el-button size="small" type="primary" :loading="embeddingConfirming" @click="confirmEmbed(true)">确认执行</el-button>
            <el-button size="small" :loading="embeddingConfirming" @click="confirmEmbed(false)">跳过向量化</el-button>
            <el-button size="small" type="danger" plain :loading="cancelBuilding" @click="cancelBuild">取消任务</el-button>
          </div>
        </div>
      </el-alert>

      <!-- 其他库在构建 -->
      <el-alert v-else-if="otherRunning" type="info" :closable="false" class="awaitbox"
                :title="`知识库「${st.runningKbName}」${st.runningPhase === 'awaiting_confirm' ? '正在等待向量化确认' : '正在构建中'}（同一时间只跑一个构建任务）`">
        <div class="awaitbtns">
          <el-button size="small" @click="goBuildKb">前往查看</el-button>
          <el-button size="small" type="danger" plain :loading="cancelBuilding" @click="cancelBuild">取消该任务</el-button>
        </div>
      </el-alert>

      <!-- 上次构建结果（按库） -->
      <div v-if="st && !runningForThis && st.last" class="idxinfo">
        <el-text size="small" type="info">
          最近构建 {{ fmtTime(st.last.builtAt) }} · 耗时 {{ fmtDur(st.last.durationMs) }}
          · 新增 {{ st.last.added }} · 更新 {{ st.last.updated }} · 跳过 {{ st.last.skipped }}
          · 删除 {{ st.last.removed }} · 向量化 {{ st.last.embedded }} 块
        </el-text>
        <el-text v-if="st.last.vectorFailed" size="small" type="warning">向量失败 {{ st.last.vectorFailed }} 块</el-text>
        <el-button v-if="failedList.length" link type="danger" size="small" @click="showFailed = !showFailed">
          失败文件 {{ failedList.length }} ▾
        </el-button>
      </div>
      <div v-else-if="st && !runningForThis && !st.last" class="idxinfo">
        <el-text size="small" type="info">该知识库尚未构建索引</el-text>
      </div>
      <div v-if="showFailed && failedList.length" class="faillist">
        <div v-for="f in failedList" :key="f.path" class="failrow">
          <span class="fp">{{ f.path }}</span>
          <span class="fr">{{ f.reason }}</span>
        </div>
        <div class="failhint">失败文件会在下次构建时自动重试</div>
      </div>

      <div v-if="stats && (stats.pending > 0)" class="buildhint">
        <el-text size="small" type="warning">
          有 {{ stats.pending }} 个文件尚未进入索引
        </el-text>
        <el-button size="small" link type="primary" :loading="rebuildLoading" @click="rebuildKb">立即构建</el-button>
      </div>

      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="文件树" name="tree">
          <div v-loading="treeLoading" class="treebox">
            <div class="tnode rootrow" title="知识库根目录">
              <span class="caret">▾</span>
              <el-icon class="tic"><Folder /></el-icon>
              <span class="nm">根目录</span>
              <span class="cnt"></span>
              <span class="rowops">
                <el-tooltip content="在根目录新建文件夹" placement="top">
                  <el-icon @click.stop="newFolderIn('')"><FolderAdd /></el-icon>
                </el-tooltip>
                <el-tooltip content="上传文件到根目录" placement="top">
                  <el-icon @click.stop="uploadTo('')"><Upload /></el-icon>
                </el-tooltip>
              </span>
            </div>
            <div class="tree">
              <div v-for="n in flatTree" :key="n.type + n.path" class="tnode"
                   :class="{ folder: n.type === 'folder', more: n.type === 'more' }"
                   :style="{ paddingLeft: 16 + n.depth * 20 + 'px', backgroundImage: guides(n.depth) }"
                   @click="n.type === 'folder' ? toggleNode(n) : n.type === 'more' ? showMore(n) : openFile(n)">
                <span class="caret" v-if="n.type !== 'more'">{{ n.type === 'folder' ? (expanded[n.path] ? '▾' : '▸') : '' }}</span>
                <el-icon class="tic" v-if="n.type !== 'more'"><component :is="n.type === 'folder' ? Folder : Document" /></el-icon>
                <span class="nm">{{ n.name }}</span>
                <template v-if="n.type !== 'more'">
                <span class="st-slot">
                  <el-icon v-if="nodeBuilding(n)" class="st-spin" :size="13"><Loading /></el-icon>
                  <el-tooltip v-else-if="n.type === 'file' && stMeta(n).tip" :content="n.failReason || stMeta(n).tip" placement="top">
                    <span class="st" :class="stMeta(n).cls">{{ stMeta(n).text }}</span>
                  </el-tooltip>
                  <span v-else-if="n.type === 'file'" class="st ok">✓</span>
                  <span v-else-if="n.status && n.status !== 'ok'" class="st" :class="stMeta(n).cls">{{ stMeta(n).text }}</span>
                </span>
                <span class="doc-tags" v-if="n.type === 'file' && tagsOf(n.path).length">
                  <span v-for="t in tagsOf(n.path).slice(0, 3)" :key="t" class="dtag" :title="t">{{ t }}</span>
                  <span v-if="tagsOf(n.path).length > 3" class="dtag more">+{{ tagsOf(n.path).length - 3 }}</span>
                </span>
                </template>
                <span class="cnt" v-if="n.type !== 'more'">
                  <template v-if="n.type === 'folder'">{{ n.fileCount || 0 }} 文件<template v-if="n.chunkSum"> · {{ n.chunkSum }} 块</template></template>
                  <template v-else-if="n.chunkCount">{{ n.chunkCount }} 分块</template>
                </span>
                <span class="rowops" v-if="n.type === 'folder'">
                  <el-tooltip content="在此目录下新建文件夹" placement="top">
                    <el-icon @click.stop="newFolderIn(n.path)"><FolderAdd /></el-icon>
                  </el-tooltip>
                  <el-tooltip content="上传文件到该目录" placement="top">
                    <el-icon @click.stop="uploadTo(n.path)"><Upload /></el-icon>
                  </el-tooltip>
                </span>
                <span class="rowops" v-if="n.type === 'file'">
                  <el-tooltip content="编辑标签" placement="top">
                    <el-icon class="del" @click.stop="openTagEdit(n)"><PriceTag /></el-icon>
                  </el-tooltip>
                  <el-tooltip content="从知识库删除该文件" placement="top">
                    <el-icon class="del" :class="{ busy: removingPath === n.path }" @click.stop="removeFile(n)"><Delete /></el-icon>
                  </el-tooltip>
                </span>
              </div>
              <el-empty v-if="!flatTree.length && !treeLoading" description="该知识库暂无文件" :image-size="60" />
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="文档检索" name="docs">
          <div class="docbar">
            <el-input v-model="docsQ" placeholder="搜索该知识库的文档（混合检索）…" clearable style="width:380px"
                      @keydown.enter="loadDocs(0)" @clear="docsQ = ''; loadDocs(0)">
              <template #append><el-button @click="loadDocs(0)">搜索</el-button></template>
            </el-input>
            <el-text size="small" type="info" style="margin-left:auto">共 {{ docs.total }} 条{{ docsQ ? '（检索结果）' : '' }}</el-text>
          </div>
          <el-table :data="docs.items" v-loading="docsLoading" size="small" @row-click="openDoc" row-class-name="clickable"
                    style="width:100%">
            <el-table-column label="类型" width="80">
              <template #default="{ row }"><el-tag size="small" effect="plain">{{ (row.ext || '?').slice(1).toUpperCase() }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="docTitle" label="标题" min-width="240" show-overflow-tooltip />
            <el-table-column prop="docPath" label="路径" min-width="240" show-overflow-tooltip />
            <el-table-column prop="chunkCount" label="分块" width="70">
              <template #default="{ row }">{{ row.chunkCount ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="摘要" min-width="280" show-overflow-tooltip>
              <template #default="{ row }"><span v-html="row.snippet"></span></template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的文档" :image-size="60" />
            </template>
          </el-table>
          <div class="pager" v-if="docs.total > docsSize && !docsQ">
            <el-pagination background layout="prev, pager, next" :total="docs.total"
                           :page-size="docsSize" :current-page="docsPage + 1"
                           @current-change="(p) => loadDocs(p - 1)" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-main>

    <!-- 上传文档对话框 -->
    <el-dialog v-model="upDialog" title="上传文档到知识库" width="540px">
      <el-form label-width="80px">
        <el-form-item label="目标目录">
          <el-select v-model="upFolder" style="width:100%">
            <el-option v-for="o in folderOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-select v-model="upTags" multiple filterable allow-create default-first-option
                     placeholder="可选；回车添加，如：上线方案、需求规格" style="width:100%">
            <el-option v-for="t in knownTags" :key="t" :label="t" :value="t" />
          </el-select>
          <div class="formhint">打了标签的文档可在文件树按标签筛选，也能在问答 / 检索时按标签缩小范围</div>
        </el-form-item>
        <el-form-item label="文件">
          <div style="width:100%">
            <input ref="fileInput" type="file" multiple hidden
                   accept=".docx,.doc,.pdf,.xlsx,.xls,.pptx,.ppt,.md,.markdown,.txt,.sql,.yml,.yaml,.html,.htm,.csv,.log,.properties,.ini,.conf,.java,.py,.ts,.sh,.bat"
                   @change="pickFiles" />
            <div class="dropzone" :class="{ over: dragOver }"
                 @click="fileInput.click()"
                 @dragover.prevent="dragOver = true"
                 @dragleave.prevent="dragOver = false"
                 @drop.prevent="onDrop">
              <el-icon class="dzicon"><Upload /></el-icon>
              <div>拖拽文件到这里，或 <span class="link">点击选择</span></div>
              <div class="dzsub">支持 docx / pdf / xlsx / pptx / md / txt / sql / html 等，单个 ≤ 200MB</div>
            </div>
            <div class="upfiles" v-if="upFiles.length">
              <div v-for="(f, i) in upFiles" :key="f.name + i" class="upfile">
                <span class="upn">{{ f.name }}</span>
                <span class="ups">{{ Math.max(1, Math.round(f.size / 1024)) }} KB</span>
                <el-icon class="upx" @click="removePicked(i)"><Delete /></el-icon>
              </div>
            </div>
            <div v-if="upBusy && upProg" class="upprog">
              <el-progress :percentage="upProg.percent" :stroke-width="8" />
              <div class="upprog-t">正在上传 {{ upProg.done + 1 }}/{{ upProg.total }}：{{ upProg.curName }}</div>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="upDialog = false">取消</el-button>
        <el-button type="primary" :loading="upBusy" :disabled="!upFiles.length" @click="doUpload">
          {{ upBusy ? `上传中 ${upProg?.done || 0}/${upProg?.total || 0}` : `上传${upFiles.length ? ` ${upFiles.length} 个` : ''}` }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 网页接入对话框 -->
    <el-dialog v-model="urlDialog" title="网页接入（抓取网页存为文档）" width="600px">
      <el-form label-width="80px">
        <el-form-item label="URL 列表">
          <el-input v-model="urlText" type="textarea" :rows="5"
                    placeholder="每行一条，如：&#10;https://example.com/docs/getting-started&#10;https://example.com/docs/faq" />
        </el-form-item>
        <el-form-item label="目标目录">
          <el-select v-model="urlFolder" style="width:100%">
            <el-option v-for="o in folderOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-select v-model="urlTags" multiple filterable allow-create default-first-option
                     placeholder="可选，回车添加" style="width:100%">
            <el-option v-for="t in knownTags" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="formhint">只支持无需登录的静态页面；JS 渲染页 / 需登录页会抓到空壳并明确报错。抓取完成后点「构建索引」进入检索。</div>
      <div v-if="urlResults.length" class="urlres">
        <div v-for="(r, i) in urlResults" :key="i" class="urlrow">
          <span class="ust" :class="r.ok ? 'ok' : 'fail'">{{ r.ok ? '✓' : '✗' }}</span>
          <span class="uurl" :title="r.url">{{ r.url }}</span>
          <span class="uinfo" v-if="r.ok">{{ r.path }} · {{ Math.round(r.chars / 1000) }}k 字</span>
          <span class="uinfo fail" v-else :title="r.error">{{ r.error?.slice(0, 40) }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="urlDialog = false">关闭</el-button>
        <el-button type="primary" :loading="urlBusy" :disabled="!urlText.trim()" @click="doFetchUrl">
          抓取{{ urlCount ? ` ${urlCount} 条` : '' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 标签编辑对话框 -->
    <el-dialog v-model="tagEdit.show" title="编辑文档标签" width="480px">
      <div class="tagpath">{{ tagEdit.path }}</div>
      <el-select v-model="tagEdit.tags" multiple filterable allow-create default-first-option
                 placeholder="回车添加标签" style="width:100%;margin-top:10px">
        <el-option v-for="t in knownTags" :key="t" :label="t" :value="t" />
      </el-select>
      <div class="formhint">清空全部标签即清除该文档的标签；标签用于检索过滤，不参与分词</div>
      <template #footer>
        <el-button @click="tagEdit.show = false">取消</el-button>
        <el-button type="primary" :loading="tagSaving" @click="saveTags">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建知识库对话框 -->
    <el-dialog v-model="kbDialog" title="新建知识库" width="500px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="kbForm.name" placeholder="例如：运维知识库" />
        </el-form-item>
        <el-form-item label="本地目录">
          <div style="display:flex;gap:8px;width:100%">
            <el-input v-model="kbForm.path" placeholder="例如 E:\docs\运维资料" />
            <el-button @click="openFs">浏览…</el-button>
          </div>
          <div class="formhint">创建后会把该目录下的文档复制到工作路径并构建独立索引，源目录不受影响</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="kbDialog = false">取消</el-button>
        <el-button type="primary" :loading="kbBusy" :disabled="kbBusy" @click="createKb">创建并构建</el-button>
      </template>
    </el-dialog>

    <!-- 目录选择器 -->
    <el-dialog v-model="fsDialog" title="选择目录" width="520px">
      <div class="fsbar">
        <el-text size="small" type="info">当前位置：</el-text>
        <el-text size="small" class="fspath">{{ fsCurrent || '（盘符列表）' }}</el-text>
        <el-button size="small" style="margin-left:auto" @click="fsGo('')">⬅ 返回盘符列表</el-button>
      </div>
      <div class="fslist">
        <div v-for="d in fsDirs" :key="d.path" class="fsnode" @click="fsGo(d.path)">
          <el-icon><Folder /></el-icon>{{ d.name }}
        </div>
        <el-empty v-if="!fsDirs.length" description="该目录下没有子目录" :image-size="60" />
      </div>
      <template #footer>
        <el-button @click="fsDialog = false">取消</el-button>
        <el-button type="primary" @click="fsPick">选择当前目录</el-button>
      </template>
    </el-dialog>

    <PreviewDrawer :target="preview" :kb-id="currentId || 'default'" @close="preview.open = false" />
  </el-container>
</template>

<style scoped>
.kbpage{height:100%;background:var(--el-bg-color-page)}
.kbside{background:#FBFBFC;border-right:1px solid var(--el-border-color-light);padding:12px 8px;overflow:auto}
.kbside-list{margin-top:8px}
.kitem{padding:9px 10px;border-radius:8px;cursor:pointer;margin-bottom:2px;border:1px solid transparent}
.kitem:hover{background:var(--el-fill-color-light)}
.kitem.on{background:var(--el-color-primary-light-9);border-color:#C7C8FA}
.kitem .knm{font-size:var(--fs-base);font-weight:600;color:var(--el-text-color-primary);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;display:flex;align-items:center;gap:5px}
.kdot{width:6px;height:6px;border-radius:50%;background:var(--el-color-warning);animation:pulse 1.2s ease-in-out infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.25}}
.kitem .kst{font-size:var(--fs-xs);color:var(--el-text-color-secondary);margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.kbmain{display:flex;flex-direction:column;overflow:hidden;padding:0 var(--page-x) 20px}
/* 固定高度的头部区块不能被长文件树压缩（否则提示条会被挤成 0 高度） */
.kbhead,.buildbar,.awaitbox,.idxinfo,.faillist{flex:0 0 auto}
.kbmain :deep(.el-tabs){flex:1 1 auto;min-height:0;display:flex;flex-direction:column}
.kbmain :deep(.el-tabs__header){flex:0 0 auto;margin-bottom:8px}
.kbmain :deep(.el-tabs__content){flex:1 1 auto;min-height:0;overflow:auto}
.kbhead{display:flex;align-items:center;gap:10px;padding:var(--page-y) 0 10px;flex-wrap:wrap}
.kbhead :deep(.el-text){font-size:var(--fs-xl);font-weight:600;letter-spacing:0;line-height:1.35}
.hstat{display:flex;align-items:center;gap:2px;margin-left:auto;flex-wrap:wrap}
.hs{font-size:var(--fs-xs);color:var(--el-text-color-secondary);padding:3px 8px;border-radius:6px;background:var(--el-fill-color-light);font-variant-numeric:tabular-nums}
.hs b{color:var(--el-text-color-primary);font-weight:600;letter-spacing:0}
.hs.ok b{color:var(--el-color-success)}
.hs.mute b{color:var(--el-text-color-secondary)}
.hs.pending b{color:var(--el-color-primary)}
.hs.stale b{color:var(--el-color-warning)}
.hs.danger b{color:var(--el-color-danger)}
.treebox{flex:1}
.tree{background:#fff;border:1px solid var(--el-border-color-light);border-radius:10px;padding:8px 12px;
  font-size:var(--fs-base);line-height:var(--lh-tight)}
.tnode{display:flex;align-items:center;gap:8px;padding:6px 8px;border-radius:7px;cursor:pointer}
.tnode:hover{background:var(--el-fill-color-light)}
.tnode.folder{font-weight:600}
.tnode.more{font-style:italic;color:var(--el-color-primary);font-weight:400}
.tnode.more .nm{font-size:var(--fs-xs)}
.tnode .caret{font-size:9px;color:var(--el-text-color-secondary);width:10px}
.tnode .tic{color:var(--el-text-color-secondary)}
.tnode .nm{color:var(--el-text-color-primary);min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.tnode .st-slot{width:64px;flex:0 0 64px;margin-left:auto;display:flex;justify-content:flex-end;align-items:center}
.tnode .st{font-size:var(--fs-2xs);white-space:nowrap;padding:1px 6px;border-radius:5px;line-height:1.5}
.tnode .st.ok{font-size:var(--fs-2xs);padding:0}
.tnode .st.empty,.tnode .st.skip{color:var(--el-text-color-secondary);background:var(--el-fill-color)}
.tnode .st.pending{color:var(--el-color-primary);background:var(--el-color-primary-light-9)}
.tnode .st.stale{color:var(--el-color-warning);background:var(--el-color-warning-light-9)}
.tnode .st.fail{color:var(--el-color-danger);background:var(--el-color-danger-light-9)}
.tnode .st-spin{color:var(--el-color-primary);animation:spin 1s linear infinite}
@keyframes spin{from{transform:rotate(0)}to{transform:rotate(360deg)}}
.tnode .cnt{width:132px;flex:0 0 132px;text-align:right;white-space:nowrap;
  font-size:var(--fs-2xs);color:var(--el-text-color-secondary);font-variant-numeric:tabular-nums}
.buildbar{display:flex;flex-direction:column;gap:5px;padding:8px 10px;margin-bottom:8px;background:#fff;border:1px solid var(--el-border-color-light);border-radius:8px}
.brow{display:flex;align-items:center;gap:10px}
.brow.sub{gap:8px}
.btxt{font-size:var(--fs-xs);color:var(--el-text-color-secondary);white-space:nowrap;font-variant-numeric:tabular-nums}
.bpath{font-size:var(--fs-2xs);color:var(--el-text-color-secondary);font-family:Consolas,monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bdanger{font-size:var(--fs-2xs);color:var(--el-color-danger);margin-left:auto}
.awaitbox{margin-bottom:10px}
.hs, .buildbar, .idxinfo{font-variant-numeric:tabular-nums}
.awaitbody{display:flex;flex-direction:column;gap:8px;margin-top:6px}
.awaittip{font-size:var(--fs-xs);color:var(--el-text-color-secondary);line-height:1.7}
.awaitbtns{display:flex;gap:8px}
.docbar{display:flex;align-items:center;gap:10px;margin-bottom:10px}
.pager{display:flex;justify-content:center;padding:8px 0}
.idxinfo{display:flex;align-items:center;gap:10px;padding:0 0 8px;flex-wrap:wrap}
.faillist{border:1px solid #F3D6D6;background:#FDF7F7;border-radius:8px;padding:8px 12px;margin-bottom:10px;max-height:200px;overflow:auto}
.failrow{display:flex;gap:10px;font-size:var(--fs-xs);padding:3px 0}
.failrow .fp{font-family:Consolas,monospace;flex:0 1 auto;color:var(--el-text-color-primary);white-space:nowrap}
.failrow .fr{color:var(--el-text-color-secondary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.failhint{font-size:var(--fs-2xs);color:var(--el-text-color-secondary);margin-top:4px}
.formhint{font-size:var(--fs-2xs);color:var(--el-text-color-secondary);margin-top:4px;line-height:1.6}
.fsbar{display:flex;gap:6px;align-items:center;margin-bottom:8px}
.fspath{font-family:Consolas,monospace}
.fslist{border:1px solid var(--el-border-color-light);border-radius:8px;max-height:260px;overflow:auto}
.fsnode{display:flex;align-items:center;gap:8px;padding:8px 12px;cursor:pointer;font-size:13px}
.fsnode:hover{background:var(--el-fill-color-light)}
:deep(.clickable){cursor:pointer}
.rowops{width:48px;flex:0 0 48px;display:flex;justify-content:center;gap:6px;opacity:0;transition:opacity var(--t-fast) var(--ease)}
.tnode:hover .rowops{opacity:1}
.rowops .del{font-size:13px;color:var(--el-text-color-secondary);cursor:pointer}
.rowops .del:hover{color:var(--el-color-danger)}
.buildhint{display:flex;align-items:center;gap:8px;padding:0 0 8px}
.upfiles{margin-top:8px;border:1px solid var(--el-border-color-light);border-radius:8px;max-height:180px;overflow:auto}
.upfile{display:flex;gap:10px;align-items:center;padding:6px 10px;font-size:var(--fs-xs);border-bottom:1px dashed var(--el-border-color-lighter)}
.upfile:last-child{border-bottom:0}
.upfile .upn{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--el-text-color-primary)}
.upfile .ups{color:var(--el-text-color-secondary);font-variant-numeric:tabular-nums}
.doc-tags{display:flex;gap:4px;margin-left:6px;flex:0 0 auto;min-width:0}
.dtag{font-size:11px;line-height:1;padding:3px 6px;border-radius:4px;background:var(--el-color-primary-light-9);
  color:var(--el-color-primary);max-width:88px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.dtag.more{background:var(--el-fill-color);color:var(--el-text-color-secondary)}
.tagpath{font-size:var(--fs-xs);color:var(--el-text-color-secondary);word-break:break-all}
.urlres{margin-top:10px;border:1px solid var(--el-border-color-light);border-radius:8px;max-height:200px;overflow:auto}
.urlrow{display:flex;gap:8px;align-items:center;padding:6px 10px;font-size:var(--fs-xs);border-bottom:1px dashed var(--el-border-color-lighter)}
.urlrow:last-child{border-bottom:0}
.urlrow .ust{flex:0 0 16px;font-weight:600}
.urlrow .ust.ok{color:var(--el-color-success)}
.urlrow .ust.fail{color:var(--el-color-danger)}
.urlrow .uurl{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--el-text-color-primary)}
.urlrow .uinfo{color:var(--el-text-color-secondary);max-width:45%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.urlrow .uinfo.fail{color:var(--el-color-danger)}
/* 删除进行中的行内图标：置灰并禁用点击 */
.del.busy{opacity:.35;pointer-events:none}
/* 上传拖拽区 */
.dropzone{border:1.5px dashed var(--el-border-color);border-radius:10px;padding:18px 10px;
  text-align:center;cursor:pointer;transition:all var(--t-fast) var(--ease);color:var(--ink2);font-size:var(--fs-sm)}
.dropzone:hover,.dropzone.over{border-color:var(--accent);background:var(--el-color-primary-light-9)}
.dropzone .dzicon{font-size:22px;color:var(--ink3);margin-bottom:4px}
.dropzone .link{color:var(--accent)}
.dropzone .dzsub{font-size:var(--fs-2xs);color:var(--ink3);margin-top:4px}
.upfile{display:flex;align-items:center;gap:8px;padding:4px 6px;border-radius:6px;font-size:var(--fs-xs)}
.upfile:hover{background:var(--mist)}
.upfile .upn{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.upfile .ups{color:var(--ink3)}
.upfile .upx{cursor:pointer;color:var(--ink3)}
.upfile .upx:hover{color:var(--el-color-danger)}
.upprog{margin-top:6px}
.upprog-t{font-size:var(--fs-2xs);color:var(--ink3);margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style>
