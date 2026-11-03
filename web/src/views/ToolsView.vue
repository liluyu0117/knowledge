<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { jsonrepair } from 'jsonrepair'
import JsonTree from '../components/JsonTree.vue'
import { Document, Timer, Key, CopyDocument } from '@element-plus/icons-vue'

const tab = ref('json')

async function copy(text, label = '已复制') {
  if (!text) { ElMessage.warning('没有可复制的内容'); return }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(label)
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

// ────────────────────────── JSON 工具 ──────────────────────────
const jsonIn = ref('')
const jsonOut = ref('')
const jsonErr = ref('')
const jsonStat = ref('')
const jsonFixNote = ref('')   // 自动修复的说明
const jsonView = ref('tree')  // tree | text
const jsonTree = ref(null)    // 解析后的对象，供折叠树渲染
const treeDepth = ref(2)      // 树默认展开层数
const treeKey = ref(0)        // 改展开层数时强制重建树

/**
 * 修复字符串里未转义的引号。
 * 真实场景：后端把数组 toString 进字符串却没转义，例如
 *   "dealCode":"["wx-yindy", "wx-zhou"]"   →   "dealCode":"[\"wx-yindy\", \"wx-zhou\"]"
 * 两种处理：
 *  1. 值以 [ 或 { 开头（被包裹的数组/对象）→ 用括号配平找真正的结束引号，中间的引号全转义
 *  2. 普通字符串 → 引号后紧跟 , } : 才算结束，否则当作内容转义
 */
function fixQuotes(src, closers) {
  let out = ''
  let inStr = false
  let fixed = 0
  let bracketMode = false
  let depth = 0
  let first = false
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (!inStr) {
      out += c
      if (c === '"') { inStr = true; first = true; bracketMode = false; depth = 0 }
      continue
    }
    if (c === '\\') { out += c + (src[i + 1] ?? ''); i++; continue }

    if (first) {
      first = false
      if (c === '[' || c === '{') { bracketMode = true; depth = 1; out += c; continue }
    }
    if (bracketMode) {
      if (c === '[' || c === '{') { depth++; out += c; continue }
      if (c === ']' || c === '}') { depth--; out += c; continue }
      if (c === '"') {
        if (depth === 0) { out += c; inStr = false; bracketMode = false }
        else { out += '\\"'; fixed++ }
        continue
      }
      out += c
      continue
    }
    if (c === '"') {
      let j = i + 1
      while (j < src.length && /\s/.test(src[j])) j++
      const next = src[j]
      if (next === undefined || closers.includes(next)) { out += c; inStr = false }
      else { out += '\\"'; fixed++ }
      continue
    }
    out += c
  }
  return { text: out, fixed }
}

/** 把首尾相接的多段 JSON（{...}{...} 或 {...}\n{...}）拆成多段 */
function splitTopLevel(src) {
  const parts = []
  let depth = 0
  let start = -1
  let inStr = false
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (inStr) {
      if (c === '\\') { i++; continue }
      if (c === '"') inStr = false
      continue
    }
    if (c === '"') { inStr = true; if (start < 0) start = i; continue }
    if (c === '{' || c === '[') { if (depth === 0) start = i; depth++; continue }
    if (c === '}' || c === ']') {
      depth--
      if (depth === 0 && start >= 0) { parts.push(src.slice(start, i + 1)); start = -1 }
      continue
    }
  }
  return parts
}

/** 把一段文本按顶层值拆开逐段解析，成功返回合并数组 */
function tryMulti(text, note) {
  const parts = splitTopLevel(text)
  if (parts.length < 2) return null
  const objs = []
  for (const p of parts) {
    try { objs.push(JSON.parse(p)) } catch { return null }
  }
  return { obj: objs, note: note.replace('{n}', String(objs.length)) }
}

