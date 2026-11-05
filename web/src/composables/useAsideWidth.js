import { ref } from 'vue'

/**
 * 左侧栏宽度拖拽（三个页面共用）。
 *
 * 用 pointer 事件自行计算位移，不依赖 CSS resize（el-aside 不支持），
 * 宽度存 localStorage，刷新后保持；双击把手恢复默认宽度。
 *
 * @param {string} key  存储键，区分页面（如 'ask' / 'kb' / 'notes'）
 * @param {number} def  默认宽度
 * @param {number} min  最小宽度
 * @param {number} max  最大宽度
 */
export function useAsideWidth(key, def = 250, min = 180, max = 560) {
  const storeKey = 'kb.aside.' + key
  const clamp = (w) => Math.min(max, Math.max(min, Math.round(w)))

  const width = ref(def)
  try {
    const saved = Number(localStorage.getItem(storeKey))
    if (Number.isFinite(saved) && saved > 0) width.value = clamp(saved)
  } catch (_) { /* 隐私模式等：忽略 */ }

  const dragging = ref(false)

  function persist() {
    try { localStorage.setItem(storeKey, String(width.value)) } catch (_) { /* 忽略 */ }
  }

  /** 按住把手开始拖拽：document 捕获监听，鼠标移出把手也不丢事件 */
  function onDown(e) {
    if (e.button !== 0) return
    e.preventDefault()
    e.stopPropagation()
    dragging.value = true
    const startX = e.clientX
    const startW = width.value
    document.body.classList.add('aside-resizing')

    const move = (ev) => { width.value = clamp(startW + ev.clientX - startX) }
    const up = () => {
      dragging.value = false
      document.body.classList.remove('aside-resizing')
      document.removeEventListener('pointermove', move, true)
      document.removeEventListener('pointerup', up, true)
      document.removeEventListener('pointercancel', up, true)
      persist()
    }
    document.addEventListener('pointermove', move, true)
    document.addEventListener('pointerup', up, true)
    document.addEventListener('pointercancel', up, true)
  }

  /** 双击恢复默认宽度 */
  function reset() {
    width.value = def
    persist()
  }

  return { width, dragging, onDown, reset }
}
