<script setup>
import { ref, shallowRef, computed, watch, nextTick } from 'vue'
import { api } from '../api'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { mdToHtml } from '../utils/md'
// pdfjs worker 用 ?url 引入，dev 与 build 都可用
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

const props = defineProps({ target: Object, kbId: { type: String, default: 'default' } })
const emit = defineEmits(['close'])

const tab = ref('preview')
const loading = ref(false)
const loadError = ref('')
const info = ref({ title: '', ext: '', path: '' })
const docxHtml = ref('')
const sheets = ref([])
const activeSheet = ref(0)
const pdfCanvasHost = ref(null)
// 必须 shallowRef：深度代理会破坏 pdf.js 的私有字段（#pagesNumber）
const pdfDoc = shallowRef(null)
const textChunks = ref([])
const highlightSeq = ref(null)
const locChunk = ref(null)        // 当前要定位的块（用于文档内高亮）
const locateState = ref('')       // '' | doc（文档内已高亮）| fallback（只能看命中片段）

const drawerVisible = computed({
  get: () => !!props.target?.open,
  set: (v) => { if (!v) emit('close') },
})

const kindOf = { word: 'doc', excel: 'xls', ppt: 'ppt', pdf: 'pdf', sql: 'sql', html: 'html', text: 'txt' }

const mode = computed(() => {
  const e = info.value.ext
  if (e === '.pdf') return 'pdf'
  if (e === '.docx') return 'docx'
  if (e === '.xlsx' || e === '.xls') return 'xlsx'
  if (e === '.html' || e === '.htm') return 'html'
  if (e === '.md' || e === '.markdown') return 'md'
  if (['.txt', '.sql', '.java', '.py', '.ts', '.sh', '.bat', '.csv', '.log', '.yml', '.yaml', '.properties', '.ini', '.conf'].includes(e)) return 'code'
  return 'unsupported'
})

/** 反向引用：哪些笔记 @doc: 引用了当前文档 */
const backrefs = ref([])
async function loadBackrefs(docPath) {
  backrefs.value = []
  if (!docPath) return
  try { backrefs.value = await api.get('/api/notes/backrefs?docPath=' + encodeURIComponent(docPath)) }
  catch { /* 无笔记或接口异常：静默 */ }
}

watch(() => props.target, async (t) => {
  if (!t || !t.open || !t.docPath) return
  docxHtml.value = ''
  sheets.value = []
  textChunks.value = []
  loadError.value = ''
  highlightSeq.value = t.seq ?? null
  locChunk.value = null
  locateState.value = ''
  tab.value = 'preview'
  if (t.seq != null && t.seq >= 0) {
    api.get('/api/doc/text?path=' + encodeURIComponent(t.docPath) + '&seq=' + t.seq + '&kbId=' + props.kbId)
      .then((d) => { locChunk.value = d.chunks?.[0] || null })
      .catch(() => { locChunk.value = null })
  }
  loading.value = true
  try {
    const head = await api.get('/api/doc/meta?path=' + encodeURIComponent(t.docPath) + '&kbId=' + props.kbId)
    info.value = { ...head, path: t.docPath }
    loadBackrefs(t.docPath)
    if (mode.value === 'docx') {
      const m = await import('mammoth/mammoth.browser')
      const buf = await fetch('/api/doc/raw?path=' + encodeURIComponent(t.docPath) + '&kbId=' + props.kbId).then((r) => {
        if (!r.ok) throw new Error('原文件获取失败 ' + r.status)
        return r.arrayBuffer()
      })
      const res = await m.convertToHtml({ arrayBuffer: buf })
      docxHtml.value = res.value
      // 旧会话里的引用可能是迁移前的路径，文件已不在：给出明确提示而不是空白
      if (!docxHtml.value || !docxHtml.value.trim()) {
        throw new Error('文档内容为空：该引用可能来自旧版路径，文档已迁移，请重新提问获取最新引用')
      }
    } else if (mode.value === 'pdf') {
      const pdfjs = await import('pdfjs-dist')
      pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl
      const buf = await fetch('/api/doc/raw?path=' + encodeURIComponent(t.docPath) + '&kbId=' + props.kbId).then((r) => {
        if (!r.ok) throw new Error('原文件获取失败 ' + r.status)
        return r.arrayBuffer()
      })
      // 注意：禁止把 pdf.js 文档对象放进深度响应式（会破坏其私有字段）
      pdfDoc.value = await pdfjs.getDocument({ data: buf }).promise
    } else if (mode.value === 'xlsx') {
      const XLSX = await import('xlsx')
      const buf = await fetch('/api/doc/raw?path=' + encodeURIComponent(t.docPath) + '&kbId=' + props.kbId).then((r) => {
        if (!r.ok) throw new Error('原文件获取失败 ' + r.status)
        return r.arrayBuffer()
      })
      const wb = XLSX.read(buf, { type: 'array' })
      sheets.value = wb.SheetNames.map((n) => ({
        name: n,
        html: XLSX.utils.sheet_to_html(wb.Sheets[n]),
      }))
      activeSheet.value = 0
    } else {
      const t2 = await api.get('/api/doc/text?path=' + encodeURIComponent(t.docPath) + (t.seq != null ? '&seq=' + t.seq : '') + '&kbId=' + props.kbId)
      textChunks.value = t2.chunks || []
      if (!textChunks.value.length && t.seq != null) {
        throw new Error('该块在索引里已不存在（引用可能来自旧版路径），请重新提问获取最新引用')
      }
    }
  } catch (e) {
    const msg = String(e && e.message ? e.message : e)
    // 旧会话的引用路径可能已被迁移 / 文档被删：换成能看懂的说法
    loadError.value = /404|原文件获取失败/.test(msg)
      ? '预览不可用：文档已不在原路径（常见于旧会话的引用），请重新提问获取最新引用'
      : msg
  } finally {
    loading.value = false
    if (mode.value === 'pdf') {
      await nextFrame()
      renderPdf()
        .catch((e) => { loadError.value = 'PDF 渲染失败: ' + (e?.message || e) })
        .finally(() => { nextTick(() => locateInDoc()) })
    } else {
      await nextTick()
      locateInDoc()
    }
  }
})