/** 依次尝试：原生解析 → 修未转义引号 → 拆分首尾相接的多段 → jsonrepair 兜底 */
function parseLoose(raw) {
  const attempts = []
  try { return { obj: JSON.parse(raw), note: '' } } catch (e) { attempts.push(e.message) }

  // 1) 先修未转义引号：修完可能是单段，也可能是多段（两段脏数据相接时两者都要做）
  for (const closers of [[',', '}', ':'], [',', '}', ':', ']']]) {
    const { text, fixed } = fixQuotes(raw, closers)
    if (!fixed) continue
    try {
      return { obj: JSON.parse(text), note: `已自动转义 ${fixed} 处未闭合的引号（原值是塞在字符串里的数组）` }
    } catch (e) { attempts.push(e.message) }
    const m = tryMulti(text, `已转义 ${fixed} 处未闭合的引号，并识别出 {n} 段首尾相接的 JSON，已合并成数组`)
    if (m) return m
  }

  // 2) 文本本身干净，只是多段相接
  const m2 = tryMulti(raw, `识别为 {n} 段首尾相接的 JSON，已合并成数组`)
  if (m2) return m2

  // 3) 宽松修复尾逗号、单引号、缺引号键等
  try {
    const fixed = jsonrepair(raw)
    const o = JSON.parse(fixed)
    return { obj: o, note: '已用宽松模式修复（尾逗号 / 单引号 / 缺引号键等）' }
  } catch { /* 落到下面报原始错误 */ }

  return { error: attempts[attempts.length - 1] || '无法解析' }
}

/** 从 JSON.parse 报错里推算出错的行列，便于定位 */
function locate(text, msg) {
  const m = /position (\d+)/.exec(msg || '')
  if (!m) return ''
  const pos = Number(m[1])
  const before = text.slice(0, pos)
  const line = before.split('\n').length
  const col = pos - before.lastIndexOf('\n')
  return `（第 ${line} 行第 ${col} 列）`
}

function jsonRun(mode) {
  jsonErr.value = ''
  jsonFixNote.value = ''
  const src = jsonIn.value.trim()
  if (!src) { jsonOut.value = ''; jsonStat.value = ''; jsonTree.value = null; return }

  const r = parseLoose(src)
  if (r.error) {
    jsonOut.value = ''
    jsonStat.value = ''
    jsonTree.value = null
    jsonErr.value = r.error + locate(src, r.error)
    return
  }
  const obj = r.obj
  jsonFixNote.value = r.note || ''
  const pretty = mode === 'min' ? JSON.stringify(obj) : JSON.stringify(obj, null, 2)
  jsonOut.value = pretty
  jsonTree.value = obj
  const keys = countKeys(obj)
  const kind = Array.isArray(obj) && r.note ? ` · ${obj.length} 段` : ''
  jsonStat.value = `合法${kind} · ${pretty.split('\n').length} 行 · ${keys} 个键 · ${(new Blob([pretty]).size / 1024).toFixed(1)} KB`
}

function countKeys(o) {
  if (o === null || typeof o !== 'object') return 0
  if (Array.isArray(o)) return o.reduce((n, v) => n + countKeys(v), 0)
  return Object.keys(o).reduce((n, k) => n + 1 + countKeys(o[k]), 0)
}

/** 改变树的默认展开层数（重建树让节点状态重置） */
function setTreeDepth(d) {
  treeDepth.value = d
  treeKey.value++
}

/** 键名按字母序递归排序，比对字段时好用 */
function jsonSort() {
  const r = parseLoose(jsonIn.value.trim() || '{}')
  if (r.error) { jsonErr.value = '不是合法 JSON：' + r.error; return }
  const sortObj = (v) => {
    if (Array.isArray(v)) return v.map(sortObj)
    if (v && typeof v === 'object') {
      return Object.keys(v).sort().reduce((acc, k) => { acc[k] = sortObj(v[k]); return acc }, {})
    }
    return v
  }
  jsonIn.value = JSON.stringify(sortObj(r.obj), null, 2)
  jsonRun('pretty')
  ElMessage.success('已按键名排序')
}

function jsonClear() {
  jsonIn.value = ''
  jsonFixNote.value = ''
  jsonTree.value = null
  jsonOut.value = ''
  jsonErr.value = ''
  jsonStat.value = ''
}

