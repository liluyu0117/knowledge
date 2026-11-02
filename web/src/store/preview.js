import { ref } from 'vue'

// 全局预览抽屉状态：任何页面 set 预览目标即弹出
export const preview = ref({ open: false, docPath: '', seq: null })