function nextFrame() { return new Promise((r) => requestAnimationFrame(() => r())) }

async function renderPdf() {
  if (!pdfDoc.value || !pdfCanvasHost.value) return
  const pdfjs = await import('pdfjs-dist')
  for (let i = 1; i <= Math.min(pdfDoc.value.numPages, 30); i++) {
    const page = await pdfDoc.value.getPage(i)
    const vp = page.getViewport({ scale: 1.4 })
    const canvas = document.createElement('canvas')
    canvas.width = vp.width; canvas.height = vp.height
    await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp }).promise
    pdfCanvasHost.value.appendChild(canvas)
    if (i === 1) await nextFrame()
  }
}

const openingRaw = ref(false)
async function openRaw() {
  if (openingRaw.value) return
  const q = 'path=' + encodeURIComponent(props.target?.docPath || '') + '&kbId=' + props.kbId
  openingRaw.value = true
  try {
    const r = await api.get('/api/doc/open?' + q)
    if (!r.ok) window.open('/api/doc/raw?' + q)
  } catch (e) {
    window.open('/api/doc/raw?' + q)
  } finally {
    openingRaw.value = false
  }
}

const codeHtml = computed(() => {
  if (mode.value !== 'code' || !textChunks.value.length) return ''
  const text = textChunks.value.map((c) => c.text).join('\n\n')
  try {
    return hljs.highlightAuto(text).value
  } catch (e) { /* ignore */ }
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;')
})

// markdown 渲染（.md/.markdown）
const mdHtml = computed(() => {
  if (mode.value !== 'md' || !textChunks.value.length) return ''
  const text = textChunks.value.map((c) => c.text).join('\n\n')
  return mdToHtml(text)
})

/** 当前格式是否直接渲染块（其余格式是原样预览，块级内容在「命中片段」里） */
const hasChunkView = computed(() => !['docx', 'pdf', 'xlsx', 'md', 'code', 'html'].includes(mode.value))

/** 规范化文本：去掉所有空白，便于跨段落匹配 */
const norm = (s2) => String(s2 || '').replace(/\s+/g, '')

/**
 * 在原样预览里定位：docx/md 高亮匹配段落；pdf 找到所在页；
 * 找不到就退回「命中片段」。
 */
