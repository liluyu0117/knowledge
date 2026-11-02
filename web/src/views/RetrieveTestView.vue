<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
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

onMounted(async () => {
  rag.value = (await api.get('/api/settings')).rag
  try { kbs.value = await api.get('/api/kb/s') } catch (e) { /* ignore */ }
  reloadScope()
})

function reloadScope() {
  api.get('/api/docs/folders?kbId=' + kbId.value)
    .then((f) => { folders.value = f; folder.value = '' }).catch(() => {})
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
      <div class="sc"><b>{{ h.rrfScore.toFixed(3) }}</b><span>RRF</span></div>
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
    <PreviewDrawer :target="preview" @close="preview.open = false" />
    </div>
  </div>
</template>

<style scoped>
.rt-body{flex:1;min-height:0;overflow:auto;padding:0 var(--page-x) 26px;display:flex;flex-direction:column;gap:var(--gap)}
.rt-q{display:flex;gap:10px}
/* 参数面板：标签在上、控件在下，避免一行里挤十几个控件 */
.panel{background:var(--card);border:1px solid var(--line);border-radius:var(--radius);padding:14px 16px;
  display:flex;flex-direction:column;gap:12px}
.pgrid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px}
.field{display:flex;flex-direction:column;gap:5px;min-width:0}
.fl{font-size:var(--fs-xs);color:var(--ink3);line-height:1.4}
.scoperow{display:flex;gap:7px;align-items:center;flex-wrap:wrap;font-size:var(--fs-xs);color:var(--ink3);
  padding-top:12px;border-top:1px dashed var(--line-soft)}
.hint{color:var(--ink3);font-size:var(--fs-xs)}
.eval-bar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:2px}
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