// ────────────────────────── 时间戳 ──────────────────────────
const nowTick = ref(Date.now())
setInterval(() => { nowTick.value = Date.now() }, 1000)

const tsIn = ref('')
const tsOut = computed(() => {
  const raw = tsIn.value.trim()
  if (!raw) return null
  if (!/^\d+$/.test(raw)) return { error: '请输入纯数字时间戳' }
  // 10 位按秒，13 位按毫秒，其它长度按毫秒兜底
  const ms = raw.length <= 10 ? Number(raw) * 1000 : Number(raw)
  if (!Number.isFinite(ms)) return { error: '数值超出范围' }
  const d = new Date(ms)
  if (Number.isNaN(d.getTime())) return { error: '不是有效时间' }
  return {
    local: fmtLocal(d),
    iso: d.toISOString(),
    utc: d.toUTCString(),
    rel: relative(ms),
    sec: Math.floor(ms / 1000),
    ms,
    week: '周' + '一二三四五六日'[d.getDay() === 0 ? 6 : d.getDay() - 1],
  }
})

const dateIn = ref('')
const dateOut = computed(() => {
  const raw = dateIn.value.trim()
  if (!raw) return null
  const d = new Date(raw.replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return { error: '无法识别，试试 2026-11-03 15:30:00' }
  return { sec: Math.floor(d.getTime() / 1000), ms: d.getTime(), local: fmtLocal(d) }
})

function fmtLocal(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function relative(ms) {
  const diff = ms - Date.now()
  const abs = Math.abs(diff)
  const units = [
    [86400000 * 365, '年'], [86400000 * 30, '个月'], [86400000, '天'],
    [3600000, '小时'], [60000, '分钟'], [1000, '秒'],
  ]
  for (const [base, name] of units) {
    if (abs >= base) {
      const n = Math.floor(abs / base)
      return diff > 0 ? `${n} ${name}后` : `${n} ${name}前`
    }
  }
  return '刚刚'
}

function useNow() {
  tsIn.value = String(Date.now())
}

// ────────────────────────── Base64 / URL ──────────────────────────
const b64Mode = ref('b64')
const b64In = ref('')
const b64Out = ref('')
const b64Err = ref('')

function b64Run(dir) {
  b64Err.value = ''
  const src = dir === 'encode' ? b64In.value : b64In.value.trim()
  if (!src) { b64Out.value = ''; return }
  try {
    if (b64Mode.value === 'url') {
      b64Out.value = dir === 'encode' ? encodeURIComponent(src) : decodeURIComponent(src)
      return
    }
    if (dir === 'encode') {
      // TextEncoder 保证中文等多字节字符正确编码
      const bytes = new TextEncoder().encode(src)
      let bin = ''
      bytes.forEach((b) => { bin += String.fromCharCode(b) })
      b64Out.value = btoa(bin)
    } else {
      const bin = atob(src.replace(/-/g, '+').replace(/_/g, '/'))
      const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0))
      b64Out.value = new TextDecoder().decode(bytes)
    }
  } catch (e) {
    b64Out.value = ''
    b64Err.value = '转换失败：' + (e.message || e)
  }
}

// ────────────────────────── 哈希 / UUID ──────────────────────────
const hashIn = ref('')
const hashAlgo = ref('SHA-256')
const hashOut = ref('')
const hashing = ref(false)

async function hashRun() {
  const src = hashIn.value
  if (!src) { hashOut.value = ''; return }
  if (!window.crypto?.subtle) { ElMessage.error('当前环境不支持 WebCrypto（需 https 或 localhost）'); return }
  hashing.value = true
  try {
    const buf = await crypto.subtle.digest(hashAlgo.value, new TextEncoder().encode(src))
    hashOut.value = [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('')
  } catch (e) {
    ElMessage.error('计算失败：' + (e.message || e))
  } finally {
    hashing.value = false
  }
}

function genUuid() {
  const id = (crypto.randomUUID && crypto.randomUUID()) ||
    'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0
      const v = c === 'x' ? r : (r & 0x3) | 0x8
      return v.toString(16)
    })
  hashOut.value = id
}

