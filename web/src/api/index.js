// 统一 API 封装：JSON 请求 + SSE 流式
const BASE = ''

async function json(res) {
  if (!res.ok) {
    let msg = res.status + ' ' + res.statusText
    try { const b = await res.json(); if (b.error) msg = b.error } catch (e) { /* ignore */ }
    throw new Error(msg)
  }
  return res.json()
}

export const api = {
  get: (url) => fetch(url).then(json),
  post: (url, body) => fetch(url, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {})
  }).then(json),
  putJson: (url, body) => fetch(url, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {})
  }).then(json),
  patch: (url, body) => fetch(url, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {})
  }).then(json),
  del: (url) => fetch(url, { method: 'DELETE' }).then(json),
}

// RAG 问答 SSE：onDelta(text)、onDone(payload)、onError(msg)
// signal 传 AbortController.signal 用于"停止生成"
export function askSse(body, onDelta, onDone, onError, signal) {
  fetch('/api/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  }).then(async (res) => {
    if (!res.ok || !res.body) { onError('请求失败 ' + res.status); return }
    const reader = res.body.getReader()
    const dec = new TextDecoder('utf-8')
    let buf = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const raw = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        let event = 'message', data = ''
        for (const line of raw.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) data += (data ? '\n' : '') + line.slice(5).trim()
        }
        if (!data) continue
        const payload = JSON.parse(data)
        if (event === 'delta') onDelta(payload.text)
        else if (event === 'done') onDone(payload)
        else if (event === 'error') onError(payload.message)
      }
    }
  }).catch((e) => onError(String(e)))
}

// 下载文本为文件
export function downloadText(name, text) {
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}
