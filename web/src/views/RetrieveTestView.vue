<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'
import { preview } from '../store/preview'
import PreviewDrawer from '../components/PreviewDrawer.vue'

const q = ref('网络类资源申请 审批环节')
const r = ref(null)
const busy = ref(false)
const rag = ref(null)
const folders = ref([])
const folder = ref('')
const evalRes = ref(null)
const busyEval = ref(false)
const kbId = ref('default')
const kbs = ref([])
const rerank = ref(null)          // 设置页里的重排配置（只读展示）
const glossaryText = ref('')
const glossaryCount = ref(0)
const flags = ref([])        // 被屏蔽的块
const feedbacks = ref([])    // 点踩的 badcase

async function loadFlags() {
  flags.value = await api.get('/api/chunk-flags?kbId=' + kbId.value).catch(() => [])
}

async function loadFeedback() {
  feedbacks.value = await api.get('/api/feedback?rating=-1&limit=20').catch(() => [])
}

/** 把解析错/无关的块屏蔽掉，之后检索不再返回它 */
async function disableChunk(h) {
  await api.post('/api/chunk-flags', {
    kbId: kbId.value, docPath: h.docPath, seq: h.seq, disabled: true, note: '检索测试中屏蔽',
  })
  if (r.value?.results) r.value.results = r.value.results.filter((x) => !(x.docPath === h.docPath && x.seq === h.seq))
  await loadFlags()
  ElMessage.success('已屏蔽该块，后续检索不再返回')
}

async function restoreChunk(f) {
  await api.post('/api/chunk-flags', { kbId: kbId.value, docPath: f.docPath, seq: f.seq, disabled: false })
  await loadFlags()
  ElMessage.success('已恢复')
}

onMounted(async () => {
  const st = await api.get('/api/settings')
  rag.value = st.rag
  rerank.value = st.rerank
  loadGlossary()
  loadFlags()
  loadFeedback()
  try { kbs.value = await api.get('/api/kb/s') } catch (e) { /* ignore */ }
  // 默认知识库可能已被删除：落到第一个可用库，避免下拉显示成裸 id
  if (kbs.value.length && !kbs.value.some((k) => k.id === kbId.value)) kbId.value = kbs.value[0].id
  reloadScope()
})

function reloadScope() {
  api.get('/api/docs/folders?kbId=' + kbId.value)
    .then((f) => { folders.value = f; folder.value = '' }).catch(() => {})
  loadFlags()
}

async function run() {
  if (!q.value.trim() || busy.value) return
  busy.value = true
  try {
    const params = new URLSearchParams({ q: q.value, kbId: kbId.value })
    if (folder.value) params.set('folder', folder.value)
    r.value = await api.get('/api/search?' + params)
  } finally {
    busy.value = false
  }
}

async function runEval() {
  if (busyEval.value) return
  busyEval.value = true
  try {
    evalRes.value = await api.get('/api/eval/run?topK=5&vector=true&kbId=' + kbId.value)
  } finally {
    busyEval.value = false
  }
}

function saveRag(k, v) {
  api.putJson('/api/settings', { rag: { [k]: v } }).then((s) => { rag.value = s.rag })
}

function openHit(h) {
  preview.value = { open: true, docPath: h.docPath, seq: h.seq }
}

function loadGlossary() {
  api.get('/api/glossary').then((list) => {
    glossaryCount.value = list.length
    glossaryText.value = list
      .map((t) => t.term + (t.aliases && t.aliases.length ? '=' + t.aliases.join(',') : ''))
      .join('\n')
  }).catch(() => {})
}

/** 每行「术语=别名1,别名2」→ 接口结构 */
function parseGlossary(text) {
  const terms = []
  for (const line of (text || '').split('\n')) {
    const raw = line.trim()
    if (!raw) continue
    const [term, aliasStr] = raw.split('=')
    const t = (term || '').trim()
    if (!t) continue
    const aliases = (aliasStr || '').split(/[,，、]/).map((x) => x.trim()).filter(Boolean)
    terms.push({ term: t, aliases })
  }
  return terms
}

async function saveGlossary() {
  const terms = parseGlossary(glossaryText.value)
  await api.putJson('/api/glossary', { terms })
  glossaryCount.value = terms.length
  ElMessage.success('术语表已保存（' + terms.length + ' 条），下次检索生效')
}