async function locateInDoc() {
  const chunk = locChunk.value
  if (!chunk || !chunk.text) return
  const key = norm(chunk.text).slice(0, 24)
  if (!key) return

  if (mode.value === 'pdf') {
    const host = pdfCanvasHost.value
    if (!host || !pdfDoc.value) { locateState.value = 'fallback'; return }
    const canvases = [...host.querySelectorAll('canvas')]
    for (let i = 0; i < canvases.length; i++) {
      try {
        const page = await pdfDoc.value.getPage(i + 1)
        const tc = await page.getTextContent()
        const text = norm(tc.items.map((it) => it.str).join(''))
        if (text.includes(key) || (key.length > 10 && text.includes(key.slice(0, 10)))) {
          canvases[i].classList.add('loc-page')
          canvases[i].scrollIntoView({ block: 'center', behavior: 'smooth' })
          locateState.value = 'doc'
          return
        }
      } catch (e) { /* 单页失败忽略 */ }
    }
    locateState.value = 'fallback'
    return
  }

  const host = document.querySelector('.docx.rich') || document.querySelector('.md-view')
  if (!host) { locateState.value = 'fallback'; return }

  const blocks = [...host.querySelectorAll('p, li, td, h1, h2, h3, h4, h5, h6, blockquote')]
  let best = null
  let bestLen = 0
  for (const el of blocks) {
    const t2 = norm(el.innerText || el.textContent)
    if (!t2) continue
    if (t2.includes(key)) { best = el; bestLen = key.length; break }
    // 段落被切开时退一步：取公共前缀长度
    let n = 0
    const max = Math.min(t2.length, key.length, 30)
    while (n < max && t2[n] === key[n]) n++
    if (n > bestLen) { bestLen = n; best = el }
  }

  if (best && bestLen >= 6) {
    best.classList.add('loc-block')
    best.scrollIntoView({ block: 'center', behavior: 'smooth' })
    locateState.value = 'doc'
  } else {
    locateState.value = 'fallback'
  }
}

watch(locChunk, (c) => { if (c) nextTick(() => locateInDoc()) })

/** 滚到命中块并闪一下，让"点引用跳过来"能一眼看到位置 */
let flashTimer = null
function jumpToChunk() {
  nextTick(() => {
    const el = document.querySelector('.dw-body .chunk.hl') || document.querySelector('.hits-pane .chunk.hl')
    if (!el) return
    el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    el.classList.add('flash')
    if (flashTimer) clearTimeout(flashTimer)
    flashTimer = setTimeout(() => el.classList.remove('flash'), 2400)
  })
}
watch([loading, () => textChunks.value.length, tab], () => { if (!loading.value) jumpToChunk() })

watch(tab, (t2) => { if (t2 === 'hits') loadHits() })
const hitChunks = ref([])
async function loadHits() {
  const t = props.target
  hitChunks.value = (await api.get('/api/doc/text?path=' + encodeURIComponent(t.docPath) + '&kbId=' + props.kbId)).chunks
  jumpToChunk()
}
</script>

<template>
  <el-drawer v-model="drawerVisible" size="660px" :with-header="false" class="kb-drawer">
    <div class="dw-bar">
      <div class="ic" :data-k="kindOf[info.kind] || 'txt'">{{ (info.ext || '').slice(1, 4).toUpperCase() || 'TXT' }}</div>
      <div class="meta">
        <div class="fn">{{ info.title || target?.docPath }}</div>
        <div class="fp">{{ info.path }}</div>
      </div>
      <el-button class="x" text @click="emit('close')">✕</el-button>
    </div>
    <el-tabs v-model="tab" class="dw-tabs">
      <el-tab-pane label="预览" name="preview" />
      <el-tab-pane label="命中片段" name="hits" />
    </el-tabs>
    <div class="dw-btns">
      <el-button size="small" :loading="openingRaw" @click="openRaw">打开原文件</el-button>
    </div>

    <div v-if="target?.seq != null" class="locate">
      <span class="ldot"></span>
      <span>已定位到第 <b>{{ target.seq }}</b> 块</span>
      <span v-if="locateState === 'doc'" class="lnote">文档内已高亮命中段落</span>
      <span v-else-if="locateState === 'fallback' && !hasChunkView" class="lnote">
        该格式无法在原文里精确定位，块内容看「命中片段」
      </span>
      <el-button v-if="tab !== 'hits'" link size="small" @click="tab = 'hits'">查看命中片段</el-button>
    </div>

    <div v-if="backrefs.length" class="backrefs">
      引用此文档的笔记：
      <span v-for="(b, i) in backrefs" :key="b.id" class="bref" :title="b.snippet">{{ b.title }}<i v-if="i < backrefs.length - 1">、</i></span>
    </div>

    <div class="dw-body" v-loading="loading" element-loading-text="加载中…">
      <div v-if="loadError" class="load-err">
        预览加载失败：{{ loadError }}
        <div class="err-tip">可点右上“打开原文件”直接查看，或关闭抽屉重试</div>
      </div>

      <template v-else-if="!loading">
        <!-- docx -->
        <div v-if="mode === 'docx'" class="docx rich" v-html="docxHtml"></div>

        <!-- pdf -->
        <div v-else-if="mode === 'pdf'" ref="pdfCanvasHost" class="pdf-host"></div>

        <!-- xlsx -->
        <div v-else-if="mode === 'xlsx'">
          <el-tabs v-model="activeSheet" class="sheet-tabs">
            <el-tab-pane v-for="(s, i) in sheets" :key="s.name" :label="s.name" :name="i" />
          </el-tabs>
          <div class="sheet-host" v-html="sheets[activeSheet]?.html"></div>
        </div>

        <!-- markdown -->
        <div v-else-if="mode === 'md'" class="md-view" v-html="mdHtml"></div>

        <!-- 代码/文本 -->
        <div v-else-if="mode === 'code'">
          <pre class="code"><code v-html="codeHtml"></code></pre>
        </div>

        <!-- html -->
        <iframe v-else-if="mode === 'html'" class="html-frame" sandbox
                :src="'/api/doc/raw?path=' + encodeURIComponent(target?.docPath) + '&kbId=' + kbId"></iframe>

        <!-- ppt 文本 / 兜底 -->
        <div v-else-if="textChunks.length" class="ppt-text">
          <div v-for="c in textChunks" :key="c.seq" class="chunk" :class="{ hl: highlightSeq === c.seq }">
            <div class="h">{{ c.heading }}</div>
            <div class="t">{{ c.text }}</div>
          </div>
        </div>

        <el-empty v-else description="该格式暂不支持内嵌预览，可用右上“打开原文件”" :image-size="80" />
      </template>
    </div>

    <!-- 命中片段数据（独立于预览渲染） -->
    <div v-if="tab === 'hits' && hitChunks.length" class="hits-pane">
      <div v-for="c in hitChunks" :key="c.seq" class="chunk" :class="{ hl: highlightSeq === c.seq }">
        <div class="h">块 {{ c.seq }} · {{ c.heading }}</div>
        <div class="t">{{ c.text }}</div>
      </div>
    </div>
  </el-drawer>
