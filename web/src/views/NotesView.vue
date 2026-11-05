<script setup>
defineOptions({ name: 'NotesView' }) // keep-alive include 依赖此名字
import { ref, computed, onMounted, onBeforeUnmount, onActivated, nextTick } from 'vue'
import { api } from '../api'
import { useAsideWidth } from '../composables/useAsideWidth'
import { mdToHtml } from '../utils/md'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Delete, FolderOpened, FolderAdd } from '@element-plus/icons-vue'
import PreviewDrawer from '../components/PreviewDrawer.vue'
import { preview } from '../store/preview'

// 左侧栏宽度可拖拽（宽度持久化到 localStorage，双击把手复位）
const { width: asideW, dragging: asideDrag, onDown: onAsideDown, reset: resetAside } =
  useAsideWidth('notes', 250, 200, 520)

const notes = ref([])
const currentId = ref('')
const title = ref('')
const type = ref('md')
const content = ref('')
const previewMode = ref(true)
const titleRef = ref(null)
const editorRef = ref(null)

const isDraft = computed(() => !currentId.value)
const currentNote = computed(() => notes.value.find((n) => n.id === currentId.value))

// ── 保存状态：dirty 有改动 / saving 保存中 / saved 已保存 / error 保存失败 ──
const AUTOSAVE_MS = 15000
const dirty = ref(false)
const saveState = ref('idle')
const lastSavedAt = ref('')
const saveError = ref('')
const manualSaved = ref(false)
const creating = ref(false)    // 新建笔记前先落盘当前草稿
const removingId = ref('')     // 正在删除的笔记 id
const renamingId = ref('')     // 正在重命名的笔记 id
const refKbId = ref('default') // @doc 引用跳转时定位到的知识库
const newDlg = ref(false)      // 新建笔记的格式选择弹窗
const newType = ref('md')
const newFolder = ref('')          // 新建对话框里选的归属文件夹
const draftFolder = ref('')        // 当前草稿的归属文件夹（创建时提交）
const contentLoaded = ref(true)   // 正文是否已加载完成；未完成时禁止保存（防止空内容覆盖）
const noteFolders = ref([])        // 现有文件夹列表
const movingId = ref('')           // 正在移动的笔记 id
// 按文件夹分组：根目录一组，其余按文件夹名分组（侧栏分组展示）
const noteGroups = computed(() => {
  const root = [], byFolder = new Map()
  for (const n of notes.value) {
    const f = n.folder || ''
    if (!f) { root.push(n); continue }
    if (!byFolder.has(f)) byFolder.set(f, [])
    byFolder.get(f).push(n)
  }
    // 物理存在但还没放笔记的文件夹（如刚新建的）也要显示
    for (const f of noteFolders.value) {
      if (f && !byFolder.has(f)) byFolder.set(f, [])
    }
    const groups = []
    if (root.length || byFolder.size) groups.push({ folder: '', name: '根目录', items: root })
    for (const f of [...byFolder.keys()].sort()) groups.push({ folder: f, name: f, items: byFolder.get(f) })
    return groups
})

const NOTE_TYPES = [
  { t: 'md', icon: '📝', name: 'Markdown', desc: '支持标题/列表，预览友好' },
  { t: 'txt', icon: '📄', name: '纯文本', desc: '随手记，无格式' },
  { t: 'doc', icon: '📃', name: 'Word 文档', desc: '生成 .docx，可导出给他人' },
]

const nowHM = () => new Date().toTimeString().slice(0, 8)

const saveText = computed(() => {
  switch (saveState.value) {
    case 'saving': return '正在保存…'
    case 'dirty': return `有改动 · ${AUTOSAVE_MS / 1000} 秒后自动保存`
    case 'saved': return `${manualSaved.value ? '已保存' : '已自动保存'} · ${lastSavedAt.value}`
    case 'error': return `未保存：${saveError.value}`
    default: return isDraft.value ? '新建中 · 有内容后会自动保存' : '已保存'
  }
})

/** 点击 @doc 引用：反查文档所在知识库并打开预览 */
async function openDocRef(e) {
  const el = e.target.closest('.docref')
  if (!el) return
  const path = el.dataset.doc
  try {
    const loc = await api.get('/api/kb/locate?path=' + encodeURIComponent(path))
    if (!loc.ok) { ElMessage.error(loc.error || '没有知识库包含该文件'); return }
    preview.value = { open: true, docPath: path, seq: null }
    refKbId.value = loc.kbId
  } catch (err) {
    ElMessage.error('定位失败：' + (err.message || err))
  }
}

function markDirty() {
  dirty.value = true
  saveError.value = ''
  if (saveState.value !== 'saving') saveState.value = 'dirty'
}

// ── 文件名 ↔ 类型：扩展名决定类型 ──
const EXT = { md: 'md', txt: 'txt', doc: 'docx' }
const extOf = (t) => EXT[t] || 'md'
/** 标题里已经写了扩展名（x.txt）→ 不再重复显示灰色后缀 */
const titleHasExt = computed(() => /\.(md|txt|docx?)$/i.test((title.value || '').trim()))

