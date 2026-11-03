<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Timer, Key, CopyDocument, Delete } from '@element-plus/icons-vue'

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
  const src = jsonIn.value.trim()
  if (!src) { jsonOut.value = ''; jsonStat.value = ''; return }
  try {
    const obj = JSON.parse(src)
    const pretty = mode === 'min'
      ? JSON.stringify(obj)
      : JSON.stringify(obj, null, 2)
    jsonOut.value = pretty
    const keys = countKeys(obj)
    jsonStat.value = `合法 · ${pretty.split('\n').length} 行 · ${keys} 个键 · ${(new Blob([pretty]).size / 1024).toFixed(1)} KB`
  } catch (e) {
    jsonOut.value = ''
    jsonStat.value = ''
    jsonErr.value = e.message + locate(src, e.message)
  }
}

function countKeys(o) {
  if (o === null || typeof o !== 'object') return 0
  if (Array.isArray(o)) return o.reduce((n, v) => n + countKeys(v), 0)
  return Object.keys(o).reduce((n, k) => n + 1 + countKeys(o[k]), 0)
}

/** 键名按字母序递归排序，比对字段时好用 */
function jsonSort() {
  try {
    const obj = JSON.parse(jsonIn.value.trim() || '{}')
    const sortObj = (v) => {
      if (Array.isArray(v)) return v.map(sortObj)
      if (v && typeof v === 'object') {
        return Object.keys(v).sort().reduce((acc, k) => { acc[k] = sortObj(v[k]); return acc }, {})
      }
      return v
    }
    jsonIn.value = JSON.stringify(sortObj(obj), null, 2)
    jsonRun('pretty')
    ElMessage.success('已按键名排序')
  } catch (e) {
    jsonErr.value = '不是合法 JSON：' + e.message
  }
}

function jsonClear() {
  jsonIn.value = ''
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
        <div class="pane">
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
              <el-input v-model="jsonIn" type="textarea" class="mono" resize="none"
                        placeholder='粘贴 JSON，例如 {"a":1,"b":[2,3]}'
                        @input="jsonRun('pretty')" />
            </div>
            <div class="col">
              <div class="col-h">
                <span>结果</span>
                <span class="grow"></span>
                <el-button link size="small" :disabled="!jsonOut" @click="copy(jsonOut, '结果已复制')">复制</el-button>
              </div>
              <el-input :model-value="jsonOut" type="textarea" class="mono" resize="none" readonly
                        placeholder="格式化结果会显示在这里" />
            </div>
          </div>
          <div class="foot">
            <el-text v-if="jsonErr" size="small" type="danger">✗ {{ jsonErr }}</el-text>
            <el-text v-else-if="jsonStat" size="small" type="success">✓ {{ jsonStat }}</el-text>
            <el-text v-else size="small" type="info">输入后自动校验并格式化</el-text>
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
.tools{display:flex;flex-direction:column;padding:0;overflow:auto}
.t-head{display:flex;align-items:baseline;gap:12px;padding:var(--page-y) var(--page-x) 0;flex-wrap:wrap}
.t-tabs{padding:8px var(--page-x) 20px;flex:1}
.pane{display:flex;flex-direction:column;gap:14px;padding-top:6px}
.cols{display:grid;grid-template-columns:1fr 1fr;gap:14px}
@media (max-width: 1100px){ .cols{grid-template-columns:1fr} }
.col,.block{display:flex;flex-direction:column;gap:8px;background:var(--card);border:1px solid var(--line);
  border-radius:var(--radius);padding:12px}
.block-h{display:flex;align-items:center;gap:8px;font-size:var(--fs-sm);font-weight:600;color:var(--ink2)}
.col-h{display:flex;align-items:center;gap:4px;font-size:var(--fs-sm);font-weight:600;color:var(--ink2)}
.grow{flex:1}
.mono :deep(.el-textarea__inner){font-family:var(--mono);font-size:var(--fs-sm);line-height:1.65}
.col .mono :deep(.el-textarea__inner){height:340px}
.foot{padding:0 2px}
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
