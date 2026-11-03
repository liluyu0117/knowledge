<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'

defineOptions({ name: 'JsonTree' })

const props = defineProps({
  node: { type: null, default: null },
  name: { type: null, default: null },     // 键名或数组下标；根节点为 null
  depth: { type: Number, default: 0 },
  isLast: { type: Boolean, default: true },
  defaultOpenDepth: { type: Number, default: 2 },  // 默认展开到第几层
})

const open = ref(props.depth < props.defaultOpenDepth)

const isObject = computed(() => props.node !== null && typeof props.node === 'object')
const isArray = computed(() => Array.isArray(props.node))
const entries = computed(() => {
  if (!isObject.value) return []
  return isArray.value
    ? props.node.map((v, i) => [i, v])
    : Object.entries(props.node)
})

const typeOf = computed(() => {
  if (props.node === null) return 'null'
  if (Array.isArray(props.node)) return 'array'
  return typeof props.node
})

const countHint = computed(() => {
  if (!isObject.value) return ''
  return isArray.value ? `[${entries.value.length}]` : `{${entries.value.length}}`
})

/** 标量显示：字符串加引号，超长截断 */
const scalarText = computed(() => {
  const v = props.node
  if (typeof v === 'string') return '"' + v + '"'
  if (v === null) return 'null'
  return String(v)
})
const longText = computed(() => typeof props.node === 'string' && props.node.length > 80)

async function copy(text, label) {
  try { await navigator.clipboard.writeText(text); ElMessage.success(label) }
  catch { ElMessage.error('复制失败') }
}

function copyValue() {
  const v = props.node
  copy(typeof v === 'string' ? v : JSON.stringify(v, null, 2), '已复制值')
}

function copyPath() {
  const p = props.name === null ? '$' : String(props.name)
  copy(p, '已复制键名')
}
</script>

<template>
  <div class="jn" :class="{ root: depth === 0 }">
    <div class="jn-row" :class="{ obj: isObject }">
      <!-- 折叠三角 -->
      <span v-if="isObject" class="jn-tri" :class="{ open }" @click="open = !open">
        <svg width="8" height="8" viewBox="0 0 8 8"><path d="M2 1l4 3-4 3z" fill="currentColor" /></svg>
      </span>
      <span v-else class="jn-dot"></span>

      <!-- 键名 -->
      <span v-if="name !== null" class="jn-key" :class="{ idx: typeof name === 'number' }"
            @click="copyPath">{{ name }}</span>
      <span v-if="name !== null" class="jn-colon">:</span>

      <!-- 值 -->
      <template v-if="isObject">
        <span class="jn-brace" @click="open = !open">{{ isArray ? '[' : '{' }}</span>
        <span v-if="!open" class="jn-count" @click="open = !open">{{ countHint }}</span>
        <span v-if="!open" class="jn-brace-close" @click="open = !open">{{ isArray ? ']' : '}' }}</span>
        <span v-else class="jn-count">{{ countHint }}</span>
      </template>
      <template v-else>
        <span class="jn-val" :class="typeOf" :title="longText ? scalarText : ''"
              @click="copyValue">{{ longText ? scalarText.slice(0, 80) + '…' : scalarText }}</span>
        <span v-if="longText" class="jn-more" @click="copyValue">（点击复制）</span>
      </template>

      <span v-if="isLast && depth > 0" class="jn-comma"></span>
    </div>

    <div v-if="isObject && open" class="jn-children">
      <JsonTree v-for="([k, v], i) in entries" :key="k" :node="v" :name="k"
                :depth="depth + 1" :is-last="i === entries.length - 1"
                :default-open-depth="defaultOpenDepth" />
    </div>
  </div>
</template>

<style scoped>
.jn{font-family:var(--mono);font-size:var(--fs-sm);line-height:1.7}
.jn-row{display:flex;align-items:flex-start;gap:2px;border-radius:4px;padding:0 4px}
.jn-row:hover{background:var(--mist)}
.jn-row.obj{cursor:default}
.jn-tri{width:12px;height:20px;display:grid;place-items:center;color:var(--ink3);cursor:pointer;
  flex:0 0 12px;transition:transform var(--t-fast) var(--ease)}
.jn-tri.open{transform:rotate(90deg)}
.jn-tri:hover{color:var(--accent)}
.jn-dot{width:12px;flex:0 0 12px}
.jn-key{color:#0B6E99;cursor:pointer;word-break:break-all}
.jn-key.idx{color:var(--ink3);font-size:var(--fs-xs)}
.jn-key:hover{text-decoration:underline}
.jn-colon{color:var(--ink3);margin-right:4px}
.jn-brace{color:var(--ink2);cursor:pointer}
.jn-brace-close{color:var(--ink2);margin-left:2px;cursor:pointer}
.jn-count{color:var(--ink3);font-size:var(--fs-2xs);margin-left:4px;cursor:pointer}
.jn-val{word-break:break-all}
.jn-val.string{color:#B06A00}
.jn-val.number{color:#1B62C4}
.jn-val.boolean{color:#8A3FFC}
.jn-val.null{color:var(--ink3);font-style:italic}
.jn-val:hover{background:var(--accent-soft);cursor:pointer;border-radius:3px}
.jn-more{color:var(--ink3);font-size:var(--fs-2xs)}
.jn-children{margin-left:14px;border-left:1px dashed var(--line-soft);padding-left:4px}
.jn-comma{color:var(--ink3)}
</style>