/** 解析输入的文件名。返回 { base, type } 或 { error }。 */
function parseName(raw, exceptId, fixedType, skipDup) {
  const name = (raw || '').trim()
  if (!name) return { error: '请先填写文件名' }
  if (/[\\/:*?"<>|]/.test(name)) return { error: '文件名不能包含 \\ / : * ? " < > |' }
  if (name.startsWith('.')) return { error: '文件名不能以 . 开头' }

  let base = name
  let t = fixedType || type.value || 'md'
  const m = name.match(/\.([A-Za-z0-9]+)$/)
  if (m) {
    const ext = m[1].toLowerCase()
    const hit = Object.entries(EXT).find(([, v]) => v === ext)
    if (!hit) return { error: `不支持 .${ext}，可用 .md / .txt / .docx` }
    base = name.slice(0, -(ext.length + 1)).trim()
    if (fixedType && hit[0] !== fixedType) {
      return { error: `类型创建后不可改，这个笔记是 .${extOf(fixedType)}` }
    }
    t = hit[0]
  }
  while (/\.(md|txt|docx|doc)$/i.test(base)) base = base.replace(/\.(md|txt|docx|doc)$/i, '').trim()
  if (!base) return { error: '请先填写文件名' }
  if (!skipDup && notes.value.some((n) => n.id !== exceptId && n.title === base)) {
    return { error: `已存在同名笔记「${base}」` }
  }
  return { base, type: t }
}

/** 没写文件名时，用正文首行兜底，保证内容不会因为没名字而丢 */
function autoNameFrom(text) {
  const line = (text || '').split('\n')
    .map((s) => s.replace(/^[#>\-*\s]+/, '').trim())
    .find((s) => s) || ''
  const clean = line.replace(/[\\/:*?"<>|]/g, ' ').replace(/\s+/g, ' ').replace(/^\.+/, '').trim()
  return clean ? clean.slice(0, 24) : '未命名笔记'
}

function focusTitle() {
  nextTick(() => titleRef.value?.focus?.())
}

/** 新建时：名字里写的扩展名即时反映到类型选择上 */
function onTitleInput() {
  markDirty()
  if (!isDraft.value) return
  const m = (title.value || '').trim().match(/\.(md|txt|docx?)$/i)
  if (m) {
    const e = m[1].toLowerCase()
    type.value = e === 'docx' ? 'doc' : e
  }
}

/**
 * 落盘：手动保存与自动保存共用。
 * manual=false 时静默失败（只更新状态标识），不弹窗不打断输入。
 */
async function persist(manual = false) {
  // 防重入：草稿态连续点击会 POST 出两条重复笔记
  if (saveState.value === 'saving') return false
  // 正文未加载完成时绝不保存（此时 content 为空，保存会把原笔记清空）
  if (currentId.value && !contentLoaded.value) {
    if (manual) ElMessage.warning('正文还在加载，请稍候再保存（避免用空内容覆盖原笔记）')
    return false
  }
  const raw = (title.value || '').trim()
  const c = content.value || ''
  if (!raw && !c.trim()) {
    if (manual) { ElMessage.warning('先在上方填写文件名，再点「保存」'); focusTitle() }
    return false
  }
  const except = currentId.value || undefined
  const fixed = currentId.value ? type.value : undefined
  // 自动保存允许重名（后端会加序号），名字不合法则退回原名/正文命名，内容优先
  let p = parseName(raw || autoNameFrom(c), except, fixed, !manual)
  let nameFixed = false
  if (p.error) {
    if (isDraft.value) {
      p = parseName(autoNameFrom(c), undefined, undefined, true)
      nameFixed = true
    } else if (currentNote.value) {
      p = { base: currentNote.value.title, type: type.value }
      nameFixed = true
    }
  }
  if (p.error) {
    saveError.value = p.error
    saveState.value = 'error'
    if (manual) { ElMessage.warning(p.error); focusTitle() }
    return false
  }

  saveState.value = 'saving'
  try {
    if (isDraft.value) {
      const n = await api.post('/api/notes', { title: p.base, type: p.type, content: c, folder: draftFolder.value })
      currentId.value = n.id
      title.value = n.title
      type.value = n.type
    } else {
      await api.putJson('/api/notes/' + currentId.value, { title: p.base, content: c })
      title.value = p.base
    }
    dirty.value = false
    manualSaved.value = manual
    lastSavedAt.value = nowHM()
    saveState.value = 'saved'
    saveError.value = ''
    notes.value = await api.get('/api/notes')
    if (nameFixed && manual) ElMessage.warning('文件名不合法，已用原文件名保存正文')
    if (manual) ElMessage.success('已保存')
    return true
  } catch (e) {
    saveError.value = String(e.message || e)
    saveState.value = 'error'
    if (manual) ElMessage.error('保存失败：' + saveError.value)
    return false
  }
}

/** 页面要关了/要切走了：用 keepalive 兜一次，来不及等响应 */
function flushBeacon() {
  if (!dirty.value) return
  const raw = (title.value || '').trim() || autoNameFrom(content.value)
  if (!raw) return
  let p = parseName(raw, currentId.value || undefined, currentId.value ? type.value : undefined, true)
  if (p.error) p = parseName(autoNameFrom(content.value), currentId.value || undefined,
    currentId.value ? type.value : undefined, true)
  if (p.error) return
  const body = isDraft.value
    ? { title: p.base, type: p.type, content: content.value }
    : { title: p.base, content: content.value }
  const url = isDraft.value ? '/api/notes' : '/api/notes/' + currentId.value
  try {
    fetch(url, {
      method: isDraft.value ? 'POST' : 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      keepalive: true,
    }).catch(() => {})
  } catch (e) { /* ignore */ }
}

let timer = null
function onTick() {
  if (dirty.value) persist(false)
}

function onVisible() {
  if (document.visibilityState === 'hidden') persist(false)
}

// ── 新建：进空白编辑页，标题处就是填文件名的地方 ──
function newNote() {
  if (creating.value) return
  const go = () => { creating.value = false; newDlg.value = true }  // 先选格式再进编辑
  if (dirty.value) {
    creating.value = true
    persist(false).then((ok) => {
      creating.value = false
      if (ok) { go(); return }
      ElMessageBox.confirm('当前草稿自动保存失败，新建会覆盖未保存内容。仍要新建吗？', '保存失败', {
        type: 'warning', confirmButtonText: '丢弃并新建', cancelButtonText: '取消',
      }).then(go).catch(() => { creating.value = false })
    })
  } else {
    go()
  }
}

/** 格式选定了 → 进入空白编辑 */
function startDraft(t) {
  newDlg.value = false
  resetDraft(t, newFolder.value)
  focusTitle()
}

function resetDraft(t = 'md', folder = '') {
  currentId.value = ''
  title.value = ''
  type.value = t
  draftFolder.value = folder
  content.value = ''
  contentLoaded.value = true
  dirty.value = false
  saveState.value = 'idle'
  lastSavedAt.value = ''
  focusTitle()
}

// ── 重命名：弹窗改文件名，只动名字不动正文 ──
function renameDialog(n) {
  if (renamingId.value) return
  ElMessageBox.prompt('只改文件名，正文与类型不变', '重命名', {
    inputValue: n.title + '.' + extOf(n.type),
    confirmButtonText: '重命名',
    cancelButtonText: '取消',
    inputValidator: (v) => parseName(v, n.id, n.type).error || true,
  }).then(async ({ value }) => {
    const p = parseName(value, n.id, n.type)
    if (p.error || p.base === n.title) return
    renamingId.value = n.id
    try {
      const r = await api.patch('/api/notes/' + n.id + '/rename', { title: p.base })
      if (!r.ok) { ElMessage.error(r.error || '重命名失败'); return }
      if (currentId.value === n.id) title.value = r.title
      notes.value = await api.get('/api/notes')
      ElMessage.success('已重命名为「' + r.title + '」')
    } catch (e) {
      ElMessage.error('重命名失败：' + (e.message || e))
    } finally {
      renamingId.value = ''
    }
  }).catch(() => {})
}

function remove() {
  if (!currentId.value) { ElMessage.warning('还没有创建这个笔记'); return }
  removeNote({ id: currentId.value, title: title.value || '未命名笔记' })
}

/** 笔记拖拽（pointer 事件实现，不依赖浏览器原生 DnD，落点自己算） */
const drag = ref(null)   // { id, title, x, y, folder }
const dropFolder = ref(null)   // 拖拽悬停的文件夹分组（高亮用）
// 分组折叠状态（folder -> true 表示收起），记住用户的选择
const collapsed = ref(JSON.parse(localStorage.getItem('kb-note-collapsed') || '{}'))
function toggleGroup(folder) {
  const next = { ...collapsed.value, [folder || '__root__']: !collapsed.value[folder || '__root__'] }
  collapsed.value = next
  localStorage.setItem('kb-note-collapsed', JSON.stringify(next))
}

function onHandleDown(n, e) {
  if (e.button !== 0) return
  e.preventDefault()
  drag.value = { id: n.id, title: n.title, x: e.clientX, y: e.clientY, folder: null }
  // document 级捕获阶段监听：无论落点在哪、有没有指针捕获干扰，事件都收得到
  document.addEventListener('pointermove', onPointerMove, true)
  document.addEventListener('pointerup', onPointerUp, true)
}

function onPointerMove(e) {
  const d = drag.value
  if (!d) return
  d.x = e.clientX
  d.y = e.clientY
  const folder = nearestFolder(e.clientX, e.clientY)
  d.folder = folder
  dropFolder.value = folder
}

/** 落点解析：优先用事件目标上溯（浏览器原生语义，最可靠），再退回坐标就近吸附 */
function folderFromEvent(e) {
  const t = e && e.target
  const wrap = t && t.closest ? t.closest('.ngwrap') : null
  if (wrap) return wrap.getAttribute('data-folder') || ''
  try { return nearestFolder(e.clientX, e.clientY) } catch (_) { return null }
}

/** 就近吸附：光标离哪个分组矩形最近就落到哪（48px 容差，拖到空隙/边缘也能识别） */
function nearestFolder(x, y) {
  let best = null
  let bestD = Infinity
  for (const w of document.querySelectorAll('.ngwrap')) {
    const r = w.getBoundingClientRect()
    const dx = Math.max(r.left - x, 0, x - r.right)
    const dy = Math.max(r.top - y, 0, y - r.bottom)
    const dist = Math.hypot(dx, dy)
    if (dist < bestD) { bestD = dist; best = w }
  }
  if (!best || bestD > 48) return null
  return best.getAttribute('data-folder') || ''
}

function onPointerUp(e) {
  document.removeEventListener('pointermove', onPointerMove, true)
  document.removeEventListener('pointerup', onPointerUp, true)
  const d = drag.value
  drag.value = null
  dropFolder.value = null
  if (!d) return
  // 一律以松手位置为准（过程事件可能丢失，不能作为判定依据）
  let folder = null
  try {
    folder = folderFromEvent(e)
  } catch (err) {
    return
  }

  if (folder === null) return
  const n = notes.value.find((x) => x.id === d.id)
  if (!n || (n.folder || '') === folder) return
  doMove(n, folder)
}

async function doMove(n, folder) {
  // 乐观更新：先把本地数据改掉，UI 立即到位（拖拽松手即时生效）
  const idx = notes.value.findIndex((x) => x.id === n.id)
  const oldFolder = (idx >= 0 ? notes.value[idx].folder : n.folder) || ''
  if (folder && !noteFolders.value.includes(folder)) noteFolders.value.push(folder)
  if (idx >= 0) notes.value[idx] = { ...notes.value[idx], folder, updatedAt: notes.value[idx].updatedAt }
  if (currentId.value === n.id) draftFolder.value = folder
  movingId.value = n.id
  try {
    const r = await api.post(`/api/notes/${n.id}/move?folder=${encodeURIComponent(folder)}`)
    if (!r.ok) throw new Error(r.error || '移动失败')
    ElMessage({ message: folder ? `已移动到「${folder}」` : '已移动到根目录', type: 'success', duration: 1200 })
  } catch (e) {
    // 失败回滚
    const i2 = notes.value.findIndex((x) => x.id === n.id)
    if (i2 >= 0) notes.value[i2] = { ...notes.value[i2], folder: oldFolder }
    if (currentId.value === n.id) draftFolder.value = oldFolder
    ElMessage.error('移动失败：' + (e.message || e))
    load()
  } finally {
    movingId.value = ''
  }
}

/** 重命名文件夹 */
function renameFolderDialog(g) {
  ElMessageBox.prompt(`重命名文件夹「${g.name}」`, '重命名文件夹', {
    inputValue: g.name,
    inputPlaceholder: '新名称',
    confirmButtonText: '保存', cancelButtonText: '取消',
  }).then(async ({ value }) => {
    const name = (value || '').trim()
    if (!name || name === g.name) return
    try {
      const r = await api.post(`/api/notes/folders/rename?from=${encodeURIComponent(g.folder)}&to=${encodeURIComponent(name)}`)
      if (!r.ok) { ElMessage.error(r.error || '重命名失败'); return }
      // 折叠状态跟到新名字
      if (collapsed.value[g.folder]) {
        const next = { ...collapsed.value }
        delete next[g.folder]
        next[name] = true
        collapsed.value = next
        localStorage.setItem('kb-note-collapsed', JSON.stringify(next))
      }
      ElMessage.success('文件夹已重命名')
      load()
    } catch (e) {
      ElMessage.error('重命名失败：' + (e.message || e))
    }
  }).catch(() => {})
}

/** 删除文件夹：内含笔记移到根目录（内容不丢） */
async function deleteFolder(g) {
  const n = (g.items || []).length
  const msg = n > 0
    ? `文件夹「${g.name}」中有 ${n} 篇笔记。\n\n删除后这些笔记会移到根目录，内容不会丢失。确定删除文件夹？`
    : `删除空文件夹「${g.name}」？`
  try {
    await ElMessageBox.confirm(msg, '删除文件夹', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch { return }
  try {
    const r = await api.del(`/api/notes/folders?name=${encodeURIComponent(g.folder)}`)
    if (!r.ok) { ElMessage.error(r.error || '删除失败'); return }
    ElMessage.success(n > 0 ? `文件夹已删除，${n} 篇笔记已移到根目录` : '文件夹已删除')
    load()
  } catch (e) {
    ElMessage.error('删除失败：' + (e.message || e))
  }
}

/** 新建文件夹（物理目录，供后续归类） */
function addFolder() {
  ElMessageBox.prompt('输入文件夹名（可多级，如「上线方案/2026-11」）', '新建文件夹', {
    inputPlaceholder: '文件夹名',
    confirmButtonText: '创建', cancelButtonText: '取消',
  }).then(async ({ value }) => {
    try {
      const r = await api.post('/api/notes/folders?name=' + encodeURIComponent((value || '').trim()))
      if (!r.ok) { ElMessage.error(r.error || '创建失败'); return }
      noteFolders.value = r.folders
      ElMessage.success('文件夹已创建')
    } catch (e) {
      ElMessage.error('创建失败：' + (e.message || e))
    }
  }).catch(() => {})
}

/** 按笔记对象删除：侧栏与编辑器共用；删的是当前打开的笔记时清空编辑器 */
async function removeNote(n) {
  if (!n?.id || removingId.value) return
  try {
    await ElMessageBox.confirm(`删除笔记「${n.title}」？删除后不可恢复。`, '删除笔记', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch { return }
  removingId.value = n.id
  try {
    await api.del('/api/notes/' + n.id)
    ElMessage.success('笔记已删除')
    if (currentId.value === n.id) {
      currentId.value = ''
      title.value = ''
      content.value = ''
      dirty.value = false
      saveState.value = 'idle'
    }
    load()
  } catch (e) {
    ElMessage.error('删除失败：' + (e.message || e))
  } finally {
    removingId.value = ''
  }
}

let everShown = false
/** keep-alive 切回本页：刷新笔记列表；草稿与编辑内容不受影响 */
onActivated(() => {
  if (!everShown) { everShown = true; return } // 首次进入由 onMounted 负责
  load()
})

onMounted(() => {
  load()
  timer = setInterval(onTick, AUTOSAVE_MS)
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('beforeunload', flushBeacon)
})

onBeforeUnmount(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisible)
  window.removeEventListener('beforeunload', flushBeacon)
  // 切到别的页面也要留住内容
  if (dirty.value) persist(false)
})

async function load() {
  notes.value = await api.get('/api/notes')
  noteFolders.value = await api.get('/api/notes/folders').catch(() => [])
  if (!currentId.value && notes.value.length) doSelect(notes.value[0])
}

/** 切换笔记：先静默存住当前内容，再切（不弹确认） */
function select(n) {
  if (n.id === currentId.value) return
  if (dirty.value) {
    persist(false).then((ok) => {
      if (ok) { doSelect(n); return }
      ElMessageBox.confirm('当前草稿自动保存失败，直接切换会丢失未保存内容。仍要切换吗？', '保存失败', {
        type: 'warning', confirmButtonText: '丢弃并切换', cancelButtonText: '留在本页',
      }).then(() => doSelect(n)).catch(() => {})
    })
  } else doSelect(n)
}

function doSelect(n) {
  currentId.value = n.id
  title.value = n.title
  type.value = n.type
  content.value = ''
  contentLoaded.value = false   // 正文还没回来：禁止保存，否则会用空内容覆盖原文件
  dirty.value = false
  manualSaved.value = true
  saveState.value = 'saved'
  lastSavedAt.value = n.updatedAt.slice(11, 19)
  api.get('/api/notes/' + n.id).then((d) => {
    // 竞态保护：响应回来时用户可能已切到别的笔记，不能把 A 的正文塞进 B
    if (currentId.value !== n.id) return
    content.value = d.content || ''
    contentLoaded.value = true
  }).catch((e) => {
    if (currentId.value !== n.id) return
    saveState.value = 'error'
    ElMessage.error('笔记内容加载失败，已暂停自动保存（防止覆盖）：' + (e.message || e))
  })
}

// 实时分屏预览
const rendered = computed(() => (type.value === 'md'
  ? mdToHtml(content.value).replace(/@doc:([^\s<"']+)/g,
    '<span class="docref" data-doc="$1" style="color:var(--accent);cursor:pointer">📎 $1</span>')
  : null))
</script>

<template>
  <el-container class="np">
    <el-aside :width="asideW + 'px'" class="nside">
      <div style="display:flex;gap:6px;margin-bottom:8px">
          <el-button type="primary" style="flex:1" :loading="creating" @click="newNote">＋ 新建笔记</el-button>
          <el-button style="flex:0 0 auto" @click="addFolder" title="新建文件夹">
            <el-icon><FolderAdd /></el-icon>
          </el-button>
        </div>
      <div class="nlist">
        <!-- 新建中的草稿行：有改动就自动保存成正式笔记 -->
        <div v-if="isDraft" class="nitem on draft">
          <span class="nt">{{ title || '未命名' }}</span>
          <div class="d">新建中 · 有内容后自动保存</div>
        </div>

        <div v-if="!notes.length && !isDraft" class="nempty">还没有笔记，点上方新建</div>

        <template v-for="g in noteGroups" :key="g.folder || '__root__'">
        <div class="ngwrap" :class="{ dropt: dropFolder === g.folder && drag }" :data-folder="g.folder">
          <div class="ngroup" @click="toggleGroup(g.folder)">
            <span class="ng-arrow" :class="{ off: collapsed[g.folder || '__root__'] }">▾</span>
            <el-icon class="ng-ic"><FolderOpened /></el-icon>{{ g.name }}
            <span class="ng-n">{{ g.items.length }}</span>
            <span v-if="g.folder" class="ng-ops">
              <el-tooltip content="重命名文件夹" placement="top">
                <el-icon @click.stop="renameFolderDialog(g)"><EditPen /></el-icon>
              </el-tooltip>
              <el-tooltip content="删除文件夹（内含笔记移到根目录）" placement="top">
                <el-icon class="del" @click.stop="deleteFolder(g)"><Delete /></el-icon>
              </el-tooltip>
            </span>
          </div>
          <template v-if="!collapsed[g.folder || '__root__']">
          <div v-for="n in g.items" :key="n.id" class="nitem"
               :class="{ on: n.id === currentId, dragging: drag && drag.id === n.id }" :style="g.folder ? 'padding-left:14px' : ''"
               @click="select(n)">
          <span class="drag-handle" title="按住拖动到文件夹分组即可移动"
                @pointerdown.stop.prevent="onHandleDown(n, $event)">⠿</span>
          <span class="nt" :title="n.title + '.' + extOf(n.type) + '（双击可重命名）'" @dblclick.stop="renameDialog(n)">{{ n.title }}</span>
          <div class="d">{{ extOf(n.type) }} · {{ n.updatedAt.slice(5, 16).replace('T', ' ') }}</div>
          <span class="nops">
            <el-tooltip content="删除" placement="top">
              <el-icon class="del" :class="{ busy: removingId === n.id }" @click.stop="removeNote(n)"><Delete /></el-icon>
            </el-tooltip>
          </span>
          </div>
          </template>
        </div>
        </template>
      </div>
    </el-aside>
    <!-- 侧栏宽度拖拽把手：拖动改变宽度，双击复位 -->
    <div class="aside-rs" :class="{ on: asideDrag }" :style="{ left: asideW + 'px' }"
         title="拖动调整宽度，双击恢复默认" @pointerdown="onAsideDown" @dblclick="resetAside" />

    <el-main class="nmain">
      <div class="page-head">
        <div class="ph-main">
          <div class="nameline">
            <el-input ref="titleRef" v-model="title" class="ntitle" :class="{ 'is-empty': !title }"
                      :placeholder="isDraft ? '填写文件名…' : '文件名'"
                      @input="onTitleInput" @keydown.enter.prevent="persist(true)" />
            <span v-if="title && !titleHasExt" class="next">.{{ extOf(type) }}</span>
          </div>
          <!-- 保存状态：文件上方常驻，随时知道内容在不在盘上 -->
          <div class="saveline" :class="saveState">
            <span class="dot"></span>
            <span class="stext">{{ saveText }}</span>
          </div>
        </div>
        <div class="pact">
          <el-segmented v-model="type" :options="['md', 'txt', 'doc']" size="small"
                        :disabled="!isDraft" disabled-tip="类型创建后不可改" />
          <el-button size="small" :type="dirty ? 'primary' : 'default'" :loading="saveState === 'saving'" @click="persist(true)">
            {{ isDraft ? '保存为文件' : (dirty ? '保存' : '已保存') }}
          </el-button>
          <el-button size="small" type="danger" plain :disabled="isDraft" :loading="removingId === currentId" @click="remove">删除</el-button>
        </div>
      </div>
      <div class="nbody">
        <div class="nedit">
          <el-input ref="editorRef" v-model="content" type="textarea" style="height:100%"
                    :placeholder="isDraft ? '在这里写正文；文件名写在上方标题处，切走会自动保存' : '直接在这里写…'"
                    @input="markDirty" />
        </div>
        <div v-if="previewMode && type === 'md'" class="npv" v-html="rendered" @click="openDocRef"></div>
      </div>
      <div class="nfoot">
        <el-switch v-model="previewMode" active-text="实时预览" />
        <el-text size="small" type="info" style="margin-left:auto">
          {{ (title || '未命名') + '.' + extOf(type) }} · {{ content.length }} 字
        </el-text>
      </div>
    </el-main>
    <!-- 拖拽跟随浮层 -->
    <div v-if="drag" class="drag-ghost" :style="{ left: drag.x + 14 + 'px', top: drag.y + 8 + 'px' }">
      <span class="dg-ic">⠿</span>{{ drag.title }}
      <span v-if="drag.folder !== null" class="dg-to">→ {{ drag.folder || '根目录' }}</span>
    </div>
    <PreviewDrawer :target="preview" :kb-id="refKbId" @close="preview.open = false" />

    <!-- 新建笔记：先选定格式 -->
    <el-dialog v-model="newDlg" title="新建笔记" width="420px" :close-on-click-modal="false">
      <div class="fmt-grid">
        <div v-for="f in NOTE_TYPES" :key="f.t" class="fmt" :class="{ sel: newType === f.t }" @click="newType = f.t">
          <div class="fmt-ic">{{ f.icon }}</div>
          <div class="fmt-n">{{ f.name }}</div>
          <div class="fmt-d">{{ f.desc }}</div>
        </div>
      </div>
      <div class="fmt-folder">
        <span class="ff-label">保存到</span>
        <el-select v-model="newFolder" filterable allow-create default-first-option size="small" style="flex:1"
                   placeholder="根目录（可输入新文件夹名）">
          <el-option label="根目录" value="" />
          <el-option v-for="f in noteFolders" :key="f" :label="f" :value="f" />
        </el-select>
      </div>
      <template #footer>
        <el-button @click="newDlg = false">取消</el-button>
        <el-button type="primary" @click="startDraft(newType)">开始编辑</el-button>
      </template>
    </el-dialog>
</el-container>
</template>

<style scoped>
.np{height:100%;background:var(--el-bg-color-page);position:relative}
.nside{background:#FBFBFC;border-right:1px solid var(--el-border-color-light);padding:12px 8px;overflow:auto}
.nlist{margin-top:8px}
.ngroup{display:flex;align-items:center;gap:5px;padding:8px 11px 4px;font-size:var(--fs-2xs);
  color:var(--ink3);font-weight:600;cursor:pointer;border-radius:6px;user-select:none}
.ngroup:hover{color:var(--ink2);background:var(--mist)}
.ng-arrow{font-size:9px;transition:transform var(--t-fast) var(--ease);width:8px;flex:0 0 8px}
.ng-arrow.off{transform:rotate(-90deg)}
.ngroup .ng-ic{font-size:12px}
.ngroup .ng-n{margin-left:auto}
.ng-ops{display:flex;gap:3px;opacity:0;margin-left:4px;transition:opacity var(--t-fast) var(--ease)}
.ngroup:hover .ng-ops{opacity:1}
.ng-ops .el-icon{font-size:12px;cursor:pointer}
.ng-ops .el-icon:hover{color:var(--accent)}
.ng-ops .el-icon.del:hover{color:var(--el-color-danger)}
.ngroup:hover .ng-n{display:none}
.drag-ghost{position:fixed;z-index:4000;pointer-events:none;background:var(--card);
  border:1px solid var(--accent);border-radius:8px;padding:5px 10px;font-size:var(--fs-xs);
  box-shadow:0 6px 18px rgba(20,24,38,.18);color:var(--ink);max-width:260px;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.drag-ghost .dg-ic{color:var(--ink3);margin-right:5px;letter-spacing:-1px}
.drag-ghost .dg-to{color:var(--accent);margin-left:6px;font-weight:600}
.ngwrap{border-radius:8px;transition:background var(--t-fast) var(--ease),outline var(--t-fast) var(--ease)}
.ngwrap.dropt{background:var(--accent-soft);outline:1.5px dashed var(--accent)}
.nitem.dragging{opacity:.4}
.drag-handle{position:absolute;left:3px;top:50%;transform:translateY(-50%);
  font-size:11px;color:var(--ink3);cursor:grab;opacity:0;letter-spacing:-1px;
  transition:opacity var(--t-fast) var(--ease);user-select:none}
.nitem:hover .drag-handle{opacity:.75}
.drag-handle:active{cursor:grabbing}
body:has(.drag-ghost),body:has(.drag-ghost) *{cursor:grabbing !important;user-select:none}
.nitem.dragging{opacity:.4}
.nitem{position:relative;padding:9px 11px 9px 18px;border-radius:8px;cursor:pointer;font-size:var(--fs-sm);line-height:1.5}
.nitem:hover{background:var(--el-fill-color-light)}
.nitem.on{background:var(--el-color-primary-light-9)}
.nitem.draft{cursor:default}
.nitem.draft .nt{color:var(--el-text-color-regular);font-style:italic}
.nitem .nt{display:block;padding-right:18px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-weight:500;color:var(--el-text-color-primary)}
.nitem .d{font-size:var(--fs-2xs);color:var(--el-text-color-secondary);margin-top:3px}
.nops{position:absolute;right:6px;top:7px;opacity:0;transition:opacity var(--t-fast) var(--ease);
  display:flex;gap:2px;background:var(--card);border:1px solid var(--line);border-radius:7px;
  padding:1px 3px;box-shadow:0 1px 4px rgba(20,24,38,.08)}
.nitem:hover .nops{opacity:1}
.nops .el-icon{font-size:13px;color:var(--el-text-color-secondary);cursor:pointer}
.nops .el-icon:hover{color:var(--el-color-primary)}
.nops .el-icon.del:hover{color:var(--el-color-danger)}
.nops .el-icon.del.busy{opacity:.35;pointer-events:none}
.fmt-folder{display:flex;align-items:center;gap:8px;margin-top:12px}
.ff-label{font-size:var(--fs-xs);color:var(--ink3);flex:0 0 auto}
.nempty{padding:14px 10px;font-size:var(--fs-xs);color:var(--el-text-color-secondary);text-align:center}
.nmain{display:flex;flex-direction:column;padding:0;overflow:hidden}
.nbody{flex:1;display:flex;gap:10px;min-height:0;margin:0 var(--page-x) 10px}
.nedit{flex:1;min-width:0;display:flex;background:#fff;border:1px solid var(--el-border-color-light);
  border-radius:8px;overflow:hidden}
.nedit :deep(.el-textarea__inner){height:100%;box-shadow:none;border:0;background:transparent;
  font-size:var(--fs-base);line-height:var(--lh-loose);padding:16px 20px;resize:none}
.fmt-grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px}
.fmt{border:1.5px solid var(--el-border-color);border-radius:10px;padding:14px 8px;text-align:center;
  cursor:pointer;transition:all var(--t-fast) var(--ease)}
.fmt:hover{border-color:var(--accent)}
.fmt.sel{border-color:var(--accent);background:var(--el-color-primary-light-9)}
.fmt-ic{font-size:22px;margin-bottom:4px}
.fmt-n{font-weight:600;font-size:var(--fs-sm)}
.fmt-d{font-size:var(--fs-2xs);color:var(--ink3);margin-top:2px}
.npv .docref:hover{text-decoration:underline}
.npv{flex:1;min-width:0;background:#fff;border:1px solid var(--el-border-color-light);border-radius:8px;padding:16px 20px;overflow:auto;
  font-size:var(--fs-base);line-height:var(--lh-loose)}
.npv :deep(h1){font-size:19px;margin:0 0 12px}
.npv :deep(h2){font-size:16px;margin:16px 0 8px;padding-bottom:5px;border-bottom:1px solid var(--el-border-color-light)}
.npv :deep(h3){font-size:14px;margin:12px 0 6px}
.npv :deep(p){margin:0 0 9px}
.npv :deep(ul),.npv :deep(ol){margin:0 0 9px;padding-left:22px}
.npv :deep(li){margin-bottom:3px}
.npv :deep(code){background:var(--el-fill-color);border-radius:4px;padding:2px 5px;font-size:12px;font-family:Consolas,monospace}
.npv :deep(.md-table){border-collapse:collapse;width:100%;margin:8px 0;font-size:12.5px}
.npv :deep(.md-table th),.npv :deep(.md-table td){border:1px solid var(--el-border-color-light);padding:6px 10px;text-align:left}
.npv :deep(.md-table th){background:#FAFAFB;font-weight:600}
.npv :deep(blockquote){margin:8px 0;padding:7px 13px;border-left:3px solid var(--el-color-primary);background:#FAFAFE;border-radius:0 8px 8px 0;color:var(--el-text-color-regular)}
.nfoot{display:flex;align-items:center;gap:10px;padding:0 var(--page-x) 16px}
/* 标题做成无边框大字，这里就是填文件名的地方 */
.nameline{display:flex;align-items:baseline;gap:6px;min-width:0}
.ntitle{width:340px;max-width:100%}
@supports (field-sizing: content){
  .ntitle{width:auto;min-width:60px;max-width:62%}
  .ntitle :deep(.el-input__wrapper){width:auto;padding-right:0}
  .ntitle :deep(.el-input__inner){field-sizing:content;min-width:1ch}
  .ntitle.is-empty :deep(.el-input__inner){min-width:14ch}
}
.ntitle :deep(.el-input__wrapper){box-shadow:none;background:transparent;padding:0}
.ntitle :deep(.el-input__inner){font-size:var(--fs-xl);font-weight:600;color:var(--ink);height:auto;line-height:1.35}
.ntitle :deep(.el-input__inner::placeholder){font-weight:400;color:var(--ink3)}
.next{font-size:var(--fs-xl);font-weight:600;color:var(--ink3);line-height:1.35;white-space:nowrap}
/* 保存状态行 */
.saveline{display:flex;align-items:center;gap:6px;margin-top:5px;font-size:var(--fs-xs);color:var(--ink3)}
.saveline .dot{width:6px;height:6px;border-radius:50%;background:var(--el-color-success);flex:0 0 6px}
.saveline.dirty{color:var(--el-color-warning)}
.saveline.dirty .dot{background:var(--el-color-warning)}
.saveline.saving{color:var(--ink2)}
.saveline.saving .dot{background:var(--el-color-primary)}
.saveline.error{color:var(--el-color-danger)}
.saveline.error .dot{background:var(--el-color-danger)}
.saveline.idle .dot{background:var(--el-border-color)}
</style>