function genRandom() {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
  const bytes = crypto.getRandomValues(new Uint8Array(32))
  hashOut.value = [...bytes].map((b) => chars[b % chars.length]).join('')
}

const TABS = [
  { v: 'json', l: 'JSON', icon: Document },
  { v: 'time', l: '时间戳', icon: Timer },
  { v: 'b64', l: 'Base64', icon: Key },
  { v: 'hash', l: '哈希', icon: Key },
]
</script>

<template>
  <el-main class="tools">
    <div class="t-head">
      <el-text tag="b" size="large">开发工具箱</el-text>
      <el-text size="small" type="info">全部在浏览器本地计算，不经过后端，不走网络</el-text>
    </div>

    <el-tabs v-model="tab" class="t-tabs">
      <!-- ── JSON ── -->
      <el-tab-pane label="JSON 格式化" name="json">
        <div class="pane json-pane">
          <div class="cols">
            <div class="col">
              <div class="col-h">
                <span>输入</span>
                <span class="grow"></span>
                <el-button link size="small" @click="jsonRun('pretty')">格式化</el-button>
                <el-button link size="small" @click="jsonRun('min')">压缩</el-button>
                <el-button link size="small" @click="jsonSort">键排序</el-button>
                <el-button link size="small" @click="jsonClear">清空</el-button>
              </div>
              <el-input v-model="jsonIn" type="textarea" class="mono fill" resize="none"
                        placeholder='粘贴 JSON，例如 {"a":1,"b":[2,3]}'
                        @input="jsonRun('pretty')" />
            </div>
            <div class="col">
              <div class="col-h">
                <span>结果</span>
                <el-radio-group v-model="jsonView" size="small" class="viewsw">
                  <el-radio-button value="tree">折叠树</el-radio-button>
                  <el-radio-button value="text">文本</el-radio-button>
                </el-radio-group>
                <el-button v-if="jsonView === 'tree'" link size="small" @click="setTreeDepth(99)">全展开</el-button>
                <el-button v-if="jsonView === 'tree'" link size="small" @click="setTreeDepth(0)">全收起</el-button>
                <el-button link size="small" :disabled="!jsonOut" @click="copy(jsonOut, '结果已复制')">复制</el-button>
              </div>
              <div v-if="jsonView === 'tree'" class="tree-box">
                <JsonTree v-if="jsonTree" :key="treeKey" :node="jsonTree" :depth="0" :default-open-depth="treeDepth" />
                <div v-else class="tree-empty">左侧输入后，这里按层级折叠展示</div>
              </div>
              <el-input v-else :model-value="jsonOut" type="textarea" class="mono fill" resize="none" readonly
                        placeholder="格式化结果会显示在这里" />
            </div>
          </div>
          <div class="foot">
            <el-text v-if="jsonErr" size="small" type="danger">✗ {{ jsonErr }}</el-text>
            <el-text v-else-if="jsonStat" size="small" type="success">✓ {{ jsonStat }}</el-text>
            <el-text v-else size="small" type="info">输入后自动校验并格式化</el-text>
            <div v-if="jsonFixNote" class="fixnote">⚠ {{ jsonFixNote }}，请核对结果</div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ── 时间戳 ── -->
      <el-tab-pane label="时间戳" name="time">
        <div class="pane">
          <div class="block">
            <div class="block-h">当前时间</div>
            <div class="now-row">
              <span class="big mono">{{ fmtLocal(new Date(nowTick)) }}</span>
              <el-button size="small" @click="copy(String(Math.floor(nowTick / 1000)), '秒级时间戳已复制')">复制秒</el-button>
              <el-button size="small" @click="copy(String(nowTick), '毫秒时间戳已复制')">复制毫秒</el-button>
            </div>
            <div class="mono sub">{{ Math.floor(nowTick / 1000) }} · {{ nowTick }}</div>
          </div>

          <div class="block">
            <div class="block-h">
              时间戳 → 日期
              <el-button link size="small" @click="useNow">用当前时间</el-button>
            </div>
            <el-input v-model="tsIn" class="mono" placeholder="粘贴10位（秒）或13位（毫秒）时间戳" clearable />
            <div v-if="tsOut?.error" class="err">{{ tsOut.error }}</div>
            <div v-else-if="tsOut" class="kv">
              <div class="k">本地时间</div><div class="v mono">{{ tsOut.local }}（{{ tsOut.week }}）</div>
              <div class="k">相对现在</div><div class="v">{{ tsOut.rel }}</div>
              <div class="k">ISO 8601</div>
              <div class="v mono">{{ tsOut.iso }}<el-button link size="small" @click="copy(tsOut.iso)"><el-icon><CopyDocument /></el-icon></el-button></div>
              <div class="k">UTC</div><div class="v mono">{{ tsOut.utc }}</div>
              <div class="k">秒 / 毫秒</div><div class="v mono">{{ tsOut.sec }} / {{ tsOut.ms }}</div>
            </div>
          </div>

          <div class="block">
            <div class="block-h">日期 → 时间戳</div>
            <el-input v-model="dateIn" class="mono" placeholder="例如 2026-11-03 15:30:00 或 2026-11-03" clearable />
            <div v-if="dateOut?.error" class="err">{{ dateOut.error }}</div>
            <div v-else-if="dateOut" class="kv">
              <div class="k">秒级</div>
              <div class="v mono">{{ dateOut.sec }}<el-button link size="small" @click="copy(String(dateOut.sec))"><el-icon><CopyDocument /></el-icon></el-button></div>
              <div class="k">毫秒</div>
              <div class="v mono">{{ dateOut.ms }}<el-button link size="small" @click="copy(String(dateOut.ms))"><el-icon><CopyDocument /></el-icon></el-button></div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ── Base64 / URL ── -->
      <el-tab-pane label="编解码" name="b64">
        <div class="pane">
          <el-radio-group v-model="b64Mode" size="small" class="mb8">
            <el-radio-button value="b64">Base64</el-radio-button>
            <el-radio-button value="url">URL 编码</el-radio-button>
          </el-radio-group>
          <el-input v-model="b64In" type="textarea" class="mono" resize="none" :rows="6"
                    :placeholder="b64Mode === 'b64' ? '要编码的文本，或要解码的 Base64 串' : '要编码的 URL 或查询参数'" />
          <div class="row mt8">
            <el-button size="small" type="primary" @click="b64Run('encode')">编码 →</el-button>
            <el-button size="small" @click="b64Run('decode')">← 解码</el-button>
            <span class="grow"></span>
            <el-button link size="small" :disabled="!b64Out" @click="copy(b64Out, '结果已复制')">复制结果</el-button>
          </div>
          <div v-if="b64Err" class="err">{{ b64Err }}</div>
          <el-input :model-value="b64Out" type="textarea" class="mono mt8" resize="none" :rows="6" readonly
                    placeholder="结果" />
          <div class="hint">Base64 按 UTF-8 处理，中文不会乱码；解码时兼容 URL-safe（-_）变体</div>
        </div>
      </el-tab-pane>

      <!-- ── 哈希 / 随机 ── -->
      <el-tab-pane label="哈希 / UUID" name="hash">
        <div class="pane">
          <div class="row mb8">
            <el-select v-model="hashAlgo" size="small" style="width:130px">
              <el-option label="SHA-256" value="SHA-256" />
              <el-option label="SHA-1" value="SHA-1" />
              <el-option label="SHA-384" value="SHA-384" />
              <el-option label="SHA-512" value="SHA-512" />
            </el-select>
            <el-button size="small" type="primary" :loading="hashing" @click="hashRun">计算</el-button>
            <span class="grow"></span>
            <el-button size="small" @click="genUuid">生成 UUID</el-button>
            <el-button size="small" @click="genRandom">32 位随机串</el-button>
          </div>
          <el-input v-model="hashIn" type="textarea" class="mono" resize="none" :rows="6"
                    placeholder="要计算哈希的文本，例如一段配置或一个密钥" />
          <el-input :model-value="hashOut" type="textarea" class="mono mt8" resize="none" :rows="3" readonly
                    placeholder="哈希值 / UUID 会显示在这里" />
          <div class="row mt8">
            <span class="grow"></span>
            <el-button link size="small" :disabled="!hashOut" @click="copy(hashOut, '已复制')">复制结果</el-button>
            <el-button link size="small" :disabled="!hashOut" @click="hashOut = ''">清空</el-button>
          </div>
          <div class="hint">哈希使用浏览器 WebCrypto；不提供 MD5（浏览器无原生实现）</div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-main>