function evRowClass({ row }) {
  return row.hit ? '' : 'ev-miss'
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="ph-main">
        <div class="pt">调参 · 检索测试</div>
        <div class="ps">对比关键词与向量两路召回结果，参数改动即时保存</div>
      </div>
      <div class="pact">
        <el-tag v-if="r" size="small" effect="plain" type="info">
          候选 {{ r.candidatesKw }} / {{ r.candidatesVec }} · 向量 {{ r.vectorUsed ? '启用' : '未启用' }}
        </el-tag>
        <el-tag v-if="r" size="small" effect="plain" :type="r.rerankUsed ? 'success' : 'info'">
          重排 {{ r.rerankUsed ? '启用' : '未启用' }}<template v-if="r.dropped"> · 过滤 {{ r.dropped }}</template>
        </el-tag>
        <el-tag v-if="r && r.expanded && r.expanded.length" size="small" effect="plain" type="warning">
          术语扩展：{{ r.expanded.join('、') }}
        </el-tag>
      </div>
    </div>
    <div class="rt-body">
    <div class="rt-q">
      <el-input v-model="q" size="large" placeholder="输入测试问题…" @keydown.enter="run" clearable />
      <el-button type="primary" size="large" :disabled="busy" @click="run">{{ busy ? '检索中…' : '测试' }}</el-button>
    </div>
    <div class="panel">
      <div class="pgrid" v-if="rag">
        <div class="field">
          <span class="fl">知识库</span>
          <el-select v-model="kbId" size="small" style="width:100%" @change="reloadScope">
            <el-option v-for="k in kbs" :key="k.id" :value="k.id" :label="k.name" />
          </el-select>
        </div>
        <div class="field">
          <span class="fl">候选数 candidate</span>
          <el-input-number v-model="rag.candidate" size="small" :min="5" :max="100" controls-position="right"
                           style="width:100%" @change="(v) => saveRag('candidate', v)" />
        </div>
        <div class="field">
          <span class="fl">融合常数 rrf_k</span>
          <el-input-number v-model="rag.rrfK" size="small" :min="1" :max="200" controls-position="right"
                           style="width:100%" @change="(v) => saveRag('rrfK', v)" />
        </div>
        <div class="field">
          <span class="fl">返回条数 top_k</span>
          <el-input-number v-model="rag.topK" size="small" :min="1" :max="30" controls-position="right"
                           style="width:100%" @change="(v) => saveRag('topK', v)" />
        </div>
        <div class="field">
          <span class="fl">相似度阈值（重排）</span>
          <el-input-number v-model="rag.minRerankScore" size="small" :min="0" :max="1" :step="0.02" :precision="2"
                           controls-position="right" style="width:100%"
                           @change="(v) => saveRag('minRerankScore', v)" />
        </div>
        <div class="field">
          <span class="fl">相对阈值（无重排）</span>
          <el-input-number v-model="rag.minRelScore" size="small" :min="0" :max="1" :step="0.05" :precision="2"
                           controls-position="right" style="width:100%"
                           @change="(v) => saveRag('minRelScore', v)" />
        </div>
        <div class="field">
          <span class="fl">中文分析器</span>
          <el-select :model-value="rag.analyzer" size="small" style="width:100%"
                     @change="(v) => saveRag('analyzer', v)">
            <el-option value="smart" label="smart（默认）" />
            <el-option value="bigram" label="bigram（二元切分）" />
            <el-option value="standard" label="standard（英文为主）" />
          </el-select>
        </div>
      </div>
      <div class="scoperow">
        <span class="fl">检索范围</span>
        <el-check-tag :checked="folder === ''" @change="folder = ''">全部</el-check-tag>
        <el-check-tag v-for="f in folders" :key="f" :checked="folder === f" @change="folder = f">{{ f }}</el-check-tag>
      </div>
    </div>

    <!-- 业务术语表：命中术语时自动扩展同义词 -->
    <el-collapse class="glo">
      <el-collapse-item name="g">
        <template #title>
          <span class="fl">业务术语表 · {{ glossaryCount }} 条
            <span class="glo-tip">命中术语时把别名一并拿去检索（只影响关键词路）</span>
          </span>
        </template>
        <el-input v-model="glossaryText" type="textarea" :rows="5" class="glo-input"
                  placeholder="每行一条，格式：术语=别名1,别名2&#10;运管=运营管理,运管平台&#10;会签=会签流程,多级会签" />
        <div class="glo-bar">
          <span class="hint">例：`两翼=两翼系统`、`上线方案=上线计划,投产方案`</span>
          <el-button size="small" type="primary" @click="saveGlossary">保存术语表</el-button>
        </div>
      </el-collapse-item>
    </el-collapse>

    <!-- 质量反馈与人工屏蔽 -->
    <el-collapse class="glo">
      <el-collapse-item name="q">
        <template #title>
          <span class="fl">质量反馈 · 屏蔽 {{ flags.length }} 块 · 点踩 {{ feedbacks.length }} 条</span>
        </template>
        <div class="qgrid">
          <div class="qbox">
            <div class="qh">已屏蔽的块（不再参与检索）</div>
            <div v-if="!flags.length" class="hint">还没有屏蔽任何块。检索结果右上角的 🚫 可以一键屏蔽。</div>
            <div v-for="(f, i) in flags" :key="i" class="qrow">
              <span class="qpath">{{ f.docPath }} · 块 {{ f.seq }}</span>
              <el-button link size="small" @click="restoreChunk(f)">恢复</el-button>
            </div>
          </div>
          <div class="qbox">
            <div class="qh">点踩的回答（badcase）</div>
            <div v-if="!feedbacks.length" class="hint">还没有点踩记录，问答页回答右上角可以评价。</div>
            <div v-for="(f, i) in feedbacks" :key="i" class="qrow col">
              <div class="qtxt">{{ (f.content || '').slice(0, 80) }}</div>
              <div class="qmeta">
                <span>{{ f.createdAt?.slice(5, 16).replace('T', ' ') }} · {{ f.sessionTitle || '会话' }}</span>
                <span v-if="f.note" class="qnote">{{ f.note }}</span>
              </div>
            </div>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <!-- 检索回归评估 -->
    <div class="eval-bar">
      <el-button :disabled="busyEval" @click="runEval">{{ busyEval ? '评估中…' : '▶ 运行评估（默认 22 题）' }}</el-button>
      <el-tag v-if="evalRes" :type="evalRes.hitAtK >= 0.8 ? 'success' : 'warning'" effect="plain">
        hit@{{ evalRes.topK }} {{ (evalRes.hitAtK * 100).toFixed(0) }}% · MRR {{ evalRes.mrr.toFixed(2) }}
        · 向量 {{ evalRes.vectorUsed ? '开' : '关（未配置 embedding）' }}
      </el-tag>
      <span class="hint" v-if="evalRes">题集 .kb/eval.json 可手工编辑</span>
    </div>
    <el-table v-if="evalRes" :data="evalRes.items" size="small" :row-class-name="evRowClass" class="eval-table">
      <el-table-column prop="q" label="问题" width="260" />
      <el-table-column label="结果" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.hit ? 'success' : 'danger'" effect="plain">
            {{ row.hit ? '第 ' + row.rank + ' 位' : '未命中' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="doc" label="命中文档" show-overflow-tooltip />
    </el-table>

    <div v-for="h in r?.results || []" :key="h.docPath + '#' + h.seq" class="blk" @click="openHit(h)">
      <div class="sc">
        <b>{{ (h.rerankScore != null ? h.rerankScore : h.rrfScore).toFixed(3) }}</b>
        <span>{{ h.rerankScore != null ? 'RERANK' : 'RRF' }}</span>
        <el-tooltip content="屏蔽这个块（不再参与检索）" placement="top">
          <span class="ban" @click.stop="disableChunk(h)">🚫</span>
        </el-tooltip>
      </div>
      <div>
        <h4>
          <el-tag size="small" :class="h.kwHit ? 'on' : 'off'" class="rtag">关键词</el-tag>
          <el-tag size="small" :class="h.vecHit ? 'on vec' : 'off'" class="rtag">向量</el-tag>
          {{ h.docTitle }}
        </h4>
        <div class="p">{{ h.docPath }} · 块 {{ h.seq }}<template v-if="h.heading"> · {{ h.heading }}</template></div>
        <div class="tx" v-html="h.snippetHtml"></div>
      </div>
    </div>
    <el-empty v-if="r && !r.results.length" description="无召回结果" />
    <PreviewDrawer :target="preview" :kb-id="kbId" @close="preview.open = false" />
    </div>
  </div>
</template>

<style scoped>
.rt-body{flex:1;min-height:0;overflow:auto;padding:0 var(--page-x) 26px;display:flex;flex-direction:column;gap:var(--gap)}
.rt-q{display:flex;gap:10px}
/* 参数面板：标签在上、控件在下，避免一行里挤十几个控件 */
.panel{background:var(--card);border:1px solid var(--line);border-radius:var(--radius);padding:14px 16px;
  display:flex;flex-direction:column;gap:12px}
.pgrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}
.field{display:flex;flex-direction:column;gap:5px;min-width:0}
.fl{font-size:var(--fs-xs);color:var(--ink3);line-height:1.4}
.scoperow{display:flex;gap:7px;align-items:center;flex-wrap:wrap;font-size:var(--fs-xs);color:var(--ink3);
  padding-top:12px;border-top:1px dashed var(--line-soft)}
.hint{color:var(--ink3);font-size:var(--fs-xs)}
.eval-bar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:2px}
.glo{background:var(--card);border:1px solid var(--line);border-radius:var(--radius);padding:0 14px}
.glo :deep(.el-collapse-item__header){background:transparent;border-bottom:0;height:42px}
.glo :deep(.el-collapse-item__wrap){background:transparent;border-bottom:0}
.glo-tip{color:var(--ink3);margin-left:8px;font-weight:400}
.glo-input{margin-bottom:8px}
.glo-input :deep(.el-textarea__inner){font-family:var(--mono);font-size:var(--fs-sm);line-height:1.7}
.glo-bar{display:flex;align-items:center;justify-content:space-between;padding-bottom:12px}
.ban{display:inline-block;margin-top:6px;font-size:12px;opacity:.45;cursor:pointer}
.ban:hover{opacity:1}
.qgrid{display:grid;grid-template-columns:1fr 1fr;gap:14px;padding-bottom:12px}
.qbox{border:1px solid var(--line-soft);border-radius:var(--radius-sm);padding:10px 12px;max-height:240px;overflow:auto}
.qh{font-size:var(--fs-xs);color:var(--ink2);font-weight:600;margin-bottom:8px}
.qrow{display:flex;align-items:center;gap:8px;justify-content:space-between;
  padding:5px 0;border-bottom:1px dashed var(--line-soft);font-size:var(--fs-xs)}
.qrow:last-child{border-bottom:0}
.qrow.col{flex-direction:column;align-items:flex-start;gap:3px}
.qpath{color:var(--ink2);word-break:break-all}
.qtxt{color:var(--ink);line-height:1.5}
.qmeta{display:flex;gap:8px;color:var(--ink3);font-size:var(--fs-2xs)}
.qnote{color:var(--el-color-danger)}
.eval-table{width:100%}
:deep(.ev-miss){background:#FCF5F4}
.blk{display:flex;gap:14px;padding:12px 18px;background:var(--card);border-radius:var(--radius);
  border:1px solid var(--line);border-left:3px solid var(--line);cursor:pointer;transition:.15s}
.blk:hover{border-left-color:var(--accent);background:#FDFDFB}
.sc{width:56px;flex:0 0 auto;text-align:center}
.sc b{display:block;font-size:var(--fs-lg);color:var(--accent);font-family:var(--mono);font-weight:700}
.sc span{font-size:var(--fs-2xs);color:var(--ink3);font-family:var(--mono)}
.blk h4{margin:0 0 4px;font-size:var(--fs-md);font-weight:600;color:var(--ink);line-height:1.45}
.rtag{margin-right:5px}
.rtag.on{background:var(--accent-soft);color:var(--accent);border-color:transparent}
.rtag.on.vec{background:#E5F3EC;color:#0B7A55}
.rtag.off{background:var(--mist);color:var(--ink3);border-color:transparent}
.p{font-size:var(--fs-2xs);color:var(--ink3);margin:3px 0 7px;font-family:var(--mono)}
.tx{font-size:var(--fs-sm);color:var(--ink2);line-height:var(--lh-loose)}
</style>