</template>

<style>
.kb-drawer{background:var(--card) !important}
.kb-drawer .el-drawer__body{padding:0;display:flex;flex-direction:column;overflow:hidden;position:relative}
.dw-bar{display:flex;align-items:center;gap:12px;padding:14px 20px;border-bottom:1px solid var(--line)}
.dw-bar .ic{width:34px;height:34px;border-radius:8px;display:grid;place-items:center;
  font-size:9px;font-weight:700;flex:0 0 auto;font-family:var(--mono)}
.dw-bar .ic[data-k="doc"]{background:var(--accent-soft);color:var(--accent)}
.dw-bar .ic[data-k="xls"]{background:#E5F3EC;color:#0B7A55}
.dw-bar .ic[data-k="pdf"]{background:#F9E9E6;color:#B23A2E}
.dw-bar .ic[data-k="ppt"]{background:#F5EEDD;color:#8A6210}
.dw-bar .ic[data-k="sql"]{background:#F5EEDD;color:#8A6210}
.dw-bar .ic[data-k="txt"]{background:var(--mist);color:var(--ink2)}
.dw-bar .ic[data-k="html"]{background:#F3EAE0;color:#96562B}
.dw-bar .meta{min-width:0;flex:1}
.backrefs{margin:6px 0 0;padding:6px 12px;background:var(--mist);border-radius:8px;
  font-size:var(--fs-xs);color:var(--ink2)}
.backrefs .bref{color:var(--accent);margin-right:2px}
.dw-bar .fn{font-size:var(--fs-md);font-weight:600;font-family:var(--serif);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dw-bar .fp{font-size:var(--fs-2xs);color:var(--ink3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-family:var(--mono)}
.dw-tabs{padding:6px 20px 0}
.dw-btns{display:flex;justify-content:flex-end;padding:0 20px 6px}
.dw-body{flex:1;overflow:auto;padding:16px 26px 26px;font-size:var(--fs-base);line-height:var(--lh-loose);color:var(--ink)}
.load-err{color:var(--rose);font-size:var(--fs-sm);padding:26px 4px;line-height:1.9}
.err-tip{color:var(--ink3);font-size:var(--fs-xs);margin-top:8px}
.docx.rich h1{font-size:var(--fs-xl);margin:6px 0 12px}
.docx.rich h2{font-size:var(--fs-lg);margin:18px 0 8px}
.docx.rich table{border-collapse:collapse;width:100%;margin:12px 0;font-size:var(--fs-sm)}
.docx.rich td,.docx.rich th{border:1px solid var(--line);padding:7px 11px;text-align:left}
.docx.rich th{background:var(--paper)}
.docx.rich img{max-width:100%}
.pdf-host canvas{max-width:100%;margin-bottom:12px;border:1px solid var(--line);border-radius:8px}
.sheet-tabs{padding:0}
.sheet-host table{border-collapse:collapse;font-size:var(--fs-xs);width:100%}
.sheet-host td{border:1px solid var(--line);padding:6px 10px;white-space:nowrap}
.md-view{font-size:var(--fs-base);line-height:var(--lh-loose)}
.md-view h2{font-size:var(--fs-lg);margin:18px 0 8px;padding-bottom:6px;border-bottom:1px solid var(--line)}
.md-view h3{font-size:var(--fs-md);margin:16px 0 6px}
.md-view h4{font-size:var(--fs-base);margin:14px 0 6px}
.md-view p{margin:0 0 10px}
.md-view .md-sp{height:6px}
.md-view ul,.md-view ol{margin:0 0 10px;padding-left:22px}
.md-view li{margin-bottom:4px}
.md-view blockquote{margin:10px 0;padding:8px 14px;border-left:3px solid var(--accent);background:var(--paper);border-radius:0 8px 8px 0;color:var(--ink2)}
.md-view .md-pre{background:var(--paper);border:1px solid var(--line);border-radius:8px;padding:12px 14px;font-size:var(--fs-xs);overflow:auto;white-space:pre-wrap;margin:10px 0;font-family:var(--mono)}
.md-view .md-table{border-collapse:collapse;width:100%;margin:10px 0;font-size:var(--fs-sm)}
.md-view .md-table th,.md-view .md-table td{border:1px solid var(--line);padding:6px 11px;text-align:left}
.md-view .md-table th{background:var(--paper);font-weight:600}
.md-view code{background:var(--mist);border-radius:5px;padding:1px 6px;font-size:var(--fs-xs);color:#8A3B26;font-family:var(--mono)}
.md-view a{color:var(--accent)}
.ppt-text .chunk{border:1px solid var(--line);border-radius:var(--radius);padding:11px 15px;margin-bottom:9px;background:var(--paper)}
.ppt-text .chunk.hl{border-color:var(--accent);background:var(--accent-soft)}
.locate{display:flex;align-items:center;gap:8px;margin:0 20px 10px;padding:7px 12px;border-radius:8px;
  background:var(--accent-soft);color:var(--ink2);font-size:var(--fs-xs)}
.locate .ldot{width:6px;height:6px;border-radius:50%;background:var(--accent);flex:0 0 6px}
.locate b{color:var(--accent);font-weight:600}
.locate .lnote{color:var(--ink3)}
.locate .el-button{margin-left:auto}
@keyframes chunkflash{
  0%{box-shadow:0 0 0 0 rgba(99,102,241,.45)}
  60%{box-shadow:0 0 0 6px rgba(99,102,241,.12)}
  100%{box-shadow:0 0 0 0 rgba(99,102,241,0)}
}
.chunk.flash{animation:chunkflash 1.2s ease-out 2;border-color:var(--accent)}
/* 文档内的命中段落高亮 */
.docx.rich .loc-block, .md-view .loc-block{
  background:linear-gradient(90deg, rgba(99,102,241,.16), rgba(99,102,241,.05));
  border-left:3px solid var(--accent); border-radius:4px; padding:2px 8px; margin-left:-11px;
  animation:docflash 1.4s ease-out 2;
}
.pdf-host canvas.loc-page{outline:2px solid var(--accent); outline-offset:4px; border-radius:4px; animation:docflash 1.4s ease-out 2}
@keyframes docflash{
  0%{box-shadow:0 0 0 0 rgba(99,102,241,.35)}
  70%{box-shadow:0 0 0 8px rgba(99,102,241,.08)}
  100%{box-shadow:0 0 0 0 rgba(99,102,241,0)}
}
.ppt-text .h{font-size:var(--fs-2xs);color:var(--ink3);margin-bottom:4px;font-family:var(--mono)}
pre.code{white-space:pre-wrap;word-break:break-all;font-size:var(--fs-sm);line-height:1.85;font-family:var(--mono)}
.html-frame{width:100%;height:100%;border:1px solid var(--line);border-radius:8px;background:#fff}
.hits-pane{position:absolute;inset:auto 0 0 0;top:150px;overflow:auto;padding:10px 20px;background:var(--card);border-top:1px solid var(--line)}
.hits-pane .chunk{border:1px solid var(--line);border-radius:var(--radius);padding:10px 14px;margin-bottom:8px;background:var(--paper)}
.hits-pane .chunk.hl{border-color:var(--accent);background:var(--accent-soft)}
.hits-pane .h{font-size:var(--fs-2xs);color:var(--ink3);margin-bottom:4px;font-family:var(--mono)}
</style>