</template>

<style scoped>
/* 页面整体撑满视口，JSON 区左右两栏占满剩余高度 */
.tools{display:flex;flex-direction:column;padding:0;overflow:hidden;height:100%}
.t-head{display:flex;align-items:baseline;gap:12px;padding:var(--page-y) var(--page-x) 0;flex-wrap:wrap;flex:0 0 auto}
.t-tabs{padding:6px var(--page-x) 12px;flex:1;min-height:0;display:flex;flex-direction:column}
.t-tabs :deep(.el-tabs__content){flex:1;min-height:0}
.t-tabs :deep(.el-tab-pane){height:100%}
.pane{display:flex;flex-direction:column;gap:12px;padding-top:6px}
/* JSON 面板吃满高度 */
.pane.json-pane{height:100%;min-height:0}
.json-pane .foot{flex:0 0 auto}
.json-pane .cols{flex:1;min-height:0}
.json-pane .col{min-height:0}
.json-pane .fill{flex:1;min-height:0}
.json-pane .fill :deep(.el-textarea),
.json-pane .fill :deep(.el-textarea__inner){height:100%}
.tree-box{flex:1;min-height:0;overflow:auto;background:var(--card);border:1px solid var(--line);
  border-radius:8px;padding:8px 6px}
.tree-empty{color:var(--ink3);font-size:var(--fs-xs);padding:10px}
.viewsw{margin-left:auto;margin-right:8px}
.cols{display:grid;grid-template-columns:1fr 1fr;gap:14px}
@media (max-width: 1100px){ .cols{grid-template-columns:1fr} }
.col,.block{display:flex;flex-direction:column;gap:8px;background:var(--card);border:1px solid var(--line);
  border-radius:var(--radius);padding:12px}
.block-h{display:flex;align-items:center;gap:8px;font-size:var(--fs-sm);font-weight:600;color:var(--ink2)}
.col-h{display:flex;align-items:center;gap:4px;font-size:var(--fs-sm);font-weight:600;color:var(--ink2)}
.grow{flex:1}
.mono :deep(.el-textarea__inner){font-family:var(--mono);font-size:var(--fs-sm);line-height:1.65}
.col .mono :deep(.el-textarea__inner){height:100%}
.foot{padding:0 2px;display:flex;flex-direction:column;gap:4px}
.fixnote{font-size:var(--fs-2xs);color:var(--el-color-warning);line-height:1.5}
.now-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.big{font-size:var(--fs-lg);font-weight:600;color:var(--ink);letter-spacing:.02em}
.sub{font-size:var(--fs-2xs);color:var(--ink3)}
.kv{display:grid;grid-template-columns:78px 1fr;gap:6px 12px;align-items:center;font-size:var(--fs-sm)}
.kv .k{color:var(--ink3);font-size:var(--fs-xs)}
.kv .v{color:var(--ink);word-break:break-all;display:flex;align-items:center;gap:4px}
.err{color:var(--el-color-danger);font-size:var(--fs-xs);line-height:1.5}
.hint{font-size:var(--fs-2xs);color:var(--ink3)}
.row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.mb8{margin-bottom:8px}.mt8{margin-top:8px}
</style>
